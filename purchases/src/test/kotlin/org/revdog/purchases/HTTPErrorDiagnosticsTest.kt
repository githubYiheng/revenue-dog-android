package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.diagnostics.DiagnosticsErrorFields
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsLevels
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.DiagnosticsRig
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 0.1.3 补发 `http_error`（契约 §1.3 的记录点「非 receipts 端点的非 2xx」）。
 *
 * 为什么 0.1.2 之前一条都没发：事件名常量在 `DiagnosticsTracker` 里躺着，但没有任何地方调它。
 * 后果是 jobs 的不变式 19 `sdk_auth_failures` 统计 `http_error`/`receipt_post` 的 401/403，
 * Android 侧只有 `receipt_post` 那一半在供数 —— 一个把 key 配错的版本，只要用户还没触发购买，
 * 线上就看不见。
 *
 * 本文件锁住四件事：发了（且字段齐）、**不该发的绝不发**（receipts 双计、诊断上传自激、
 * 304 协商命中）、`path` 不带 `app_user_id` 也不带查询串、以及没有响应时 `status` 缺省。
 */
@RunWith(RobolectricTestRunner::class)
class HTTPErrorDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
        // 默认没有待补报购买：省得 `getCustomerInfo(FETCH_CURRENT)` 先发一发 receipts。
        billing.purchasesOnDevice = emptyList()
    }

    private class InfoRecorder : ReceiveCustomerInfoCallback {
        override fun onReceived(customerInfo: CustomerInfo) = Unit
        override fun onError(error: PurchasesError) = Unit
    }

    private class LogInRecorder : LogInCallback {
        override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = Unit
        override fun onError(error: PurchasesError) = Unit
    }

    private fun httpErrors(): List<Map<String, Any?>> =
        harness.diagnostics.named(DiagnosticsTracker.EVENT_HTTP_ERROR)

    // region 1. 发了，而且字段齐

    @Test
    fun `identify 401 记一条 http_error（auth）`() {
        harness.httpClient.enqueue(401, """{"code":7243,"message":"invalid credentials"}""")

        harness.orchestrator.logIn("user-99", LogInRecorder())

        val event = httpErrors().single()
        assertThat(event["path"]).isEqualTo("/v1/subscribers/identify")
        assertThat(event[DiagnosticsErrorFields.KEY_STATUS]).isEqualTo(401)
        assertThat(event[DiagnosticsErrorFields.KEY_ERROR_CLASS]).isEqualTo(DiagnosticsErrorFields.AUTH)
        assertThat(event[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isEqualTo(7243)
        assertThat(event["request_id"]).isEqualTo("req-test")
        assertThat(event["duration_ms"] as Long).isGreaterThanOrEqualTo(0)
        // 这条事件就是不变式 19 缺的那一半：Android 终于能在没有购买的情况下报出 401。
        assertThat(DiagnosticsLevels.levelFor(DiagnosticsTracker.EVENT_HTTP_ERROR, event))
            .isEqualTo(DiagnosticsLevel.ERROR)
    }

    @Test
    fun `subscribers GET 503 记 server`() {
        harness.httpClient.enqueue(503, """{"code":7503,"message":"upstream unavailable"}""")

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        val event = httpErrors().single()
        assertThat(event["path"]).isEqualTo("/v1/subscribers/*")
        assertThat(event[DiagnosticsErrorFields.KEY_STATUS]).isEqualTo(503)
        assertThat(event[DiagnosticsErrorFields.KEY_ERROR_CLASS]).isEqualTo(DiagnosticsErrorFields.SERVER)
        assertThat(event[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isEqualTo(7503)
    }

    @Test
    fun `404 这类 4xx 记 client`() {
        harness.httpClient.enqueue(404, """{"code":7404,"message":"no such subscriber"}""")

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        assertThat(httpErrors().single()[DiagnosticsErrorFields.KEY_ERROR_CLASS])
            .isEqualTo(DiagnosticsErrorFields.CLIENT)
    }

    @Test
    fun `没有响应时 status 缺省，超时记 timeout、断网记 network`() {
        harness.httpClient.enqueueThrowable(SocketTimeoutException("read timed out"))
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        val timeout = httpErrors().single()
        assertThat(timeout[DiagnosticsErrorFields.KEY_ERROR_CLASS]).isEqualTo(DiagnosticsErrorFields.TIMEOUT)
        assertThat(timeout[DiagnosticsErrorFields.KEY_STATUS]).isNull()
        assertThat(timeout["request_id"]).isNull()
        assertThat(timeout[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isNull()
        // 缺省 = 这个键根本不进 wire（契约「fields 全部可选，缺失容忍」）。
        val normalized = DiagnosticsEvent.normalizeFields(timeout)
        assertThat(normalized.has(DiagnosticsErrorFields.KEY_STATUS)).isFalse()
        assertThat(normalized.getString("path")).isEqualTo("/v1/subscribers/*")

        harness.httpClient.enqueueThrowable(UnknownHostException("api.revdog.test"))
        harness.orchestrator.logIn("user-99", LogInRecorder())

        assertThat(httpErrors()).hasSize(2)
        assertThat(httpErrors()[1][DiagnosticsErrorFields.KEY_ERROR_CLASS])
            .isEqualTo(DiagnosticsErrorFields.NETWORK)
    }

    @Test
    fun `path 只留脱敏形态：不带 app_user_id，也不带查询串`() {
        harness.httpClient.enqueue(500, """{"message":"boom"}""")

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        // 真正发出去的 URL 里当然有 app_user_id —— 诊断字段里一个字都不能有。
        val sent = harness.httpClient.recordedRequests.single { it.fullURL.path.startsWith("/v1/subscribers/") }
        assertThat(sent.fullURL.path).contains(harness.appUserID)

        val path = httpErrors().single()["path"] as String
        assertThat(path).isEqualTo("/v1/subscribers/*")
        assertThat(path).doesNotContain(harness.appUserID).doesNotContain("?").doesNotContain("=")
    }

    // endregion

    // region 2. 不该发的绝不发

    @Test
    fun `receipts 非 2xx 只记 receipt_post，不记 http_error（双计会污染不变式 18）`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        harness.httpClient.enqueue(503, """{"code":7503,"message":"unavailable"}""")
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        assertThat(harness.receiptRequests()).hasSize(1)
        assertThat(harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST)).hasSize(1)
        assertThat(httpErrors()).isEmpty()
    }

    @Test
    fun `2xx 一条都不记`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        assertThat(httpErrors()).isEmpty()
    }

    @Test
    fun `304 是 ETag 协商命中，不是错误`() {
        // 本地没有 payload → 304 之后会带 refreshETag 整个重发一次，第二次拿到 200。
        harness.httpClient.enqueue(304, "", eTag = "\"v1\"")
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        assertThat(harness.httpClient.recordedRequests).hasSize(2)
        assertThat(httpErrors()).isEmpty()
    }

    @Test
    fun `诊断上传自己失败不产生 http_error（否则自激成事件雪崩）`() {
        val rig = DiagnosticsRig(context)
        rig.queue.append(
            DiagnosticsEvent.create(
                id = "evt-1",
                tsMs = DiagnosticsRig.FIXED_NOW_MS,
                appUserID = "user-42",
                seq = 1,
                type = DiagnosticsTracker.EVENT_SDK_CONFIGURED,
                level = DiagnosticsLevel.INFO,
                fields = emptyMap(),
            ),
        )
        // 三种处置各来一次：5xx 退避、401 停 1h、4xx 丢批。哪一种都不许生出新事件。
        listOf(503, 401, 400).forEach { status ->
            rig.httpClient.enqueue(status, """{"code":7$status,"message":"nope"}""")
            rig.uploader.upload()
        }

        assertThat(rig.diagnosticsRequests()).isNotEmpty()
        assertThat(rig.events().map { it.type }).doesNotContain(DiagnosticsTracker.EVENT_HTTP_ERROR)
    }

    // endregion
}
