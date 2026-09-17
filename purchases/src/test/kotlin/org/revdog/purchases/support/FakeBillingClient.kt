package org.revdog.purchases.support

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.json.JSONArray
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.DelayedRunner
import org.revdog.purchases.models.StoreTransaction

/**
 * 记录被排期的重连，**不真的跑**。
 *
 * 这样退避序列（1s → 2s → 4s… → 15min 封顶）与「重复调度守卫」都能被直接断言，
 * 而不是靠 sleep 去碰运气。
 */
internal class RecordingDelayedRunner : DelayedRunner {

    val scheduledDelays: MutableList<Long> = mutableListOf()
    private val pending: MutableList<() -> Unit> = mutableListOf()

    override fun postDelayed(delayMillis: Long, action: () -> Unit) {
        scheduledDelays += delayMillis
        pending += action
    }

    /** 手动跑掉所有排期任务（模拟时间到了）。 */
    fun runPending() {
        val toRun = pending.toList()
        pending.clear()
        toRun.forEach { it() }
    }

    fun clear() {
        scheduledDelays.clear()
        pending.clear()
    }
}

/**
 * `BillingClient` 是 Google 的 final 类，只能用 MockK 的 inline mock
 * （RC 的单测也是这么做的）。这里把「建 client + 捕获它注册的 listener」封成一个夹具。
 */
internal class FakeBillingClientFixture {

    val billingClient: BillingClient = mockk(relaxed = true)

    private var ready: Boolean = false

    /** BillingWrapper 注册给 BillingClient 的 `PurchasesUpdatedListener`。 */
    var capturedPurchasesUpdatedListener: PurchasesUpdatedListener? = null
        private set

    val clientFactory: BillingWrapper.ClientFactory = mockk {
        val listenerSlot = slot<PurchasesUpdatedListener>()
        every { buildClient(capture(listenerSlot)) } answers {
            capturedPurchasesUpdatedListener = listenerSlot.captured
            billingClient
        }
    }

    init {
        every { billingClient.isReady } answers { ready }
    }

    fun setReady(value: Boolean) {
        ready = value
    }

    // region consume / acknowledge 的捕获（七分支断言用）

    val consumedTokens: MutableList<String> = mutableListOf()
    val acknowledgedTokens: MutableList<String> = mutableListOf()

    var consumeResult: BillingResult = ok()
    var acknowledgeResult: BillingResult = ok()

    /** 把 `consumeAsync` / `acknowledgePurchase` 接上，记 token 并按脚本回结果。 */
    fun stubFinishCalls() {
        every { billingClient.consumeAsync(any(), any()) } answers {
            val params = firstArg<ConsumeParams>()
            val listener = secondArg<ConsumeResponseListener>()
            consumedTokens += params.purchaseToken
            listener.onConsumeResponse(consumeResult, params.purchaseToken)
        }
        every { billingClient.acknowledgePurchase(any(), any()) } answers {
            val params = firstArg<AcknowledgePurchaseParams>()
            val listener = secondArg<AcknowledgePurchaseResponseListener>()
            acknowledgedTokens += params.purchaseToken
            listener.onAcknowledgePurchaseResponse(acknowledgeResult)
        }
    }

    // endregion

    // region queryPurchasesAsync 的捕获

    /**
     * `queryPurchasesAsync` 的应答队列，**按调用顺序**出队。
     *
     * `QueryPurchasesParams` 没有公开的 `getProductType()`，所以没法从入参分辨这次问的是
     * SUBS 还是 INAPP。靠顺序：我方代码的契约是**先 SUBS 再 INAPP**
     * （`QueryPurchasesUseCase.executeAsync` / `BillingWrapper.getPurchaseType`），
     * 队列第一项就是 SUBS 的答复。
     */
    val queryPurchasesResponses: ArrayDeque<List<Purchase>> = ArrayDeque()

    val queryPurchasesCallCount: Int get() = queryPurchasesCalls

    private var queryPurchasesCalls = 0

    fun stubQueryPurchases() {
        every { billingClient.queryPurchasesAsync(any(), any()) } answers {
            queryPurchasesCalls++
            val listener = secondArg<PurchasesResponseListener>()
            listener.onQueryPurchasesResponse(ok(), queryPurchasesResponses.removeFirstOrNull() ?: emptyList())
        }
    }

    // endregion

    companion object {
        fun billingResult(responseCode: Int, debugMessage: String = ""): BillingResult =
            BillingResult.newBuilder()
                .setResponseCode(responseCode)
                .setDebugMessage(debugMessage)
                .build()

        fun ok(): BillingResult = billingResult(BillingClient.BillingResponseCode.OK)
    }
}

/**
 * 记录型购买更新监听。两个面都记，测试才能断言「不重复回调」这种事。
 */
internal class RecordingPurchasesUpdatedListener : BillingWrapper.BillingPurchasesUpdatedListener {

    val updates: MutableList<List<StoreTransaction>> = mutableListOf()
    val failures: MutableList<Pair<PurchasesError, Boolean>> = mutableListOf()

    /** 所有批次拍平。 */
    val allTransactions: List<StoreTransaction> get() = updates.flatten()

    override fun onPurchasesUpdated(transactions: List<StoreTransaction>) {
        updates += transactions
    }

    override fun onPurchasesFailedToUpdate(error: PurchasesError, userCancelled: Boolean) {
        failures += error to userCancelled
    }
}

/**
 * 造一个**真实**的 `Purchase`。
 *
 * `Purchase(originalJson, signature)` 是公开构造器，所以不 mock：mock 出来的
 * `Purchase` 只能测到「mock 配得对不对」，而真实对象会跑 Play 自己的 JSON 解析
 * （`purchaseState` 的 1/4 映射就是在那里做的）。
 */
@Suppress("LongParameterList")
internal fun purchaseFixture(
    productIds: List<String> = listOf("sub_premium"),
    purchaseToken: String = "token-${productIds.firstOrNull()}",
    purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    acknowledged: Boolean = false,
    autoRenewing: Boolean = true,
    purchaseTime: Long = 1_789_000_000_000L,
    orderId: String = "GPA.1234-5678-9012-34567",
): Purchase = Purchase(
    """
    {
      "orderId": "$orderId",
      "packageName": "com.loomalabs.demo",
      "productIds": ${JSONArray(productIds)},
      "purchaseTime": $purchaseTime,
      "purchaseState": ${if (purchaseState == Purchase.PurchaseState.PENDING) PLAY_JSON_PENDING else PLAY_JSON_PURCHASED},
      "purchaseToken": "$purchaseToken",
      "acknowledged": $acknowledged,
      "autoRenewing": $autoRenewing
    }
    """.trimIndent(),
    "signature-$purchaseToken",
)

// Play 的 originalJson 里 purchaseState 用的是另一套数值（1 = 已购买、4 = 待处理），
// `Purchase.getPurchaseState()` 会把它翻成 PURCHASED(1) / PENDING(2)。抄自 RC 的 stub。
private const val PLAY_JSON_PURCHASED = 1
private const val PLAY_JSON_PENDING = 4
