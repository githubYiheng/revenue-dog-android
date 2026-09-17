package org.revdog.purchases

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoManager
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.identity.PurchasesErrorHolder
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.HTTPClient
import org.revdog.purchases.offerings.Offerings
import org.revdog.purchases.offerings.OfferingsManager
import java.util.concurrent.Executors

/**
 * 唯一编排入口。结构对照 RC `PurchasesOrchestrator.kt`（M1 只装本切片的能力）。
 *
 * **`init` 的顺序是有讲究的**（考古 §1.3，四条纪律）：
 * 1. **身份先于一切** —— 后面一切请求都要 appUserID。
 * 2. **listener 先于 `startConnection()`** —— 连接成功回调可能同步到达。
 * 3. 生命周期观察者最后注册（M2：观察者方法里会调 BillingClient）。
 * 4. 连接在后台线程发起（防 ANR）。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class PurchasesOrchestrator(
    private val appConfig: AppConfig,
    private val identityManager: IdentityManager,
    private val backend: Backend,
    private val customerInfoManager: CustomerInfoManager,
    private val updateHandler: CustomerInfoUpdateHandler,
    private val offeringsManager: OfferingsManager,
    private val billing: BillingWrapper,
    private val mainDispatcher: MainDispatcher,
    private val diagnostics: DiagnosticsTracker,
    configuredAppUserID: String?,
) {

    private val customerInfoMutableFlow = MutableSharedFlow<CustomerInfo>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** `distinctUntilChanged`：与 listener 那条通道同口径的去重（相同状态不重复发）。 */
    val customerInfoFlow: Flow<CustomerInfo> = customerInfoMutableFlow.asSharedFlow().distinctUntilChanged()

    init {
        // ① 身份先于一切。
        identityManager.configure(configuredAppUserID)

        // ② listener 先挂再连接。M1 的 purchasesUpdatedListener 只记日志，
        //    但**必须现在就挂**：不挂的话 `executeRequestOnUIThread` 会直接回错误（见 BillingWrapper）。
        billing.purchasesUpdatedListener = BillingWrapper.BillingPurchasesUpdatedListener { purchases ->
            Logger.debug { "M1：观察到 ${purchases.size} 笔购买更新，上报链路在 M2" }
        }
        billing.stateListener = BillingWrapper.StateListener {
            Logger.debug { "BillingClient 已连接" }
            // M2：这里是「补报的唯一可靠触发点」（考古 §1.3 的启示）。
        }
        billing.appInBackground = appConfig.isAppBackgrounded
        billing.startConnection()

        updateHandler.internalObserver = { customerInfo -> customerInfoMutableFlow.tryEmit(customerInfo) }

        diagnostics.track(
            DiagnosticsTracker.EVENT_SDK_CONFIGURED,
            mapOf(
                "purchases_completed_by" to appConfig.purchasesAreCompletedBy.rawValue,
                "diagnostics_enabled" to appConfig.diagnosticsEnabled,
                "is_anonymous" to identityManager.currentUserIsAnonymous(),
            ),
        )
    }

    // region 身份

    val appUserID: String get() = identityManager.currentAppUserID

    val isAnonymous: Boolean get() = identityManager.currentUserIsAnonymous()

    /**
     * logIn。新旧 id 相同时**完全不调后端**（RC 同款早返回，ADR 0048 第 2 条同源）：
     * 宿主每次启动都调 `logIn(同一个 uid)` 是常态，不该每次都打一发 identify。
     */
    fun logIn(newAppUserID: String, callback: LogInCallback) {
        if (newAppUserID == identityManager.currentAppUserID) {
            Logger.debug { "logIn 的 id 与当前身份相同，直接供 CustomerInfo" }
            getCustomerInfo(
                CacheFetchPolicy.default(),
                object : ReceiveCustomerInfoCallback {
                    override fun onReceived(customerInfo: CustomerInfo) = callback.onReceived(customerInfo, false)
                    override fun onError(error: PurchasesError) = callback.onError(error)
                },
            )
            return
        }
        identityManager.logIn(
            newAppUserID = newAppUserID,
            onSuccess = { customerInfo, created ->
                updateHandler.resetLastSent()
                updateHandler.notifyListeners(customerInfo, newAppUserID)
                // 身份换了，offerings 也要按新身份重拉（不同用户可能命中不同 offering）。
                offeringsManager.getOfferings(
                    appUserID = newAppUserID,
                    appInBackground = appConfig.isAppBackgrounded,
                    fetchCurrent = true,
                    onError = { Logger.warn { "logIn 后刷新 offerings 失败：$it" } },
                    onSuccess = { },
                )
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGIN,
                    mapOf("created" to created),
                )
                mainDispatcher.dispatch { callback.onReceived(customerInfo, created) }
            },
            onError = { error ->
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGIN,
                    mapOf("error_code" to error.code.name),
                )
                mainDispatcher.dispatch { callback.onError(error) }
            },
        )
    }

    /**
     * logOut。**偏离 RC 的纯本地实现**（ADR 0046–0048，设计 §1）：
     *
     * ① 匿名态守卫 + 生成**不落盘**的候选（纯函数，不改任何状态）；
     * ② 拿候选打 `GET /v1/subscribers/{candidate}`（服务端 get-or-create）；
     * ③ **仅当 ② 成功**才落盘身份切换、清缓存、发通知；
     * ④ ② 失败：身份、缓存、流**一个字节都不动**，原样把错误抛给宿主。
     *
     * 不这么做的后果：一旦离线，设备就被留在一个**后端从没见过**的匿名 ID 上，身份分裂。
     */
    fun logOut(callback: ReceiveCustomerInfoCallback) {
        val candidateResult = identityManager.candidateAnonymousAppUserID()
        val candidate = candidateResult.getOrElse { throwable ->
            val error = (throwable as? PurchasesErrorHolder)?.error
                ?: PurchasesError(PurchasesErrorCode.UnknownError, throwable.message)
            mainDispatcher.dispatch { callback.onError(error) }
            return
        }

        backend.getCustomerInfo(
            appUserID = candidate,
            appInBackground = appConfig.isAppBackgrounded,
            onSuccess = { customerInfo ->
                // 提交段。先落身份（落的就是服务端刚 get-or-create 的那个 ID），再动缓存与流。
                identityManager.commitLogOut(candidate)
                updateHandler.resetLastSent()
                updateHandler.cacheAndNotifyListeners(customerInfo, candidate)
                diagnostics.track(DiagnosticsTracker.EVENT_IDENTITY_LOGOUT)
                mainDispatcher.dispatch { callback.onReceived(customerInfo) }
            },
            onError = { error, _ ->
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGOUT,
                    mapOf("error_code" to error.code.name),
                )
                mainDispatcher.dispatch { callback.onError(error) }
            },
        )
    }

    // endregion

    // region CustomerInfo

    var updatedCustomerInfoListener: UpdatedCustomerInfoListener?
        get() = updateHandler.updatedCustomerInfoListener
        set(value) {
            updateHandler.updatedCustomerInfoListener = value
        }

    val cachedCustomerInfo: CustomerInfo? get() = customerInfoManager.cachedCustomerInfo(appUserID)

    fun getCustomerInfo(fetchPolicy: CacheFetchPolicy, callback: ReceiveCustomerInfoCallback) {
        customerInfoManager.getCustomerInfo(
            appUserID = appUserID,
            fetchPolicy = fetchPolicy,
            appInBackground = appConfig.isAppBackgrounded,
            onSuccess = { callback.onReceived(it) },
            onError = { callback.onError(it) },
        )
    }

    fun invalidateCustomerInfoCache() {
        customerInfoManager.invalidateCustomerInfoCache(appUserID)
    }

    // endregion

    // region Offerings

    fun getOfferings(callback: ReceiveOfferingsCallback) {
        offeringsManager.getOfferings(
            appUserID = appUserID,
            appInBackground = appConfig.isAppBackgrounded,
            onError = { callback.onError(it) },
            onSuccess = { callback.onReceived(it) },
        )
    }

    val cachedOfferings: Offerings? get() = offeringsManager.cachedOfferings

    // endregion

    /** 前后台状态。M2 接生命周期观察者后自动维护；M1 由宿主/测试显式设。 */
    fun setAppBackgrounded(backgrounded: Boolean) {
        appConfig.isAppBackgrounded = backgrounded
        billing.appInBackground = backgrounded
    }

    fun close() {
        billing.close()
        backend.close()
        updateHandler.updatedCustomerInfoListener = null
        updateHandler.internalObserver = null
    }

    internal companion object {

        /**
         * 生产装配。结构对照 RC `PurchasesFactory`。
         *
         * 线程（设计 §6）：
         * - 一个后台单线程 executor 跑全部业务 HTTP；
         * - 一条专用 `HandlerThread("revdog-billing")` 跑 BillingClient；
         * - 主线程 Handler 做回调分发，**可空兜底**（坑 38）。
         */
        @Suppress("LongMethod")
        @VisibleForTesting
        fun create(configuration: PurchasesConfiguration): PurchasesOrchestrator {
            val context = configuration.context
            val mainHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
            val mainDispatcher = MainDispatcher(mainHandler)

            val appConfig = AppConfig(
                context = context,
                apiKey = configuration.apiKey,
                baseURL = configuration.baseURL,
                purchasesAreCompletedBy = configuration.purchasesCompletedBy,
                isDebugBuild = context.isDebugBuild(),
                diagnosticsEnabled = configuration.diagnosticsEnabled,
            )

            val httpClient = configuration.httpClientOverride
                ?: HTTPClient(appConfig, ETagManager(context))
            val backend = Backend(
                httpClient = httpClient,
                dispatcher = configuration.dispatcherOverride
                    ?: Dispatcher(Executors.newSingleThreadScheduledExecutor(), mainHandler),
            )
            val deviceCache = DeviceCache(context, configuration.apiKey)
            val identityManager = IdentityManager(deviceCache, backend)
            val updateHandler = CustomerInfoUpdateHandler(deviceCache, identityManager, mainDispatcher)
            val customerInfoManager = CustomerInfoManager(backend, deviceCache, updateHandler, mainDispatcher)
            val billing = BillingWrapper(
                clientFactory = BillingWrapper.ClientFactory(
                    context,
                    configuration.pendingTransactionsForPrepaidPlansEnabled,
                ),
                mainHandler = mainHandler,
            )
            val offeringsManager = OfferingsManager(deviceCache, backend, billing, mainDispatcher)

            return PurchasesOrchestrator(
                appConfig = appConfig,
                identityManager = identityManager,
                backend = backend,
                customerInfoManager = customerInfoManager,
                updateHandler = updateHandler,
                offeringsManager = offeringsManager,
                billing = billing,
                mainDispatcher = mainDispatcher,
                diagnostics = org.revdog.purchases.diagnostics.NoOpDiagnosticsTracker,
                configuredAppUserID = configuration.appUserID,
            )
        }

        private fun android.content.Context.isDebugBuild(): Boolean =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
