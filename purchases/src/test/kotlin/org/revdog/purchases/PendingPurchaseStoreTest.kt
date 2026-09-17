package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.PendingPurchase
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.posting.InitiationSource
import org.revdog.purchases.posting.PlatformProductId
import org.revdog.purchases.posting.ReceiptInfo
import org.revdog.purchases.support.FakeHTTPClient
import org.robolectric.RobolectricTestRunner

/**
 * 合并后的购买上下文表（决策 D）+ 上报上下文台账（等价 RC `LocalTransactionMetadataStore`）。
 *
 * 落盘这件事的全部意义：**进程被杀之后归因不能丢**（考古 §5.4 / §5.5）。
 */
@RunWith(RobolectricTestRunner::class)
class PendingPurchaseStoreTest {

    private lateinit var context: Context
    private lateinit var prefsName: String
    private lateinit var store: PendingPurchaseStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "pending-${System.nanoTime()}"
        store = newStore()
    }

    private fun newStore() = PendingPurchaseStore(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        FakeHTTPClient.TEST_API_KEY,
    )

    private val noopCallback = object : PurchaseCallback {
        override fun onCompleted(result: PurchaseResult) = Unit
        override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
    }

    private fun receiptInfo(productId: String = "sub_premium", offering: String? = "default") = ReceiptInfo(
        productIds = listOf(productId),
        platformProductIds = listOf(PlatformProductId(productId, "monthly-base", "welcome-offer")),
        purchaseTime = 1_789_000_000_000L,
        presentedOfferingIdentifier = offering,
        priceAmountMicros = 4_990_000L,
        currency = "USD",
        formattedPrice = "$4.99",
        durationIso = "P1M",
        replacementMode = ReplacementMode.DEFERRED,
        sdkOriginated = true,
    )

    private fun start(key: String = "sub_premium"): Boolean = store.start(
        key = key,
        productType = ProductType.SUBS,
        subscriptionOptionId = "monthly-base:welcome-offer",
        presentedPackageIdentifier = "\$rc_monthly".let { it },
        receiptInfo = receiptInfo(),
        callback = noopCallback,
    )

    // region 发起中的购买

    @Test
    fun `key 按任意 productId 归一化查得到（坑 16 的唯一实现点）`() {
        start()

        assertThat(store.contextFor("sub_premium")).isNotNull
        // 宿主 / Play 两边的写法都认：`sub_premium` 与 `sub_premium:monthly-base`。
        assertThat(store.contextFor("sub_premium:monthly-base")).isNotNull
        assertThat(store.contextFor("other")).isNull()
    }

    @Test
    fun `同商品第二次 start 被拒（operationAlreadyInProgress 的来源）`() {
        assertThat(start()).isTrue()
        assertThat(start()).isFalse()
    }

    @Test
    fun `markPending 保留上下文但放行下一次购买`() {
        start()
        store.markPending("sub_premium")

        assertThat(store.contextFor("sub_premium")?.state).isEqualTo(PendingPurchase.STATE_PENDING)
        assertThat(store.hasActive("sub_premium")).isFalse()
        assertThat(start()).isTrue()
    }

    @Test
    fun `takeCallback 只能取一次 —— 重复回调守卫`() {
        start()

        assertThat(store.takeCallback("sub_premium")).isNotNull
        assertThat(store.takeCallback("sub_premium")).isNull()
    }

    @Test
    fun `finish 清掉上下文与回调`() {
        start()
        store.finish("sub_premium:monthly-base")

        assertThat(store.contextFor("sub_premium")).isNull()
        assertThat(store.takeCallback("sub_premium")).isNull()
    }

    @Test
    fun `整体失败时排空回调，但 PENDING 状态的上下文留着`() {
        start()
        start("coins_100")
        store.markPending("coins_100")

        val callbacks = store.takeAllCallbacksAndClearLaunched()

        assertThat(callbacks).hasSize(2)
        // launched 的那笔购买根本没发生 → 清掉。
        assertThat(store.contextFor("sub_premium")).isNull()
        // pending 的那笔钱可能随后就到账 → 归因不能丢。
        assertThat(store.contextFor("coins_100")).isNotNull
    }

    @Test
    fun `上下文跨实例可读 —— 进程被杀之后归因还在`() {
        start()

        val reloaded = newStore().contextFor("sub_premium")

        assertThat(reloaded).isNotNull
        assertThat(reloaded?.productType).isEqualTo(ProductType.SUBS)
        assertThat(reloaded?.subscriptionOptionId).isEqualTo("monthly-base:welcome-offer")
        assertThat(reloaded?.receiptInfo?.presentedOfferingIdentifier).isEqualTo("default")
        assertThat(reloaded?.receiptInfo?.priceAmountMicros).isEqualTo(4_990_000L)
        assertThat(reloaded?.receiptInfo?.replacementMode).isEqualTo(ReplacementMode.DEFERRED)
        assertThat(reloaded?.receiptInfo?.platformProductIds?.single()?.basePlanId).isEqualTo("monthly-base")
        // 回调**不会**跨进程恢复 —— 没有任何办法恢复它，只有上下文能。
        assertThat(newStore().takeCallback("sub_premium")).isNull()
    }

    // endregion

    // region 上报上下文台账

    @Test
    fun `只在 purchase 时落盘 —— restore 与补报不凭空造上下文`() {
        store.getOrPutPostContext("token-a", receiptInfo(), InitiationSource.RESTORE, PurchasesAreCompletedBy.REVENUE_DOG)
        assertThat(store.hasPostContext("token-a")).isFalse()

        store.getOrPutPostContext(
            "token-b",
            receiptInfo(),
            InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
            PurchasesAreCompletedBy.REVENUE_DOG,
        )
        assertThat(store.hasPostContext("token-b")).isFalse()

        store.getOrPutPostContext("token-c", receiptInfo(), InitiationSource.PURCHASE, PurchasesAreCompletedBy.REVENUE_DOG)
        assertThat(store.hasPostContext("token-c")).isTrue()
    }

    @Test
    fun `已有不覆盖、缓存优先 —— 第二次上报不会把归因冲掉`() {
        store.getOrPutPostContext(
            "token-a",
            receiptInfo(offering = "default"),
            InitiationSource.PURCHASE,
            PurchasesAreCompletedBy.REVENUE_DOG,
        )

        val second = store.getOrPutPostContext(
            "token-a",
            receiptInfo(offering = "retention_offer"),
            InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
            PurchasesAreCompletedBy.MY_APP,
        )

        assertThat(second.receiptInfo.presentedOfferingIdentifier).isEqualTo("default")
        // 完成者模式也是购买当时那一份（考古 §5.4 的注释点）。
        assertThat(second.purchasesAreCompletedBy).isEqualTo(PurchasesAreCompletedBy.REVENUE_DOG)
    }

    @Test
    fun `台账跨实例可读，clearPostContext 之后消失`() {
        store.getOrPutPostContext("token-a", receiptInfo(), InitiationSource.PURCHASE, PurchasesAreCompletedBy.REVENUE_DOG)

        assertThat(newStore().allPostContexts().map { it.token }).containsExactly("token-a")

        store.clearPostContext("token-a")
        assertThat(newStore().allPostContexts()).isEmpty()
    }

    @Test
    fun `坏掉的持久化内容按空处理，不抛`() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("org.revdog.purchases.${FakeHTTPClient.TEST_API_KEY}.pendingPurchases", "{not json")
            .putString("org.revdog.purchases.${FakeHTTPClient.TEST_API_KEY}.postedTransactions", "]]]")
            .apply()

        val fresh = newStore()
        assertThat(fresh.contextFor("sub_premium")).isNull()
        assertThat(fresh.allPostContexts()).isEmpty()
    }

    // endregion
}
