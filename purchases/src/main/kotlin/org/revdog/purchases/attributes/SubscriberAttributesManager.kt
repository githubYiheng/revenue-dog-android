package org.revdog.purchases.attributes

import android.content.Context
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.DiagnosticsWarningCode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 属性的唯一入口。结构对照 RC `subscriberattributes/SubscriberAttributesManager.kt`。
 *
 * 同步时机（设计 §5「属性同步时机」，与 iOS 逐条一致）：
 * 1. **进后台**（`onStop`）与**回前台**（`onStart`）；
 * 2. **购买上报搭车**（`POST /v1/receipts` 的 `attributes` 键，省一次请求）；
 * 3. **logIn 合并前后**（先把旧身份的刷出去，再把未同步的搬到新身份）；
 * 4. 宿主显式调 `Purchases.syncAttributes()`。
 *
 * 三条纪律：
 * - setter 是 **fire-and-forget**：不抛错、不回调，只落盘 + 打日志 + 记诊断。
 *   非法键在端上就挡掉（服务端对非法键**整批 400**，一个拼错的键会连累同批的合法键）。
 * - LWW 时间戳取 `max(now, 库中值 + 1)`：本地 setter 表达的是「用户此刻的最新意图」，必须赢；
 *   `+1` 保证本地时钟被拨回过去时服务端的 `excluded.updated_at_ms >= 库中值` 仍然成立
 *   （不然写入会被服务端静默丢弃）。与 iOS 逐字同款。
 * - **出错的键要标成已同步**（`attributes_error_response` / 确定性 4xx）：
 *   否则 SDK 每次前后台切换都把同一批非法属性重传一遍（考古 §2.9）。
 */
@Suppress("TooManyFunctions")
internal class SubscriberAttributesManager(
    private val cache: SubscriberAttributesCache,
    private val poster: SubscriberAttributesPoster,
    private val diagnostics: DiagnosticsTracker,
    /** 采集设备标识要阻塞 IO，必须挪到后台线程（见 [DeviceIdentifiers]）。 */
    private val dispatcher: Dispatcher,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    /** 单飞闸：前后台抖动 / 购买与定时撞在一起时不重复打请求。 */
    private val isSyncing = AtomicBoolean(false)

    // region 写入

    /**
     * 写入一批属性。
     *
     * - `value == null` 或空串 = 墓碑（删除，契约 §2.4）；
     * - 返回**被拒绝的键**（键名非法 / value > 500 / 触及 50 个自定义属性上限）。
     */
    @Suppress("ReturnCount")
    fun setAttributes(attributes: Map<String, String?>, appUserID: String): List<String> {
        if (attributes.isEmpty()) return emptyList()
        if (appUserID.isBlank()) {
            Logger.warn { "setAttributes 在身份就绪前被调用，丢弃 ${attributes.size} 条" }
            return attributes.keys.sorted()
        }

        val rejected = mutableListOf<String>()
        val accepted = validate(attributes, rejected)
        val stored = cache.allStored(appUserID)
        val toWrite = withinCustomBudget(accepted, stored, rejected)
            .mapNotNull { (key, value) -> newAttributeOrNull(key, value, stored[key]) }
            .associateBy { it.key }

        cache.setAttributes(appUserID, toWrite)

        if (rejected.isNotEmpty()) {
            Logger.warn { "属性被拒绝（键名非法 / value>500 / 超 50 自定义上限）：${rejected.sorted()}" }
            diagnostics.warn(DiagnosticsWarningCode.ATTRIBUTES_REJECTED_LOCALLY, "count=${rejected.size}")
        }
        return rejected.sorted()
    }

    /** 键名与 value 长度的前置校验。非法的进 [rejected]，合法的按键排序返回（写入顺序可复现）。 */
    private fun validate(
        attributes: Map<String, String?>,
        rejected: MutableList<String>,
    ): List<Pair<String, String?>> {
        val accepted = mutableListOf<Pair<String, String?>>()
        attributes.toSortedMap().forEach { (key, raw) ->
            if (!SubscriberAttributeLimits.isValidKey(key)) {
                rejected += key
                return@forEach
            }
            // 空串与 null 同义：都是墓碑。
            val normalized = raw?.takeIf { it.isNotEmpty() }
            if (normalized != null && normalized.length > SubscriberAttributeLimits.MAX_VALUE_LENGTH) {
                rejected += key
                return@forEach
            }
            accepted += key to normalized
        }
        return accepted
    }

    /**
     * 50 个自定义属性上限。与服务端同口径：**只数非空的自定义键**，且本批里要设的那些不重复计入
     * （它们要么覆盖已有条目，要么才刚占一个位）。
     */
    private fun withinCustomBudget(
        accepted: List<Pair<String, String?>>,
        stored: Map<String, SubscriberAttribute>,
        rejected: MutableList<String>,
    ): List<Pair<String, String?>> {
        val incomingCustomKeys = accepted.filterNot { SubscriberAttributeKeys.isReserved(it.first) }
            .map { it.first }.toSet()
        var budget = SubscriberAttributeLimits.MAX_CUSTOM_ATTRIBUTES - stored.values.count { attribute ->
            !SubscriberAttributeKeys.isReserved(attribute.key) &&
                !attribute.isTombstone &&
                attribute.key !in incomingCustomKeys
        }
        val allowed = mutableListOf<Pair<String, String?>>()
        accepted.forEach { (key, value) ->
            val takesNewSlot = !SubscriberAttributeKeys.isReserved(key) && value != null
            if (takesNewSlot) {
                if (budget <= 0) {
                    rejected += key
                    return@forEach
                }
                budget--
            }
            allowed += key to value
        }
        return allowed
    }

    /**
     * 造一条待写入的属性；**值没变而且已经同步过就返回 `null`**（不重写、不把 `is_synced` 打回去）
     * —— 宿主每次启动都 `setEmail(同一个值)` 是常态，不该每次都白发一个请求。
     *
     * LWW 时间戳 `max(now, 库中值 + 1)` 的理由见类注释。
     */
    private fun newAttributeOrNull(
        key: String,
        value: String?,
        previous: SubscriberAttribute?,
    ): SubscriberAttribute? {
        if (previous != null && previous.value == value && previous.isSynced) return null
        return SubscriberAttribute(
            key = key,
            value = value,
            updatedAtMs = maxOf(dateProvider.now().time, (previous?.updatedAtMs ?: 0L) + 1),
            isSynced = false,
        )
    }

    fun setAttribute(key: String, value: String?, appUserID: String): List<String> =
        setAttributes(mapOf(key to value), appUserID)

    /**
     * 采集设备标识（`$gpsAdId` / `$androidId` / `$ip` / `$deviceVersion`，见 [DeviceIdentifiers]）。
     * 阻塞 IO，整段挪到后台线程。
     */
    fun collectDeviceIdentifiers(context: Context, appUserID: String) {
        dispatcher.enqueue(
            Runnable {
                val identifiers = DeviceIdentifiers.collect(context)
                if (identifiers.isEmpty()) {
                    Logger.debug { "没有可采集的设备标识" }
                    return@Runnable
                }
                setAttributes(identifiers, appUserID)
            },
        )
    }

    // endregion

    // region 读视图

    fun unsyncedAttributes(appUserID: String): List<SubscriberAttribute> =
        cache.unsynced(appUserID).values.sortedBy { it.key }

    fun storedAttributes(appUserID: String): List<SubscriberAttribute> =
        cache.allStored(appUserID).values.sortedBy { it.key }

    // endregion

    // region 同步

    /**
     * 把待同步属性发出去。**全部身份**都发（结构对照 RC
     * `synchronizeSubscriberAttributesForAllUsers`）：logOut / logIn 之后旧身份桶里可能还有
     * 没发出去的东西，只发当前身份会把它们永久卡住。
     *
     * @param onFinished 全部身份都处理完之后调用一次（测试与「进后台前刷一遍」用）。
     */
    fun synchronizeIfNeeded(
        currentAppUserID: String,
        appInBackground: Boolean,
        onFinished: (() -> Unit)? = null,
    ) {
        if (!isSyncing.compareAndSet(false, true)) {
            Logger.debug { "属性同步已在进行中，跳过这一次" }
            onFinished?.invoke()
            return
        }
        val unsyncedPerUser = cache.unsyncedForAllUsers()
        if (unsyncedPerUser.isEmpty()) {
            Logger.debug { "没有待同步的属性" }
            isSyncing.set(false)
            onFinished?.invoke()
            return
        }

        val total = unsyncedPerUser.size
        var completed = 0
        val onOneDone = {
            completed++
            if (completed == total) {
                isSyncing.set(false)
                onFinished?.invoke()
            }
        }

        unsyncedPerUser.forEach { (appUserID, unsynced) ->
            val sent = unsynced.values.sortedBy { it.key }
            poster.postSubscriberAttributes(
                appUserID = appUserID,
                attributes = sent,
                appInBackground = appInBackground,
                onSuccess = {
                    cache.markSynced(appUserID, sent)
                    if (appUserID != currentAppUserID) cache.clearIfAllSynced(appUserID)
                    diagnostics.track(
                        DiagnosticsTracker.EVENT_ATTRIBUTES_SYNC,
                        mapOf("count" to sent.size, "outcome" to DiagnosticsTracker.OUTCOME_SUCCESS),
                    )
                    onOneDone()
                },
                onError = { error, didBackendGetAttributes, attributeErrors ->
                    handleSyncError(appUserID, sent, error, didBackendGetAttributes, attributeErrors)
                    onOneDone()
                },
            )
        }
    }

    private fun handleSyncError(
        appUserID: String,
        sent: List<SubscriberAttribute>,
        error: PurchasesError,
        didBackendGetAttributes: Boolean,
        attributeErrors: List<SubscriberAttributeError>,
    ) {
        if (attributeErrors.isNotEmpty()) {
            Logger.error { "服务端拒绝了这些属性键：$attributeErrors" }
        }
        if (didBackendGetAttributes) {
            // 确定性拒绝：后端已经看过这批属性了，重传不会有不同结果（RC 原话）。标记已同步。
            Logger.warn { "属性同步被确定性拒绝（$error），标记已同步不再重试" }
            cache.markSynced(appUserID, sent)
            diagnostics.warn(DiagnosticsWarningCode.ATTRIBUTES_REJECTED, "status=${error.httpStatusCode}")
        } else {
            Logger.warn { "属性同步暂时失败，保留待发：$error" }
        }
        diagnostics.track(
            DiagnosticsTracker.EVENT_ATTRIBUTES_SYNC,
            mapOf(
                "count" to sent.size,
                "outcome" to if (didBackendGetAttributes) {
                    DiagnosticsTracker.OUTCOME_REJECTED
                } else {
                    DiagnosticsTracker.OUTCOME_RETRYABLE
                },
                "error_code" to error.code.name,
                "status" to error.httpStatusCode,
                "request_id" to error.requestId,
                "attribute_error_count" to attributeErrors.size,
            ),
        )
    }

    /**
     * 搭车通道的收尾：`POST /v1/receipts` **200** 之后调用。
     *
     * 出错的键（`attributes_error_response`）同样标成已同步 —— 与 RC 的
     * `markAsSynced(appUserID, unsyncedAttributes, attributeErrors)` 一致：
     * 服务端已经处理过这批了，非法的那几个重传一万次也不会进库。
     */
    fun markSyncedAfterReceiptPost(
        appUserID: String,
        sent: List<SubscriberAttribute>,
        attributeErrors: List<SubscriberAttributeError>,
    ) {
        if (sent.isEmpty()) return
        if (attributeErrors.isNotEmpty()) {
            Logger.error { "搭车属性里有被服务端拒绝的键：$attributeErrors" }
            diagnostics.warn(
                DiagnosticsWarningCode.ATTRIBUTES_REJECTED,
                "channel=receipts count=${attributeErrors.size}",
            )
        }
        cache.markSynced(appUserID, sent)
    }

    /** logIn 成功后把未同步属性搬到新身份（RC `copyUnsyncedSubscriberAttributes`）。 */
    fun copyUnsyncedAttributes(from: String, to: String) {
        cache.copyUnsynced(from, to)
    }

    // endregion
}
