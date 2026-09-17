package org.revdog.purchases

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.offerings.Offerings

/**
 * SDK 门面。结构对照 RC `Purchases.kt`：**无业务逻辑，全部转发给
 * [PurchasesOrchestrator]**。三套 API 形态共用同一份实现（设计 §1）：
 *
 * | 形态 | 面向 | 位置 |
 * |---|---|---|
 * | callback 接口 | Java | 本类 |
 * | `…With { }` lambda | Kotlin 非协程 | `ListenerConversions.kt` |
 * | `await…` 挂起函数 | Kotlin 协程 | `CoroutinesExtensions.kt` |
 *
 * M1 只装：configure / 身份 / CustomerInfo / offerings。
 * 购买、恢复、属性在 M2–M3 追加（公开面**只进不出**，追加 = 次版本号）。
 */
public class Purchases private constructor(
    private val orchestrator: PurchasesOrchestrator,
) {

    // region 身份

    /** 当前 App User ID。匿名时是 `$RDAnonymousID:<32hex>`。 */
    public val appUserID: String
        get() = orchestrator.appUserID

    /** 当前是不是匿名身份。`$RCAnonymousID:` 也算（影子期宿主会注入 RC 的匿名 id）。 */
    public val isAnonymous: Boolean
        get() = orchestrator.isAnonymous

    /**
     * 把当前身份合并到 [appUserID]。
     *
     * **先服务端成功、再切本地身份**（ADR 0046–0048）：服务端失败时本地身份不动，
     * 购买与补报仍然算在旧 id 上。新旧 id 相同时不打后端，直接供 CustomerInfo。
     */
    public fun logIn(appUserID: String, callback: LogInCallback) {
        orchestrator.logIn(appUserID, callback)
    }

    /**
     * 登出，切回一个新的匿名身份。
     *
     * **与 [logIn] 同形**：先让服务端 get-or-create 出那个匿名身份，成功了才落盘
     * （偏离 RC 的纯本地 logOut，理由见设计 §1）。当前已是匿名身份时回
     * `invalidAppUserIdError`。
     */
    public fun logOut(callback: ReceiveCustomerInfoCallback) {
        orchestrator.logOut(callback)
    }

    // endregion

    // region CustomerInfo

    /**
     * 取用户的权益快照（默认策略 `CACHED_OR_FETCHED`）。
     *
     * **两个显式重载而不是 `@JvmOverloads`**：默认值在第一个参数上，
     * `@JvmOverloads` 只会从**末尾**开始省略，生成不出 Java 要的 `getCustomerInfo(callback)`。
     */
    public fun getCustomerInfo(callback: ReceiveCustomerInfoCallback) {
        getCustomerInfo(CacheFetchPolicy.default(), callback)
    }

    /**
     * 取用户的权益快照。
     *
     * @param fetchPolicy 四态见 [CacheFetchPolicy]。
     */
    public fun getCustomerInfo(fetchPolicy: CacheFetchPolicy, callback: ReceiveCustomerInfoCallback) {
        orchestrator.getCustomerInfo(fetchPolicy, callback)
    }

    /**
     * CustomerInfo 变更通知。挂上时会立刻把缓存里那份发一次。
     *
     * 两道守卫：上一个身份的迟到响应不通知；与上次相同的不重复通知。
     */
    public var updatedCustomerInfoListener: UpdatedCustomerInfoListener?
        get() = orchestrator.updatedCustomerInfoListener
        set(value) {
            orchestrator.updatedCustomerInfoListener = value
        }

    /** [updatedCustomerInfoListener] 的 Kotlin 形态，事件同源。 */
    public val customerInfoFlow: Flow<CustomerInfo>
        get() = orchestrator.customerInfoFlow

    /** 同步读缓存，离线可用。没有缓存时为 `null`。 */
    public val cachedCustomerInfo: CustomerInfo?
        get() = orchestrator.cachedCustomerInfo

    /**
     * 作废 CustomerInfo 缓存，让下一次 `getCustomerInfo` 必定联网。
     * 缓存**内容**留着 —— 离线时仍能供出上一份（与 iOS 同策略）。
     */
    public fun invalidateCustomerInfoCache() {
        orchestrator.invalidateCustomerInfoCache()
    }

    // endregion

    // region Offerings

    /**
     * 取 offerings。`Package.product` 由一次 Play 批量查询填充；
     * **查不到的商品 `product` 为 `null`**，并进 [Offerings.notFoundProductIds]
     * （偏离 RC：RC 会把那个 package 整个丢掉，宿主看不见配置错了）。
     */
    public fun getOfferings(callback: ReceiveOfferingsCallback) {
        orchestrator.getOfferings(callback)
    }

    /** 同步读内存里的 offerings（可能为 `null`）。 */
    public val cachedOfferings: Offerings?
        get() = orchestrator.cachedOfferings

    // endregion

    /** 前后台状态。M2 接进程生命周期后自动维护。 */
    public fun setAppBackgrounded(backgrounded: Boolean) {
        orchestrator.setAppBackgrounded(backgrounded)
    }

    public companion object {

        private val lock = Any()

        @Volatile
        private var instance: Purchases? = null

        /**
         * 已配置好的单例。**未 configure 就访问会抛** —— 与 RC 一致：
         * 一个「静默返回 null 的 sharedInstance」会让丢单问题推迟到线上才被发现。
         */
        @JvmStatic
        public val sharedInstance: Purchases
            get() = instance ?: throw UncheckedPurchasesException(
                PurchasesError.configuration("Purchases 尚未 configure；请在 Application.onCreate 里调用"),
            )

        @JvmStatic
        public val isConfigured: Boolean
            get() = instance != null

        @JvmStatic
        public var logLevel: LogLevel
            get() = Logger.logLevel
            set(value) {
                Logger.logLevel = value
            }

        /** 宿主可把 SDK 日志接进自家管线。 */
        @JvmStatic
        public var logHandler: LogHandler
            get() = Logger.handler
            set(value) {
                Logger.handler = value
            }

        /**
         * 配置 SDK。**必须在启动期调用**（`Application.onCreate`）：
         * 晚于第一笔购买的 configure 就是丢单源。
         *
         * 重复 configure **打日志并替换**（RC 同款）：宿主热重载 / 多进程时会发生，
         * 静默忽略比替换更危险 —— 那会让第二次传进来的 appUserID 完全不生效。
         */
        @JvmStatic
        public fun configure(configuration: PurchasesConfiguration): Purchases = synchronized(lock) {
            Logger.logLevel = configuration.logLevel
            instance?.let {
                Logger.warn { "Purchases 已经 configure 过了，用新配置替换旧实例" }
                it.orchestrator.close()
            }
            val purchases = Purchases(PurchasesOrchestrator.create(configuration))
            instance = purchases
            purchases
        }

        /** 仅测试用：拆掉单例，免得测试之间互相串味。 */
        @VisibleForTesting
        internal fun resetSharedInstance() = synchronized(lock) {
            instance?.orchestrator?.close()
            instance = null
        }
    }
}

/**
 * 门面在「用错了」的时候抛的非受检异常（未 configure 就访问 [Purchases.sharedInstance]）。
 * 业务失败一律走 callback 的 `onError(PurchasesError)`，**不抛**。
 */
public class UncheckedPurchasesException(
    public val error: PurchasesError,
) : IllegalStateException(error.toString())
