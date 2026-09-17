package org.revdog.purchases.caching

import android.content.SharedPreferences
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.PendingPurchaseKey
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchaseCallback
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.keysSequence
import org.revdog.purchases.common.optNullableString
import org.revdog.purchases.posting.InitiationSource
import org.revdog.purchases.posting.ReceiptInfo

/**
 * 一笔**发起中**的购买。key = 归一化 productId（[PendingPurchaseKey.normalize]）。
 *
 * [receiptInfo] 是**发起那一刻**的归因与价格快照：offering / placement / 价格 micros /
 * offer 的 pricing phases / 升降级模式。补报时用它，而不是补报那一刻现查的值。
 */
@Suppress("LongParameterList") // 值类型：这 7 个字段就是「一笔发起中的购买」的全部内容
internal class PendingPurchase(
    val key: String,
    val productType: ProductType,
    val subscriptionOptionId: String?,
    val presentedPackageIdentifier: String?,
    val receiptInfo: ReceiptInfo,
    val startedAtMs: Long,
    val state: String,
) {
    companion object {
        /** 已 `launchBillingFlow`，等 `onPurchasesUpdated`。同商品再次购买会被拒。 */
        const val STATE_LAUNCHED: String = "launched"

        /** Play 回了 `PENDING`：钱没扣，上下文留着等它转 `PURCHASED`，但**放行**下一次购买。 */
        const val STATE_PENDING: String = "pending"
    }
}

/** 一笔**已发起上报**的交易的上下文台账。key = purchaseToken。 */
internal class PostedTransactionContext(
    val token: String,
    val receiptInfo: ReceiptInfo,
    /**
     * **购买当时**的完成者模式（考古 §5.4）。宿主在购买之后翻了 `purchasesCompletedBy`，
     * 补报时仍然用购买当时这一份，否则一笔在途购买会换一套 finish 语义。
     */
    val purchasesAreCompletedBy: PurchasesAreCompletedBy,
    /** 首次上报时刻。A8「24h 自保 ack」就靠它算超时。 */
    val firstAttemptAtMs: Long,
    /**
     * A8 已经触发过：SDK 在后端确认之前**自己 ack 了**这笔（防 Google 3 天自动退款，设计 §3 A8）。
     *
     * 一旦为 `true`，后续每一次上报都带 `acknowledged_by: "sdk_timeout"` —— 后端据此知道
     * 「这笔的 ack 不是我做的」。落盘：进程重启后这个事实不能丢，否则后端永远不知道。
     */
    val ackSelfProtected: Boolean = false,
)

/**
 * 购买上下文与回调的唯一存放处（设计 §3 A3 + 考古 §9.3 **决策 D**）。
 *
 * **偏离 RC**：RC 把它拆成两个类、两把锁 —— `PurchasesState.purchaseCallbacksByProductId`
 * （Orchestrator 持有）与 `BillingWrapper.purchaseContext`（Wrapper 持有）。代价是
 * DEFERRED 的 productId 归一化逻辑在三个地方各写了一遍（坑 16），且两把锁之间没有任何一致性保证。
 * 我方合并成一张表、**一个 monitor**，归一化收敛到 [PendingPurchaseKey.normalize]。
 *
 * 两张索引在同一把锁下：
 * - `pending`：归一化 productId → [PendingPurchase]（发起上下文，**落盘**）+ 回调（内存）；
 * - `posted`：purchaseToken → [PostedTransactionContext]（上报上下文，**落盘**，
 *   等价 RC 的 `LocalTransactionMetadataStore`）。
 *
 * 落盘的意义（考古 §5.5）：进程在「购买成功、上报之前」被杀，下次前台
 * `queryPurchases` 差集能找回 token，但**找不回归因**；已 consume 的消耗品连 token 都查不到，
 * 只能靠 `posted` 里的残留记录补报。
 */
@Suppress("TooManyFunctions")
internal class PendingPurchaseStore(
    private val preferences: SharedPreferences,
    apiKey: String,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    private val prefix = "${DeviceCache.SHARED_PREFERENCES_PREFIX}$apiKey"
    private val pendingCacheKey = "$prefix.pendingPurchases"
    private val postedCacheKey = "$prefix.postedTransactions"

    /** 回调只活在内存里：进程被杀之后没有任何回调可以恢复，只有上下文能。 */
    private val callbacks = mutableMapOf<String, PurchaseCallback>()

    // region 发起中的购买

    /**
     * 落盘上下文并登记回调。**必须在 `launchBillingFlow` 之前调用**（铁律 A3）。
     *
     * @return `false` = 同一个商品已经有一笔在进行中（RC 的 `operationAlreadyInProgressError` 语义）。
     */
    @Synchronized
    @Suppress("LongParameterList")
    fun start(
        key: String,
        productType: ProductType,
        subscriptionOptionId: String?,
        presentedPackageIdentifier: String?,
        receiptInfo: ReceiptInfo,
        callback: PurchaseCallback,
    ): Boolean {
        val existing = readPending()[key]
        if (existing != null && existing.state == PendingPurchase.STATE_LAUNCHED && callbacks.containsKey(key)) {
            return false
        }
        val pending = PendingPurchase(
            key = key,
            productType = productType,
            subscriptionOptionId = subscriptionOptionId,
            presentedPackageIdentifier = presentedPackageIdentifier,
            receiptInfo = receiptInfo,
            startedAtMs = dateProvider.now().time,
            state = PendingPurchase.STATE_LAUNCHED,
        )
        writePending(readPending() + (key to pending))
        callbacks[key] = callback
        return true
    }

    /** 按**任意** productId 取上下文（内部归一化）。BillingWrapper 用它补齐交易的类型与归因。 */
    @Synchronized
    fun contextFor(productId: String): PendingPurchase? = readPending()[PendingPurchaseKey.normalize(productId)]

    @Synchronized
    fun hasActive(productId: String): Boolean {
        val key = PendingPurchaseKey.normalize(productId)
        return readPending()[key]?.state == PendingPurchase.STATE_LAUNCHED
    }

    /** 取走回调（只能取一次 —— **重复回调守卫**：同一笔购买回调两次只处理一次）。 */
    @Synchronized
    fun takeCallback(productId: String): PurchaseCallback? =
        callbacks.remove(PendingPurchaseKey.normalize(productId))

    /**
     * 购买整体失败（`onPurchasesFailedToUpdate`）时排空。
     *
     * 只清掉 [PendingPurchase.STATE_LAUNCHED] 的上下文（这些购买根本没发生），
     * [PendingPurchase.STATE_PENDING] 的**留着** —— 那些钱可能随后就到账，归因不能丢。
     */
    @Synchronized
    fun takeAllCallbacksAndClearLaunched(): List<PurchaseCallback> {
        val all = callbacks.values.toList()
        callbacks.clear()
        writePending(readPending().filterValues { it.state != PendingPurchase.STATE_LAUNCHED })
        return all
    }

    /** `PENDING`：上下文留着（等它转 `PURCHASED`），但不再挡住同商品的下一次购买。 */
    @Synchronized
    fun markPending(productId: String) {
        val key = PendingPurchaseKey.normalize(productId)
        val existing = readPending()[key] ?: return
        writePending(
            readPending() + (
                key to PendingPurchase(
                    key = existing.key,
                    productType = existing.productType,
                    subscriptionOptionId = existing.subscriptionOptionId,
                    presentedPackageIdentifier = existing.presentedPackageIdentifier,
                    receiptInfo = existing.receiptInfo,
                    startedAtMs = existing.startedAtMs,
                    state = PendingPurchase.STATE_PENDING,
                )
                ),
        )
    }

    /** 终态（成功、或购买根本没发生）：上下文与回调一起清掉。 */
    @Synchronized
    fun finish(productId: String) {
        val key = PendingPurchaseKey.normalize(productId)
        callbacks.remove(key)
        writePending(readPending() - key)
    }

    // endregion

    // region 上报上下文台账

    /**
     * 「缓存优先 + 只在购买时写 + 已有不覆盖」三条一起（结构对照 RC `getOrPutDataToPost`）。
     *
     * - **缓存优先**：同一个 token 的第二次上报用第一次的归因，避免补报把归因冲掉；
     * - **只在 `purchase` 时写**：restore / 补报不该凭空造上下文；
     * - **已有不覆盖**：同 token 第二次调用直接返回已存的那份。
     */
    @Synchronized
    fun getOrPutPostContext(
        token: String,
        receiptInfo: ReceiptInfo,
        initiationSource: String,
        purchasesAreCompletedBy: PurchasesAreCompletedBy,
    ): PostedTransactionContext {
        val posted = readPosted()
        posted[token]?.let { return it }
        val context = PostedTransactionContext(
            token = token,
            receiptInfo = receiptInfo,
            purchasesAreCompletedBy = purchasesAreCompletedBy,
            firstAttemptAtMs = dateProvider.now().time,
        )
        if (initiationSource == InitiationSource.PURCHASE) {
            writePosted(posted + (token to context))
        }
        return context
    }

    @Synchronized
    fun hasPostContext(token: String): Boolean = readPosted().containsKey(token)

    @Synchronized
    fun postContext(token: String): PostedTransactionContext? = readPosted()[token]

    /**
     * A8：标记「SDK 已经自保 ack 过这笔」。**只更新已落盘的上下文** ——
     * restore / 补报路径不落盘上下文（`getOrPutPostContext` 只在 `purchase` 时写），
     * 它们的 `firstAttemptAtMs` 永远是「此刻」，24h 阈值不可能被触发，所以这里也不会有东西要标。
     *
     * @return 是否真的写入了（`false` = 没有这个 token 的落盘上下文）。
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun markAckSelfProtected(token: String): Boolean {
        val posted = readPosted()
        val existing = posted[token] ?: return false
        if (existing.ackSelfProtected) return true
        writePosted(
            posted + (
                token to PostedTransactionContext(
                    token = existing.token,
                    receiptInfo = existing.receiptInfo,
                    purchasesAreCompletedBy = existing.purchasesAreCompletedBy,
                    firstAttemptAtMs = existing.firstAttemptAtMs,
                    ackSelfProtected = true,
                )
                ),
        )
        return true
    }

    @Synchronized
    fun allPostContexts(): List<PostedTransactionContext> = readPosted().values.sortedBy { it.firstAttemptAtMs }

    /** 只在**上报成功**或**确定性 4xx** 之后调用（铁律 A3）。 */
    @Synchronized
    fun clearPostContext(token: String) {
        val posted = readPosted()
        if (!posted.containsKey(token)) return
        writePosted(posted - token)
    }

    // endregion

    // region 落盘

    private fun readPending(): Map<String, PendingPurchase> =
        preferences.getString(pendingCacheKey, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                json.keysSequence().mapNotNull { key ->
                    json.optJSONObject(key)?.let { key to it.toPendingPurchase(key) }
                }.toMap()
            }.getOrElse {
                Logger.warn { "待完成购买上下文解析失败，按空处理" }
                emptyMap()
            }
        } ?: emptyMap()

    private fun writePending(value: Map<String, PendingPurchase>) {
        val json = JSONObject()
        value.forEach { (key, pending) -> json.put(key, pending.toJson()) }
        preferences.edit().putString(pendingCacheKey, json.toString()).apply()
    }

    private fun readPosted(): Map<String, PostedTransactionContext> =
        preferences.getString(postedCacheKey, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                json.keysSequence().mapNotNull { token ->
                    json.optJSONObject(token)?.let { token to it.toPostedContext(token) }
                }.toMap()
            }.getOrElse {
                Logger.warn { "上报上下文台账解析失败，按空处理" }
                emptyMap()
            }
        } ?: emptyMap()

    private fun writePosted(value: Map<String, PostedTransactionContext>) {
        val json = JSONObject()
        value.forEach { (token, context) -> json.put(token, context.toJson()) }
        preferences.edit().putString(postedCacheKey, json.toString()).apply()
    }

    // endregion
}

private const val KEY_PRODUCT_TYPE = "product_type"
private const val KEY_SUBSCRIPTION_OPTION_ID = "subscription_option_id"
private const val KEY_PACKAGE_ID = "package_id"
private const val KEY_RECEIPT_INFO = "receipt_info"
private const val KEY_STARTED_AT = "started_at_ms"
private const val KEY_STATE = "state"
private const val KEY_COMPLETED_BY = "purchases_are_completed_by"
private const val KEY_FIRST_ATTEMPT_AT = "first_attempt_at_ms"
private const val KEY_ACK_SELF_PROTECTED = "ack_self_protected"

private fun PendingPurchase.toJson(): JSONObject = JSONObject().apply {
    put(KEY_PRODUCT_TYPE, productType.rawValue)
    subscriptionOptionId?.let { put(KEY_SUBSCRIPTION_OPTION_ID, it) }
    presentedPackageIdentifier?.let { put(KEY_PACKAGE_ID, it) }
    put(KEY_RECEIPT_INFO, receiptInfo.toJson())
    put(KEY_STARTED_AT, startedAtMs)
    put(KEY_STATE, state)
}

private fun JSONObject.toPendingPurchase(key: String): PendingPurchase = PendingPurchase(
    key = key,
    productType = ProductType.fromString(optNullableString(KEY_PRODUCT_TYPE)),
    subscriptionOptionId = optNullableString(KEY_SUBSCRIPTION_OPTION_ID),
    presentedPackageIdentifier = optNullableString(KEY_PACKAGE_ID),
    receiptInfo = ReceiptInfo.fromJson(optJSONObject(KEY_RECEIPT_INFO) ?: JSONObject()),
    startedAtMs = optLong(KEY_STARTED_AT),
    state = optNullableString(KEY_STATE) ?: PendingPurchase.STATE_LAUNCHED,
)

private fun PostedTransactionContext.toJson(): JSONObject = JSONObject().apply {
    put(KEY_RECEIPT_INFO, receiptInfo.toJson())
    put(KEY_COMPLETED_BY, purchasesAreCompletedBy.rawValue)
    put(KEY_FIRST_ATTEMPT_AT, firstAttemptAtMs)
    if (ackSelfProtected) put(KEY_ACK_SELF_PROTECTED, true)
}

private fun JSONObject.toPostedContext(token: String): PostedTransactionContext = PostedTransactionContext(
    token = token,
    receiptInfo = ReceiptInfo.fromJson(optJSONObject(KEY_RECEIPT_INFO) ?: JSONObject()),
    purchasesAreCompletedBy = if (optNullableString(KEY_COMPLETED_BY) == PurchasesAreCompletedBy.MY_APP.rawValue) {
        PurchasesAreCompletedBy.MY_APP
    } else {
        PurchasesAreCompletedBy.REVENUE_DOG
    },
    firstAttemptAtMs = optLong(KEY_FIRST_ATTEMPT_AT),
    ackSelfProtected = optBoolean(KEY_ACK_SELF_PROTECTED),
)
