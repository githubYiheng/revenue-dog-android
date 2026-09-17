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
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.obfuscatedAccountIdToSend
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * 购买闭环（设计 §3）：**落盘上下文 → launch → listener → 上报 → 七分支 → 回调**。
 *
 * 用真实编排层 + 真实 `Backend` / `PostReceiptHelper` / `PendingPurchaseStore`，
 * 只把 Billing 层换成可编程替身（连接与退避由 `BillingWrapperTest` 覆盖）。
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class PurchaseFlowTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    /** 服务端签发的 32hex 账户令牌（与 `Fixtures.SUBSCRIBER_RESPONSE` 里的一致）。 */
    private val accountToken = "0123456789abcdef0123456789abcdef"

    private val subscription: StoreProduct
        get() = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()

    private val consumable: StoreProduct
        get() = StoreProductBuilders.inApp("coins_100").withMockDetails()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    // region 工具

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

    /** 灌一份缓存 CustomerInfo，让 `account_token` 可用。 */
    private fun seedCachedCustomerInfo() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        var info: CustomerInfo? = null
        harness.orchestrator.getCustomerInfo(
            CacheFetchPolicy.FETCH_CURRENT,
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    info = customerInfo
                }

                override fun onError(error: PurchasesError) = throw AssertionError("不该失败：$error")
            },
        )
        assertThat(info?.accountToken).isEqualTo(accountToken)
        harness.httpClient.recordedRequests.clear()
    }

    private fun purchase(
        product: StoreProduct = subscription,
        oldProductId: String? = null,
        replacementMode: ReplacementMode? = null,
    ): Recorder {
        val recorder = Recorder()
        val builder = PurchaseParams.Builder(billing.activity, product)
        oldProductId?.let { builder.oldProductId(it) }
        replacementMode?.let { builder.replacementMode(it) }
        harness.orchestrator.purchase(builder.build(), recorder)
        return recorder
    }

    private fun subsTransaction(
        productId: String = "sub_premium",
        token: String = "token-sub",
        acknowledged: Boolean = false,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    ): StoreTransaction = purchaseFixture(
        productIds = listOf(productId),
        purchaseToken = token,
        acknowledged = acknowledged,
        purchaseState = purchaseState,
    ).toStoreTransaction(
        type = ProductType.SUBS,
        subscriptionOptionId = "monthly-base",
        presentedOfferingIdentifier = null,
    )

    // endregion

    // region 主链路

    @Test
    fun `购买成功 —— 先落盘上下文再 launch，上报后按后端下发走七分支并回调`() {
        seedCachedCustomerInfo()
        billing.subscriptionProducts = listOf(subscription)

        val recorder = purchase()

        // ① 上下文先落盘（进程此刻被杀也能补报）。
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNotNull
        // ② 才 launch，且带上服务端签发的令牌。
        assertThat(billing.launched).hasSize(1)
        assertThat(billing.launched.single().obfuscatedAccountId).isEqualTo(accountToken)
        assertThat(billing.launched.single().replaceProductInfo).isNull()

        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(subsTransaction())

        // ③ 上报了，且是 purchase 语义。
        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getString("initiation_source")).isEqualTo("purchase")
        assertThat(body.getBoolean("is_restore")).isFalse()
        assertThat(body.getBoolean("sdk_originated")).isTrue()
        // ④ 七分支收到了后端的 should_consume。
        assertThat(billing.consumeAndSaveCalls).hasSize(1)
        assertThat(billing.consumeAndSaveCalls.single().shouldConsume).isFalse()
        assertThat(billing.consumeAndSaveCalls.single().deterministicallyRejected).isFalse()
        // ⑤ 回调一次成功，上下文清掉。
        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.results.single().isPending).isFalse()
        assertThat(recorder.results.single().storeTransaction?.purchaseToken).isEqualTo("token-sub")
        assertThat(recorder.errors).isEmpty()
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNull()
        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isFalse()
    }

    @Test
    fun `消耗型购买 —— 后端说 consume 就 consume`() {
        billing.inAppProducts = listOf(consumable)

        purchase(consumable)
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("coins_100" to true)))
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("coins_100"), purchaseToken = "token-coins", autoRenewing = false)
                .toStoreTransaction(ProductType.INAPP),
        )

        assertThat(billing.consumeAndSaveCalls.single().shouldConsume).isTrue()
    }

    @Test
    fun `同一个商品重复购买 —— 第二次直接回 operationAlreadyInProgress`() {
        billing.subscriptionProducts = listOf(subscription)

        purchase()
        val second = purchase()

        assertThat(billing.launched).hasSize(1)
        assertThat(second.errors).hasSize(1)
        assertThat(second.errors.single().first.code)
            .isEqualTo(PurchasesErrorCode.OperationAlreadyInProgressError)
    }

    @Test
    fun `重复回调守卫 —— 同一笔购买回调两次只处理一次`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()

        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        val transaction = subsTransaction()
        billing.deliverPurchases(transaction)
        billing.deliverPurchases(transaction)

        // 回调只能被取走一次（`takeCallback`）。第二次仍然会上报（幂等由后端保证），但不会再回调。
        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.errors).isEmpty()
    }

    // endregion

    // region PENDING

    @Test
    fun `PENDING —— 回调 pending 结果、不上报、不记台账、上下文留着`() {
        billing.subscriptionProducts = listOf(subscription)
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val recorder = purchase()

        billing.deliverPurchases(subsTransaction(purchaseState = Purchase.PurchaseState.PENDING))

        assertThat(harness.receiptRequests()).isEmpty()
        assertThat(billing.consumeAndSaveCalls).isEmpty()
        assertThat(harness.deviceCache.getPreviouslySentHashedTokens()).isEmpty()
        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.results.single().isPending).isTrue()
        assertThat(recorder.errors).isEmpty()
        // 上下文留着等它转 PURCHASED；但不再挡住同商品的下一次购买。
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNotNull
        assertThat(harness.pendingPurchases.hasActive("sub_premium")).isFalse()
        assertThat(harness.diagnostics.names()).contains(DiagnosticsTracker.EVENT_PURCHASE_PENDING)
    }

    // endregion

    // region 失败边界

    @Test
    fun `USER_CANCELED —— 回 purchaseCancelledError 并带 userCancelled 标志`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()

        billing.deliverPurchaseFailure(
            PurchasesError(PurchasesErrorCode.PurchaseCancelledError, "用户关掉了弹窗"),
            userCancelled = true,
        )

        assertThat(recorder.errors).hasSize(1)
        assertThat(recorder.errors.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseCancelledError)
        assertThat(recorder.errors.single().second).isTrue()
        // 购买根本没发生 → 上下文清掉。
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNull()
    }

    @Test
    fun `ITEM_ALREADY_OWNED —— 照常回错误，同时触发一轮 queryPurchases 补报`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()
        billing.purchasesOnDevice = listOf(subsTransaction(token = "token-owned"))
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        billing.deliverPurchaseFailure(
            PurchasesError(PurchasesErrorCode.ProductAlreadyPurchasedError, "已拥有"),
            userCancelled = false,
        )

        assertThat(recorder.errors.single().first.code)
            .isEqualTo(PurchasesErrorCode.ProductAlreadyPurchasedError)
        // 这个码的含义就是「Play 那边已经有这笔了」——多半是上次买成功但没上报成功。
        assertThat(harness.receiptRequests()).hasSize(1)
        assertThat(requireNotNull(harness.receiptRequests().single().body).getString("initiation_source"))
            .isEqualTo("unsynced_active_purchases")
    }

    @Test
    fun `没有 ProductDetails 的商品 —— 回 productNotAvailableForPurchase，不落上下文不 launch`() {
        val recorder = purchase(StoreProductBuilders.subscription("sub_premium", "monthly-base"))

        assertThat(billing.launched).isEmpty()
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNull()
        assertThat(recorder.errors.single().first.code)
            .isEqualTo(PurchasesErrorCode.ProductNotAvailableForPurchaseError)
    }

    // endregion

    // region 901 / 902

    @Test
    fun `5xx —— 回 901、不 ack 不 consume、上报上下文留存`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()

        harness.httpClient.enqueue(500, """{"message":"boom"}""")
        billing.deliverPurchases(subsTransaction())

        assertThat(billing.consumeAndSaveCalls).isEmpty()
        assertThat(recorder.errors.single().first.code)
            .isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isTrue()
    }

    @Test
    fun `确定性 4xx —— 回 902、ack 但不 consume、上下文清掉`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()

        harness.httpClient.enqueue(400, """{"code":7000,"message":"bad platform"}""")
        billing.deliverPurchases(subsTransaction())

        assertThat(billing.consumeAndSaveCalls).hasSize(1)
        assertThat(billing.consumeAndSaveCalls.single().deterministicallyRejected).isTrue()
        assertThat(billing.consumeAndSaveCalls.single().shouldConsume).isNull()
        assertThat(recorder.errors.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseRejectedByServer)
        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isFalse()
    }

    @Test
    fun `401 按可重试处理 —— 密钥是可修的配置，不能把交易 finish 掉`() {
        billing.subscriptionProducts = listOf(subscription)
        val recorder = purchase()

        harness.httpClient.enqueue(401, """{"code":7225,"message":"invalid key"}""")
        billing.deliverPurchases(subsTransaction())

        assertThat(billing.consumeAndSaveCalls).isEmpty()
        assertThat(recorder.errors.single().first.code)
            .isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
        assertThat(harness.pendingPurchases.hasPostContext("token-sub")).isTrue()
    }

    @Test
    fun `901 之后模拟进程重启 —— 新实例在 onConnected 时补报成功并清上下文`() {
        val prefsName = "restart-${System.nanoTime()}"
        val firstBilling = BillingHarness()
        val first = OrchestratorHarness(context, firstBilling, sharedPrefsName = prefsName)
        first.billing.subscriptionProducts = listOf(subscription)
        val recorder = Recorder()
        first.orchestrator.purchase(
            PurchaseParams.Builder(firstBilling.activity, subscription).build(),
            recorder,
        )
        first.httpClient.enqueue(500, """{"message":"boom"}""")
        firstBilling.deliverPurchases(subsTransaction())
        assertThat(first.pendingPurchases.hasPostContext("token-sub")).isTrue()

        // 进程重启：新的 orchestrator、新的 Billing 替身，**同一个 prefs 文件**。
        val secondBilling = BillingHarness()
        val second = OrchestratorHarness(context, secondBilling, sharedPrefsName = prefsName)
        // Play 已经看不到这笔了（消耗品被 consume / 订阅已入账），只有本地上下文能救它。
        secondBilling.purchasesOnDevice = emptyList()
        second.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        secondBilling.deliverConnected()

        val body = requireNotNull(second.receiptRequests().single().body)
        assertThat(body.getString("initiation_source")).isEqualTo("unsynced_active_purchases")
        assertThat(body.getString("fetch_token")).isEqualTo("token-sub")
        // 归因来自**购买当时**落盘的那一份（`sdk_originated = true`），不是补报这一刻现造的。
        assertThat(body.getBoolean("sdk_originated")).isTrue()
        assertThat(second.pendingPurchases.hasPostContext("token-sub")).isFalse()
        assertThat(second.deviceCache.getPreviouslySentHashedTokens()).contains("token-sub".sha1())
    }

    // endregion

    // region 升降级

    @Test
    fun `升降级 —— 旧购买由 SDK 现查，replacementMode 传到 Billing 层`() {
        seedCachedCustomerInfo()
        billing.subscriptionProducts = listOf(subscription)
        billing.oldPurchase = subsTransaction(productId = "sub_basic", token = "token-old")

        purchase(oldProductId = "sub_basic:monthly-base", replacementMode = ReplacementMode.CHARGE_PRORATED_PRICE)

        val launched = billing.launched.single()
        val replace = requireNotNull(launched.replaceProductInfo)
        // 宿主传的 `sub_basic:monthly-base` 被剥成裸 productId（坑 16 的归一化）。
        assertThat(replace.oldProductId).isEqualTo("sub_basic")
        assertThat(replace.oldPurchaseToken).isEqualTo("token-old")
        assertThat(replace.replacementMode).isEqualTo(ReplacementMode.CHARGE_PRORATED_PRICE)
    }

    @Test
    fun `升降级找不到旧订阅 —— 回错误并清掉上下文`() {
        billing.subscriptionProducts = listOf(subscription)
        billing.oldPurchase = null

        val recorder = purchase(oldProductId = "sub_basic", replacementMode = ReplacementMode.WITH_TIME_PRORATION)

        assertThat(billing.launched).isEmpty()
        assertThat(recorder.errors.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseInvalidError)
        assertThat(harness.pendingPurchases.contextFor("sub_premium")).isNull()
    }

    @Test
    fun `DEFERRED —— 回调挂在旧商品上（Play 回的交易是旧商品的）`() {
        billing.subscriptionProducts = listOf(subscription)
        billing.oldPurchase = subsTransaction(productId = "sub_basic", token = "token-old")

        val recorder = purchase(oldProductId = "sub_basic:monthly-base", replacementMode = ReplacementMode.DEFERRED)
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_basic" to false)))

        // Play 在 DEFERRED 下返回的是**旧商品**的交易。
        billing.deliverPurchases(subsTransaction(productId = "sub_basic", token = "token-old-deferred"))

        assertThat(recorder.results).hasSize(1)
        assertThat(recorder.errors).isEmpty()
    }

    @Test
    fun `非订阅商品传 oldProductId 直接拒绝`() {
        billing.inAppProducts = listOf(consumable)
        val recorder = purchase(consumable, oldProductId = "sub_basic")

        assertThat(billing.launched).isEmpty()
        assertThat(recorder.errors.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseNotAllowedError)
    }

    @Test
    fun `Play 明确不支持 SUBSCRIPTIONS_UPDATE 时拒绝升降级；判定不了则放行`() {
        billing.subscriptionProducts = listOf(subscription)
        billing.oldPurchase = subsTransaction(productId = "sub_basic", token = "token-old")

        billing.featureSupported = false
        val blocked = purchase(oldProductId = "sub_basic")
        assertThat(blocked.errors.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseNotAllowedError)
        assertThat(billing.launched).isEmpty()

        // null = 还没连上，不该因为一次连接抖动就让用户点不了升级按钮。
        billing.featureSupported = null
        purchase(oldProductId = "sub_basic")
        assertThat(billing.launched).hasSize(1)
    }

    @Test
    fun `坑 10 —— 升降级时一律不发 obfuscatedAccountId`() {
        assertThat(obfuscatedAccountIdToSend(accountToken, isProductChange = false)).isEqualTo(accountToken)
        assertThat(obfuscatedAccountIdToSend(accountToken, isProductChange = true)).isNull()
    }

    // endregion

    @Test
    fun `MY_APP 模式照常上报，但把完成交易的责任交给宿主`() {
        val observerBilling = BillingHarness()
        val observer = OrchestratorHarness(
            context,
            observerBilling,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.MY_APP,
        )
        observerBilling.subscriptionProducts = listOf(subscription)
        observer.orchestrator.purchase(
            PurchaseParams.Builder(observerBilling.activity, subscription).build(),
            Recorder(),
        )
        observer.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        observerBilling.deliverPurchases(subsTransaction())

        val body = requireNotNull(observer.receiptRequests().single().body)
        assertThat(body.getBoolean("observer_mode")).isTrue()
        assertThat(body.getString("purchase_completed_by")).isEqualTo("my_app")
        // consumeAndSave 仍然会被调用（它内部只记台账，绝不碰 Billing —— 见 ConsumeAndSaveTest）。
        assertThat(observerBilling.consumeAndSaveCalls.single().purchasesAreCompletedBy)
            .isEqualTo(PurchasesAreCompletedBy.MY_APP)
    }
}
