package org.revdog.purchases.support

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.DelayedRunner

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

    companion object {
        fun billingResult(responseCode: Int, debugMessage: String = ""): BillingResult =
            BillingResult.newBuilder()
                .setResponseCode(responseCode)
                .setDebugMessage(debugMessage)
                .build()

        fun ok(): BillingResult = billingResult(BillingClient.BillingResponseCode.OK)
    }
}
