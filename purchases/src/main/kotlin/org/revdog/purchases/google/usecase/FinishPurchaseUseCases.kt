package org.revdog.purchases.google.usecase

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesErrorCallback
import org.revdog.purchases.google.billingResponseToPurchasesError
import org.revdog.purchases.google.toHumanReadableDescription

/**
 * `consumeAsync` / `acknowledgePurchase` 的共同骨架。
 *
 * 两处**偏离 RC**，都写在这里：
 *
 * 1. **`backoffForNetworkErrors` 恒为 `true`**（设计 §3 A2 的落地细节）。RC 只在
 *    `UNSYNCED_ACTIVE_PURCHASES` 下开退避，`purchase` / `restore` 下网络错误只快速重试 3 次就放弃。
 *    对我方不成立：**ack 失败 = 3 天后 Google 自动退款**（坑 2），这是全 SDK 里最不该「快速放弃」的调用。
 *    退避到 15 分钟封顶，配合前台 / 连接成功的补报，才是这条路径该有的耐心。
 *
 * 2. **`ITEM_NOT_OWNED` 视为已完成**。RC 只在 restore 时降级成 warning 日志，仍然回错误
 *    （源码里还留着 `// TODO-retry: if ITEM_NOT_OWNED queryPurchasesAsync`）。
 *    语义上 `ITEM_NOT_OWNED` = Play 已经不认为这笔还挂在用户名下 = 它已经被消耗 / 确认过了，
 *    再报错只会让这笔交易永远进不了台账、每次前台都重报一遍。
 */
internal abstract class FinishPurchaseUseCase(
    useCaseParams: UseCaseParams,
    private val purchaseToken: String,
    private val onReceive: (purchaseToken: String) -> Unit,
    private val onErrorCallback: PurchasesErrorCallback,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : BillingClientUseCase<String>(useCaseParams, onErrorCallback, executeRequestOnUIThread) {

    override val backoffForNetworkErrors: Boolean get() = true

    override fun onOk(received: String) {
        onReceive(received)
    }

    /** 统一的错误分支：`ITEM_NOT_OWNED` 按成功处理，其余照常报错。 */
    protected fun handleErrorResult(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_NOT_OWNED) {
            Logger.warn {
                "$errorMessage：Play 返回 ITEM_NOT_OWNED，视为这笔已经被完成过，记台账并继续" +
                    "（${billingResult.toHumanReadableDescription()}）"
            }
            onReceive(purchaseToken)
            return
        }
        val underlyingErrorMessage = "$errorMessage - ${billingResult.toHumanReadableDescription()}"
        Logger.error { underlyingErrorMessage }
        onErrorCallback.onError(
            billingResult.responseCode.billingResponseToPurchasesError(
                underlyingErrorMessage,
                billingResult.debugMessage,
            ),
        )
    }
}

internal class ConsumePurchaseUseCaseParams(
    val purchaseToken: String,
    override val appInBackground: Boolean,
) : UseCaseParams

/**
 * 消耗型商品的 `consumeAsync`。结构对照 RC `google/usecase/ConsumePurchaseUseCase.kt`。
 * **consume 自带 acknowledge**（Play 官方：consumeAsync 会自动确认），所以消耗分支不再另调 ack。
 */
internal class ConsumePurchaseUseCase(
    private val useCaseParams: ConsumePurchaseUseCaseParams,
    onReceive: (purchaseToken: String) -> Unit,
    onError: PurchasesErrorCallback,
    private val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : FinishPurchaseUseCase(
    useCaseParams,
    useCaseParams.purchaseToken,
    onReceive,
    onError,
    executeRequestOnUIThread,
) {

    override val errorMessage: String get() = "消耗购买失败"

    override fun executeAsync() {
        withConnectedClient {
            val params = ConsumeParams.newBuilder().setPurchaseToken(useCaseParams.purchaseToken).build()
            consumeAsync(params) { billingResult, token ->
                processResult(billingResult, token, onErrorResult = ::handleErrorResult)
            }
        }
    }
}

internal class AcknowledgePurchaseUseCaseParams(
    val purchaseToken: String,
    override val appInBackground: Boolean,
) : UseCaseParams

/**
 * `acknowledgePurchase`。结构对照 RC `google/usecase/AcknowledgePurchaseUseCase.kt`。
 *
 * 设计 §8：**服务端才是 ack 权威**，这里只是「服务端还没来得及 ack」时的补位 ——
 * 调用前先看 `Purchase.isAcknowledged` 快照，为 `true` 就根本不会走到这里。
 */
internal class AcknowledgePurchaseUseCase(
    private val useCaseParams: AcknowledgePurchaseUseCaseParams,
    onReceive: (purchaseToken: String) -> Unit,
    onError: PurchasesErrorCallback,
    private val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : FinishPurchaseUseCase(
    useCaseParams,
    useCaseParams.purchaseToken,
    onReceive,
    onError,
    executeRequestOnUIThread,
) {

    override val errorMessage: String get() = "确认购买失败"

    override fun executeAsync() {
        withConnectedClient {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(useCaseParams.purchaseToken)
                .build()
            acknowledgePurchase(params) { billingResult ->
                processResult(billingResult, useCaseParams.purchaseToken, onErrorResult = ::handleErrorResult)
            }
        }
    }
}
