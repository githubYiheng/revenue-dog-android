package org.revdog.purchases

import android.app.Activity
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import org.revdog.purchases.attributes.SubscriberAttributeKeys
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
 * 已装：configure / 身份 / CustomerInfo / offerings / 购买 / 恢复 / 同步。
 * 属性同步在 M3 追加（公开面**只进不出**，追加 = 次版本号）。
 */
// 属性 setter 全集就有 40 多个（契约 §2.4 的保留键一个一个方法，与 iOS 公开面逐个对应）。
// 门面本身零逻辑，全部转发给编排层 —— 拆类只会让宿主要记住两个入口。
@Suppress("TooManyFunctions")
public class Purchases private constructor(
    private val orchestrator: PurchasesOrchestrator,
    /** 建这个实例用的那份配置。只用于 [configure] 的「同配置重复调用」判定。 */
    private val configuration: PurchasesConfiguration,
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

    // region 购买与恢复

    /**
     * 发起购买。
     *
     * ```kotlin
     * Purchases.sharedInstance.purchase(
     *     PurchaseParams.Builder(activity, offerings.current!!.monthly!!).build(),
     *     callback,
     * )
     * ```
     *
     * 链路（设计 §3）：**先落盘购买上下文 → 主线程 `launchBillingFlow` → 等 Play 回调 →
     * `POST /v1/receipts` → 按后端下发的 `should_consume` 完成交易 → 回调**。
     *
     * 三件宿主必须知道的事：
     * 1. [PurchaseResult.isPending] 为 `true` 时**钱还没扣**：既不要发权益，也不要提示失败；
     * 2. 错误码 901 `purchasePendingServerConfirmation` = **钱扣了、后端还没确认**，
     *    SDK 会自动重放，**绝不要引导用户重买**；
     * 3. 错误码 902 `purchaseRejectedByServer` = 钱扣了但后端确定性拒绝，走客服 / 退款。
     */
    public fun purchase(purchaseParams: PurchaseParams, callback: PurchaseCallback) {
        orchestrator.purchase(purchaseParams, callback)
    }

    /**
     * 恢复购买：把 Play 上当前可见的交易重新上报到后端（`initiation_source=restore`）。
     *
     * **尽力而为**：Play Billing 8 起已经没有「购买历史」API，端上只能看到
     * **活跃订阅 + 未消耗的一次性商品**。完整历史的权威在后端。
     */
    public fun restorePurchases(callback: ReceiveCustomerInfoCallback) {
        orchestrator.restorePurchases(callback)
    }

    /**
     * 同步购买：只上报，**绝不** ack / consume（`initiation_source=unsynced_active_purchases`）。
     * 宿主自管交易完成（`purchasesCompletedBy = my_app`）时用它。
     */
    public fun syncPurchases(callback: ReceiveCustomerInfoCallback) {
        orchestrator.syncPurchases(callback)
    }

    /**
     * 展示 Play in-app message：用户的订阅续费被拒（卡过期 / 余额不足）时，
     * Play 会弹一条 snackbar 告诉他并给出修复入口 —— Google 官方的挽回通道。
     *
     * **默认不用调**：`PurchasesConfiguration.Builder.showInAppMessagesAutomatically` 默认为 `true`，
     * SDK 会在每个 Activity 的 `onStart` 自动展示一次。关掉了自动展示的宿主
     * （想自己挑时机、避开引导页 / 全屏视频）用这个方法手动触发。
     *
     * 没有可展示的消息时什么都不会发生。用户在 snackbar 里把订阅救回来之后，
     * SDK 会自动跑一次 `syncPurchases`，新权益经 `updatedCustomerInfoListener` /
     * [customerInfoFlow] 推给宿主 —— **本方法本身不回调**。
     *
     * @param activity 用来挂 snackbar 的 Activity。排队期间它被销毁的话这次展示会被跳过。
     * @param inAppMessageTypes 要展示的类别，默认 [InAppMessageType.ALL]。传空列表 = 什么都不展示。
     */
    @JvmOverloads
    public fun showInAppMessagesIfNeeded(
        activity: Activity,
        inAppMessageTypes: List<InAppMessageType> = InAppMessageType.ALL,
    ) {
        orchestrator.showInAppMessagesIfNeeded(activity, inAppMessageTypes)
    }

    // endregion

    // region 订阅者属性（契约 §2.4）

    /**
     * 写入一批**自定义**属性。
     *
     * - 键：字母开头、≤ 40 字符、只含 `[A-Za-z0-9_-]`；**不得以 `$` 开头**（保留前缀，请用专用 setter）；
     * - 值：≤ 500 字符；`null` 或空串 = **删除该属性**；
     * - 每个用户最多 50 个非空自定义属性。
     *
     * **不会立刻发请求**（与 iOS / RC 同款）：同步时机是进后台 / 回前台 / 购买上报搭车 /
     * logIn 合并前后 / [syncAttributes]。所以它是 fire-and-forget —— 不抛错、不回调；
     * 不合规的键被忽略并打 warn 日志（服务端对非法键**整批 400**，端上先挡才不会连累合法键）。
     */
    public fun setAttributes(attributes: Map<String, String?>) {
        val reserved = attributes.keys.filter { it.startsWith("$") }
        if (reserved.isNotEmpty()) {
            Logger.warn { "setAttributes 收到保留键（`$` 前缀），已忽略：${reserved.sorted()} —— 请用专用 setter" }
        }
        orchestrator.setAttributes(attributes.filterKeys { !it.startsWith("$") })
    }

    /** 采集设备标识：`$gpsAdId`（宿主自己引了 play-services-ads-identifier 才拿得到）/ `$androidId` / `$ip` / `$deviceVersion`。 */
    public fun collectDeviceIdentifiers() {
        orchestrator.collectDeviceIdentifiers()
    }

    /** 立刻把待同步属性发出去。正常不必调 —— 生命周期与购买链路已经覆盖了同步时机。 */
    public fun syncAttributes() {
        orchestrator.syncAttributes()
    }

    // 保留键的专用 setter（契约 §2.4 保留键全集 + 附录 A 决策 12 的归因键）。
    // 一律 `null` / 空串 = 删除。与 iOS 的公开面逐个对应。

    public fun setEmail(value: String?): Unit = setReserved(SubscriberAttributeKeys.EMAIL, value)
    public fun setPhoneNumber(value: String?): Unit = setReserved(SubscriberAttributeKeys.PHONE_NUMBER, value)
    public fun setDisplayName(value: String?): Unit = setReserved(SubscriberAttributeKeys.DISPLAY_NAME, value)

    /** Android 的推送 token = FCM token（`$fcmTokens`）。iOS 那边同名方法写的是 APNs。 */
    public fun setPushToken(value: String?): Unit = setReserved(SubscriberAttributeKeys.FCM_TOKENS, value)
    public fun setFCMToken(value: String?): Unit = setReserved(SubscriberAttributeKeys.FCM_TOKENS, value)

    /** iOS 专属语义的保留键，Android 侧留着让跨端宿主一套代码能跑（服务端一视同仁存下来）。 */
    public fun setAPNSToken(value: String?): Unit = setReserved(SubscriberAttributeKeys.APNS_TOKENS, value)
    public fun setATTConsentStatus(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.ATT_CONSENT_STATUS, value)
    public fun setIDFA(value: String?): Unit = setReserved(SubscriberAttributeKeys.IDFA, value)
    public fun setIDFV(value: String?): Unit = setReserved(SubscriberAttributeKeys.IDFV, value)
    public fun setAppleRefundHandlingPreference(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.APPLE_REFUND_HANDLING_PREFERENCE, value)

    public fun setGPSAdID(value: String?): Unit = setReserved(SubscriberAttributeKeys.GPS_AD_ID, value)
    public fun setAndroidID(value: String?): Unit = setReserved(SubscriberAttributeKeys.ANDROID_ID, value)
    public fun setAmazonAdID(value: String?): Unit = setReserved(SubscriberAttributeKeys.AMAZON_AD_ID, value)
    public fun setIP(value: String?): Unit = setReserved(SubscriberAttributeKeys.IP, value)
    public fun setDeviceVersion(value: String?): Unit = setReserved(SubscriberAttributeKeys.DEVICE_VERSION, value)

    public fun setAdjustID(value: String?): Unit = setReserved(SubscriberAttributeKeys.ADJUST_ID, value)
    public fun setAmplitudeDeviceID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.AMPLITUDE_DEVICE_ID, value)
    public fun setAmplitudeUserID(value: String?): Unit = setReserved(SubscriberAttributeKeys.AMPLITUDE_USER_ID, value)
    public fun setAppsflyerID(value: String?): Unit = setReserved(SubscriberAttributeKeys.APPSFLYER_ID, value)
    public fun setAppstackID(value: String?): Unit = setReserved(SubscriberAttributeKeys.APPSTACK_ID, value)
    public fun setBrazeAliasName(value: String?): Unit = setReserved(SubscriberAttributeKeys.BRAZE_ALIAS_NAME, value)
    public fun setBrazeAliasLabel(value: String?): Unit = setReserved(SubscriberAttributeKeys.BRAZE_ALIAS_LABEL, value)
    public fun setCleverTapID(value: String?): Unit = setReserved(SubscriberAttributeKeys.CLEVERTAP_ID, value)
    public fun setCustomerioID(value: String?): Unit = setReserved(SubscriberAttributeKeys.CUSTOMERIO_ID, value)
    public fun setFBAnonymousID(value: String?): Unit = setReserved(SubscriberAttributeKeys.FB_ANON_ID, value)
    public fun setFirebaseAppInstanceID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.FIREBASE_APP_INSTANCE_ID, value)
    public fun setKochavaDeviceID(value: String?): Unit = setReserved(SubscriberAttributeKeys.KOCHAVA_DEVICE_ID, value)
    public fun setMixpanelDistinctID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.MIXPANEL_DISTINCT_ID, value)
    public fun setMparticleID(value: String?): Unit = setReserved(SubscriberAttributeKeys.MPARTICLE_ID, value)
    public fun setOnesignalID(value: String?): Unit = setReserved(SubscriberAttributeKeys.ONESIGNAL_ID, value)
    public fun setAirshipChannelID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.AIRSHIP_CHANNEL_ID, value)
    public fun setIterableUserID(value: String?): Unit = setReserved(SubscriberAttributeKeys.ITERABLE_USER_ID, value)
    public fun setIterableCampaignID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.ITERABLE_CAMPAIGN_ID, value)
    public fun setIterableTemplateID(value: String?): Unit =
        setReserved(SubscriberAttributeKeys.ITERABLE_TEMPLATE_ID, value)
    public fun setPostHogUserID(value: String?): Unit = setReserved(SubscriberAttributeKeys.POSTHOG_USER_ID, value)
    public fun setTenjinID(value: String?): Unit = setReserved(SubscriberAttributeKeys.TENJIN_ID, value)

    public fun setMediaSource(value: String?): Unit = setReserved(SubscriberAttributeKeys.MEDIA_SOURCE, value)
    public fun setCampaign(value: String?): Unit = setReserved(SubscriberAttributeKeys.CAMPAIGN, value)
    public fun setAdGroup(value: String?): Unit = setReserved(SubscriberAttributeKeys.AD_GROUP, value)
    public fun setAd(value: String?): Unit = setReserved(SubscriberAttributeKeys.AD, value)
    public fun setKeyword(value: String?): Unit = setReserved(SubscriberAttributeKeys.KEYWORD, value)
    public fun setCreative(value: String?): Unit = setReserved(SubscriberAttributeKeys.CREATIVE, value)

    private fun setReserved(key: String, value: String?) {
        orchestrator.setAttribute(key, value)
    }

    // endregion

    /**
     * 诊断管线是否开着（`PurchasesConfiguration.Builder.diagnosticsEnabled`，默认 `true`）。
     * 只读 —— 运行期切换会让一半事件在盘上、一半不在，排障时反而更糊。
     */
    public val diagnosticsEnabled: Boolean
        get() = orchestrator.diagnosticsEnabled

    /** 前后台状态。SDK 已自动跟随进程生命周期，这里留给宿主 / 测试显式覆盖。 */
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
         * 重复 configure 分两种（判定与分支均对照 RC `Purchases.configure`）：
         * - **配置与当前实例相同** → 直接返回已有实例，只打一条 info。宿主在多个入口
         *   （`Application.onCreate` + 某个 Activity）各调一次是常态，为此重建实例会白白丢掉
         *   BillingClient 连接、进行中的购买回调与内存缓存；
         * - **配置不同** → 关掉旧实例、用新配置重建。静默忽略比替换更危险 ——
         *   那会让第二次传进来的 appUserID 完全不生效。关闭时进行中的购买回调会收到一个错误
         *   （见 `PurchasesOrchestrator.close`），不会被永远挂着。
         */
        @JvmStatic
        public fun configure(configuration: PurchasesConfiguration): Purchases = synchronized(lock) {
            Logger.logLevel = configuration.logLevel
            instance?.let { existing ->
                if (existing.configuration.sameAs(configuration)) {
                    Logger.info { "Purchases 已经用同一份配置 configure 过了，直接返回已有实例" }
                    return existing
                }
                Logger.warn { "Purchases 已经 configure 过了，用新配置替换旧实例" }
                existing.orchestrator.close()
            }
            val purchases = Purchases(PurchasesOrchestrator.create(configuration), configuration)
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
