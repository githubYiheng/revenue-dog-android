package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.posting.PostReceiptHelper
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * **A8 ack 超时自保**（设计 §3 A8 / 考古 §9.3 决策 A，偏离 RC）。
 *
 * 场景：后端连续不可用。Google 会在**第 3 天**自动退款并撤销权益，而 ack 是「对 Google 说
 * 这笔我收到了」，与「权益是否成立」是两件事。所以首次上报起 24h 仍未成功 → 先 ack 保住这笔钱，
 * 权益仍由后端最终裁决；后续上报带 `acknowledged_by=sdk_timeout` 让后端知道这次 ack 不是它做的。
 *
 * 前提（设计 §8）：服务端是 ack 权威。所以自保之前**必须先查 `isAcknowledged`** ——
 * 服务端很可能已经代为 ack 过了。
 */
@RunWith(RobolectricTestRunner::class)
class AckSelfProtectTest {

    private lateinit var context: Context

    /** 比 24h 差一分钟。 */
    private val almostThreshold = PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS - 60_000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val noopCallback = object : PurchaseCallback {
        override fun onCompleted(result: PurchaseResult) = Unit
        override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
    }

    private class Rig(context: Context, purchasesAreCompletedBy: PurchasesAreCompletedBy) {
        val billing = BillingHarness()
        val harness = OrchestratorHarness(context, billing, purchasesAreCompletedBy = purchasesAreCompletedBy)
        val token = "token-a8"
    }

    private fun Rig.buyAndFail(responseCode: Int = 503, isAcknowledged: Boolean = false) {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)
        harness.httpClient.enqueue(responseCode, """{"message":"unavailable"}""")
        val transaction = purchaseFixture(
            productIds = listOf("sub_premium"),
            purchaseToken = token,
            acknowledged = isAcknowledged,
        ).toStoreTransaction(ProductType.SUBS, "monthly-base")
        // A8 会 `queryPurchases` 回查这笔的 `isAcknowledged`。
        billing.purchasesOnDevice = listOf(transaction)
        billing.deliverPurchases(transaction)
    }

    /** 再跑一轮补报（时钟已被推过阈值）。 */
    private fun Rig.retryPost(responseCode: Int = 503, payload: String = """{"message":"unavailable"}""") {
        harness.httpClient.enqueue(responseCode, payload)
        harness.orchestrator.syncPendingPurchaseQueue()
    }

    @Test
    fun `23h59m 不触发自保`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail()
        rig.harness.nowMs += almostThreshold

        rig.retryPost()

        assertThat(rig.billing.acknowledgedTokens).isEmpty()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isFalse()
        assertThat(rig.harness.diagnostics.named(DiagnosticsTracker.EVENT_CONSUME_DECISION))
            .noneMatch { it["decision"] == BillingWrapper.DECISION_ACK_SELF_PROTECT }
    }

    @Test
    fun `24h 且未 ack：ack 一次、标记落盘、后续上报带 acknowledged_by`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)).isNotNull
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS

        rig.retryPost()

        // ① ack 了，**没有 consume**。
        assertThat(rig.billing.acknowledgedTokens).containsExactly(rig.token)
        assertThat(rig.billing.consumedTokens).isEmpty()
        // ② 标记落盘。
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isTrue()
        // ③ 诊断。
        val decision = rig.harness.diagnostics.named(DiagnosticsTracker.EVENT_CONSUME_DECISION)
            .single { it["decision"] == BillingWrapper.DECISION_ACK_SELF_PROTECT }
        assertThat(decision["elapsed_hours"]).isEqualTo(24L)
        assertThat(decision["threshold_hours"]).isEqualTo(24L)

        // ④ 再来一轮上报，body 带 acknowledged_by = sdk_timeout。
        rig.retryPost()
        val lastBody = requireNotNull(rig.harness.receiptRequests().last().body)
        assertThat(lastBody.getString(Backend.ACKNOWLEDGED_BY))
            .isEqualTo(Backend.ACKNOWLEDGED_BY_SDK_TIMEOUT)
    }

    @Test
    fun `最终成功的那次上报也带 acknowledged_by（后端才知道这笔的 ack 不是它做的）`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail()
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS
        rig.retryPost()

        rig.retryPost(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        val successBody = requireNotNull(rig.harness.receiptRequests().last().body)
        assertThat(successBody.getString(Backend.ACKNOWLEDGED_BY)).isEqualTo(Backend.ACKNOWLEDGED_BY_SDK_TIMEOUT)
        // 成功之后上下文被清掉，标记跟着一起走（这笔已经落地了）。
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)).isNull()
    }

    @Test
    fun `24h 且服务端已代为 ack：只标记不再 ack`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail(isAcknowledged = true)
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS

        rig.retryPost()

        assertThat(rig.billing.acknowledgedTokens).isEmpty()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isTrue()
        assertThat(rig.harness.diagnostics.named(DiagnosticsTracker.EVENT_CONSUME_DECISION))
            .anyMatch { it["decision"] == BillingWrapper.DECISION_ACK_SELF_PROTECT_ALREADY_ACKED }
    }

    @Test
    fun `按 token 单飞：一轮补报里同一笔失败两次、查询又是异步的，自保也只查一次、只记一条诊断`() {
        // 真机 2026-09-21（D16）：回前台并发几轮补报 + 每轮同一 token 报两次（差集 + 残留上下文，RC 同款），
        // `queryPurchases` 在真机上是异步的 → 修前同一笔会并发查两次、诊断 `already_acknowledged` 记两条。
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail(isAcknowledged = true)
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS
        rig.billing.deferQueryPurchases = true

        rig.retryPost()
        assertThat(rig.billing.flushQueryPurchases()).isEqualTo(1) // 补报那一轮自己的查询
        // 两次失败上报只换来**一个**在途的自保查询
        assertThat(rig.billing.flushQueryPurchases()).isEqualTo(1)

        assertThat(
            rig.harness.diagnostics.named(DiagnosticsTracker.EVENT_CONSUME_DECISION)
                .filter { it["decision"] == BillingWrapper.DECISION_ACK_SELF_PROTECT_ALREADY_ACKED },
        ).hasSize(1)
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isTrue()
        assertThat(rig.billing.acknowledgedTokens).isEmpty()
    }

    @Test
    fun `ack 失败：不标记、上下文不清，下一轮再试`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail()
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS
        rig.billing.acknowledgeSucceeds = false

        rig.retryPost()

        // 试过了。一轮补报里这个 token 会被上报两次（差集路径 + 残留上下文路径，RC 同款），ack 失败后
        // 单飞占位已释放，同一轮的第二次失败会再试一次 —— `acknowledgePurchase` 是幂等的，
        // 所以这里只断言「试过」而不是「只试过一次」。
        assertThat(rig.billing.acknowledgedTokens).containsOnly(rig.token)
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isFalse()
        // 下一轮 ack 成功 → 这次标上。
        rig.billing.acknowledgeSucceeds = true
        rig.retryPost()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isTrue()
    }

    @Test
    fun `确定性 4xx 不走自保（七分支第七条已经 ack 掉了）`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail(responseCode = 422)

        // 确定性拒绝 → 上下文已清，A8 根本没有可判定的对象。
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)).isNull()
        assertThat(rig.harness.diagnostics.named(DiagnosticsTracker.EVENT_CONSUME_DECISION))
            .noneMatch { it["decision"] == BillingWrapper.DECISION_ACK_SELF_PROTECT }
        // 七分支第 ⑦ 条走的是 `consumeAndSave(deterministicallyRejected = true)`
        // （它内部「ack 但不 consume」的分支由 `ConsumeAndSaveTest` 用真实 wrapper 覆盖）。
        val call = rig.harness.billing.consumeAndSaveCalls.single()
        assertThat(call.deterministicallyRejected).isTrue()
        assertThat(call.shouldConsume).isNull()
        assertThat(rig.billing.acknowledgedTokens).isEmpty()
    }

    @Test
    fun `my_app 模式下绝不自保 ack（宿主自管交易完成）`() {
        val rig = Rig(context, PurchasesAreCompletedBy.MY_APP)
        rig.buyAndFail()
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS

        rig.retryPost()

        assertThat(rig.billing.acknowledgedTokens).isEmpty()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isFalse()
    }

    @Test
    fun `Play 上已经看不到这笔时跳过（没什么可 ack 的）`() {
        val rig = Rig(context, PurchasesAreCompletedBy.REVENUE_DOG)
        rig.buyAndFail()
        rig.harness.nowMs += PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS
        rig.billing.purchasesOnDevice = emptyList()

        rig.retryPost()

        assertThat(rig.billing.acknowledgedTokens).isEmpty()
        assertThat(rig.harness.pendingPurchases.postContext(rig.token)!!.ackSelfProtected).isFalse()
    }

    @Test
    fun `自保阈值是 24 小时`() {
        assertThat(PostReceiptHelper.ACK_SELF_PROTECT_THRESHOLD_MS).isEqualTo(24L * 60L * 60L * 1000L)
    }
}
