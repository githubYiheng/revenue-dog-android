package org.revdog.purchases

import org.json.JSONObject
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.models.SubscriptionOptions
import org.revdog.purchases.offerings.OfferingParser
import org.revdog.purchases.offerings.Offerings

/**
 * **测试专用**的模型工厂（主代理裁定 7）。
 *
 * 对照 RC：RC 的商店侧模型（如 `GoogleStoreProduct`）构造器是公开的，混合框架测试直接构造；
 * 我方公开模型的构造器一律 `internal`，所以改为集中到本入口（偏离，理由同上：不给宿主凭空多出构造面）。
 *
 * 宿主不该自己拼 CustomerInfo，但 Flutter 插件的 Bridge 测试要从
 * 同一组 backend fixture 造出**真实**原生模型，再断言「原生模型 → 通道 map == wire fixture」。
 * 这里给出唯一的构造入口：
 *
 * - 后端 JSON 能表达的（CustomerInfo / Offerings）→ **复用 SDK 自己的解析器**，与真实网络路径同一套代码，
 *   不另写解析；
 * - 商店侧模型（StoreProduct / SubscriptionOption / PricingPhase / StoreTransaction）后端 JSON 构造不出，
 *   按字段构造；派生属性（`id`、`defaultOption`、`freePhase` 等）仍由模型自身 / 既有规则推导，不在这里另算。
 *
 * 全部 `@JvmStatic`：插件 Android 侧是 Java（ADR 0099）。`@InternalRevenueDogAPI` → 不进公开 API 基线。
 */
@InternalRevenueDogAPI
public object RevenueDogTestModels {

    /**
     * `GET /v1/subscribers/{id}` 的响应体 → [CustomerInfo]。`requestDate` 取响应体里的 `request_date`
     * （与真实路径同一解析器 `CustomerInfoFactory`；真实路径头里有服务端时间时优先用头，这里没有头）。
     *
     * @throws org.json.JSONException 响应体缺契约必填字段（`subscriber` / `original_app_user_id` 等），
     *   与真实解析路径同样失败。
     */
    @JvmStatic
    public fun customerInfoFromJson(body: String): CustomerInfo =
        CustomerInfoFactory.buildCustomerInfo(JSONObject(body), overrideRequestDate = null, loadedFromCache = false)

    /**
     * `GET /v1/subscribers/{id}/offerings` 的响应体 + 「Play 查回来的商品」→ [Offerings]。
     * 匹配规则与真实路径同一个 `OfferingParser.createOfferings`（按 `productId:basePlanId` 精确匹配；
     * 匹配不上的 package 保留、`product` 为 `null`、进 `notFoundProductIds`）。
     */
    @JvmStatic
    public fun offeringsFromJson(body: String, products: List<StoreProduct>): Offerings =
        OfferingParser.createOfferings(
            offeringsJson = JSONObject(body),
            productsById = products.groupBy { it.productId },
            notFoundProductIds = emptyList(),
        )

    /**
     * 按字段构造 [StoreProduct]。
     *
     * `defaultOption` 由既有规则推导（`SubscriptionOptions.defaultOffer`，与 `Conversions.toStoreProduct`
     * 同一处）；[subscriptionOptions] 为 `null` = 一次性商品。
     *
     * @param period base plan 计费周期的 ISO 8601 原文（例如 `"P1M"`）；一次性商品传 `null`。
     */
    @Suppress("LongParameterList")
    @JvmStatic
    public fun storeProduct(
        productId: String,
        basePlanId: String?,
        type: ProductType,
        priceAmountMicros: Long,
        priceCurrencyCode: String,
        formattedPrice: String,
        name: String,
        title: String,
        description: String,
        period: String?,
        subscriptionOptions: List<SubscriptionOption>?,
    ): StoreProduct {
        val options = subscriptionOptions?.let { SubscriptionOptions(it) }
        return StoreProduct(
            productId = productId,
            basePlanId = basePlanId,
            type = type,
            price = Price(formattedPrice, priceAmountMicros, priceCurrencyCode),
            name = name,
            title = title,
            description = description,
            period = period?.let { Period.create(it) },
            subscriptionOptions = options,
            defaultOption = options?.defaultOffer,
        )
    }

    /**
     * 按字段构造 [SubscriptionOption]（字段 = 它的公开属性）。`offerToken` 测试里传假串即可 ——
     * 它只在 `launchBillingFlow` 时原样交给 Play。
     */
    @Suppress("LongParameterList")
    @JvmStatic
    public fun subscriptionOption(
        productId: String,
        basePlanId: String,
        offerId: String?,
        pricingPhases: List<PricingPhase>,
        tags: List<String>,
        offerToken: String,
    ): SubscriptionOption = SubscriptionOption(
        productId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        pricingPhases = pricingPhases,
        tags = tags,
        offerToken = offerToken,
    )

    /**
     * 按字段构造 [PricingPhase]。价格与周期按原始值传（与 [storeProduct] 同形），
     * 由这里组装成 [Price] / [Period]。
     *
     * @param billingPeriod ISO 8601 原文（例如 `"P1W"`）。
     * @param billingCycleCount `INFINITE_RECURRING` / `NON_RECURRING` 下传 `null`。
     */
    @Suppress("LongParameterList")
    @JvmStatic
    public fun pricingPhase(
        billingPeriod: String,
        recurrenceMode: RecurrenceMode,
        billingCycleCount: Int?,
        priceAmountMicros: Long,
        priceCurrencyCode: String,
        formattedPrice: String,
    ): PricingPhase = PricingPhase(
        billingPeriod = Period.create(billingPeriod),
        recurrenceMode = recurrenceMode,
        billingCycleCount = billingCycleCount,
        price = Price(formattedPrice, priceAmountMicros, priceCurrencyCode),
    )

    /** 按字段构造 [StoreTransaction]（字段 = 它的公开属性；没有原始 `Purchase`）。 */
    @Suppress("LongParameterList")
    @JvmStatic
    public fun storeTransaction(
        orderId: String?,
        productIds: List<String>,
        type: ProductType,
        purchaseTime: Long,
        purchaseToken: String,
        purchaseState: PurchaseState,
        isAutoRenewing: Boolean?,
        isAcknowledged: Boolean,
        presentedOfferingIdentifier: String?,
        subscriptionOptionId: String?,
        replacementMode: ReplacementMode?,
    ): StoreTransaction = StoreTransaction(
        orderId = orderId,
        productIds = productIds,
        type = type,
        purchaseTime = purchaseTime,
        purchaseToken = purchaseToken,
        purchaseState = purchaseState,
        isAutoRenewing = isAutoRenewing,
        isAcknowledged = isAcknowledged,
        presentedOfferingIdentifier = presentedOfferingIdentifier,
        subscriptionOptionId = subscriptionOptionId,
        replacementMode = replacementMode,
    )
}
