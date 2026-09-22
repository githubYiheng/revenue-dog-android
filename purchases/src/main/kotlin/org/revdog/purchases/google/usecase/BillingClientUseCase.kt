package org.revdog.purchases.google.usecase

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCallback
import org.revdog.purchases.google.BillingResponse
import org.revdog.purchases.google.billingResponseToPurchasesError
import org.revdog.purchases.google.toHumanReadableDescription
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 「等连接 → 发请求 → 按 responseCode 分类 → 退避重试」的统一骨架。
 * 结构对照 RC `google/usecase/BillingClientUseCase.kt`（考古 §9.1 必抄第 3 项）。
 *
 * 所有 BillingClient 调用都走它，这样重试与退避只有一份实现。
 */
internal typealias ExecuteRequestOnUIThreadFunction = (delayInMillis: Long, onError: (PurchasesError?) -> Unit) -> Unit

internal interface UseCaseParams {
    val appInBackground: Boolean
}

internal abstract class BillingClientUseCase<T>(
    private val useCaseParams: UseCaseParams,
    private val onErrorCallback: PurchasesErrorCallback,
    private val executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) {

    abstract val errorMessage: String

    /**
     * 是否对网络类错误做指数退避。默认 `false` = **立即重试 3 次**。
     * 只有后台批处理（M2 的 consume / acknowledge 补报）才开 —— 那种场景可以慢慢来。
     */
    protected open val backoffForNetworkErrors: Boolean = false

    private var retryAttempt: Int = 0
    private var retryBackoff: Duration = RETRY_TIMER_START

    fun run(delayMilliseconds: Long = 0) {
        executeRequestOnUIThread(delayMilliseconds) { connectionError ->
            if (connectionError == null) executeAsync() else onErrorCallback.onError(connectionError)
        }
    }

    abstract fun executeAsync()

    abstract fun onOk(received: T)

    fun processResult(
        billingResult: BillingResult,
        response: T,
        onSuccess: (T) -> Unit = ::onOk,
        onErrorResult: (BillingResult) -> Unit = ::forwardError,
    ) {
        when (BillingResponse.fromCode(billingResult.responseCode)) {
            BillingResponse.OK -> {
                retryBackoff = RETRY_TIMER_START
                onSuccess(response)
            }

            // 断连不是失败：重新排队等连接，由 BillingWrapper 的懒重连接上。
            BillingResponse.ServiceDisconnected -> {
                Logger.warn { "BillingClient 已断连，重新排队：$errorMessage" }
                run()
            }

            BillingResponse.ServiceUnavailable -> backoffOrErrorIfServiceUnavailable(onErrorResult, billingResult)

            BillingResponse.NetworkError, BillingResponse.Error ->
                backoffOrRetryNetworkError(onErrorResult, billingResult)

            else -> onErrorResult(billingResult)
        }
    }

    protected fun BillingClient?.withConnectedClient(receivingFunction: BillingClient.() -> Unit) {
        this?.takeIf { it.isReady }?.receivingFunction()
            ?: Logger.warn { "BillingClient 未连接，跳过：$errorMessage" }
    }

    private fun forwardError(billingResult: BillingResult) {
        val underlyingErrorMessage = "$errorMessage - ${billingResult.toHumanReadableDescription()}"
        Logger.error { underlyingErrorMessage }
        onErrorCallback.onError(
            billingResult.responseCode.billingResponseToPurchasesError(
                underlyingErrorMessage,
                billingResult.debugMessage,
            ),
        )
    }

    private fun backoffOrRetryNetworkError(onErrorResult: (BillingResult) -> Unit, billingResult: BillingResult) {
        when {
            backoffForNetworkErrors && retryBackoff < RETRY_TIMER_MAX_TIME -> retryWithBackoff()
            !backoffForNetworkErrors && retryAttempt < MAX_RETRIES_DEFAULT -> {
                retryAttempt++
                executeAsync()
            }
            else -> onErrorResult(billingResult)
        }
    }

    /**
     * `SERVICE_UNAVAILABLE`：**前台只退避到 4 秒就报错，后台可以退避到 15 分钟**。
     * 用户在等的时候不能干等（考古 §3.9）。
     */
    private fun backoffOrErrorIfServiceUnavailable(
        onErrorResult: (BillingResult) -> Unit,
        billingResult: BillingResult,
    ) {
        val maxBackoff = if (useCaseParams.appInBackground) {
            RETRY_TIMER_MAX_TIME
        } else {
            RETRY_TIMER_SERVICE_UNAVAILABLE_MAX_TIME_FOREGROUND
        }
        if (retryBackoff < maxBackoff) retryWithBackoff() else onErrorResult(billingResult)
    }

    private fun retryWithBackoff() {
        val currentDelay = retryBackoff
        retryBackoff = minOf(retryBackoff * 2, RETRY_TIMER_MAX_TIME)
        run(currentDelay.inWholeMilliseconds)
    }

    internal companion object {
        const val MAX_RETRIES_DEFAULT: Int = 3

        /** 878ms 起，这样翻倍到最后一次刚好接近 15 分钟（RC 原注释）。 */
        val RETRY_TIMER_START: Duration = 878.milliseconds
        val RETRY_TIMER_MAX_TIME: Duration = 15.minutes
        val RETRY_TIMER_SERVICE_UNAVAILABLE_MAX_TIME_FOREGROUND: Duration = 4.seconds
    }
}
