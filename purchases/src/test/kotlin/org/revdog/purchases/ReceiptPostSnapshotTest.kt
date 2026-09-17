package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.offerings.Package
import org.revdog.purchases.offerings.PackageType
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.RequestSnapshot
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * **出站 `POST /v1/receipts` 的五份快照**（设计 §7 / `google-play-plan.md` §5）。
 *
 * 上行契约在这里变成可 diff 的产物：改了字段名、少发了一个字段、`proration_mode`
 * 换了命名，diff 都会在 code review 里被看见，而不是等 G3 联调才发现。
 *
 * 请求本体由**生产代码**拼出来（`Backend.receiptBody` + `HTTPClient.buildRequest`），
 * 假后端只替换了最底层的 IO。
 */
@RunWith(RobolectricTestRunner::class)
class ReceiptPostSnapshotTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    /** 三段式 offer：免费试用（P1W，0）→ 折扣期（P1M，1.99）→ 全价（P1M，4.99）。 */
    private val trialOffer: SubscriptionOption = StoreProductBuilders.subscriptionOption(
        productId = "sub_premium",
        basePlanId = "monthly-base",
        offerId = "welcome-offer",
        pricingPhases = listOf(
            StoreProductBuilders.pricingPhase(
                iso8601 = "P1W",
                amountMicros = 0L,
                recurrenceMode = org.revdog.purchases.models.RecurrenceMode.FINITE_RECURRING,
                billingCycleCount = 1,
            ),
            StoreProductBuilders.pricingPhase(
                iso8601 = "P1M",
                amountMicros = 1_990_000L,
                recurrenceMode = org.revdog.purchases.models.RecurrenceMode.FINITE_RECURRING,
                billingCycleCount = 3,
            ),
            StoreProductBuilders.pricingPhase(iso8601 = "P1M", amountMicros = 4_990_000L),
        ),
    )

    private val subscription: StoreProduct =
        StoreProductBuilders.subscription("sub_premium", "monthly-base", offers = listOf(trialOffer))
            .withMockDetails()

    private val consumable: StoreProduct = StoreProductBuilders.inApp("coins_100").withMockDetails()

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

    private val noopCustomerInfoCallback = object : ReceiveCustomerInfoCallback {
        override fun onReceived(customerInfo: org.revdog.purchases.customerinfo.CustomerInfo) = Unit
        override fun onError(error: PurchasesError) = Unit
    }

    private fun transaction(
        productIds: List<String>,
        token: String,
        type: ProductType,
        subscriptionOptionId: String? = null,
        offering: String? = null,
        replacementMode: ReplacementMode? = null,
        autoRenewing: Boolean = true,
    ): StoreTransaction = purchaseFixture(
        productIds = productIds,
        purchaseToken = token,
        autoRenewing = autoRenewing,
    ).toStoreTransaction(type, subscriptionOptionId, offering, replacementMode)

    @Test
    fun `订阅首购（从 offering 的 package 买，三段式 offer + pricing_phases）`() {
        billing.subscriptionProducts = listOf(subscription)
        val monthlyPackage = Package(
            identifier = PACKAGE_MONTHLY,
            packageType = PackageType.MONTHLY,
            offeringIdentifier = "default",
            platformProductIdentifier = "sub_premium",
            platformProductPlanIdentifier = "monthly-base",
            product = subscription,
        )
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, monthlyPackage).build(),
            noopCallback,
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        billing.deliverPurchases(
            transaction(
                productIds = listOf("sub_premium"),
                token = "token-sub-first",
                type = ProductType.SUBS,
                // 默认选项就是那个三段式 offer（最长免费试用优先）。
                subscriptionOptionId = "monthly-base:welcome-offer",
                offering = "default",
            ),
        )

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getString("presented_offering_identifier")).isEqualTo("default")
        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-subscription-offer")
    }

    @Test
    fun `消耗型商品`() {
        billing.inAppProducts = listOf(consumable)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, consumable).build(),
            noopCallback,
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("coins_100" to true)))

        billing.deliverPurchases(
            transaction(
                productIds = listOf("coins_100"),
                token = "token-coins",
                type = ProductType.INAPP,
                autoRenewing = false,
            ),
        )

        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-consumable")
    }

    @Test
    fun `restore —— 退化形状（拿不到 base_plan_id）`() {
        billing.purchasesOnDevice = listOf(
            transaction(listOf("sub_premium"), "token-restore", ProductType.SUBS),
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        harness.orchestrator.restorePurchases(noopCustomerInfoCallback)

        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-restore")
    }

    @Test
    fun `sync —— 只上报，不碰 Billing`() {
        billing.purchasesOnDevice = listOf(
            transaction(listOf("sub_premium"), "token-sync", ProductType.SUBS),
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        harness.orchestrator.syncPurchases(noopCustomerInfoCallback)

        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-sync")
        assertThat(billing.consumeAndSaveCalls).isEmpty()
    }

    @Test
    fun `升降级 —— proration_mode 发干净枚举名`() {
        billing.subscriptionProducts = listOf(subscription)
        billing.oldPurchase = transaction(listOf("sub_basic"), "token-old", ProductType.SUBS)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, subscription)
                .oldProductId("sub_basic")
                .replacementMode(ReplacementMode.CHARGE_PRORATED_PRICE)
                .build(),
            noopCallback,
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        billing.deliverPurchases(
            transaction(
                productIds = listOf("sub_premium"),
                token = "token-upgrade",
                type = ProductType.SUBS,
                subscriptionOptionId = "monthly-base:welcome-offer",
                replacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
            ),
        )

        val body = requireNotNull(harness.receiptRequests().single().body)
        // 不抄 RC 的 legacy 名 `IMMEDIATE_AND_CHARGE_PRORATED_PRICE`（ADR 0069 决策 4）。
        assertThat(body.getString("proration_mode")).isEqualTo("CHARGE_PRORATED_PRICE")
        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-upgrade")
    }

    private companion object {
        const val PACKAGE_MONTHLY = "${'$'}rc_monthly"
    }

    @Test
    fun `payload_version 恒为 1，且 store_user_id 绝不出现`() {
        billing.inAppProducts = listOf(consumable)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, consumable).build(),
            noopCallback,
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("coins_100" to true)))
        billing.deliverPurchases(
            transaction(listOf("coins_100"), "token-coins", ProductType.INAPP, autoRenewing = false),
        )

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getInt("payload_version")).isEqualTo(1)
        assertThat(body.has("store_user_id")).isFalse()
        assertThat(body.has("marketplace")).isFalse()
        // 价格发 micros（Long），不发 RC 的 Double。
        assertThat(body.get("price_amount_micros")).isInstanceOf(java.lang.Long::class.java)
    }
}
