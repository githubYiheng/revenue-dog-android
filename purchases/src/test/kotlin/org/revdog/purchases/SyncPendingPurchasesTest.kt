package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.Purchase
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.common.sha1
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.posting.InitiationSource
import org.revdog.purchases.posting.PlatformProductId
import org.revdog.purchases.posting.ReceiptInfo
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * **前台恢复 / 连接成功时的补报三条链路**（考古 §5.5）：
 * 1. `queryPurchases` 差集；2. `isAutoRenewing` diff；3. 本地上报上下文残留。
 *
 * 这三条缺一不可，各覆盖一种「钱收了但后端不知道」的形态。
 */
@RunWith(RobolectricTestRunner::class)
class SyncPendingPurchasesTest {

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
        billing.inAppProducts = listOf(StoreProductBuilders.inApp("coins_100").withMockDetails())
    }

    private fun transaction(
        productId: String = "sub_premium",
        token: String,
        type: ProductType = ProductType.SUBS,
        autoRenewing: Boolean = true,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        purchaseToken = token,
        autoRenewing = autoRenewing,
        purchaseState = purchaseState,
    ).toStoreTransaction(type, subscriptionOptionId = if (type == ProductType.SUBS) "monthly-base" else null)

    private fun postedTokens(): List<String> =
        harness.receiptRequests().map { requireNotNull(it.body).getString("fetch_token") }

    private fun enqueueSuccess(times: Int, productId: String = "sub_premium", shouldConsume: Boolean = false) {
        repeat(times) { harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf(productId to shouldConsume))) }
    }

    // region 链路 1：差集

    @Test
    fun `台账外的 token 上报一次，已在台账的不再报`() {
        harness.deviceCache.addSuccessfullyPostedToken("token-known", true)
        billing.purchasesOnDevice = listOf(
            transaction(token = "token-known"),
            transaction(token = "token-new"),
        )
        enqueueSuccess(1)

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(postedTokens()).containsExactly("token-new")
        assertThat(requireNotNull(harness.receiptRequests().single().body).getString("initiation_source"))
            .isEqualTo(InitiationSource.UNSYNCED_ACTIVE_PURCHASES)
        // 上报成功 → 七分支被调用 → 台账在 consumeAndSave 里写（这里替身只记入参）。
        assertThat(billing.consumeAndSaveCalls.map { it.purchase.purchaseToken }).containsExactly("token-new")
    }

    @Test
    fun `连接成功就是补报的触发点`() {
        billing.purchasesOnDevice = listOf(transaction(token = "token-on-connect"))
        enqueueSuccess(1)

        billing.deliverConnected()

        assertThat(postedTokens()).containsExactly("token-on-connect")
    }

    @Test
    fun `没有任何待补报时不发请求`() {
        harness.deviceCache.addSuccessfullyPostedToken("token-known", true)
        billing.purchasesOnDevice = listOf(transaction(token = "token-known"))

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.receiptRequests()).isEmpty()
    }

    @Test
    fun `台账里已不活跃的条目被清掉`() {
        harness.deviceCache.addSuccessfullyPostedToken("token-gone", true)
        billing.purchasesOnDevice = emptyList()

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).isEmpty()
    }

    // endregion

    // region 链路 2：isAutoRenewing diff

    @Test
    fun `isAutoRenewing 翻转再报一次（Play 商店外取消的唯一侦测手段）`() {
        harness.deviceCache.addSuccessfullyPostedToken("token-sub", isAutoRenewing = true)
        billing.purchasesOnDevice = listOf(transaction(token = "token-sub", autoRenewing = false))
        enqueueSuccess(1)

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(postedTokens()).containsExactly("token-sub")
    }

    @Test
    fun `isAutoRenewing 没变就不报`() {
        harness.deviceCache.addSuccessfullyPostedToken("token-sub", isAutoRenewing = true)
        billing.purchasesOnDevice = listOf(transaction(token = "token-sub", autoRenewing = true))

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.receiptRequests()).isEmpty()
    }

    // endregion

    // region PENDING 的排除

    @Test
    fun `PENDING 的 token 不进补报，也不记台账`() {
        billing.purchasesOnDevice = listOf(
            transaction(token = "token-pending", purchaseState = Purchase.PurchaseState.PENDING),
        )

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(billing.consumeAndSaveCalls).isEmpty()
        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).isEmpty()
    }

    @Test
    fun `有本地上下文的 PENDING token 也被排除在残留补报之外`() {
        // 本地留着一份上报上下文（上次购买 5xx 失败），而这笔现在是 PENDING。
        harness.pendingPurchases.getOrPutPostContext(
            token = "token-pending",
            receiptInfo = ReceiptInfo(
                productIds = listOf("coins_100"),
                platformProductIds = listOf(PlatformProductId("coins_100")),
                sdkOriginated = true,
            ),
            initiationSource = InitiationSource.PURCHASE,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        )
        billing.purchasesOnDevice = listOf(
            transaction(
                productId = "coins_100",
                token = "token-pending",
                type = ProductType.INAPP,
                autoRenewing = false,
                purchaseState = Purchase.PurchaseState.PENDING,
            ),
        )

        harness.orchestrator.syncPendingPurchaseQueue()

        // 报了就等于把一笔不存在的收入报上去（坑 3 / 铁律 A3）。
        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(harness.pendingPurchases.hasPostContext("token-pending")).isTrue()
    }

    // endregion

    // region 链路 3：本地上下文残留

    @Test
    fun `Play 已经看不到的消耗品靠本地上下文补报`() {
        harness.pendingPurchases.getOrPutPostContext(
            token = "token-consumed",
            receiptInfo = ReceiptInfo(
                productIds = listOf("coins_100"),
                platformProductIds = listOf(PlatformProductId("coins_100")),
                priceAmountMicros = 990_000L,
                currency = "USD",
                sdkOriginated = true,
            ),
            initiationSource = InitiationSource.PURCHASE,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        )
        // queryPurchases 看不到它（已 consume）。
        billing.purchasesOnDevice = emptyList()
        enqueueSuccess(1, productId = "coins_100", shouldConsume = true)

        harness.orchestrator.syncPendingPurchaseQueue()

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getString("fetch_token")).isEqualTo("token-consumed")
        assertThat(body.getString("initiation_source")).isEqualTo(InitiationSource.UNSYNCED_ACTIVE_PURCHASES)
        assertThat(body.getLong("price_amount_micros")).isEqualTo(990_000L)
        // 这条路上 Play 已经没有这笔交易了，只能直接记台账。
        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).contains("token-consumed".sha1())
        assertThat(harness.pendingPurchases.hasPostContext("token-consumed")).isFalse()
    }

    @Test
    fun `差集与残留两轮都会跑 —— 顺序是先差集再残留`() {
        harness.pendingPurchases.getOrPutPostContext(
            token = "token-leftover",
            receiptInfo = ReceiptInfo(
                productIds = listOf("coins_100"),
                platformProductIds = listOf(PlatformProductId("coins_100")),
                sdkOriginated = true,
            ),
            initiationSource = InitiationSource.PURCHASE,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        )
        billing.purchasesOnDevice = listOf(transaction(token = "token-active"))
        enqueueSuccess(2)

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(postedTokens()).containsExactly("token-active", "token-leftover")
    }

    @Test
    fun `queryPurchases 失败时不发任何上报`() {
        billing.queryPurchasesError = PurchasesError(PurchasesErrorCode.StoreProblemError, "Play 挂了")

        harness.orchestrator.syncPendingPurchaseQueue()

        assertThat(harness.receiptRequests()).isEmpty()
    }

    // endregion
}
