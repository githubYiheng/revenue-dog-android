package org.revdog.purchases.support

import org.revdog.purchases.ProductType
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.models.SubscriptionOptions

/**
 * 手搭 [StoreProduct]。
 *
 * **不 mock `ProductDetails`**：`ProductDetails` 的构造被 Play 藏起来了，
 * mock 它只会测出「mock 配得对不对」。这里直接构造我方模型，
 * `ProductDetails` → 模型的那一步由 `ConversionsTest` 单独覆盖。
 */
internal object StoreProductBuilders {

    fun pricingPhase(
        iso8601: String = "P1M",
        amountMicros: Long = 4_990_000L,
        recurrenceMode: RecurrenceMode = RecurrenceMode.INFINITE_RECURRING,
        billingCycleCount: Int? = null,
    ): PricingPhase = PricingPhase(
        billingPeriod = Period.create(iso8601),
        recurrenceMode = recurrenceMode,
        billingCycleCount = billingCycleCount,
        price = Price(formattedPrice(amountMicros), amountMicros, "USD"),
    )

    fun subscriptionOption(
        productId: String,
        basePlanId: String,
        offerId: String? = null,
        pricingPhases: List<PricingPhase> = listOf(pricingPhase()),
        tags: List<String> = emptyList(),
    ): SubscriptionOption = SubscriptionOption(
        productId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        pricingPhases = pricingPhases,
        tags = tags,
        offerToken = "token-$productId-$basePlanId${offerId?.let { ":$it" }.orEmpty()}",
    )

    fun subscription(
        productId: String,
        basePlanId: String,
        offers: List<SubscriptionOption> = emptyList(),
        billingPeriod: String = "P1M",
    ): StoreProduct {
        val base = subscriptionOption(productId, basePlanId, pricingPhases = listOf(pricingPhase(billingPeriod)))
        val options = SubscriptionOptions(listOf(base) + offers)
        return StoreProduct(
            productId = productId,
            basePlanId = basePlanId,
            type = ProductType.SUBS,
            price = requireNotNull(base.fullPricePhase).price,
            name = productId,
            title = "$productId ($basePlanId)",
            description = "desc",
            period = base.billingPeriod,
            subscriptionOptions = options,
            defaultOption = options.defaultOffer,
        )
    }

    fun inApp(productId: String, amountMicros: Long = 990_000L): StoreProduct = StoreProduct(
        productId = productId,
        basePlanId = null,
        type = ProductType.INAPP,
        price = Price(formattedPrice(amountMicros), amountMicros, "USD"),
        name = productId,
        title = productId,
        description = "desc",
        period = null,
        subscriptionOptions = null,
        defaultOption = null,
    )

    private fun formattedPrice(amountMicros: Long): String =
        if (amountMicros == 0L) "$0.00" else "$" + "%.2f".format(amountMicros / 1_000_000.0)
}
