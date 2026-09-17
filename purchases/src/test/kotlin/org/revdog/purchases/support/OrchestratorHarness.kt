package org.revdog.purchases.support

import android.content.Context
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesOrchestrator
import org.revdog.purchases.attributes.SubscriberAttributesCache
import org.revdog.purchases.attributes.SubscriberAttributesManager
import org.revdog.purchases.attributes.SubscriberAttributesPoster
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.customerinfo.CustomerInfoManager
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.offerings.OfferingsManager
import org.revdog.purchases.posting.PostPendingTransactionsHelper
import org.revdog.purchases.posting.PostReceiptHelper
import org.revdog.purchases.posting.PostTransactionsHelper
import java.util.Date

/** 记录型诊断 tracker：断言打点清单用。 */
internal class RecordingDiagnosticsTracker : DiagnosticsTracker {

    val events: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()

    override fun track(name: String, properties: Map<String, Any?>) {
        events += name to properties
    }

    fun named(name: String): List<Map<String, Any?>> = events.filter { it.first == name }.map { it.second }

    fun names(): List<String> = events.map { it.first }
}

/**
 * 真实 [PurchasesOrchestrator] + 真实 `Backend` / `PostReceiptHelper` / `PendingPurchaseStore`
 * + 假后端（[FakeHTTPClient]）+ 可编程 Billing（[BillingHarness]）。
 *
 * 装配逻辑与生产的 `PurchasesOrchestrator.create` 一一对应，只换掉两处：
 * Billing 层（换成替身）与进程生命周期观察者（关掉 —— 单测里 `ProcessLifecycleOwner`
 * 的 onStart 会在不确定的时刻多跑一轮补报，把断言搅乱）。
 *
 * **prefs 每个用例一个新文件**：不隔离的话上一个用例留下的台账 / 上下文会串味。
 */
@Suppress("LongParameterList")
internal class OrchestratorHarness(
    context: Context,
    val billing: BillingHarness = BillingHarness(),
    purchasesAreCompletedBy: PurchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
    val appUserID: String = "user-42",
    prefsName: String = "m2-${System.nanoTime()}",
    now: Date = Date(FIXED_NOW_MS),
    /** 传同一个名字可以模拟「进程重启但磁盘还在」。 */
    sharedPrefsName: String? = null,
    /** A8 的自保阈值。默认就是生产值（24h），A8 用例传小值。 */
    ackSelfProtectThresholdMs: Long = PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS,
) {

    val diagnostics: RecordingDiagnosticsTracker = RecordingDiagnosticsTracker()

    private val preferences = context.getSharedPreferences(sharedPrefsName ?: prefsName, Context.MODE_PRIVATE)

    val appConfig: AppConfig = AppConfig(
        context = context,
        apiKey = FakeHTTPClient.TEST_API_KEY,
        baseURL = FakeHTTPClient.TEST_BASE_URL,
        purchasesAreCompletedBy = purchasesAreCompletedBy,
        isDebugBuild = false,
        diagnosticsEnabled = true,
    ).apply { isAppBackgrounded = false }

    val httpClient: FakeHTTPClient = FakeHTTPClient(appConfig, ETagManager(context))

    /** 可变的「现在」：A8 用例要把时钟往前推 24h。 */
    var nowMs: Long = now.time

    private val dateProvider = DateProvider { Date(nowMs) }

    val backend: Backend = Backend(httpClient, DirectDispatcher())
    val deviceCache: DeviceCache = DeviceCache(preferences, FakeHTTPClient.TEST_API_KEY, dateProvider)
    val pendingPurchases: PendingPurchaseStore =
        PendingPurchaseStore(preferences, FakeHTTPClient.TEST_API_KEY, dateProvider)

    private val identityManager = IdentityManager(deviceCache, backend)
    private val updateHandler = CustomerInfoUpdateHandler(deviceCache, identityManager, MainDispatcher(null))
    private val customerInfoManager =
        CustomerInfoManager(backend, deviceCache, updateHandler, MainDispatcher(null), dateProvider)

    val attributesCache: SubscriberAttributesCache =
        SubscriberAttributesCache(preferences, FakeHTTPClient.TEST_API_KEY)

    val attributesManager: SubscriberAttributesManager = SubscriberAttributesManager(
        cache = attributesCache,
        poster = SubscriberAttributesPoster(backend),
        diagnostics = diagnostics,
        dispatcher = DirectDispatcher(),
        dateProvider = dateProvider,
    )

    val postReceiptHelper: PostReceiptHelper = PostReceiptHelper(
        appConfig = appConfig,
        backend = backend,
        billing = billing.wrapper,
        customerInfoUpdateHandler = updateHandler,
        deviceCache = deviceCache,
        pendingPurchases = pendingPurchases,
        diagnostics = diagnostics,
        attributesManager = attributesManager,
        dateProvider = dateProvider,
        ackSelfProtectThresholdMs = ackSelfProtectThresholdMs,
    )

    private val postTransactionsHelper = PostTransactionsHelper(billing.wrapper, postReceiptHelper)

    val orchestrator: PurchasesOrchestrator = PurchasesOrchestrator(
        appConfig = appConfig,
        identityManager = identityManager,
        backend = backend,
        customerInfoManager = customerInfoManager,
        updateHandler = updateHandler,
        offeringsManager = OfferingsManager(
            deviceCache,
            backend,
            billing.wrapper,
            MainDispatcher(null),
            dateProvider,
        ),
        billing = billing.wrapper,
        mainDispatcher = MainDispatcher(null),
        diagnostics = diagnostics,
        pendingPurchases = pendingPurchases,
        postReceiptHelper = postReceiptHelper,
        postTransactionsHelper = postTransactionsHelper,
        postPendingTransactionsHelper = PostPendingTransactionsHelper(
            deviceCache = deviceCache,
            billing = billing.wrapper,
            dispatcher = DirectDispatcher(),
            postTransactionsHelper = postTransactionsHelper,
            postReceiptHelper = postReceiptHelper,
        ),
        attributesManager = attributesManager,
        configuredAppUserID = appUserID,
        observeProcessLifecycle = false,
        dateProvider = dateProvider,
    )

    /** 出站的 `POST /v1/receipts` 请求（按发出顺序）。 */
    fun receiptRequests() = httpClient.recordedRequests.filter { it.fullURL.path == "/v1/receipts" }

    companion object {
        /** 钉死的「现在」：台账时间戳与上下文时间戳进快照，必须可复现。 */
        const val FIXED_NOW_MS: Long = 1_789_000_000_000L
    }
}
