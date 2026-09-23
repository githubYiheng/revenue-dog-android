package org.revdog.purchases

import android.content.Context
import org.revdog.purchases.common.Config
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.networking.HTTPClient

/**
 * SDK 配置。结构对照 RC `PurchasesConfiguration.kt`：**Builder 形态**（Java 友好）。
 *
 * ```kotlin
 * Purchases.configure(
 *     PurchasesConfiguration.Builder(context, apiKey = "pk_…")
 *         .appUserID(uid)
 *         .diagnosticsEnabled(true)
 *         .logLevel(LogLevel.INFO)
 *         .build(),
 * )
 * ```
 */
public class PurchasesConfiguration internal constructor(builder: Builder) {

    /** 一律取 `applicationContext`：宿主传 Activity 进来会泄漏它。 */
    public val context: Context = builder.context.applicationContext

    public val apiKey: String = builder.apiKey

    /** `null` = 匿名启动。 */
    public val appUserID: String? = builder.appUserID

    public val purchasesCompletedBy: PurchasesAreCompletedBy = builder.purchasesCompletedBy

    public val diagnosticsEnabled: Boolean = builder.diagnosticsEnabled

    public val logLevel: LogLevel = builder.logLevel

    public val baseURL: String = builder.baseURL

    /**
     * 预付费套餐的 pending 交易。默认关（与 RC 一致）：开了之后
     * `PENDING` 状态的预付费购买也会走 `onPurchasesUpdated`，M2 的七分支要能吃下。
     */
    public val pendingTransactionsForPrepaidPlansEnabled: Boolean = builder.pendingTransactionsForPrepaidPlansEnabled

    /** 自动展示 Play in-app message。默认**开**（与 RC 一致）。 */
    public val showInAppMessagesAutomatically: Boolean = builder.showInAppMessagesAutomatically

    /**
     * 混合框架标记（`X-Platform-Flavor`）。原生宿主恒为 `native`；
     * 由 [Builder.platformInfo] 覆写（对照 RC `PlatformInfo.flavor`）。
     */
    internal val platformFlavor: String = builder.platformFlavor

    /** 混合框架自身的版本（`X-Platform-Flavor-Version`）。原生宿主为 `null`（不发这个头）。 */
    internal val platformFlavorVersion: String? = builder.platformFlavorVersion

    /** **仅测试用**：替换整个 HTTP 层。生产路径永远为 `null`。 */
    internal val httpClientOverride: HTTPClient? = builder.httpClientOverride

    /** **仅测试用**：换成同步执行的 dispatcher，让回调在断言之前跑完。 */
    internal val dispatcherOverride: Dispatcher? = builder.dispatcherOverride

    /**
     * **仅测试用**：替换整个 Billing 层。生产路径永远为 `null`。
     *
     * 门面端到端测试必须有它：真实 `BillingWrapper` 在 Robolectric 里连不上 Play，
     * `queryPurchases` 的待办会一直排在队列里不回调 —— 而「联网取 CustomerInfo 之前先补报」
     * 之后，取 CustomerInfo 这条路会等这个回调。
     */
    internal val billingOverride: BillingWrapper? = builder.billingOverride

    /**
     * 「这两份配置是同一份吗」（`Purchases.configure` 的重复配置判定，对照 RC
     * `PurchasesConfiguration.equals`）。
     *
     * **internal 而不是 `equals` 重写**：公开面只进不出，不给宿主凭空多一个相等语义。
     *
     * 比哪些字段照 RC：RC 比的是**会改变实例行为**的那些，不比 `context`
     * （一律 `applicationContext`，进程内同一个）。`logLevel` RC 根本不在配置里
     * （它那边是 `Purchases.logLevel` 静态项），我方也不比 —— `configure` 无论走哪条分支
     * 都已经把它设上去了，拿它判「不同」只会白白重建实例。
     * 两个测试注入项按**引用**比：换了假后端还复用旧实例，测试之间会串味。
     */
    internal fun sameAs(other: PurchasesConfiguration): Boolean =
        apiKey == other.apiKey &&
            appUserID == other.appUserID &&
            purchasesCompletedBy == other.purchasesCompletedBy &&
            diagnosticsEnabled == other.diagnosticsEnabled &&
            baseURL == other.baseURL &&
            pendingTransactionsForPrepaidPlansEnabled == other.pendingTransactionsForPrepaidPlansEnabled &&
            showInAppMessagesAutomatically == other.showInAppMessagesAutomatically &&
            platformFlavor == other.platformFlavor &&
            platformFlavorVersion == other.platformFlavorVersion &&
            httpClientOverride === other.httpClientOverride &&
            dispatcherOverride === other.dispatcherOverride &&
            billingOverride === other.billingOverride

    // 一个配置项一个 setter，这就是 Builder 的全部内容（RC 的同名 Builder 有 15 个）。
    // 拆成两个 Builder 只会让宿主要记住两个入口。
    @Suppress("TooManyFunctions")
    public class Builder(
        internal val context: Context,
        internal val apiKey: String,
    ) {

        internal var appUserID: String? = null
            private set
        internal var purchasesCompletedBy: PurchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG
            private set
        internal var diagnosticsEnabled: Boolean = true
            private set
        internal var logLevel: LogLevel = LogLevel.INFO
            private set
        internal var baseURL: String = DEFAULT_BASE_URL
            private set
        internal var pendingTransactionsForPrepaidPlansEnabled: Boolean = false
            private set
        internal var showInAppMessagesAutomatically: Boolean = true
            private set
        internal var platformFlavor: String = Config.PLATFORM_FLAVOR_NATIVE
            private set
        internal var platformFlavorVersion: String? = null
            private set
        internal var httpClientOverride: HTTPClient? = null
            private set
        internal var dispatcherOverride: Dispatcher? = null
            private set
        internal var billingOverride: BillingWrapper? = null
            private set

        /**
         * 宿主当前用户 id。**强制在启动期配置**（堵死「登录后再 init」的丢单源）：
         * 真的还不知道就传 `null` 走匿名，之后 `logIn` 合并。
         */
        public fun appUserID(appUserID: String?): Builder = apply { this.appUserID = appUserID }

        /**
         * 谁负责 acknowledge / consume。[PurchasesAreCompletedBy.MY_APP] 下 SDK
         * **绝不**碰 Billing 的完成动作，只记台账。
         */
        public fun purchasesCompletedBy(value: PurchasesAreCompletedBy): Builder =
            apply { this.purchasesCompletedBy = value }

        public fun diagnosticsEnabled(enabled: Boolean): Builder = apply { this.diagnosticsEnabled = enabled }

        public fun logLevel(level: LogLevel): Builder = apply { this.logLevel = level }

        /** 自建后端地址。宿主一般不用改。 */
        public fun baseURL(url: String): Builder = apply { this.baseURL = url }

        public fun pendingTransactionsForPrepaidPlansEnabled(enabled: Boolean): Builder =
            apply { this.pendingTransactionsForPrepaidPlansEnabled = enabled }

        /**
         * 自动展示 Play in-app message（扣款失败的官方挽回 snackbar）。**默认开**（与 RC 一致）。
         *
         * 开着时 SDK 注册 `Application.ActivityLifecycleCallbacks`，在每个 Activity 的
         * `onStart` 调一次 [Purchases.showInAppMessagesIfNeeded]。
         * 关掉之后由宿主自己挑时机调那个方法 —— 关掉且不调 = 扣款失败的用户永远收不到提示。
         */
        public fun showInAppMessagesAutomatically(enabled: Boolean): Builder =
            apply { this.showInAppMessagesAutomatically = enabled }

        /**
         * **混合框架专用**（Flutter / RN 插件，R1），原生宿主不要调。
         *
         * 对照 RC：值的形状同 RC `PlatformInfo(flavor, version)`；RC 由混合层在 configure 前写进程级的
         * `Purchases.platformInfo`，我方放进配置 Builder（偏离：跟着实例走，并纳入同配置重复 configure 的判定）。
         *
         * 值原样进 `X-Platform-Flavor` / `X-Platform-Flavor-Version` 两个请求头，
         * 服务端按它给混合框架宿主分组排障。不调 = `native` / 不发版本头。
         *
         * @param flavor 框架名，例如 `"flutter"`。
         * @param version 插件自身版本（例如 pubspec 版本）；`null` = 不发版本头。
         */
        @InternalRevenueDogAPI
        public fun platformInfo(flavor: String, version: String?): Builder = apply {
            this.platformFlavor = flavor
            this.platformFlavorVersion = version
        }

        /** **仅测试用**（internal）：注入假后端。 */
        internal fun httpClientOverride(client: HTTPClient?): Builder = apply { this.httpClientOverride = client }

        /** **仅测试用**（internal）：注入同步 dispatcher。 */
        internal fun dispatcherOverride(dispatcher: Dispatcher?): Builder =
            apply { this.dispatcherOverride = dispatcher }

        /** **仅测试用**（internal）：注入可编程 Billing 层。 */
        internal fun billingOverride(billing: BillingWrapper?): Builder =
            apply { this.billingOverride = billing }

        public fun build(): PurchasesConfiguration {
            require(apiKey.isNotBlank()) { "apiKey 不能为空" }
            return PurchasesConfiguration(this)
        }
    }

    public companion object {
        /** 生产 base URL（`docs/ops/cloudflare-account.md`：域名 revdog.org）。 */
        public const val DEFAULT_BASE_URL: String = "https://api.revdog.org"
    }
}
