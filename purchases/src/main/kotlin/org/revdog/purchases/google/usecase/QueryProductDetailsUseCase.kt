package org.revdog.purchases.google.usecase

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.UnfetchedProduct
import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCallback
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.google.QueryProductDetailsParamsBuilderException
import org.revdog.purchases.google.buildQueryProductDetailsParams
import org.revdog.purchases.google.toGoogleProductType
import org.revdog.purchases.google.toStoreProducts
import org.revdog.purchases.models.StoreProduct
import java.util.concurrent.atomic.AtomicBoolean

internal class QueryProductDetailsUseCaseParams(
    val productIds: Set<String>,
    val productType: ProductType,
    override val appInBackground: Boolean,
) : UseCaseParams

/** 查询结果：查到的商品 + 没查到的 productId（后者进 `not_found_product_ids` 诊断）。 */
internal class QueryProductDetailsResponse(
    val storeProducts: List<StoreProduct>,
    val notFoundProductIds: List<String>,
)

/**
 * `queryProductDetailsAsync` 的单次调用（**单一 type**）。
 * 结构对照 RC `google/usecase/QueryProductDetailsUseCase.kt`。
 *
 * 分 type 查两次由调用方负责（考古 §3.8）。
 */
internal class QueryProductDetailsUseCase(
    private val useCaseParams: QueryProductDetailsUseCaseParams,
    private val onReceive: (QueryProductDetailsResponse) -> Unit,
    private val onError: PurchasesErrorCallback,
    private val withConnectedClient: (BillingClient.() -> Unit) -> Unit,
    executeRequestOnUIThread: ExecuteRequestOnUIThreadFunction,
) : BillingClientUseCase<QueryProductDetailsResult>(useCaseParams, onError, executeRequestOnUIThread) {

    override val errorMessage: String get() = "查询商品详情失败"

    override fun executeAsync() {
        // 坑 13：**空字符串 productId 会让整批查询失败**，先过滤。
        val nonEmptyProductIds = useCaseParams.productIds.filter { it.isNotEmpty() }.toSet()
        if (nonEmptyProductIds.isEmpty()) {
            Logger.debug { "productId 列表为空，跳过查询" }
            onReceive(QueryProductDetailsResponse(emptyList(), emptyList()))
            return
        }
        withConnectedClient {
            val googleType = useCaseParams.productType.toGoogleProductType() ?: BillingClient.ProductType.INAPP
            try {
                queryProductDetailsAsyncEnsuringOneResponse(this, googleType, nonEmptyProductIds, ::processResult)
            } catch (e: QueryProductDetailsParamsBuilderException) {
                onError.onError(
                    PurchasesError(PurchasesErrorCode.StoreProblemError, "${e.message}: ${e.cause?.message}"),
                )
            }
        }
    }

    override fun onOk(received: QueryProductDetailsResult) {
        val (storeProducts, droppedProductIds) = received.productDetailsList.toStoreProducts()

        // `unfetchedProductList`（PBL 8 新增）带 statusCode，是**「为什么这个商品查不到」的唯一线索**。
        // 每一条都要落日志（M3 会把它们送进 `billing_query` 诊断事件）。
        // 日志必须带上**这一次查的是哪个 type**：同一批 id 会按 SUBS / INAPP 各查一次（考古 §3.8），
        // 订阅在 INAPP 那次里必然 PRODUCT_NOT_FOUND（反之亦然），不写 type 会被读成「订阅也查不到」
        // （2026-09-20 真机排障两次被它误导）。真正「两次都没查到」的清单看 offerings 那条 warn。
        val queriedType = useCaseParams.productType
        val unfetchedProductIds = received.unfetchedProductList.map { unfetched ->
            Logger.info {
                "商品未返回（$queriedType 查询）：${unfetched.productId}（${statusCodeName(unfetched.statusCode)}）"
            }
            unfetched.productId
        }

        // **未知 productId 不算错误**（考古 §3.8）：后端配了但 Play 没有，属于配置问题，
        // 不该让整次 getOfferings 失败。
        val notFound = (unfetchedProductIds + droppedProductIds +
            useCaseParams.productIds.filter { requested -> storeProducts.none { it.productId == requested } })
            .distinct()

        onReceive(QueryProductDetailsResponse(storeProducts, notFound))
    }

    /**
     * 坑 6：**BillingClient 的 async 回调可能被调用多次**。RC 在六个不同的地方各放了一道
     * `AtomicBoolean` 守卫，说明这不是偶发。
     */
    @Synchronized
    private fun queryProductDetailsAsyncEnsuringOneResponse(
        billingClient: BillingClient,
        productType: String,
        productIds: Set<String>,
        listener: ProductDetailsResponseListener,
    ) {
        val params = productType.buildQueryProductDetailsParams(productIds)
        val hasResponded = AtomicBoolean(false)
        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            if (hasResponded.getAndSet(true)) {
                Logger.error { "queryProductDetailsAsync 回调被重复触发（code=${billingResult.responseCode}），已忽略" }
                return@queryProductDetailsAsync
            }
            listener.onProductDetailsResponse(billingResult, result)
        }
    }

    private fun statusCodeName(statusCode: Int): String = when (statusCode) {
        UnfetchedProduct.StatusCode.UNKNOWN -> "UNKNOWN"
        UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND -> "PRODUCT_NOT_FOUND"
        UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT -> "INVALID_PRODUCT_ID_FORMAT"
        UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER -> "NO_ELIGIBLE_OFFER"
        else -> "UNKNOWN_STATUS_CODE: $statusCode"
    }
}
