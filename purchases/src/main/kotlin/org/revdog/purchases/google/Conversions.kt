package org.revdog.purchases.google

import com.android.billingclient.api.ProductDetails
import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.models.SubscriptionOptions

/**
 * `ProductDetails` → 我方模型。结构对照 RC `google/storeProductConversions.kt`
 * + `subscriptionOptionConversions.kt` + `pricingPhaseConversions.kt`。
 */

internal fun ProductDetails.PricingPhase.toRevenueDogPricingPhase(): PricingPhase = PricingPhase(
    billingPeriod = Period.create(billingPeriod),
    recurrenceMode = RecurrenceMode.fromIdentifier(recurrenceMode),
    billingCycleCount = billingCycleCount,
    // 价格用 micros（Play 原生就是 micros），`formattedPrice` 只做展示。
    price = Price(formattedPrice, priceAmountMicros, priceCurrencyCode),
)

/**
 * **只有一个 pricing phase 就是 base plan**（坑 15）。判据很脆但它是 Play 的实际语义。
 */
internal val ProductDetails.SubscriptionOfferDetails.isBasePlan: Boolean
    get() = pricingPhases.pricingPhaseList.size == 1

internal fun ProductDetails.SubscriptionOfferDetails.toSubscriptionOption(productId: String): SubscriptionOption =
    SubscriptionOption(
        productId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        pricingPhases = pricingPhases.pricingPhaseList.map { it.toRevenueDogPricingPhase() },
        tags = offerTags,
        offerToken = offerToken,
    )

/**
 * **一个 `ProductDetails` 炸开成 N 个 `StoreProduct`，N = base plan 数量**（考古 §3.7）。
 *
 * 取不到价格的商品**被整个丢弃**并打日志 —— 这是「offerings 里商品莫名消失」的第一嫌疑（坑 14）。
 * 我方额外把丢弃的 productId 冒泡给调用方，进 `not_found_product_ids`，
 * 不像 RC 那样只留一行日志。
 */
internal fun List<ProductDetails>.toStoreProducts(): Pair<List<StoreProduct>, List<String>> {
    val storeProducts = mutableListOf<StoreProduct>()
    val droppedProductIds = mutableListOf<String>()

    forEach { productDetails ->
        val offerDetails = productDetails.subscriptionOfferDetails.orEmpty()
        val basePlans = offerDetails.filter { it.isBasePlan }
        val offerDetailsByBasePlanId = offerDetails.groupBy { it.basePlanId }

        if (basePlans.isEmpty()) {
            // 没有 base plan = 一次性商品（或配错了的订阅）。
            val product = productDetails.toInAppStoreProduct()
            if (product != null) storeProducts.add(product) else droppedProductIds.add(productDetails.productId)
            return@forEach
        }
        basePlans.forEach { basePlan ->
            val product = productDetails.toStoreProduct(offerDetailsByBasePlanId[basePlan.basePlanId].orEmpty())
            if (product != null) {
                storeProducts.add(product)
            } else {
                Logger.error { "商品 ${productDetails.productId} 取不到价格，已丢弃" }
                droppedProductIds.add(productDetails.productId)
            }
        }
    }
    return storeProducts to droppedProductIds.distinct()
}

/** 一次性商品没有 base plan，也没有 offer。 */
internal fun ProductDetails.toInAppStoreProduct(): StoreProduct? = toStoreProduct(emptyList())

internal fun ProductDetails.toStoreProduct(
    offerDetails: List<ProductDetails.SubscriptionOfferDetails>,
): StoreProduct? {
    val revenueDogType = productType.toRevenueDogProductType()
    val subscriptionOptions = if (revenueDogType == ProductType.SUBS) {
        SubscriptionOptions(offerDetails.map { it.toSubscriptionOption(productId) })
    } else {
        null
    }

    val basePlan = subscriptionOptions?.basePlan
    // 一次性用 oneTimePurchaseOfferDetails；订阅用 base plan 的全价阶段。两者都拿不到就整个丢弃。
    val price = createOneTimeProductPrice() ?: basePlan?.fullPricePhase?.price ?: return null

    return StoreProduct(
        productId = productId,
        basePlanId = basePlan?.basePlanId,
        type = revenueDogType,
        price = price,
        name = name,
        title = title,
        description = description,
        period = basePlan?.billingPeriod,
        subscriptionOptions = subscriptionOptions,
        defaultOption = subscriptionOptions?.defaultOffer,
    ).withProductDetails(this)
}

private fun ProductDetails.createOneTimeProductPrice(): Price? {
    if (productType.toRevenueDogProductType() != ProductType.INAPP) return null
    @Suppress("DEPRECATION")
    return oneTimePurchaseOfferDetails?.let {
        Price(it.formattedPrice, it.priceAmountMicros, it.priceCurrencyCode)
    }
}
