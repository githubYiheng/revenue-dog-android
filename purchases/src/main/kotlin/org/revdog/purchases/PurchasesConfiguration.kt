package org.revdog.purchases

import android.content.Context
import org.revdog.purchases.common.Dispatcher
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

    /** **仅测试用**：替换整个 HTTP 层。生产路径永远为 `null`。 */
    internal val httpClientOverride: HTTPClient? = builder.httpClientOverride

    /** **仅测试用**：换成同步执行的 dispatcher，让回调在断言之前跑完。 */
    internal val dispatcherOverride: Dispatcher? = builder.dispatcherOverride

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
        internal var httpClientOverride: HTTPClient? = null
            private set
        internal var dispatcherOverride: Dispatcher? = null
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

        /** **仅测试用**（internal）：注入假后端。 */
        internal fun httpClientOverride(client: HTTPClient?): Builder = apply { this.httpClientOverride = client }

        /** **仅测试用**（internal）：注入同步 dispatcher。 */
        internal fun dispatcherOverride(dispatcher: Dispatcher?): Builder =
            apply { this.dispatcherOverride = dispatcher }

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
