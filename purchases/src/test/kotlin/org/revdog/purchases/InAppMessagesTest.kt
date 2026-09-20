package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.purchaseFixture
import org.robolectric.RobolectricTestRunner

/**
 * Play in-app messages（扣款失败时 Google 官方的挽回 snackbar）的编排层。
 * 结构对照 RC `PurchasesOrchestrator.onActivityStarted:412-417` / `:1021-1025`。
 *
 * Billing 层自己那一半（连接排队 / WeakReference / 三道 Activity 守卫 / 两个响应码）
 * 在 `BillingWrapperTest` 的「Play in-app messages」区。
 */
@RunWith(RobolectricTestRunner::class)
class InAppMessagesTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
    }

    private fun harness(showAutomatically: Boolean = true) = OrchestratorHarness(
        context,
        billing,
        showInAppMessagesAutomatically = showAutomatically,
    )

    private fun transaction(productId: String, token: String): StoreTransaction =
        purchaseFixture(
            productIds = listOf(productId),
            purchaseToken = token,
            purchaseState = Purchase.PurchaseState.PURCHASED,
        ).toStoreTransaction(ProductType.SUBS)

    // region 自动展示

    @Test
    fun `自动展示开着时 onActivityStarted 展示一次，类别是全集`() {
        val harness = harness(showAutomatically = true)

        harness.orchestrator.onActivityStarted(billing.activity)

        assertThat(billing.inAppMessageCalls).hasSize(1)
        val (activity, types) = billing.inAppMessageCalls.single()
        assertThat(activity).isSameAs(billing.activity)
        assertThat(types).isEqualTo(InAppMessageType.ALL)
    }

    @Test
    fun `自动展示关掉时 onActivityStarted 什么都不做`() {
        val harness = harness(showAutomatically = false)

        harness.orchestrator.onActivityStarted(billing.activity)

        assertThat(billing.inAppMessageCalls).isEmpty()
    }

    @Test
    fun `close 之后不再响应 Activity 回调`() {
        val harness = harness(showAutomatically = true)

        harness.orchestrator.close()
        harness.orchestrator.onActivityStarted(billing.activity)

        assertThat(billing.inAppMessageCalls).isEmpty()
    }

    // endregion

    // region 手动触发与状态更新

    @Test
    fun `手动 showInAppMessagesIfNeeded 原样把类别交给 Billing 层`() {
        val harness = harness(showAutomatically = false)

        harness.orchestrator.showInAppMessagesIfNeeded(billing.activity, listOf(InAppMessageType.BILLING_ISSUES))

        assertThat(billing.inAppMessageCalls.single().second)
            .containsExactly(InAppMessageType.BILLING_ISSUES)
    }

    /**
     * 用户在 snackbar 里把扣款问题修好之后 Play 只说「状态变了」，不说变成了什么 ——
     * 所以走一遍既有的 `syncPurchases`（`unsynced_active_purchases`，只上报不碰 Billing），
     * 由后端定权益。
     */
    @Test
    fun `订阅状态被更新时跑一轮 syncPurchases 上报`() {
        val harness = harness()
        billing.purchasesOnDevice = listOf(transaction("sub_premium", "token-premium"))
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        harness.orchestrator.onActivityStarted(billing.activity)

        billing.deliverSubscriptionStatusUpdated()

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getString("fetch_token")).isEqualTo("token-premium")
        assertThat(body.getString("initiation_source")).isEqualTo("unsynced_active_purchases")
        assertThat(body.getBoolean("is_restore")).isFalse()
        // sync 语义：绝不 ack / consume。
        assertThat(billing.consumeAndSaveCalls).isEmpty()
    }

    @Test
    fun `没有状态更新（NO_ACTION_NEEDED）时不发任何上报`() {
        val harness = harness()

        harness.orchestrator.onActivityStarted(billing.activity)

        assertThat(billing.inAppMessageCalls).hasSize(1)
        assertThat(harness.receiptRequests()).isEmpty()
    }

    // endregion

    // region 同配置判定

    @Test
    fun `sameAs：只有 showInAppMessagesAutomatically 不同也算两份配置`() {
        val on = PurchasesConfiguration.Builder(context, "pk_test").build()
        val alsoOn = PurchasesConfiguration.Builder(context, "pk_test").build()
        val off = PurchasesConfiguration.Builder(context, "pk_test")
            .showInAppMessagesAutomatically(false)
            .build()

        assertThat(on.showInAppMessagesAutomatically).isTrue()
        assertThat(on.sameAs(alsoOn)).isTrue()
        assertThat(on.sameAs(off)).isFalse()
        assertThat(off.sameAs(on)).isFalse()
    }

    // endregion
}
