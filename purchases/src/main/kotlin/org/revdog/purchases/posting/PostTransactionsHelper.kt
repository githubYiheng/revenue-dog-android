package org.revdog.purchases.posting

import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction

/**
 * 上报前先把商品详情查回来，好让 `platform_product_ids` / `pricing_phases` / 价格填满。
 * 结构对照 RC `PostTransactionWithProductDetailsHelper.kt`。
 *
 * 用在两条路上：`onPurchasesUpdated`（`initiation_source=purchase`）与
 * 前台补报（`unsynced_active_purchases`）。**restore 不走它** —— 与 RC 一致，
 * restore 直接以退化形状上报（拿不到 `subscriptionOptionId`，`base_plan_id` 无从得知，
 * 考古 §2.3 第 3 条），权威历史在后端（决策 C）。
 *
 * 查商品失败**不阻断上报**：宁可上报一笔归因不全的交易，也不能让它丢掉。
 */
internal class PostTransactionsHelper(
    private val billing: BillingWrapper,
    private val postReceiptHelper: PostReceiptHelper,
) {

    @Suppress("LongParameterList")
    fun postTransactions(
        transactions: List<StoreTransaction>,
        isRestore: Boolean,
        appUserID: String,
        initiationSource: String,
        sdkOriginated: Boolean,
        onTransactionSuccess: (StoreTransaction, CustomerInfo) -> Unit,
        onTransactionError: (StoreTransaction, PurchasesError) -> Unit,
    ) {
        transactions.forEach { transaction ->
            val post = { storeProduct: StoreProduct? ->
                postReceiptHelper.postTransactionAndConsumeIfNeeded(
                    purchase = transaction,
                    storeProduct = storeProduct,
                    // v1 明确不支持多行订阅 / add-ons（坑 19），所以永远没有「每个 productId 一个 option」
                    // 的映射要传。字段形状保留在 `ReceiptInfo.from` 里，M3 接多行时只填这一个参数。
                    subscriptionOptionsForProductIds = null,
                    isRestore = isRestore,
                    appUserID = appUserID,
                    initiationSource = initiationSource,
                    sdkOriginated = sdkOriginated,
                    onSuccess = onTransactionSuccess,
                    onError = onTransactionError,
                )
            }
            billing.queryProductDetailsAsync(
                productType = transaction.type,
                productIds = transaction.productIds.toSet(),
                onReceive = { response -> post(pickPurchasedProduct(transaction, response.storeProducts)) },
                onError = { error ->
                    Logger.warn { "上报前查商品详情失败，按退化形状继续上报：$error" }
                    post(null)
                },
            )
        }
    }

    /**
     * 挑出「用户真正买的那个」商品。结构对照 RC `PostTransactionWithProductDetailsHelper:42-52`。
     *
     * 订阅按 `subscriptionOptionId` 反查（一个 productId 会炸成 N 个 `StoreProduct`，
     * N = base plan 数量，考古 §3.7 —— 只有 option id 能定位到具体是哪一个）；
     * 一次性商品按 productId 直接匹配。**匹配不到返回 `null`**，不猜。
     */
    private fun pickPurchasedProduct(
        transaction: StoreTransaction,
        storeProducts: List<StoreProduct>,
    ): StoreProduct? {
        val firstProductId = transaction.productIds.firstOrNull()
        return if (transaction.type == ProductType.SUBS) {
            val optionId = transaction.subscriptionOptionId ?: return null
            storeProducts.firstOrNull { product ->
                product.subscriptionOptions?.any { it.id == optionId } == true
            }
        } else {
            storeProducts.firstOrNull { it.productId == firstProductId }
        }
    }
}
