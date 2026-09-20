package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * 实例关闭（宿主换配置重新 `configure`）时**进行中的购买回调必须收到结果**。
 *
 * **偏离 RC**：RC 的 `close()` 只把回调表清空（`PurchasesOrchestrator.kt:960-962`），
 * 宿主那边的 loading 转圈永远停不下来。我方照既有纪律
 * （`BillingWrapper.executeRequestOnUIThread`：「不能让 `purchase()` 永远不回调」）逐个回错误。
 */
@RunWith(RobolectricTestRunner::class)
class CloseAndReconfigureTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

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

    private fun launchPurchase(): Recorder {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        val recorder = Recorder()
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), recorder)
        assertThat(billing.launched).hasSize(1)
        assertThat(recorder.errors).isEmpty()
        return recorder
    }

    @Test
    fun `close 时进行中的购买回调恰好收到一次错误`() {
        val recorder = launchPurchase()

        harness.orchestrator.close()

        assertThat(recorder.results).isEmpty()
        assertThat(recorder.errors).hasSize(1)
        val (error, userCancelled) = recorder.errors.single()
        assertThat(error.code).isEqualTo(PurchasesErrorCode.ConfigurationError)
        assertThat(userCancelled).isFalse()
    }

    @Test
    fun `再 close 一次不会重复回调`() {
        val recorder = launchPurchase()

        harness.orchestrator.close()
        harness.orchestrator.close()

        assertThat(recorder.errors).hasSize(1)
    }

    @Test
    fun `close 不清落盘的上报上下文 —— 钱可能已经扣了，新实例还要靠它补报`() {
        launchPurchase()
        harness.pendingPurchases.getOrPutPostContext(
            token = "token-inflight",
            receiptInfo = org.revdog.purchases.posting.ReceiptInfo(
                productIds = listOf("sub_premium"),
                platformProductIds = listOf(org.revdog.purchases.posting.PlatformProductId("sub_premium")),
                sdkOriginated = true,
            ),
            initiationSource = org.revdog.purchases.posting.InitiationSource.PURCHASE,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        )

        harness.orchestrator.close()

        assertThat(harness.pendingPurchases.hasPostContext("token-inflight")).isTrue()
    }
}
