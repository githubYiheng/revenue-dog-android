package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.common.CacheDurations
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * **联网取 CustomerInfo 之前先补报待同步购买**（对照 RC `CustomerInfoHelper.kt:88-109, 130-183`）。
 *
 * 缺这一步的后果：一笔「买成功但上报前进程被杀」的交易，要等到下一次 BillingClient 连接成功
 * 或回前台才会被送出去；在那之前宿主主动 `getCustomerInfo()` 拉到的权益里没有它 ——
 * 用户付了钱、刷新了页面，还是看不到权益。
 *
 * 口径（逐条钉死）：
 * - 补报**真的报了东西且拿回了 CustomerInfo** → 直接交付那份，不再多发一次 GET；
 * - 没有待补报 / 补报出错 / `my_app` 模式 → 退回原来的 GET；
 * - `CACHE_ONLY` 与「缓存未过期直接命中」→ 一个请求都不发，自然也不补报。
 */
@RunWith(RobolectricTestRunner::class)
class SyncBeforeFetchTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
        billing.subscriptionProducts = listOf(
            StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails(),
        )
        billing.inAppProducts = emptyList()
    }

    private fun unsyncedPurchase(token: String = "token-unsynced"): StoreTransaction = purchaseFixture(
        productIds = listOf("sub_premium"),
        purchaseToken = token,
        autoRenewing = true,
    ).toStoreTransaction(ProductType.SUBS, subscriptionOptionId = "monthly-base")

    /** 只数 `GET /v1/subscribers/{id}`（排除 identify / attributes / offerings）。 */
    private fun subscriberRequests() =
        harness.httpClient.recordedRequests.filter { SUBSCRIBER_GET.matches(it.fullURL.path) }

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

    /** 上报响应做个记号，好断言「交付的是上报回来的那份、不是 GET 回来的那份」。 */
    private fun markedReceiptResponse(marker: String): String =
        Fixtures.receiptResponse(mapOf("sub_premium" to false)).replace("XXX-XXXXX-XXXXX-XX", marker)

    @Test
    fun `有未上报购买时 FETCH_CURRENT 只发 receipts，交付的是上报带回来的那份`() {
        billing.purchasesOnDevice = listOf(unsyncedPurchase())
        harness.httpClient.enqueue(200, markedReceiptResponse("from-post"))

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        assertThat(harness.receiptRequests()).hasSize(1)
        assertThat(subscriberRequests()).isEmpty()
        assertThat(recorder.received.single().originalAppUserId).isEqualTo("from-post")
    }

    @Test
    fun `没有待补报时 FETCH_CURRENT 只发一次 GET`() {
        billing.purchasesOnDevice = emptyList()
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(subscriberRequests()).hasSize(1)
        assertThat(recorder.received).hasSize(1)
    }

    @Test
    fun `补报 5xx 失败时退回 GET，宿主照样拿到 CustomerInfo`() {
        billing.purchasesOnDevice = listOf(unsyncedPurchase())
        // 补报走的是 `unsynced_active_purchases`，不落上报上下文（`getOrPutPostContext` 只在
        // `purchase` 时写），所以这一轮只会有一发 POST。
        harness.httpClient.enqueue(500, """{"message":"boom"}""")
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        assertThat(harness.receiptRequests()).hasSize(1)
        assertThat(subscriberRequests()).hasSize(1)
        assertThat(recorder.errors).isEmpty()
        assertThat(recorder.received).hasSize(1)
    }

    @Test
    fun `CACHE_ONLY 与未过期命中都不触发补报`() {
        // 先把缓存填上（这一次是允许补报的，用空 Play 状态避免干扰）。
        billing.purchasesOnDevice = emptyList()
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, InfoRecorder())

        // 之后 Play 上冒出一笔没上报的购买：这两条路一个请求都不该发。
        billing.purchasesOnDevice = listOf(unsyncedPurchase())
        val requestsBefore = harness.httpClient.recordedRequests.size

        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.CACHE_ONLY, InfoRecorder())
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.CACHED_OR_FETCHED, InfoRecorder())
        harness.nowMs += CacheDurations.FOREGROUND.inWholeMilliseconds - 1_000
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT, InfoRecorder())

        assertThat(harness.httpClient.recordedRequests.size).isEqualTo(requestsBefore)
        assertThat(harness.receiptRequests()).isEmpty()
    }

    @Test
    fun `my_app 模式下不顺带补报，直接 GET`() {
        val myAppHarness = OrchestratorHarness(
            context,
            billing,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.MY_APP,
        )
        billing.purchasesOnDevice = listOf(unsyncedPurchase())
        myAppHarness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val recorder = InfoRecorder()
        myAppHarness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        assertThat(myAppHarness.receiptRequests()).isEmpty()
        assertThat(recorder.received).hasSize(1)

        // 但显式触发点不受影响：my_app 下仍然照常上报（只是不 ack / consume）。
        myAppHarness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        myAppHarness.orchestrator.syncPendingPurchaseQueue()

        assertThat(myAppHarness.receiptRequests()).hasSize(1)
    }

    @Test
    fun `BillingClient 迟迟不回时超时兜底直接走 GET —— 回调不被 Play 的连接状态劫持，且只交付一次`() {
        billing.queryPurchasesNeverResponds = true
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)

        // 补报卡在 queryPurchases 上：此刻既没有上报也没有 GET，回调还没来
        assertThat(subscriberRequests()).isEmpty()
        assertThat(recorder.received).isEmpty()

        harness.fireSyncTimeouts()

        assertThat(subscriberRequests()).hasSize(1)
        assertThat(recorder.received).hasSize(1)
        assertThat(recorder.errors).isEmpty()
    }

    @Test
    fun `补报先回来之后超时再到 —— 不再多发 GET、不重复交付`() {
        billing.purchasesOnDevice = emptyList()
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val recorder = InfoRecorder()
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, recorder)
        assertThat(recorder.received).hasSize(1)

        harness.fireSyncTimeouts()

        assertThat(subscriberRequests()).hasSize(1)
        assertThat(recorder.received).hasSize(1)
    }

    private companion object {
        val SUBSCRIBER_GET = Regex("^/v1/subscribers/[^/]+$")
    }
}
