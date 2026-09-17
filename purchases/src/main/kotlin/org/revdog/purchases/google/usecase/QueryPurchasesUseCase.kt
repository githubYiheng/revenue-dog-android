package org.revdog.purchases.google.usecase

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.common.sha1
import org.revdog.purchases.google.buildQueryPurchasesParams
import org.revdog.purchases.google.toRevenueDogProductType
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.models.StoreTransaction
import java.util.concurrent.atomic.AtomicBoolean

internal class QueryPurchasesByTypeUseCaseParams(
    val googleProductType: String,
    override val appInBackground: Boolean,
) : UseCaseParams

/**
 * 单一 type 的 `queryPurchasesAsync`。结构对照 RC `google/usecase/QueryPurchasesByTypeUseCase.kt`。
 *
 * 结果按 **`sha1(purchaseToken)`** 建索引 —— 台账用的就是这个 key（考古 §2.2），
 * 差集比对时两边形状必须一致。
 */
internal class QueryPurchasesByTypeUseCase(
    private val useCaseParams: QueryPurchasesByTypeUseCaseParams,
    private val onSuccess: (Map<String, StoreTransaction>) -> Unit,
    onError: (PurchasesError) -> Unit,
    private val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : BillingClientUseCase<Map<String, StoreTransaction>>(
    useCaseParams,
    { error -> onError(error) },
    executeRequestOnUIThread,
) {

    override val errorMessage: String get() = "查询 ${useCaseParams.googleProductType} 购买失败"

    override fun executeAsync() {
        withConnectedClient {
            val params = useCaseParams.googleProductType.buildQueryPurchasesParams()
            if (params == null) {
                Logger.error { "未知的商品类型：${useCaseParams.googleProductType}" }
                processResult(developerError(), emptyMap())
                return@withConnectedClient
            }
            queryPurchasesEnsuringOneResponse(this, params) { billingResult, purchases ->
                processResult(billingResult, purchases.toTransactionsByHashedToken())
            }
        }
    }

    override fun onOk(received: Map<String, StoreTransaction>) {
        onSuccess(received)
    }

    /** 坑 6：BillingClient 的 async 回调会被重复触发，每处都要一道 `AtomicBoolean` 守卫。 */
    private fun queryPurchasesEnsuringOneResponse(
        billingClient: BillingClient,
        params: QueryPurchasesParams,
        listener: PurchasesResponseListener,
    ) {
        val hasResponded = AtomicBoolean(false)
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (hasResponded.getAndSet(true)) {
                Logger.error { "queryPurchasesAsync 回调被重复触发（code=${billingResult.responseCode}），已忽略" }
                return@queryPurchasesAsync
            }
            listener.onQueryPurchasesResponse(billingResult, purchases)
        }
    }

    private fun List<Purchase>.toTransactionsByHashedToken(): Map<String, StoreTransaction> = associate { purchase ->
        purchase.purchaseToken.sha1() to
            purchase.toStoreTransaction(useCaseParams.googleProductType.toRevenueDogProductType())
    }

    private fun developerError(): BillingResult =
        BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR).build()
}

internal class QueryPurchasesUseCaseParams(override val appInBackground: Boolean) : UseCaseParams

/**
 * 全量 `queryPurchases`：**分 SUBS / INAPP 两次查再合并**（考古 §3.4，坑 11）。
 * Play 不接受混合 type 的查询。
 *
 * 注意它返回的**不是历史**：PBL 8 起 `queryPurchaseHistoryAsync` 已被删除（坑 21，
 * 9.1.0 的 `BillingClient` 上确认没有这个方法），能看到的只有
 * **活跃订阅 + 未消耗的一次性商品**。历史的权威在后端（决策 C）。
 */
internal class QueryPurchasesUseCase(
    private val useCaseParams: QueryPurchasesUseCaseParams,
    private val onSuccess: (Map<String, StoreTransaction>) -> Unit,
    private val onErrorCallback: (PurchasesError) -> Unit,
    private val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    private val executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : BillingClientUseCase<Map<String, StoreTransaction>>(
    useCaseParams,
    { error -> onErrorCallback(error) },
    executeRequestOnUIThread,
) {

    override val errorMessage: String get() = "查询购买失败"

    override fun executeAsync() {
        queryByType(BillingClient.ProductType.SUBS) { subscriptions ->
            queryByType(BillingClient.ProductType.INAPP) { inApps ->
                onOk(subscriptions + inApps)
            }
        }
    }

    override fun onOk(received: Map<String, StoreTransaction>) {
        onSuccess(received)
    }

    private fun queryByType(googleProductType: String, onReceived: (Map<String, StoreTransaction>) -> Unit) {
        QueryPurchasesByTypeUseCase(
            useCaseParams = QueryPurchasesByTypeUseCaseParams(googleProductType, useCaseParams.appInBackground),
            onSuccess = onReceived,
            onError = onErrorCallback,
            withConnectedClient = withConnectedClient,
            executeRequestOnUIThread = executeRequestOnUIThread,
        ).run()
    }
}
