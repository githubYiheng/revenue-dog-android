package org.revdog.purchases

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.revdog.purchases.attributes.SubscriberAttributesCache
import org.revdog.purchases.attributes.SubscriberAttributesManager
import org.revdog.purchases.attributes.SubscriberAttributesPoster
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.customerinfo.CustomerInfoManager
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.revdog.purchases.diagnostics.DiagnosticsRecorder
import org.revdog.purchases.diagnostics.DiagnosticsSettings
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.DiagnosticsUploader
import org.revdog.purchases.diagnostics.HandlerDiagnosticsScheduler
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.HTTPClient
import org.revdog.purchases.offerings.OfferingsManager
import org.revdog.purchases.posting.PostPendingTransactionsHelper
import org.revdog.purchases.posting.PostReceiptHelper
import org.revdog.purchases.posting.PostTransactionsHelper
import java.util.UUID
import java.util.concurrent.Executors

/**
 * [PurchasesOrchestrator] 的生产装配工厂。结构对照 RC `PurchasesFactory.kt`：
 * 编排入口只管「谁在什么时候调了谁」，「谁由谁构造」单独放一个文件。
 */
internal object PurchasesFactory {

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
            showInAppMessagesAutomatically = configuration.showInAppMessagesAutomatically,
        )

        val httpClient = configuration.httpClientOverride
            ?: HTTPClient(appConfig, ETagManager(context))
        val dispatcher = configuration.dispatcherOverride
            ?: Dispatcher(Executors.newSingleThreadScheduledExecutor(), mainHandler)
        val backend = Backend(httpClient = httpClient, dispatcher = dispatcher)
        val deviceCache = DeviceCache(context, configuration.apiKey)
        val pendingPurchases = PendingPurchaseStore(
            context.getSharedPreferences(
                "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                android.content.Context.MODE_PRIVATE,
            ),
            configuration.apiKey,
        )
        val identityManager = IdentityManager(deviceCache, backend)
        val updateHandler = CustomerInfoUpdateHandler(deviceCache, identityManager, mainDispatcher)

        // 诊断：专用线程（对照 RC `revenuecat-events-thread`）+ 文件队列 + 攒批上传。
        // **即使 diagnosticsEnabled = false 也要建**：`start()` 那一下要把上一版开着的时候
        // 写在盘上的队列清掉（关掉诊断之后不该还有事件留在设备上）。
        val diagnosticsThread = HandlerThread(DiagnosticsRecorder.THREAD_NAME).apply { start() }
        val diagnosticsSettings = DiagnosticsSettings(
            context.getSharedPreferences(
                "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                android.content.Context.MODE_PRIVATE,
            ),
            configuration.apiKey,
        )
        val diagnosticsQueue = DiagnosticsQueue(DiagnosticsQueue.directoryIn(context.filesDir))
        val diagnosticsDispatcher = Dispatcher(
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, DiagnosticsRecorder.THREAD_NAME).apply { isDaemon = true }
            },
            mainHandler,
        )
        val diagnosticsRecorder = DiagnosticsRecorder(
            queue = diagnosticsQueue,
            uploader = DiagnosticsUploader(
                backend = backend,
                queue = diagnosticsQueue,
                settings = diagnosticsSettings,
                appConfig = appConfig,
                sessionID = UUID.randomUUID().toString().lowercase(),
            ),
            settings = diagnosticsSettings,
            dispatcher = diagnosticsDispatcher,
            scheduler = HandlerDiagnosticsScheduler(Handler(diagnosticsThread.looper)),
            appUserIDProvider = { deviceCache.getCachedAppUserID() },
            enabled = configuration.diagnosticsEnabled,
            ownedThread = diagnosticsThread,
        )
        val diagnostics: DiagnosticsTracker = diagnosticsRecorder
        // `http_error` 的记录点在 HTTP 层，而 Recorder 的 uploader 反过来要用这个 client
        // 发 `/v1/diagnostics/events` —— 构造期闭不了环，只能在这里后置注入一次
        // （对照 iOS `Purchases.start()` 里的 `httpClient.setDiagnostics(_:)`）。
        httpClient.diagnostics = diagnostics

        val attributesManager = SubscriberAttributesManager(
            cache = SubscriberAttributesCache(
                context.getSharedPreferences(
                    "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                    android.content.Context.MODE_PRIVATE,
                ),
                configuration.apiKey,
            ),
            poster = SubscriberAttributesPoster(backend),
            diagnostics = diagnostics,
            dispatcher = dispatcher,
        )

        val billing = configuration.billingOverride ?: BillingWrapper(
            clientFactory = BillingWrapper.ClientFactory(
                context,
                configuration.pendingTransactionsForPrepaidPlansEnabled,
            ),
            mainHandler = mainHandler,
            deviceCache = deviceCache,
            diagnostics = diagnostics,
        )
        val offeringsManager = OfferingsManager(deviceCache, backend, billing, mainDispatcher)
        val postReceiptHelper = PostReceiptHelper(
            appConfig = appConfig,
            backend = backend,
            billing = billing,
            customerInfoUpdateHandler = updateHandler,
            deviceCache = deviceCache,
            pendingPurchases = pendingPurchases,
            diagnostics = diagnostics,
            attributesManager = attributesManager,
        )
        val postTransactionsHelper = PostTransactionsHelper(billing, postReceiptHelper)
        // 补报 helper 必须先于 CustomerInfoManager 建好：后者的每条「要发请求」的路径都先过它一轮。
        val postPendingTransactionsHelper = PostPendingTransactionsHelper(
            appConfig = appConfig,
            deviceCache = deviceCache,
            billing = billing,
            dispatcher = dispatcher,
            postTransactionsHelper = postTransactionsHelper,
            postReceiptHelper = postReceiptHelper,
        )
        val customerInfoManager = CustomerInfoManager(
            backend = backend,
            deviceCache = deviceCache,
            updateHandler = updateHandler,
            mainDispatcher = mainDispatcher,
            postPendingTransactionsHelper = postPendingTransactionsHelper,
        )

        return PurchasesOrchestrator(
            appConfig = appConfig,
            identityManager = identityManager,
            backend = backend,
            customerInfoManager = customerInfoManager,
            updateHandler = updateHandler,
            offeringsManager = offeringsManager,
            billing = billing,
            mainDispatcher = mainDispatcher,
            diagnostics = diagnostics,
            pendingPurchases = pendingPurchases,
            postReceiptHelper = postReceiptHelper,
            postTransactionsHelper = postTransactionsHelper,
            postPendingTransactionsHelper = postPendingTransactionsHelper,
            attributesManager = attributesManager,
            dispatcher = dispatcher,
            configuredAppUserID = configuration.appUserID,
            diagnosticsRecorder = diagnosticsRecorder,
        )
    }

    private fun android.content.Context.isDebugBuild(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
