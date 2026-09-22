package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.diagnostics.DiagnosticsErrorFields
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.billingResponseToPurchasesError
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.posting.PostReceiptErrorHandling
import org.revdog.purchases.posting.toPurchasePostingError
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 0.1.2「失败类诊断补齐原因字段」。
 *
 * 起因是 2026-09-22 生产走查里的两条 error 级诊断：`sync` 只报了
 * `purchaseNotAllowedError`（背后是三个 Play 响应码之一，分不出），
 * `identity_login` 只报了 `unknownBackendError`（没有 `status`，判不了是谁回的）。
 *
 * 本文件锁住三件事：[DiagnosticsErrorFields.classify] 的七个分支各一例、
 * 两条真实失败链路带上了新字段、以及新字段不会突破截断/体积上限、不会带出凭据。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsErrorFieldsTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    private class Recorder : ReceiveCustomerInfoCallback {
        val errors: MutableList<PurchasesError> = mutableListOf()
        override fun onReceived(customerInfo: CustomerInfo) = Unit
        override fun onError(error: PurchasesError) {
            errors += error
        }
    }

    private class LogInRecorder : LogInCallback {
        val errors: MutableList<PurchasesError> = mutableListOf()
        override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = Unit
        override fun onError(error: PurchasesError) {
            errors += error
        }
    }

    // region 1. error_class 映射表：七个分支各一例

    @Test
    fun `error_class billing：Play 给了响应码就一定是 billing`() {
        val error = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            .billingResponseToPurchasesError("查询购买失败", "Play Store unavailable")
        assertThat(DiagnosticsErrorFields.classify(error)).isEqualTo(DiagnosticsErrorFields.BILLING)
    }

    @Test
    fun `error_class billing：端上自己判定的 Play 语义错误也是 billing`() {
        // 「只有订阅支持升降级」这类错误拿不到响应码，只能靠码位归类。
        val error = PurchasesError(PurchasesErrorCode.PurchaseNotAllowedError, "只有订阅支持升降级")
        assertThat(error.billingResponseCode).isNull()
        assertThat(DiagnosticsErrorFields.classify(error)).isEqualTo(DiagnosticsErrorFields.BILLING)
    }

    @Test
    fun `error_class timeout：码位同是 networkError，靠底层异常类名分出超时`() {
        val timeout = PurchasesError(PurchasesErrorCode.NetworkError, "read timed out")
            .withCause(SocketTimeoutException("read timed out"))
        assertThat(DiagnosticsErrorFields.classify(timeout)).isEqualTo(DiagnosticsErrorFields.TIMEOUT)
    }

    @Test
    fun `error_class network：断网的 networkError 不是 timeout`() {
        val offline = PurchasesError(PurchasesErrorCode.NetworkError, "api.revdog.test")
            .withCause(UnknownHostException("api.revdog.test"))
        assertThat(DiagnosticsErrorFields.classify(offline)).isEqualTo(DiagnosticsErrorFields.NETWORK)

        // 连异常都没有（离线权益等路径）同样归 network。
        val offlineCode = PurchasesError(PurchasesErrorCode.OfflineConnectionError)
        assertThat(DiagnosticsErrorFields.classify(offlineCode)).isEqualTo(DiagnosticsErrorFields.NETWORK)
    }

    @Test
    fun `error_class http：服务端确实回了非 2xx`() {
        val error = PurchasesError(
            PurchasesErrorCode.UnknownBackendError,
            "unavailable",
            backendCode = 7503,
            httpStatusCode = 503,
        )
        assertThat(DiagnosticsErrorFields.classify(error)).isEqualTo(DiagnosticsErrorFields.HTTP)
    }

    @Test
    fun `error_class parse：响应不是我们要的形状`() {
        assertThat(
            DiagnosticsErrorFields.classify(
                PurchasesError(PurchasesErrorCode.UnexpectedBackendResponseError, "identify 响应为空"),
            ),
        ).isEqualTo(DiagnosticsErrorFields.PARSE)
    }

    @Test
    fun `error_class config：接线或用法问题`() {
        assertThat(
            DiagnosticsErrorFields.classify(PurchasesError.configuration("缺少 INTERNET 权限？")),
        ).isEqualTo(DiagnosticsErrorFields.CONFIG)
        assertThat(
            DiagnosticsErrorFields.classify(
                PurchasesError(PurchasesErrorCode.InvalidAppUserIdError, "logIn 需要一个非空的 appUserID"),
            ),
        ).isEqualTo(DiagnosticsErrorFields.CONFIG)
    }

    @Test
    fun `error_class unknown：落不上任何一条就是 unknown，绝不硬塞`() {
        assertThat(
            DiagnosticsErrorFields.classify(PurchasesError(PurchasesErrorCode.UnknownError, "说不清")),
        ).isEqualTo(DiagnosticsErrorFields.UNKNOWN)
    }

    @Test
    fun `classify 对全部码位都只返回表内的七个值`() {
        PurchasesErrorCode.ALL.forEach { code ->
            assertThat(DiagnosticsErrorFields.classify(PurchasesError(code)))
                .describedAs("码位 ${code.name}")
                .isIn(DiagnosticsErrorFields.ALL_CLASSES)
        }
        assertThat(DiagnosticsErrorFields.ALL_CLASSES).hasSize(7).doesNotHaveDuplicates()
    }

    @Test
    fun `of 把七个键一次性交齐，缺值的由 normalizeFields 丢掉`() {
        val full = PurchasesError(
            PurchasesErrorCode.UnknownBackendError,
            "unavailable",
            backendCode = 7503,
            httpStatusCode = 503,
        )
        assertThat(DiagnosticsErrorFields.of(full).keys).containsExactlyInAnyOrder(
            DiagnosticsErrorFields.KEY_ERROR_CODE,
            DiagnosticsErrorFields.KEY_STATUS,
            DiagnosticsErrorFields.KEY_BACKEND_CODE,
            DiagnosticsErrorFields.KEY_ERROR_CLASS,
            DiagnosticsErrorFields.KEY_UNDERLYING,
            DiagnosticsErrorFields.KEY_BILLING_RESPONSE_CODE,
            DiagnosticsErrorFields.KEY_BILLING_DEBUG_MESSAGE,
        )

        // 只有码位的错误：其余六个键都是 null，规整之后一个都不进 wire。
        val bare = PurchasesError(PurchasesErrorCode.UnknownError)
        val normalized = DiagnosticsEvent.normalizeFields(DiagnosticsErrorFields.of(bare))
        assertThat(normalized.length()).isEqualTo(2)
        assertThat(normalized.getString(DiagnosticsErrorFields.KEY_ERROR_CODE)).isEqualTo("unknownError")
        assertThat(normalized.getString(DiagnosticsErrorFields.KEY_ERROR_CLASS))
            .isEqualTo(DiagnosticsErrorFields.UNKNOWN)
    }

    @Test
    fun `包成 901 时诊断附注跟着搬，不在包装那一刻丢掉原因`() {
        val timeout = PurchasesError(PurchasesErrorCode.NetworkError, "read timed out")
            .withCause(SocketTimeoutException("read timed out"))
        val wrapped = timeout.toPurchasePostingError(PostReceiptErrorHandling.SHOULD_NOT_CONSUME)
        assertThat(wrapped.code).isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
        assertThat(DiagnosticsErrorFields.classify(wrapped)).isEqualTo(DiagnosticsErrorFields.TIMEOUT)
    }

    // endregion

    // region 2. 真实失败链路

    @Test
    fun `sync 失败带 billing_response_code 与 billing_debug_message`() {
        billing.queryPurchasesError = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            .billingResponseToPurchasesError(
                "查询购买失败 - DebugMessage: Play Store unavailable. ErrorCode: BILLING_UNAVAILABLE.",
                "Play Store unavailable",
            )

        val recorder = Recorder()
        harness.orchestrator.syncPurchases(recorder)

        assertThat(recorder.errors).hasSize(1)
        val failed = harness.diagnostics.named(DiagnosticsTracker.EVENT_SYNC)
            .single { it["outcome"] == DiagnosticsTracker.OUTCOME_FAILED }
        // 0.1.1 只有这一个 —— 三个 Play 响应码共用它，线上分不出是哪一个。
        assertThat(failed["error_code"]).isEqualTo("purchaseNotAllowedError")
        // 0.1.2 补的两个：原始码位与 Play 自己的说明。
        assertThat(failed[DiagnosticsErrorFields.KEY_BILLING_RESPONSE_CODE])
            .isEqualTo(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        assertThat(failed[DiagnosticsErrorFields.KEY_BILLING_DEBUG_MESSAGE]).isEqualTo("Play Store unavailable")
        assertThat(failed[DiagnosticsErrorFields.KEY_ERROR_CLASS]).isEqualTo(DiagnosticsErrorFields.BILLING)
        assertThat(failed[DiagnosticsErrorFields.KEY_UNDERLYING] as String).contains("BILLING_UNAVAILABLE")
        // 既有字段一个没动。
        assertThat(failed["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_FAILED)
        assertThat(failed["duration_ms"]).isNotNull()
    }

    @Test
    fun `restore 失败同样带 Billing 两项`() {
        billing.queryPurchasesError = BillingClient.BillingResponseCode.ITEM_NOT_OWNED
            .billingResponseToPurchasesError("查询购买失败", "nothing owned")

        harness.orchestrator.restorePurchases(Recorder())

        val failed = harness.diagnostics.named(DiagnosticsTracker.EVENT_RESTORE)
            .single { it["outcome"] == DiagnosticsTracker.OUTCOME_FAILED }
        assertThat(failed[DiagnosticsErrorFields.KEY_BILLING_RESPONSE_CODE])
            .isEqualTo(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        assertThat(failed[DiagnosticsErrorFields.KEY_BILLING_DEBUG_MESSAGE]).isEqualTo("nothing owned")
    }

    @Test
    fun `identity_login 失败带 status 与 backend_code`() {
        harness.httpClient.enqueue(503, """{"code":7503,"message":"upstream unavailable"}""")

        val recorder = LogInRecorder()
        harness.orchestrator.logIn("user-99", recorder)

        assertThat(recorder.errors).hasSize(1)
        val failed = harness.diagnostics.named(DiagnosticsTracker.EVENT_IDENTITY_LOGIN).single()
        assertThat(failed["error_code"]).isEqualTo("unknownBackendError")
        // 0.1.2 补的：没有这三个就判不出是边缘层还是我方 API。
        assertThat(failed[DiagnosticsErrorFields.KEY_STATUS]).isEqualTo(503)
        assertThat(failed[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isEqualTo(7503)
        assertThat(failed[DiagnosticsErrorFields.KEY_ERROR_CLASS]).isEqualTo(DiagnosticsErrorFields.HTTP)
        assertThat(failed[DiagnosticsErrorFields.KEY_UNDERLYING]).isEqualTo("upstream unavailable")
        assertThat(failed["request_id"]).isEqualTo("req-test")
    }

    @Test
    fun `purchase_result 失败带 Billing 响应码，购买取消仍然是 info 口径的 cancelled`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        billing.deliverPurchaseFailure(
            BillingClient.BillingResponseCode.USER_CANCELED
                .billingResponseToPurchasesError("购买更新失败", "User canceled the flow"),
            userCancelled = true,
        )

        val result = harness.diagnostics.named(DiagnosticsTracker.EVENT_PURCHASE_RESULT).single()
        assertThat(result["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_CANCELLED)
        assertThat(result[DiagnosticsErrorFields.KEY_BILLING_RESPONSE_CODE])
            .isEqualTo(BillingClient.BillingResponseCode.USER_CANCELED)
        assertThat(result[DiagnosticsErrorFields.KEY_BILLING_DEBUG_MESSAGE]).isEqualTo("User canceled the flow")
    }

    @Test
    fun `receipt_post 的 error_class 仍是契约 §1_3 的四值口径，只多了 backend_code 与 underlying`() {
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

        val post = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        // 不变式 18 与 admin 的 launch-sync 分组逐字依赖这个取值，0.1.2 一个字没动。
        assertThat(post["error_class"]).isEqualTo("server")
        assertThat(post["status"]).isEqualTo(503)
        // 只增的两个。
        assertThat(post[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isEqualTo(7503)
        assertThat(post[DiagnosticsErrorFields.KEY_UNDERLYING]).isEqualTo("unavailable")
    }

    // endregion

    // region 3. 截断、体积与脱敏

    @Test
    fun `超长 debugMessage 截到 200，单条仍然守住 2048 字节并成功入队`() {
        val huge = "x".repeat(5_000)
        val error = BillingClient.BillingResponseCode.DEVELOPER_ERROR
            .billingResponseToPurchasesError(huge, huge)

        val event = DiagnosticsEvent.create(
            id = "evt-huge",
            tsMs = 1_789_000_000_000L,
            appUserID = "user-42",
            seq = 1,
            type = DiagnosticsTracker.EVENT_SYNC,
            level = DiagnosticsLevel.ERROR,
            fields = mapOf("outcome" to DiagnosticsTracker.OUTCOME_FAILED) + DiagnosticsErrorFields.of(error),
        )

        assertThat(event.fields.getString(DiagnosticsErrorFields.KEY_BILLING_DEBUG_MESSAGE))
            .hasSize(DiagnosticsEvent.MAX_FIELD_STRING_LENGTH)
        assertThat(event.fields.getString(DiagnosticsErrorFields.KEY_UNDERLYING))
            .hasSize(DiagnosticsEvent.MAX_FIELD_STRING_LENGTH)
        assertThat(event.toLine().toByteArray(Charsets.UTF_8).size)
            .describedAs("单条序列化必须守住契约 §1.3 的 2 KB 上限，否则端上直接丢事件")
            .isLessThanOrEqualTo(DiagnosticsEvent.MAX_SERIALIZED_BYTES)

        // 真队列也得收下它（超限的会被 append 静默丢掉，那等于原因字段白加了）。
        val queue = DiagnosticsQueue(File(context.cacheDir, "diag-huge-${System.nanoTime()}"))
        assertThat(queue.append(event)).isEqualTo(0)
        assertThat(queue.count()).isEqualTo(1)
    }

    @Test
    fun `新字段不带出 apiKey、purchaseToken 或 Authorization 头`() {
        // 一条完整的失败链路：购买 → 上报 503 → 再补一次 sync 失败。
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        harness.httpClient.enqueue(401, """{"code":7243,"message":"invalid credentials"}""")
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = SECRET_TOKEN)
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )
        billing.queryPurchasesError = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            .billingResponseToPurchasesError("查询购买失败", "DebugMessage: unavailable")
        harness.orchestrator.syncPurchases(Recorder())

        val everyStringValue = harness.diagnostics.events
            .flatMap { it.second.values }
            .filterIsInstance<String>()
        assertThat(everyStringValue).isNotEmpty()
        everyStringValue.forEach { value ->
            assertThat(value)
                .describedAs("诊断字段里出现了不该出现的东西：%s", value)
                .doesNotContain(FakeHTTPClient.TEST_API_KEY)
                .doesNotContain(SECRET_TOKEN)
                .doesNotContain("Authorization")
                .doesNotContain("Bearer ")
        }
        // 确认这一轮真的打出了 401 的失败诊断（否则上面的断言是在空跑）。
        val post = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        assertThat(post[DiagnosticsErrorFields.KEY_BACKEND_CODE]).isEqualTo(7243)
        assertThat(post["error_class"]).isEqualTo("auth")
    }

    // endregion

    private companion object {
        /** 故意长得像凭据：断言扫到它就说明有东西漏进了 fields。 */
        const val SECRET_TOKEN = "purchase-token-do-not-log-4f3a9c"
    }
}
