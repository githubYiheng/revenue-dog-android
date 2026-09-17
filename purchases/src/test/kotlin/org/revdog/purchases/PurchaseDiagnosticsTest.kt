package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * M2 的诊断打点清单。
 *
 * M1 的 tracker 是 no-op，M3 才换成真实管线（JSONL 队列 + 攒批上传）。
 * **打点位置与字段现在就得钉死** —— 它们分散在编排、上报、Billing 三层，
 * M3 补管线时不该再去改这些调用点（设计 §2 / 考古 §7.5）。
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    private val noopCallback = object : PurchaseCallback {
        override fun onCompleted(result: PurchaseResult) = Unit
        override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
    }

    @Test
    fun `一次成功购买要打 purchase_started 与 purchase_result 与 receipt_post`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)

        val started = harness.diagnostics.named(DiagnosticsTracker.EVENT_PURCHASE_STARTED).single()
        assertThat(started["product_id"]).isEqualTo("sub_premium")
        assertThat(started["is_upgrade"]).isEqualTo(false)

        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val post = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        assertThat(post["initiation_source"]).isEqualTo("purchase")
        assertThat(post["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_SUCCESS)

        val result = harness.diagnostics.named(DiagnosticsTracker.EVENT_PURCHASE_RESULT).single()
        assertThat(result["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_COMPLETED)
    }

    @Test
    fun `5xx 的 receipt_post 打 retryable 且带错误码与 http 状态`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)
        harness.httpClient.enqueue(503, """{"message":"unavailable"}""")

        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val post = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        assertThat(post["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_RETRYABLE)
        assertThat(post["http_status"]).isEqualTo(503)
        assertThat(post["error_code"]).isEqualTo(PurchasesErrorCode.UnknownBackendError.name)
    }

    @Test
    fun `取消购买打 cancelled`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)

        billing.deliverPurchaseFailure(
            PurchasesError(PurchasesErrorCode.PurchaseCancelledError),
            userCancelled = true,
        )

        val result = harness.diagnostics.named(DiagnosticsTracker.EVENT_PURCHASE_RESULT).single()
        assertThat(result["outcome"]).isEqualTo(DiagnosticsTracker.OUTCOME_CANCELLED)
    }

    @Test
    fun `M2 的事件名全集都在 DiagnosticsTracker 的常量里（M3 换实现不改名）`() {
        assertThat(
            listOf(
                DiagnosticsTracker.EVENT_PURCHASE_STARTED,
                DiagnosticsTracker.EVENT_PURCHASE_RESULT,
                DiagnosticsTracker.EVENT_RECEIPT_POST,
                DiagnosticsTracker.EVENT_CONSUME_DECISION,
                DiagnosticsTracker.EVENT_PURCHASE_PENDING,
                DiagnosticsTracker.EVENT_BILLING_PURCHASE_UPDATE,
                DiagnosticsTracker.EVENT_RESTORE_PURCHASES,
                DiagnosticsTracker.EVENT_SYNC_PURCHASES,
            ),
        ).containsExactly(
            "purchase_started",
            "purchase_result",
            "receipt_post",
            "consume_decision",
            "purchase_pending",
            "billing_purchase_update",
            "restore_purchases",
            "sync_purchases",
        )
    }

    @Test
    fun `诊断字段里不出现 purchaseToken 原文`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-secret")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val allValues = harness.diagnostics.events.flatMap { it.second.values }.map { it.toString() }
        assertThat(allValues).noneMatch { it.contains("token-secret") }
    }
}
