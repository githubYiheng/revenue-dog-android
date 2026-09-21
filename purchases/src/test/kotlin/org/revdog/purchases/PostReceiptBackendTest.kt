package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.posting.InitiationSource
import org.revdog.purchases.posting.PlatformProductId
import org.revdog.purchases.posting.PostReceiptErrorHandling
import org.revdog.purchases.posting.ReceiptInfo
import org.revdog.purchases.posting.classifyPostReceiptError
import org.revdog.purchases.support.DeferredDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.robolectric.RobolectricTestRunner

/**
 * `Backend.postReceiptData` 的并发去重键与错误分类。
 *
 * 去重键必须把**上报语义**全带上（考古 §2.13）：同一个 token 以 `purchase` 与以 `restore`
 * 上报是两件不同的事 —— `is_restore` 影响转移判定（ADR 0046 ②），合并成一次请求就错了。
 */
@RunWith(RobolectricTestRunner::class)
class PostReceiptBackendTest {

    private lateinit var context: Context
    private lateinit var httpClient: FakeHTTPClient
    private lateinit var dispatcher: DeferredDispatcher
    private lateinit var backend: Backend

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        dispatcher = DeferredDispatcher()
        backend = Backend(httpClient, dispatcher)
    }

    private fun receiptInfo(productId: String = "sub_premium") = ReceiptInfo(
        productIds = listOf(productId),
        platformProductIds = listOf(PlatformProductId(productId, "monthly-base", null)),
        sdkOriginated = true,
    )

    private fun post(
        token: String = "token-a",
        isRestore: Boolean = false,
        initiationSource: String = InitiationSource.PURCHASE,
        appUserID: String = "user-42",
        onDone: () -> Unit = {},
    ) {
        backend.postReceiptData(
            purchaseToken = token,
            appUserID = appUserID,
            isRestore = isRestore,
            receiptInfo = receiptInfo(),
            initiationSource = initiationSource,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
            appInBackground = false,
            onSuccess = { onDone() },
            onError = { _, _ -> onDone() },
        )
    }

    @Test
    fun `完全相同的两次上报合并成一次请求，但两个回调都被调用`() {
        httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        var callbacks = 0
        post(onDone = { callbacks++ })
        post(onDone = { callbacks++ })

        dispatcher.runAll()

        assertThat(httpClient.recordedRequests).hasSize(1)
        assertThat(callbacks).isEqualTo(2)
    }

    @Test
    fun `合并回调的 debug 日志不含 purchaseToken 原文，也不含 app_user_id`() {
        // 去重键里装着 purchaseToken + appUserID + 整个 receiptInfo JSON。
        // 直接把 key 打进 logcat 等于泄露购买凭据（0.1.1 修）。
        val secretToken = "token-super-secret-abcdefghijklmnop"
        val captured = mutableListOf<String>()
        val previousHandler = Logger.handler
        val previousLevel = Logger.logLevel
        Logger.handler = object : LogHandler {
            override fun log(level: LogLevel, message: String, throwable: Throwable?) {
                captured += message
            }
        }
        Logger.logLevel = LogLevel.VERBOSE
        try {
            httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
            post(token = secretToken)
            post(token = secretToken)

            dispatcher.runAll()
        } finally {
            Logger.handler = previousHandler
            Logger.logLevel = previousLevel
        }

        assertThat(httpClient.recordedRequests).hasSize(1)
        val mergeLogs = captured.filter { it.contains("合并回调") }
        assertThat(mergeLogs).describedAs("合并分支应该记了日志，否则这条用例什么都没守住").hasSize(1)
        assertThat(captured).noneMatch { it.contains(secretToken) }
        assertThat(captured).noneMatch { it.contains("user-42") }
        assertThat(mergeLogs.single()).contains("sha1=")
    }

    @Test
    fun `同一 token 的 purchase 与 restore 绝不合并（is_restore 影响转移判定）`() {
        httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        post(isRestore = false, initiationSource = InitiationSource.PURCHASE)
        post(isRestore = true, initiationSource = InitiationSource.RESTORE)

        dispatcher.runAll()

        assertThat(httpClient.recordedRequests).hasSize(2)
    }

    @Test
    fun `不同 token、不同 appUserID 都不合并`() {
        repeat(3) { httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false))) }
        post(token = "token-a")
        post(token = "token-b")
        post(token = "token-a", appUserID = "user-99")

        dispatcher.runAll()

        assertThat(httpClient.recordedRequests).hasSize(3)
    }

    @Test
    fun `错误分类与 iOS TransactionPoster 逐条对齐`() {
        val retryable = listOf(500, 502, 503, 401, 403, 404, 408, 429)
        retryable.forEach { code ->
            assertThat(classifyPostReceiptError(code))
                .describedAs("HTTP %s 必须可重试（→ 901）", code)
                .isEqualTo(PostReceiptErrorHandling.SHOULD_NOT_CONSUME)
        }
        // 网络层失败没有状态码。
        assertThat(classifyPostReceiptError(null)).isEqualTo(PostReceiptErrorHandling.SHOULD_NOT_CONSUME)

        val deterministic = listOf(400, 402, 409, 410, 422)
        deterministic.forEach { code ->
            assertThat(classifyPostReceiptError(code))
                .describedAs("HTTP %s 是确定性拒绝（→ 902，ack 但不 consume）", code)
                .isEqualTo(PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED)
        }
    }
}
