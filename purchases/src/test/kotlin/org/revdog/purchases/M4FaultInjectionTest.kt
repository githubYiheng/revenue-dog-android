package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.sha1
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.diagnostics.DiagnosticsErrorClass
import org.revdog.purchases.diagnostics.DiagnosticsRecorder
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.DiagnosticsWarningCode
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.HTTPRequest
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.DeferredDispatcher
import org.revdog.purchases.support.DiagnosticsRig
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * **M4 故障注入 + 全场景回归**（设计 §9 M4，对位 iOS 的 `M4FaultInjection` 场景套件）。
 *
 * 这个文件回答的是 iOS 坑矩阵「M4 出门必答」第 1 问的 Android 版：
 * **5xx / 超时 / 断网 / 401 / 403 / 404 / 408 / 429 / 断连 / 进程被杀 / 回调重复 / 时钟回拨
 * 的任意组合，有没有哪一种会导致丢单或重复发货。**
 *
 * 纪律：
 * - 每条用例只注入**一种**故障，断言落在三个可观察量上 ——
 *   **Billing 的完成动作**（`consumeAndSave` / `acknowledge`）、**上报上下文是否留存**、**回给宿主的错误码**。
 *   这三个量决定了「钱扣了权益给不给」「下次还能不能补回来」「宿主会不会引导用户重买」。
 * - 「上下文留存」= 这笔还能被补回来；「上下文清掉」= 这笔终态了。**任何一条可重试故障
 *   把上下文清掉就是丢单**，所以矩阵是穷举而不是抽样。
 * - 其余更细的单元断言在各自的文件里（`PurchaseFlowTest` / `SyncPendingPurchasesTest` /
 *   `ETagManagerTest` / `DiagnosticsUploaderTest`）；这里只做**跨层**的端到端组合。
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class M4FaultInjectionTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    private val subscription: StoreProduct
        get() = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()

    private val consumable: StoreProduct
        get() = StoreProductBuilders.inApp("coins_100").withMockDetails()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    // region 工具

    private class Recorder : PurchaseCallback {
        val results: MutableList<PurchaseResult> = mutableListOf()
        val errors: MutableList<Pair<PurchasesError, Boolean>> = mutableListOf()

        override fun onCompleted(result: PurchaseResult) {
            results += result
        }

        override fun onError(error: PurchasesError, userCancelled: Boolean) {
            errors += error to userCancelled
        }
    }

    private class InfoRecorder : ReceiveCustomerInfoCallback {
        val received: MutableList<CustomerInfo> = mutableListOf()
        val errors: MutableList<PurchasesError> = mutableListOf()

        override fun onReceived(customerInfo: CustomerInfo) {
            received += customerInfo
        }

        override fun onError(error: PurchasesError) {
            errors += error
        }
    }

    private fun subsTransaction(
        productId: String = "sub_premium",
        token: String = "token-sub",
        acknowledged: Boolean = false,
        autoRenewing: Boolean = true,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        purchaseToken = token,
        acknowledged = acknowledged,
        autoRenewing = autoRenewing,
        purchaseState = purchaseState,
    ).toStoreTransaction(
        type = ProductType.SUBS,
        subscriptionOptionId = "monthly-base",
        presentedOfferingIdentifier = null,
    )

    /** 一次「购买 → 注入故障 → Play 回调」的完整跑法，每次都用新 harness（prefs 不串味）。 */
    private fun purchaseWithInjectedFault(inject: (OrchestratorHarness) -> Unit): Pair<OrchestratorHarness, Recorder> {
        val faultBilling = BillingHarness()
        val faultHarness = OrchestratorHarness(context, faultBilling)
        faultBilling.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        faultHarness.orchestrator.purchase(
            PurchaseParams.Builder(faultBilling.activity, subscription).build(),
            recorder,
        )
        inject(faultHarness)
        faultBilling.deliverPurchases(subsTransaction())
        return faultHarness to recorder
    }

    // endregion

    // region 1. 上报故障注入矩阵

    @Test
    fun `可重试故障全矩阵 —— 一律不 ack 不 consume、上下文留存、回 901`() {
        // 每一行都是一种「后端没说不算，所以这笔还算」的故障。
        val faults: List<Pair<String, (OrchestratorHarness) -> Unit>> = listOf(
            "断网" to { h -> h.httpClient.enqueueThrowable(UnknownHostException("api.revdog.test")) },
            "读超时" to { h -> h.httpClient.enqueueThrowable(SocketTimeoutException("read timed out")) },
            "连接被中断" to { h -> h.httpClient.enqueueThrowable(InterruptedIOException("interrupted")) },
            "500" to { h -> h.httpClient.enqueue(500, """{"message":"boom"}""") },
            "503" to { h -> h.httpClient.enqueue(503, """{"message":"unavailable"}""") },
            "401" to { h -> h.httpClient.enqueue(401, """{"code":7225,"message":"invalid key"}""") },
            "403" to { h -> h.httpClient.enqueue(403, """{"code":7243,"message":"wrong key type"}""") },
            "404" to { h -> h.httpClient.enqueue(404, """{"code":7259,"message":"no such customer"}""") },
            "408" to { h -> h.httpClient.enqueue(408, """{"message":"request timeout"}""") },
            "429" to { h -> h.httpClient.enqueue(429, """{"message":"slow down"}""") },
        )

        faults.forEach { (label, inject) ->
            val (faultHarness, recorder) = purchaseWithInjectedFault(inject)

            assertThat(faultHarness.billing.consumeAndSaveCalls).describedAs("$label 不该有任何完成动作").isEmpty()
            assertThat(recorder.errors.single().first.code)
                .describedAs("$label 应回 901")
                .isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
            assertThat(recorder.errors.single().second).describedAs("$label 不是用户取消").isFalse()
            assertThat(faultHarness.pendingPurchases.hasPostContext("token-sub"))
                .describedAs("$label 上下文必须留存，否则这笔永远补不回来")
                .isTrue()
            assertThat(faultHarness.deviceCache.getPreviouslySentHashedTokens())
                .describedAs("$label 绝不能记台账")
                .doesNotContain("token-sub".sha1())
        }
    }

    @Test
    fun `确定性拒绝全矩阵 —— 一律 ack 但不 consume、上下文清掉、回 902`() {
        // 4xx（401/403/404/408/429 除外）= 后端确定性拒绝，重试不会有不同结果：
        // ack 是「对 Google 说这笔我收到了」（防 3 天自动退款），consume 绝不做（消耗品还能补报）。
        val statuses = listOf(400, 402, 409, 422, 451)

        statuses.forEach { status ->
            val (faultHarness, recorder) = purchaseWithInjectedFault { h ->
                h.httpClient.enqueue(status, """{"code":7000,"message":"rejected"}""")
            }

            val call = faultHarness.billing.consumeAndSaveCalls.single()
            assertThat(call.deterministicallyRejected).describedAs("$status").isTrue()
            assertThat(call.shouldConsume).describedAs("$status 绝不 consume").isNull()
            assertThat(recorder.errors.single().first.code)
                .describedAs("$status 应回 902")
                .isEqualTo(PurchasesErrorCode.PurchaseRejectedByServer)
            assertThat(faultHarness.pendingPurchases.hasPostContext("token-sub"))
                .describedAs("$status 上下文应清掉（终态）")
                .isFalse()
        }
    }

    @Test
    fun `可重试失败之后前台重放 —— 同一 token 补报成功、上下文清掉、记台账`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )
        harness.httpClient.enqueue(503, """{"message":"unavailable"}""")
        billing.deliverPurchases(subsTransaction())
        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isTrue()

        // 回前台 / 重连：Play 仍然认这笔（订阅还活着），差集那条链路会把它重报一次。
        billing.purchasesOnDevice = listOf(subsTransaction())
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isFalse()
        assertThat(harness.billing.consumeAndSaveCalls.single().shouldConsume).isFalse()
        // 重放用的是**购买当时**落盘的归因（`sdk_originated = true`），不是补报这一刻现造的。
        assertThat(harness.receiptRequests().last().body?.getBoolean("sdk_originated")).isTrue()
    }

    @Test
    fun `每次故障只发一次请求 —— HTTP 层不重试（429 的 Retry-After 也不例外）`() {
        billing.subscriptionProducts = listOf(subscription)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            Recorder(),
        )
        harness.httpClient.enqueue(
            org.revdog.purchases.networking.HTTPResult(
                responseCode = 429,
                payload = """{"message":"slow down"}""",
                origin = org.revdog.purchases.networking.HTTPResult.Origin.BACKEND,
                requestDate = null,
                requestId = "req-429",
                serverIsRetryable = true,
                retryAfterSeconds = 30,
                eTagHeaderValue = null,
            ),
        )

        billing.deliverPurchases(subsTransaction())

        // HTTP 层不睡不重发（考古 §2.12）：同步调用睡在 Dispatcher 线程上会堵住整条队列。
        assertThat(harness.receiptRequests()).hasSize(1)
    }

    @Test
    fun `可重试失败的 receipt_post 诊断带 error_class 与 status —— 巡检不变式 18 靠这两列`() {
        billing.subscriptionProducts = listOf(subscription)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            Recorder(),
        )
        harness.httpClient.enqueueThrowable(SocketTimeoutException("read timed out"))

        billing.deliverPurchases(subsTransaction())

        val post = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        assertThat(post["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_RETRYABLE)
        // 超时没有 HTTP 状态码 → `error_class = network`（与 iOS 同口径）。
        assertThat(post["error_class"]).isEqualTo(DiagnosticsErrorClass.NETWORK)
        assertThat(post["status"]).isNull()
    }

    // endregion

    // region 2. 断连、进程被杀

    @Test
    fun `断连中购买 —— onPurchasesUpdated 从未到达，重连后靠 queryPurchases 差集补报`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )
        // Play 服务在购买途中断开：launch 发出去了，回调一个字都没回来。
        assertThat(billing.launched).hasSize(1)
        assertThat(recorder.results).isEmpty()
        assertThat(recorder.errors).isEmpty()
        assertThat(harness.receiptRequests()).isEmpty()

        // 重连是补报的唯一可靠触发点（考古 §3.4）：这时 Play 已经认这笔了。
        billing.purchasesOnDevice = listOf(subsTransaction())
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverConnected()

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getString("fetch_token")).isEqualTo("token-sub")
        assertThat(body.getString("initiation_source")).isEqualTo("unsynced_active_purchases")
        // 真实 `BillingWrapper.consumeAndSave` 会 ack 并记台账（`ConsumeAndSaveTest` 锁住那七分支）；
        // 编排层用的是 Billing 替身，这里断言「被告知去完成这笔」即可。
        assertThat(harness.billing.consumeAndSaveCalls.single().shouldConsume).isFalse()
        // 宿主的 purchase 回调**没有**被补报路径顶掉：补报不是购买，不该冒充购买结果。
        assertThat(recorder.results).isEmpty()
        assertThat(recorder.errors).isEmpty()
    }

    @Test
    fun `购买后进程被杀（回调从未到达）—— 新实例在 onConnected 时按差集补报恰好一次`() {
        val prefsName = "killed-${System.nanoTime()}"
        val firstBilling = BillingHarness()
        val first = OrchestratorHarness(context, firstBilling, sharedPrefsName = prefsName)
        firstBilling.subscriptionProducts = listOf(subscription)
        first.orchestrator.purchase(
            PurchaseParams.Builder(firstBilling.activity, subscription).build(),
            Recorder(),
        )
        // 进程在 launch 之后、回调之前被杀：一次上报都没发生过。
        assertThat(first.receiptRequests()).isEmpty()

        val secondBilling = BillingHarness()
        val second = OrchestratorHarness(context, secondBilling, sharedPrefsName = prefsName)
        secondBilling.purchasesOnDevice = listOf(subsTransaction())
        second.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        secondBilling.deliverConnected()

        assertThat(second.receiptRequests()).hasSize(1)
        assertThat(second.billing.consumeAndSaveCalls.single().shouldConsume).isFalse()

        // 台账由真实 `consumeAndSave` 写（替身不写），手动补上之后再连一次：
        // **不该重复上报** —— 台账就是 Android 版的 finish。
        second.deviceCache.addSuccessfullyPostedToken("token-sub", isAutoRenewing = true)
        secondBilling.deliverConnected()
        assertThat(second.receiptRequests()).hasSize(1)
    }

    @Test
    fun `消耗品已 consume 但上报失败 + 进程被杀 —— 新实例只能靠本地上下文补报`() {
        val prefsName = "consumed-${System.nanoTime()}"
        val firstBilling = BillingHarness()
        val first = OrchestratorHarness(context, firstBilling, sharedPrefsName = prefsName)
        firstBilling.inAppProducts = listOf(consumable)
        val inAppTransaction = purchaseFixture(
            productIds = listOf("coins_100"),
            purchaseToken = "token-coins",
            autoRenewing = false,
        ).toStoreTransaction(ProductType.INAPP, null, null)
        first.orchestrator.purchase(
            PurchaseParams.Builder(firstBilling.activity, consumable).build(),
            Recorder(),
        )
        first.httpClient.enqueue(500, """{"message":"boom"}""")
        firstBilling.deliverPurchases(inAppTransaction)
        assertThat(first.pendingPurchases.hasPostContext("token-coins")).isTrue()

        // 新进程：Play 看不到这笔（消耗品被 consume / 过期），差集那条链路救不了它。
        val secondBilling = BillingHarness()
        val second = OrchestratorHarness(context, secondBilling, sharedPrefsName = prefsName)
        secondBilling.purchasesOnDevice = emptyList()
        second.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("coins_100" to true)))

        secondBilling.deliverConnected()

        val body = requireNotNull(second.receiptRequests().single().body)
        assertThat(body.getString("fetch_token")).isEqualTo("token-coins")
        assertThat(second.pendingPurchases.hasPostContext("token-coins")).isFalse()
    }

    // endregion

    // region 3. 回调重复、空列表

    @Test
    fun `onPurchasesUpdated 空列表 —— 不上报、不回调、上下文仍在进行中`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )

        billing.deliverPurchases()

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(recorder.results).isEmpty()
        assertThat(recorder.errors).isEmpty()
        // 上下文留着 —— 空列表什么也没说明，这笔可能马上就来。
        assertThat(harness.pendingPurchases.hasActive("sub_premium")).isTrue()
    }

    @Test
    fun `同一笔交易被回调两次 —— 宿主只收到一次结果（幂等由服务端按 token 保证）`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(subsTransaction())

        // Play 把同一批 purchases 又投了一次（坑：这在真机上真的会发生）。
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(subsTransaction())

        // 回调只一次：`takeCallback` 只能取一次。
        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.errors).isEmpty()
        // 第二次仍会上报 —— 端上不做 token 级去重（服务端按 purchaseToken 幂等，契约 §2.1）。
        // 这条断言是**故意**记录现状的：如果哪天改成端上去重，必须连这条一起改并说明理由。
        assertThat(harness.receiptRequests()).hasSize(2)
        assertThat(harness.receiptRequests().map { it.body?.getString("fetch_token") })
            .containsExactly("token-sub", "token-sub")
    }

    @Test
    fun `整体失败回调重复到达 —— 回调只排空一次，宿主不会收到两个错误`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )

        billing.deliverPurchaseFailure(PurchasesError(PurchasesErrorCode.StoreProblemError))
        billing.deliverPurchaseFailure(PurchasesError(PurchasesErrorCode.StoreProblemError))

        assertThat(recorder.errors).hasSize(1)
    }

    @Test
    fun `PENDING 之后转 PURCHASED —— 第一次不上报，第二次才上报且只回调一次 pending`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription).build(),
            recorder,
        )

        // 慢速测试卡：先来一笔 PENDING。
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        billing.deliverPurchases(subsTransaction(purchaseState = Purchase.PurchaseState.PENDING))

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(recorder.results.single().isPending).isTrue()

        // 几分钟后 Play 把它转成 PURCHASED：走补报链路（购买回调已经用掉了）。
        billing.purchasesOnDevice = listOf(subsTransaction())
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverConnected()

        assertThat(harness.receiptRequests()).hasSize(1)
        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.errors).isEmpty()
    }

    // endregion

    // region 4. 时钟回拨与 requestDate grace

    /** `{"expires_date": …}` 的最小 Subscriber 响应（权益 + 订阅两处日期一致）。 */
    private fun subscriberWithExpiry(expires: Date, requestDate: Date): String = """
        {
          "request_date": "${iso(requestDate)}",
          "subscriber": {
            "entitlements": {
              "premium": {
                "expires_date": "${iso(expires)}",
                "grace_period_expires_date": null,
                "product_identifier": "sub_premium",
                "purchase_date": "${iso(Date(requestDate.time - 1.hours.inWholeMilliseconds))}"
              }
            },
            "first_seen": "${iso(requestDate)}",
            "last_seen": "${iso(requestDate)}",
            "original_app_user_id": "user-42",
            "non_subscriptions": {},
            "subscriptions": {
              "sub_premium": {
                "expires_date": "${iso(expires)}",
                "purchase_date": "${iso(Date(requestDate.time - 1.hours.inWholeMilliseconds))}",
                "original_purchase_date": "${iso(Date(requestDate.time - 1.hours.inWholeMilliseconds))}",
                "store": "play_store",
                "period_type": "normal",
                "ownership_type": "PURCHASED",
                "is_sandbox": false
              }
            }
          }
        }
    """.trimIndent()

    private fun iso(date: Date): String = org.revdog.purchases.common.Iso8601Utils.format(date)

    @Test
    fun `本地钟被拨到过去（服务端时间在未来）—— 权益按服务端时间判定，薅不到羊毛`() {
        val serverNow = Date(System.currentTimeMillis() + 10.days.inWholeMilliseconds)
        // 服务端说：这个订阅一小时前就到期了。本地钟（被拨回 10 天）看起来还没到期。
        val expired = Date(serverNow.time - 1.hours.inWholeMilliseconds)
        harness.httpClient.enqueue(200, subscriberWithExpiry(expired, serverNow), requestDate = serverNow)

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        val info = recorder.received.single()
        assertThat(info.entitlements["premium"]?.isActive)
            .describedAs("服务端时间在 3 天 grace 内 → 一律信服务端，不信本地钟")
            .isFalse()
    }

    @Test
    fun `服务端时间超过 3 天 grace —— 回落本地钟，防永久离线白嫖`() {
        val staleServerNow = Date(System.currentTimeMillis() - 10.days.inWholeMilliseconds)
        // 按服务端时间算还有 5 天有效；按本地钟算 5 天前就过期了。
        val expired = Date(System.currentTimeMillis() - 5.days.inWholeMilliseconds)
        harness.httpClient.enqueue(
            200,
            subscriberWithExpiry(expired, staleServerNow),
            requestDate = staleServerNow,
        )

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        assertThat(recorder.received.single().entitlements["premium"]?.isActive).isFalse()
    }

    @Test
    fun `缓存里的 CustomerInfo 重新解析时用落盘的 request_date，不是读盘那一刻`() {
        val serverNow = Date(System.currentTimeMillis())
        val expiresSoon = Date(serverNow.time + 1.hours.inWholeMilliseconds)
        harness.httpClient.enqueue(200, subscriberWithExpiry(expiresSoon, serverNow), requestDate = serverNow)
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        // 直接从缓存读（CACHE_ONLY 一个请求都不发）：request_date 随 payload 一起落了盘。
        val cached = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.CACHE_ONLY, cached)

        assertThat(cached.received.single().entitlements["premium"]?.isActive).isTrue()
        assertThat(cached.received.single().requestDate.time).isEqualTo(serverNow.time)
    }

    // endregion

    // region 5. 身份：logIn 中途失败零副作用 + 并发串行

    @Test
    fun `logIn 中途失败 —— 身份、缓存、待同步属性、offerings 全部零副作用`() {
        // 先让当前身份有缓存与一条待同步属性。
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())
        harness.orchestrator.setAttribute("tier", "gold")
        val unsyncedBefore = harness.orchestrator.unsyncedAttributes().map { it.key }
        assertThat(unsyncedBefore).isNotEmpty

        // identify 5xx（属性搭车请求先走一发，用队列顶住两次）。
        harness.httpClient.defaultResponse = Result.success(FakeHTTPClient.response(500, """{"message":"boom"}"""))
        val callback = object : LogInCallback {
            var error: PurchasesError? = null
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) =
                throw AssertionError("不该成功")

            override fun onError(error: PurchasesError) {
                this.error = error
            }
        }
        harness.orchestrator.logIn("user-99", callback)

        assertThat(callback.error).isNotNull
        assertThat(harness.orchestrator.appUserID).isEqualTo("user-42")
        assertThat(harness.deviceCache.getCachedAppUserID()).isEqualTo("user-42")
        assertThat(harness.deviceCache.getCachedCustomerInfo("user-42")).isNotNull
        assertThat(harness.deviceCache.getCachedCustomerInfo("user-99")).isNull()
        // 属性没有被搬到新身份（搬过去就会落到别人名下）。
        assertThat(harness.attributesCache.unsynced("user-99")).isEmpty()
    }

    @Test
    fun `并发 logIn 同一个新身份 —— 只发一次 identify，两个回调都被调用，身份只切一次`() {
        val deferred = DeferredDispatcher()
        val httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        val backend = Backend(httpClient, deferred)
        val deviceCache = DeviceCache(
            context.getSharedPreferences("login-race-${System.nanoTime()}", Context.MODE_PRIVATE),
            FakeHTTPClient.TEST_API_KEY,
        )
        deviceCache.cacheAppUserID("user-42")
        val identity = IdentityManager(deviceCache, backend)

        var successes = 0
        repeat(2) {
            identity.logIn(
                newAppUserID = "user-99",
                onSuccess = { _, _ -> successes++ },
                onError = { throw AssertionError("不该失败：$it") },
            )
        }
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        deferred.runAll()

        assertThat(httpClient.recordedRequests.filter { it.fullURL.path == "/v1/subscribers/identify" }).hasSize(1)
        assertThat(successes).isEqualTo(2)
        assertThat(deviceCache.getCachedAppUserID()).isEqualTo("user-99")
    }

    @Test
    fun `并发 logIn 到两个不同身份 —— 各发一次、都带发起时的旧身份，提交串行`() {
        val deferred = DeferredDispatcher()
        val httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        val backend = Backend(httpClient, deferred)
        val deviceCache = DeviceCache(
            context.getSharedPreferences("login-race2-${System.nanoTime()}", Context.MODE_PRIVATE),
            FakeHTTPClient.TEST_API_KEY,
        )
        deviceCache.cacheAppUserID("user-42")
        val identity = IdentityManager(deviceCache, backend)

        val switched = mutableListOf<String>()
        listOf("user-a", "user-b").forEach { target ->
            identity.logIn(
                newAppUserID = target,
                onSuccess = { _, _ -> switched += target },
                onError = { throw AssertionError("不该失败：$it") },
            )
        }
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        deferred.runAll()

        val identifyBodies = httpClient.recordedRequests
            .filter { it.fullURL.path == "/v1/subscribers/identify" }
            .map { requireNotNull(it.body) }
        assertThat(identifyBodies).hasSize(2)
        // 两发都带**发起那一刻**的旧身份：第二发不会因为第一发已提交而变成 user-a→user-b。
        assertThat(identifyBodies.map { it.getString("app_user_id") }).containsExactly("user-42", "user-42")
        assertThat(switched).containsExactly("user-a", "user-b")
        // 提交在 `@Synchronized` 段里串行：最终身份是最后完成的那个，缓存不混。
        assertThat(deviceCache.getCachedAppUserID()).isEqualTo("user-b")
        assertThat(deviceCache.getCachedCustomerInfo("user-b")).isNotNull
    }

    // endregion

    // region 6. ETag 304 与 payload 损坏

    @Test
    fun `304 命中本地缓存 —— 第二次请求带上 eTag，空 body 的 304 也能解析出 CustomerInfo`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE, eTag = "etag-1")
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        // 304 的 body 是空的 —— 能成功解析只可能是因为读到了本地 payload。
        harness.httpClient.enqueue(304, "", eTag = "etag-1")
        val second = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, second)

        assertThat(second.errors).isEmpty()
        assertThat(second.received).hasSize(1)
        val subscriberRequests = harness.httpClient.recordedRequests.filter {
            it.fullURL.path.startsWith("/v1/subscribers/") && it.method == "GET"
        }
        assertThat(subscriberRequests).hasSize(2)
        assertThat(subscriberRequests[0].headers[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
        assertThat(subscriberRequests[1].headers[HTTPRequest.ETAG_HEADER_NAME]).isEqualTo("etag-1")
    }

    @Test
    fun `304 但本地 payload 被清掉 —— 自动重发一次且这次不带 eTag（缓存能自愈）`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE, eTag = "etag-1")
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        // 模拟 cacheDir 被系统清掉 / CRC 校验不过：元数据还在，payload 没了。
        harness.eTagPayloadStore.clear()

        harness.httpClient.enqueue(304, "", eTag = "etag-1")
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE, eTag = "etag-2")
        val third = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, third)

        assertThat(third.errors).isEmpty()
        assertThat(third.received).hasSize(1)
        val subscriberRequests = harness.httpClient.recordedRequests.filter {
            it.fullURL.path.startsWith("/v1/subscribers/") && it.method == "GET"
        }
        assertThat(subscriberRequests).hasSize(3)
        // 第三发是 refreshETag：**不带 eTag**，否则服务端会再回一个我们读不出来的 304。
        assertThat(subscriberRequests[2].headers[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
    }

    @Test
    fun `logIn 成功之后 ETag 被清 —— 新身份绝不会命中旧身份的 304`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE, eTag = "etag-1")
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.logIn(
            "user-99",
            object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = Unit
                override fun onError(error: PurchasesError) = throw AssertionError("不该失败：$error")
            },
        )

        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE, eTag = "etag-2")
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        val lastGet = harness.httpClient.recordedRequests.last {
            it.fullURL.path.startsWith("/v1/subscribers/") && it.method == "GET"
        }
        assertThat(lastGet.fullURL.path).contains("user-99")
        assertThat(lastGet.headers[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
    }

    // endregion

    // region 7. 诊断上传故障

    @Test
    fun `诊断上传 4xx —— 整批丢弃、不退避、不停机，后续事件照常上传`() {
        val rig = DiagnosticsRig(context)
        rig.httpClient.enqueue(400, """{"code":7400,"message":"bad shape"}""")
        // 攒到阈值那一下就地上传（`DiagnosticsRecorderTest` 锁住这个触发点）。
        repeat(DiagnosticsRecorder.UPLOAD_THRESHOLD) {
            rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED, mapOf("i" to it))
        }

        assertThat(rig.diagnosticsRequests()).hasSize(1)
        assertThat(rig.queue.count()).describedAs("确定性 4xx 丢批：留着只会一直被拒").isZero()
        assertThat(rig.uploader.backoffMs).isZero()
        assertThat(rig.uploader.isSuspended).isFalse()

        // 下一批照常发得出去（4xx 不该毒死整条管线）。
        rig.recorder.track(DiagnosticsTracker.EVENT_PURCHASE_STARTED, mapOf("product_id" to "sub_premium"))
        rig.enqueueAccepted()
        rig.recorder.flush()

        assertThat(rig.diagnosticsRequests()).hasSize(2)
        assertThat(rig.queue.count()).isZero()
    }

    @Test
    fun `诊断上传 5xx —— 队列保留并退避，恢复后原样补发（一条不丢）`() {
        val rig = DiagnosticsRig(context)
        rig.recorder.track(
            DiagnosticsTracker.EVENT_RECEIPT_POST,
            mapOf("outcome" to DiagnosticsTracker.OUTCOME_RETRYABLE, "status" to 503),
        )
        rig.httpClient.enqueue(503, """{"message":"unavailable"}""")
        rig.scheduler.runPending()

        assertThat(rig.uploader.backoffMs).isGreaterThan(0)
        // 队列已经轮转成「在飞文件」，失败时原样写回去 —— **一条不丢**（成功才删文件）。
        assertThat(rig.queue.linesOf(rig.queue.inflightFiles().single())).describedAs("一条不丢").hasSize(1)

        rig.nowMs += rig.uploader.backoffMs
        rig.enqueueAccepted()
        rig.recorder.flush()

        assertThat(rig.uploader.backoffMs).isZero()
        assertThat(rig.queue.inflightFiles()).isEmpty()
        // 恢复之后队列里只剩那条「诊断自己失败过」的告警（§6-13：绝不为诊断失败再发诊断请求）。
        assertThat(rig.events().map { it.type }).containsExactly(DiagnosticsTracker.EVENT_SDK_WARNING)
        assertThat(rig.events().single().fields.getString("code"))
            .isEqualTo(DiagnosticsWarningCode.DIAG_UPLOAD_FAILED)
    }

    // endregion

}
