package org.revdog.purchases.models

import com.android.billingclient.api.ProductDetails
import dev.drewhamilton.poko.Poko
import org.revdog.purchases.ProductType

/**
 * 一个可购买的订阅选项（base plan 或 offer）。结构对照 RC `models/SubscriptionOption.kt`。
 */
@Poko
public class SubscriptionOption internal constructor(
    public val productId: String,
    public val basePlanId: String,
    public val offerId: String?,
    /** 用户实际会经历的付费阶段序列（免费试用 → 折扣期 → 全价）。 */
    public val pricingPhases: List<PricingPhase>,
    /** base plan 与 offer 上配置的 tag。**offer 会自动继承 base plan 的 tag**。 */
    public val tags: List<String>,
    /** `launchBillingFlow` 要用的 token。M2 购买链路用。 */
    public val offerToken: String,
) {

    /** `basePlanId`，或 offer 的 `basePlanId:offerId`（RC 同款）。 */
    public val id: String
        get() = if (offerId.isNullOrEmpty()) basePlanId else "$basePlanId$OFFER_ID_SEPARATOR$offerId"

    /**
     * 是不是 base plan。
     *
     * 判据来自 RC：**只有一个 pricing phase 就是 base plan**（坑 15）。
     * 这个判据很脆 —— 一个配了 intro price 的 base plan 会被误判成 offer ——
     * 但它确实是 Play 的实际语义，`ProductDetails.SubscriptionOfferDetails` 没有别的信号。
     */
    public val isBasePlan: Boolean get() = pricingPhases.size == 1

    /** 全价阶段 = 最后一个阶段。 */
    public val fullPricePhase: PricingPhase? get() = pricingPhases.lastOrNull()

    public val billingPeriod: Period? get() = fullPricePhase?.billingPeriod

    /** 预付费套餐（不自动续订），`willRenew` 的第五项否定就看它。 */
    public val isPrepaid: Boolean get() = fullPricePhase?.recurrenceMode == RecurrenceMode.NON_RECURRING

    /**
     * 免费试用阶段。**先 `dropLast(1)`**（去掉全价阶段）再找第一个 `amountMicros == 0` 的
     * —— RC 同款，否则一个免费商品会被当成「全程免费试用」。
     */
    public val freePhase: PricingPhase?
        get() = pricingPhases.dropLast(1).firstOrNull { it.price.amountMicros == 0L }

    /** 折扣阶段。同样先 `dropLast(1)`，再找第一个 `amountMicros > 0` 的。 */
    public val introPhase: PricingPhase?
        get() = pricingPhases.dropLast(1).firstOrNull { it.price.amountMicros > 0L }

    /**
     * 原始 `ProductDetails`。`launchBillingFlow` 要用它（对照 RC `GoogleSubscriptionOption.productDetails`）。
     * 由 `Conversions.toStoreProduct` 在建好 [StoreProduct] 之后回填。
     */
    internal var productDetails: ProductDetails? = null
        private set

    /** 回指所属商品：上报时的价格 / 周期快照取自它。 */
    internal var storeProduct: StoreProduct? = null
        private set

    internal fun attach(details: ProductDetails, product: StoreProduct): SubscriptionOption = apply {
        productDetails = details
        storeProduct = product
    }

    internal companion object {
        const val OFFER_ID_SEPARATOR: String = ":"
    }
}

/**
 * 一个 base plan 下的全部购买选项。结构对照 RC `models/SubscriptionOptions.kt`。
 */
public class SubscriptionOptions internal constructor(
    private val options: List<SubscriptionOption>,
) : List<SubscriptionOption> by options {

    public val basePlan: SubscriptionOption? get() = firstOrNull { it.isBasePlan }

    public val freeTrial: SubscriptionOption? get() = firstOrNull { it.freePhase != null }

    public val introOffer: SubscriptionOption? get() = firstOrNull { it.introPhase != null }

    /**
     * 默认选项。挑选顺序照抄 RC：
     * 排除带忽略 tag 的 offer → **最长免费试用** → **最便宜的 intro** → base plan。
     */
    public val defaultOffer: SubscriptionOption?
        get() {
            val base = basePlan ?: return null
            val validOffers = filter { !it.isBasePlan }.filter { !it.tags.contains(IGNORE_OFFER_TAG) }
            return findLongestFreeTrial(validOffers) ?: findLowestNonFreeOffer(validOffers) ?: base
        }

    public fun withTag(tag: String): List<SubscriptionOption> = filter { it.tags.contains(tag) }

    override fun equals(other: Any?): Boolean = this === other || (other as? SubscriptionOptions)?.options == options

    override fun hashCode(): Int = options.hashCode()

    override fun toString(): String = "SubscriptionOptions($options)"

    private fun findLongestFreeTrial(offers: List<SubscriptionOption>): SubscriptionOption? =
        offers.mapNotNull { offer ->
            offer.freePhase?.let { phase -> offer to billingPeriodToDays(phase.billingPeriod) }
        }.maxByOrNull { it.second }?.first

    private fun findLowestNonFreeOffer(offers: List<SubscriptionOption>): SubscriptionOption? =
        offers.mapNotNull { offer ->
            offer.introPhase?.let { phase -> offer to phase.price.amountMicros }
        }.minByOrNull { it.second }?.first

    private fun billingPeriodToDays(period: Period): Int = period.value * Period.daysIn(period.unit)

    internal companion object {
        /** 与 RC 的 `rc-ignore-offer` 对位。宿主在 Play Console 上打这个 tag 即可让默认选择跳过。 */
        const val IGNORE_OFFER_TAG: String = "rd-ignore-offer"
    }
}

/**
 * 一个可展示、可购买的商品。结构对照 RC `models/GoogleStoreProduct.kt`。
 *
 * **一个 `ProductDetails` 会炸开成 N 个 `StoreProduct`，N = base plan 数量**（考古 §3.7）。
 * 每个 `StoreProduct` 的 [subscriptionOptions] 只含它那个 base plan 及其 offers。
 */
@Poko
public class StoreProduct internal constructor(
    /** Play 的 productId（订阅 id 或 inapp id）。 */
    public val productId: String,
    /** Google base plan id。一次性商品为 `null`。 */
    public val basePlanId: String?,
    public val type: ProductType,
    /** 订阅取 base plan 的全价阶段价格；一次性取 `oneTimePurchaseOfferDetails`。 */
    public val price: Price,
    public val name: String,
    public val title: String,
    public val description: String,
    /** base plan 的计费周期。一次性商品为 `null`。 */
    public val period: Period?,
    public val subscriptionOptions: SubscriptionOptions?,
    public val defaultOption: SubscriptionOption?,
) {

    /**
     * **Google 的「商品」是 `productId:basePlanId` 二元组，不是单个 id**（考古 §2.3）。
     * offerings 里的 `platform_product_identifier` + `platform_product_plan_identifier`
     * 组合出来的就是这个 id。
     */
    public val id: String
        get() = basePlanId?.let { "$productId$ID_SEPARATOR$it" } ?: productId

    /** 原始 `ProductDetails`。M2 的 `launchBillingFlow` 要用它。 */
    internal var productDetails: ProductDetails? = null
        private set

    internal fun withProductDetails(details: ProductDetails): StoreProduct = apply {
        productDetails = details
        // option 也要拿到 details：`PurchaseParams.Builder(activity, subscriptionOption)` 是公开入口，
        // 宿主从 offerings 里挑一个 offer 直接买时，SDK 手上只有这个 option。
        subscriptionOptions?.forEach { it.attach(details, this) }
    }

    internal companion object {
        const val ID_SEPARATOR: String = ":"
    }
}
