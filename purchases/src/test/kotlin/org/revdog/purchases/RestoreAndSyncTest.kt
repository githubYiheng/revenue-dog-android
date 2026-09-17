package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.common.sha1
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.purchaseFixture
import org.robolectric.RobolectricTestRunner

/**
 * `restorePurchases` 与 `syncPurchases`（设计 §3 A6、考古 §9.3 决策 C）。
 *
 * 两者的差别很隐蔽但很重要：
 * | | `initiation_source` | `is_restore` | 碰 Billing 的完成动作 |
 * |---|---|---|---|
 * | restore | `restore` | `true` | **会**（consume / ack） |
 * | sync | `unsynced_active_purchases` | `false` | **不会**（只记台账） |
 */
@RunWith(RobolectricTestRunner::class)
class RestoreAndSyncTest {

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
        val received: MutableList<CustomerInfo> = mutableListOf()
        val errors: MutableList<PurchasesError> = mutableListOf()

        override fun onReceived(customerInfo: CustomerInfo) {
            received += customerInfo
        }

        override fun onError(error: PurchasesError) {
            errors += error
        }
    }

    private fun transaction(
        productId: String,
        token: String,
        purchaseTime: Long,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        purchaseToken = token,
        purchaseTime = purchaseTime,
        purchaseState = purchaseState,
    ).toStoreTransaction(ProductType.SUBS)

    private fun bodies() = harness.receiptRequests().map { requireNotNull(it.body) }

    // region restore

    @Test
    fun `restore —— 逐笔按购买时间升序上报，语义是 restore`() {
        billing.purchasesOnDevice = listOf(
            transaction("sub_b", "token-b", purchaseTime = 2_000L),
            transaction("sub_a", "token-a", purchaseTime = 1_000L),
        )
        repeat(2) { harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_a" to false, "sub_b" to false))) }
        val recorder = Recorder()

        harness.orchestrator.restorePurchases(recorder)

        assertThat(bodies().map { it.getString("fetch_token") }).containsExactly("token-a", "token-b")
        bodies().forEach { body ->
            assertThat(body.getString("initiation_source")).isEqualTo("restore")
            assertThat(body.getBoolean("is_restore")).isTrue()
            assertThat(body.getBoolean("sdk_originated")).isFalse()
        }
        // restore 会完成交易（七分支）。
        assertThat(billing.consumeAndSaveCalls).hasSize(2)
        // 回调**只被调一次**，用最后一个 CustomerInfo。
        assertThat(recorder.received).hasSize(1)
        assertThat(recorder.errors).isEmpty()
    }

    @Test
    fun `restore —— 不先查商品详情（与 RC 一致，退化形状上报）`() {
        billing.purchasesOnDevice = listOf(transaction("sub_a", "token-a", 1_000L))
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_a" to false)))

        harness.orchestrator.restorePurchases(Recorder())

        assertThat(billing.queriedProductTypes).isEmpty()
        assertThat(bodies().single().getJSONArray("platform_product_ids").getJSONObject(0).has("base_plan_id"))
            .isFalse()
    }

    @Test
    fun `restore —— 没有可恢复交易时只刷一次 CustomerInfo`() {
        billing.purchasesOnDevice = emptyList()
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val recorder = Recorder()

        harness.orchestrator.restorePurchases(recorder)

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(recorder.received).hasSize(1)
    }

    @Test
    fun `restore —— 任一笔失败就整体报错（只报第一个错误）`() {
        billing.purchasesOnDevice = listOf(
            transaction("sub_a", "token-a", 1_000L),
            transaction("sub_b", "token-b", 2_000L),
        )
        harness.httpClient.enqueue(500, """{"message":"boom"}""")
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_b" to false)))
        val recorder = Recorder()

        harness.orchestrator.restorePurchases(recorder)

        assertThat(recorder.received).isEmpty()
        assertThat(recorder.errors).hasSize(1)
        assertThat(recorder.errors.single().code).isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
    }

    @Test
    fun `restore —— PENDING 交易被整笔跳过`() {
        billing.purchasesOnDevice = listOf(
            transaction("sub_a", "token-pending", 1_000L, purchaseState = Purchase.PurchaseState.PENDING),
        )
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val recorder = Recorder()

        harness.orchestrator.restorePurchases(recorder)

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(recorder.received).hasSize(1)
    }

    @Test
    fun `restore —— queryPurchases 失败直接回错误`() {
        billing.queryPurchasesError = PurchasesError(PurchasesErrorCode.StoreProblemError, "Play 挂了")
        val recorder = Recorder()

        harness.orchestrator.restorePurchases(recorder)

        assertThat(recorder.errors.single().code).isEqualTo(PurchasesErrorCode.StoreProblemError)
    }

    // endregion

    // region sync

    @Test
    fun `sync —— 只上报不碰 Billing，成功即记台账`() {
        billing.purchasesOnDevice = listOf(transaction("sub_a", "token-a", 1_000L))
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_a" to false)))
        val recorder = Recorder()

        harness.orchestrator.syncPurchases(recorder)

        val body = bodies().single()
        assertThat(body.getString("initiation_source")).isEqualTo("unsynced_active_purchases")
        assertThat(body.getBoolean("is_restore")).isFalse()
        // 「不碰 Billing」= 一次 consumeAndSave 都没有。
        assertThat(billing.consumeAndSaveCalls).isEmpty()
        // 但仍要记台账，避免每次前台都重报一遍。
        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).containsExactly("token-a".sha1())
        assertThat(recorder.received).hasSize(1)
    }

    @Test
    fun `sync —— 确定性 4xx 也记台账（重试无意义），错误码是 902`() {
        billing.purchasesOnDevice = listOf(transaction("sub_a", "token-a", 1_000L))
        harness.httpClient.enqueue(400, """{"code":7000,"message":"bad"}""")
        val recorder = Recorder()

        harness.orchestrator.syncPurchases(recorder)

        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).containsExactly("token-a".sha1())
        assertThat(recorder.errors.single().code).isEqualTo(PurchasesErrorCode.PurchaseRejectedByServer)
    }

    @Test
    fun `sync —— 5xx 不记台账（留给下次补报），错误码是 901`() {
        billing.purchasesOnDevice = listOf(transaction("sub_a", "token-a", 1_000L))
        harness.httpClient.enqueue(503, """{"message":"unavailable"}""")
        val recorder = Recorder()

        harness.orchestrator.syncPurchases(recorder)

        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).isEmpty()
        assertThat(recorder.errors.single().code).isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
    }

    // endregion
}
