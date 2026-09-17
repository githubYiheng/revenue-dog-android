package org.revdog.purchases.identity

import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.networking.Backend
import java.util.Locale
import java.util.UUID

/**
 * 身份唯一真相源。结构对照 RC `identity/IdentityManager.kt`。
 *
 * **两处偏离 RC，都有 ADR 依据：**
 *
 * 1. 匿名 ID 前缀是 `$RDAnonymousID:`（ADR 0019）。冒用 RC 的前缀会让排查的第一反应是
 *    「RC 给的」。识别端**同时认** `$RCAnonymousID:` —— 影子期宿主会把 RC 的 appUserID
 *    （含 RC 匿名 ID）注入进来，认不出来就会把一个匿名用户当成具名用户。
 *
 * 2. **`logOut` 也要服务端先确认**（ADR 0046–0048，设计 §1）。RC 的 logOut 是纯本地的：
 *    生成一个新匿名 ID 就地写盘。那样一旦离线，设备就被留在一个**后端从没见过**的匿名 ID 上，
 *    身份分裂。我方 logOut 与 logIn 同形：**先服务端成功、再切本地身份，失败零副作用**。
 *    落地形式是两段式 —— [candidateAnonymousAppUserID] 生成不落盘的候选，
 *    编排层拿它打 `GET /v1/subscribers/{candidate}`（服务端 get-or-create），
 *    成功了才调 [commitLogOut]。
 */
internal class IdentityManager(
    private val deviceCache: DeviceCache,
    private val backend: Backend,
) {

    val currentAppUserID: String
        get() = deviceCache.getCachedAppUserID().orEmpty()

    /**
     * 启动期解析身份。优先级（对照 RC `configure`）：
     * 显式传入 > 已持久化 > 新生成匿名 ID。
     */
    @Synchronized
    fun configure(appUserID: String?) {
        val appUserIDToUse = when {
            appUserID.isNullOrBlank() -> {
                if (appUserID?.isBlank() == true) {
                    Logger.warn { "configure 传入了空白 appUserID，将按匿名身份启动" }
                }
                deviceCache.getCachedAppUserID() ?: generateAnonymousAppUserID()
            }
            else -> {
                val cached = deviceCache.getCachedAppUserID()
                if (cached != null && cached != appUserID) {
                    Logger.warn { "configure 传入的 appUserID 与缓存中的不同，以传入值为准" }
                }
                appUserID
            }
        }
        Logger.debug { "身份就位：$appUserIDToUse" }
        deviceCache.cacheAppUserID(appUserIDToUse)
    }

    @Synchronized
    fun currentUserIsAnonymous(): Boolean = isUserIDAnonymous(currentAppUserID)

    /**
     * logIn：`POST /v1/subscribers/identify`。
     *
     * **顺序钉死**（ADR 0046 ①、ADR 0048 第 2 条）：服务端合并成功之后才动本地身份与缓存。
     * 服务端失败时本地身份、设备缓存一个字节都不动 —— 购买 / 补报仍然算在旧 uid 上。
     *
     * 新旧 id 相同时**完全不调后端**（RC 同款早返回），由编排层直接供 CustomerInfo。
     */
    fun logIn(
        newAppUserID: String,
        onSuccess: (CustomerInfo, Boolean) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        if (newAppUserID.isBlank()) {
            onError(
                PurchasesError(PurchasesErrorCode.InvalidAppUserIdError, "logIn 需要一个非空的 appUserID")
                    .also { Logger.error { it.toString() } },
            )
            return
        }
        val oldAppUserID = currentAppUserID
        Logger.debug { "logIn：$oldAppUserID → $newAppUserID" }
        backend.logIn(
            appUserID = oldAppUserID,
            newAppUserID = newAppUserID,
            onSuccessHandler = { customerInfo, created ->
                // 到这里服务端已经合并成功，本地切换必须是原子的一段（对照 RC 的 synchronized 块）。
                synchronized(this@IdentityManager) {
                    Logger.debug { "logIn 成功（created=$created），切换本地身份" }
                    deviceCache.clearCachesForAppUserID(oldAppUserID)
                    deviceCache.cacheAppUserID(newAppUserID)
                    deviceCache.cacheCustomerInfo(newAppUserID, customerInfo)
                    // 不清 ETag，新身份会命中旧身份的 304（考古 §6.4 必抄项）。
                    backend.clearCaches()
                }
                onSuccess(customerInfo, created)
            },
            onErrorHandler = onError,
        )
    }

    /**
     * logOut 第一段：生成一个**尚未持久化**的匿名 ID 候选（内存里都不记）。
     *
     * 匿名态调用直接失败 —— 与 RC 的 `LogOutWithAnonymousUserError` 同语义，
     * 但用的是 iOS 同款码位 `invalidAppUserIdError`（错误码表两端一一对应，不额外开码位）。
     * 判定发生在**任何副作用之前**。
     */
    @Synchronized
    fun candidateAnonymousAppUserID(): Result<String> {
        if (currentUserIsAnonymous()) {
            val error = PurchasesError(PurchasesErrorCode.InvalidAppUserIdError, "当前已是匿名身份，logOut 无意义")
            Logger.error { error.toString() }
            return Result.failure(PurchasesErrorHolder(error))
        }
        return Result.success(generateAnonymousAppUserID())
    }

    /**
     * logOut 第二段：把身份切到**服务端已经确认过**的那个匿名 ID。
     *
     * 必须接收预生成的 ID 而不是自己再生成一个：落盘的 ID 必须与服务端刚 get-or-create
     * 出来的那个**逐字相同**，否则设备会拿着一个后端不认识的匿名身份上路。
     */
    @Synchronized
    fun commitLogOut(anonymousAppUserID: String) {
        val previous = currentAppUserID
        deviceCache.clearCachesForAppUserID(previous)
        deviceCache.cacheAppUserID(anonymousAppUserID)
        // 同 logIn：ETag 必须清，否则新匿名用户命中旧用户的 304。
        backend.clearCaches()
        Logger.debug { "logOut 成功：$previous → $anonymousAppUserID" }
    }

    companion object {
        /** 我方自生成的前缀（ADR 0019）。 */
        const val ANONYMOUS_PREFIX: String = "\$RDAnonymousID:"

        /** RC 兼容前缀：**只识别、不生成**。影子期宿主注入的 RC 匿名 ID 长这样。 */
        const val LEGACY_ANONYMOUS_PREFIX: String = "\$RCAnonymousID:"

        private val ANONYMOUS_ID_REGEX =
            "^\\\$(RD|RC)AnonymousID:([a-f0-9]{32})$".toRegex()

        fun isUserIDAnonymous(appUserID: String): Boolean = ANONYMOUS_ID_REGEX.matches(appUserID)

        /** `$RDAnonymousID:` + 32 位无连字符**小写** hex（与 iOS `generateAnonymousAppUserID` 同形）。 */
        fun generateAnonymousAppUserID(): String =
            ANONYMOUS_PREFIX + UUID.randomUUID().toString().lowercase(Locale.ROOT).replace("-", "")
    }
}

/**
 * 把 [PurchasesError] 塞进 `Result.failure` 用的载体。
 * （内部用；对外一律是 callback 上的 `onError(PurchasesError)`。）
 */
internal class PurchasesErrorHolder(val error: PurchasesError) : Exception(error.toString())
