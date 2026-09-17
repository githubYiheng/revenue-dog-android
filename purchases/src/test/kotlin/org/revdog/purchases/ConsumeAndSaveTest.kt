package org.revdog.purchases

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.sha1
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.support.FakeBillingClientFixture
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.RecordingDelayedRunner
import org.revdog.purchases.support.RecordingDiagnosticsTracker
import org.revdog.purchases.support.RecordingPurchasesUpdatedListener
import org.revdog.purchases.support.purchaseFixture
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * **`consumeAndSave` 七分支**（考古 §9.1 必抄第 1 项，设计 §3 A2）。
 *
 * 用**真实** `BillingWrapper` + mock 的 `BillingClient`：这 50 行直接决定
 * 「钱收了东西给没给」与「消耗品能不能复购」，不能测替身。
 *
 * 台账（`addSuccessfullyPostedToken`）才是 Android 版的 `finish()`，所以每条分支都要
 * 同时断言「调没调 Billing」与「记没记台账」。
 */
@RunWith(RobolectricTestRunner::class)
class ConsumeAndSaveTest {

    private lateinit var context: Context
    private lateinit var fixture: FakeBillingClientFixture
    private lateinit var deviceCache: DeviceCache
    private lateinit var diagnostics: RecordingDiagnosticsTracker
    private lateinit var wrapper: BillingWrapper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = FakeBillingClientFixture()
        fixture.stubFinishCalls()
        deviceCache = DeviceCache(
            context.getSharedPreferences("consume-${System.nanoTime()}", Context.MODE_PRIVATE),
            FakeHTTPClient.TEST_API_KEY,
        )
        diagnostics = RecordingDiagnosticsTracker()
        val runner = RecordingDelayedRunner()
        wrapper = BillingWrapper(
            clientFactory = fixture.clientFactory,
            mainHandler = Handler(Looper.getMainLooper()),
            deviceCache = deviceCache,
            diagnostics = diagnostics,
            backgroundRunner = runner,
        )
        wrapper.purchasesUpdatedListener = RecordingPurchasesUpdatedListener()
        // 让 client 建出来并处于 ready：consume / ack 都要走「等连接 → 发」这条路。
        fixture.setReady(false)
        wrapper.startConnection()
        runner.runPending()
        fixture.setReady(true)
        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun inApp(
        productId: String = "coins_100",
        acknowledged: Boolean = false,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        acknowledged = acknowledged,
        purchaseState = purchaseState,
        autoRenewing = false,
    ).toStoreTransaction(ProductType.INAPP)

    private fun subs(
        productId: String = "sub_premium",
        acknowledged: Boolean = false,
        autoRenewing: Boolean = true,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        acknowledged = acknowledged,
        autoRenewing = autoRenewing,
    ).toStoreTransaction(ProductType.SUBS)

    private fun ledger(): Set<String> = deviceCache.getPreviouslySentHashedTokens()

    private fun decisions(): List<String?> =
        diagnostics.named(org.revdog.purchases.diagnostics.DiagnosticsTracker.EVENT_CONSUME_DECISION)
            .map { it["decision"] as? String }

    private fun run(
        purchase: StoreTransaction,
        shouldConsume: Boolean?,
        completedBy: PurchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        deterministicallyRejected: Boolean = false,
    ) {
        wrapper.consumeAndSave(completedBy, purchase, shouldConsume, deterministicallyRejected)
        idle()
    }

    // region 七分支

    @Test
    fun `分支 2 —— INAPP 且后端说 consume 则 consume 并记台账`() {
        val purchase = inApp()
        run(purchase, shouldConsume = true)

        assertThat(fixture.consumedTokens).containsExactly(purchase.purchaseToken)
        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_CONSUMED)
    }

    @Test
    fun `分支 3 —— INAPP 且后端说不 consume 则只 ack 并记台账（消耗品绝不能被误 consume）`() {
        val purchase = inApp()
        run(purchase, shouldConsume = false)

        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).containsExactly(purchase.purchaseToken)
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_ACKNOWLEDGED)
    }

    @Test
    fun `决策 B —— should_consume 缺失时不 ack 不 consume 不记台账，并记 error 级诊断`() {
        val purchase = inApp()
        run(purchase, shouldConsume = null)

        // RC 在这里缺省 false 走 acknowledge，结果是消耗品永远无法复购（坑 1）。我方 fail-loud。
        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(ledger()).isEmpty()
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_MISSING_SHOULD_CONSUME)
    }

    @Test
    fun `分支 5 —— SUBS 尚未 ack 则 ack`() {
        val purchase = subs()
        run(purchase, shouldConsume = false)

        assertThat(fixture.acknowledgedTokens).containsExactly(purchase.purchaseToken)
        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
    }

    @Test
    fun `分支 6 —— SUBS 已 ack 则只记台账（服务端是 ack 权威）`() {
        val purchase = subs(acknowledged = true)
        run(purchase, shouldConsume = false)

        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_ACKNOWLEDGED_BY_SERVER)
    }

    @Test
    fun `分支 1 —— PENDING 完全跳过：不 ack 不 consume 不记台账`() {
        val purchase = inApp(purchaseState = Purchase.PurchaseState.PENDING)
        run(purchase, shouldConsume = true)

        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).isEmpty()
        // 记了台账，用户付款完成后这笔就永远不会被补报（坑 3）。
        assertThat(ledger()).isEmpty()
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_SKIPPED_NOT_PURCHASED)
    }

    @Test
    fun `分支 1 —— 类型 UNKNOWN（应用外购买反查失败）同样完全跳过`() {
        val purchase = purchaseFixture().toStoreTransaction(ProductType.UNKNOWN)
        run(purchase, shouldConsume = true)

        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(ledger()).isEmpty()
    }

    @Test
    fun `分支 7 —— 确定性 4xx 时 ack 但不 consume（即使是消耗品）`() {
        val purchase = inApp()
        run(purchase, shouldConsume = null, deterministicallyRejected = true)

        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).containsExactly(purchase.purchaseToken)
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_ACK_AFTER_REJECTION)
    }

    @Test
    fun `MY_APP 模式只记台账，绝不碰 Billing`() {
        val purchase = inApp()
        run(purchase, shouldConsume = true, completedBy = PurchasesAreCompletedBy.MY_APP)

        assertThat(fixture.consumedTokens).isEmpty()
        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
        assertThat(decisions()).containsExactly(BillingWrapper.DECISION_OBSERVER_LEDGER_ONLY)
    }

    // endregion

    // region 服务端已 ack 的两种表现

    @Test
    fun `isAcknowledged 快照为 true 时根本不调 ack`() {
        val purchase = inApp(acknowledged = true)
        run(purchase, shouldConsume = false)

        assertThat(fixture.acknowledgedTokens).isEmpty()
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
    }

    @Test
    fun `isAcknowledged 为 false 但 ack 返回 ITEM_NOT_OWNED —— 按成功处理并记台账`() {
        fixture.acknowledgeResult =
            FakeBillingClientFixture.billingResult(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        val purchase = subs()
        run(purchase, shouldConsume = false)

        assertThat(fixture.acknowledgedTokens).containsExactly(purchase.purchaseToken)
        // ITEM_NOT_OWNED = Play 已经不认为这笔挂在用户名下 = 它已经被确认过了。
        // 报错只会让这笔永远进不了台账、每次前台都重报一遍。
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
    }

    @Test
    fun `consume 返回 ITEM_NOT_OWNED 同样按成功处理`() {
        fixture.consumeResult =
            FakeBillingClientFixture.billingResult(BillingClient.BillingResponseCode.ITEM_NOT_OWNED)
        val purchase = inApp()
        run(purchase, shouldConsume = true)

        assertThat(fixture.consumedTokens).containsExactly(purchase.purchaseToken)
        assertThat(ledger()).containsExactly(purchase.purchaseToken.sha1())
    }

    @Test
    fun `ack 真失败时不记台账 —— 留给下次前台重试`() {
        fixture.acknowledgeResult =
            FakeBillingClientFixture.billingResult(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
        val purchase = subs()
        run(purchase, shouldConsume = false)

        assertThat(fixture.acknowledgedTokens).containsExactly(purchase.purchaseToken)
        assertThat(ledger()).isEmpty()
    }

    // endregion

    @Test
    fun `台账记的是 sha1 哈希而不是 token 原文`() {
        val purchase = subs()
        run(purchase, shouldConsume = false)

        assertThat(ledger()).doesNotContain(purchase.purchaseToken)
        assertThat(ledger().single()).hasSize(40)
    }
}
