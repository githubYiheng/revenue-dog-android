package org.revdog.purchases.posting

import org.json.JSONArray
import org.json.JSONObject
import org.revdog.purchases.ProductType
import org.revdog.purchases.ReplacementMode
import org.revdog.purchases.common.keysSequence
import org.revdog.purchases.common.objects
import org.revdog.purchases.common.optNullableInt
import org.revdog.purchases.common.optNullableString
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.models.SubscriptionOption

/**
 * `initiation_source` 取值（契约 §2.1 + `google-play-plan.md` §5）。
 *
 * ⚠️ 与 iOS 的差异：iOS 的第三个值是 `queue`，Android 是 [UNSYNCED_ACTIVE_PURCHASES]
 * （考古 §2.6）。两者在 ADR 0046 ② 下同义 —— **非 purchase / restore 一律按后台处理，不触发转移**。
 */
internal object InitiationSource {
    const val PURCHASE: String = "purchase"
    const val RESTORE: String = "restore"
    const val UNSYNCED_ACTIVE_PURCHASES: String = "unsynced_active_purchases"
}

/**
 * `platform_product_ids[]` 的一项。结构对照 RC `common/ReceiptInfo.kt` 的 `GooglePlatformProductId`。
 *
 * Google 的「商品」是 `productId` + `basePlanId`（+ `offerId`）的组合，不是单个 id（考古 §2.3）。
 * restore / 应用外购买匹配不到 `SubscriptionOption` 时退化为只有 `product_id`。
 */
internal class PlatformProductId(
    val productId: String,
    val basePlanId: String? = null,
    val offerId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PRODUCT_ID, productId)
        basePlanId?.let { put(KEY_BASE_PLAN_ID, it) }
        offerId?.let { put(KEY_OFFER_ID, it) }
    }

    companion object {
        const val KEY_PRODUCT_ID: String = "product_id"
        const val KEY_BASE_PLAN_ID: String = "base_plan_id"
        const val KEY_OFFER_ID: String = "offer_id"

        fun fromJson(json: JSONObject): PlatformProductId = PlatformProductId(
            productId = json.optString(KEY_PRODUCT_ID),
            basePlanId = json.optNullableString(KEY_BASE_PLAN_ID),
            offerId = json.optNullableString(KEY_OFFER_ID),
        )
    }
}

/**
 * 一次 `POST /v1/receipts` 的商品侧上下文。结构对照 RC `common/ReceiptInfo.kt`。
 *
 * **整个对象会被落盘**（`PendingPurchaseStore`，考古 §5.4）：进程被杀之后补报时，
 * 归因（offering / 价格 / offer 阶段 / 升降级模式）必须还是**购买当时**那一份，
 * 而不是补报那一刻现查出来的。
 *
 * 偏离 RC 的两处（设计 §5）：
 * - 价格发 [priceAmountMicros]（Long）而不是 RC 的 `price: Double` —— Play 原生就是 micros，
 *   转成浮点是白白引入精度问题；
 * - `store_user_id` / `marketplace` 两个 Amazon 字段整个不要。
 */
@Suppress("LongParameterList")
internal class ReceiptInfo(
    val productIds: List<String>,
    val platformProductIds: List<PlatformProductId>,
    val purchaseTime: Long? = null,
    val presentedOfferingIdentifier: String? = null,
    /** Targeting / placement 是 P1，本期恒为 `null`，只留字段形状。 */
    val presentedPlacementIdentifier: String? = null,
    val priceAmountMicros: Long? = null,
    val currency: String? = null,
    val formattedPrice: String? = null,
    /** `normal_duration`：base plan 的 ISO 8601 周期。 */
    val durationIso: String? = null,
    val pricingPhases: List<PricingPhase>? = null,
    val replacementMode: ReplacementMode? = null,
    val sdkOriginated: Boolean = false,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PRODUCT_IDS, JSONArray(productIds))
        put(KEY_PLATFORM_PRODUCT_IDS, JSONArray(platformProductIds.map { it.toJson() }))
        purchaseTime?.let { put(KEY_PURCHASE_TIME, it) }
        presentedOfferingIdentifier?.let { put(KEY_PRESENTED_OFFERING, it) }
        presentedPlacementIdentifier?.let { put(KEY_PRESENTED_PLACEMENT, it) }
        priceAmountMicros?.let { put(KEY_PRICE_AMOUNT_MICROS, it) }
        currency?.let { put(KEY_CURRENCY, it) }
        formattedPrice?.let { put(KEY_FORMATTED_PRICE, it) }
        durationIso?.let { put(KEY_DURATION, it) }
        pricingPhases?.let { phases -> put(KEY_PRICING_PHASES, JSONArray(phases.map { it.toWireJson() })) }
        replacementMode?.let { put(KEY_REPLACEMENT_MODE, it.wireName) }
        put(KEY_SDK_ORIGINATED, sdkOriginated)
    }

    companion object {
        const val KEY_PRODUCT_IDS: String = "product_ids"
        const val KEY_PLATFORM_PRODUCT_IDS: String = "platform_product_ids"
        const val KEY_PURCHASE_TIME: String = "purchase_time"
        const val KEY_PRESENTED_OFFERING: String = "presented_offering_identifier"
        const val KEY_PRESENTED_PLACEMENT: String = "presented_placement_identifier"
        const val KEY_PRICE_AMOUNT_MICROS: String = "price_amount_micros"
        const val KEY_CURRENCY: String = "currency"
        const val KEY_FORMATTED_PRICE: String = "price_string"
        const val KEY_DURATION: String = "normal_duration"
        const val KEY_PRICING_PHASES: String = "pricing_phases"
        const val KEY_REPLACEMENT_MODE: String = "proration_mode"
        const val KEY_SDK_ORIGINATED: String = "sdk_originated"

        /**
         * 从一笔交易 + 查回来的商品详情组装。结构对照 RC `ReceiptInfo.from`。
         *
         * `platform_product_ids` 与 `product_ids` **一一对应、顺序相同**，
         * 且 base 商品排第一 —— 顺序直接沿用 Play 返回的 `productIds` 顺序（考古 §2.3）。
         */
        fun from(
            transaction: StoreTransaction,
            storeProduct: StoreProduct?,
            subscriptionOptionsForProductIds: Map<String, SubscriptionOption>?,
            sdkOriginated: Boolean,
        ): ReceiptInfo {
            val purchasedOption = storeProduct?.subscriptionOptions
                ?.firstOrNull { it.id == transaction.subscriptionOptionId }
            val productPlatformId = purchasedOption?.toPlatformProductId()
                ?: storeProduct?.toPlatformProductId()

            val platformProductIds = transaction.productIds.map { productId ->
                when {
                    productId == productPlatformId?.productId -> productPlatformId
                    else -> subscriptionOptionsForProductIds?.get(productId)?.toPlatformProductId()
                        ?: PlatformProductId(productId)
                }
            }

            return ReceiptInfo(
                productIds = transaction.productIds,
                platformProductIds = platformProductIds,
                purchaseTime = transaction.purchaseTime,
                presentedOfferingIdentifier = transaction.presentedOfferingIdentifier,
                priceAmountMicros = storeProduct?.price?.amountMicros,
                currency = storeProduct?.price?.currencyCode,
                formattedPrice = storeProduct?.price?.formatted,
                durationIso = storeProduct?.period?.iso8601?.takeUnless { it.isEmpty() },
                pricingPhases = purchasedOption?.pricingPhases,
                replacementMode = transaction.replacementMode,
                sdkOriginated = sdkOriginated,
            )
        }

        fun fromJson(json: JSONObject): ReceiptInfo = ReceiptInfo(
            productIds = json.optJSONArray(KEY_PRODUCT_IDS).toStringList(),
            platformProductIds = json.optJSONArray(KEY_PLATFORM_PRODUCT_IDS)
                ?.objects().orEmpty().map { PlatformProductId.fromJson(it) },
            purchaseTime = json.optLong(KEY_PURCHASE_TIME).takeIf { it > 0L },
            presentedOfferingIdentifier = json.optNullableString(KEY_PRESENTED_OFFERING),
            presentedPlacementIdentifier = json.optNullableString(KEY_PRESENTED_PLACEMENT),
            priceAmountMicros = if (json.has(KEY_PRICE_AMOUNT_MICROS)) json.optLong(KEY_PRICE_AMOUNT_MICROS) else null,
            currency = json.optNullableString(KEY_CURRENCY),
            formattedPrice = json.optNullableString(KEY_FORMATTED_PRICE),
            durationIso = json.optNullableString(KEY_DURATION),
            pricingPhases = json.optJSONArray(KEY_PRICING_PHASES)?.objects()?.map { it.toPricingPhase() },
            replacementMode = ReplacementMode.fromWireName(json.optNullableString(KEY_REPLACEMENT_MODE)),
            sdkOriginated = json.optBoolean(KEY_SDK_ORIGINATED),
        )
    }
}

/** 一次性商品没有 base plan；订阅用商品自己的 base plan。 */
private fun StoreProduct.toPlatformProductId(): PlatformProductId =
    if (type == ProductType.SUBS) PlatformProductId(productId, basePlanId) else PlatformProductId(productId)

private fun SubscriptionOption.toPlatformProductId(): PlatformProductId =
    PlatformProductId(productId, basePlanId, offerId)

/**
 * `pricing_phases[]` 的编码。结构对照 RC `Backend.PricingPhase.toMap()`。
 *
 * ⚠️ **key 是 camelCase** —— 整个 body 里唯一的 camelCase 块（考古 §2.4）。
 * 这是 RC 的既成契约，后端按它解析；改成 snake_case 只会在迁移期多一套分支。
 */
internal fun PricingPhase.toWireJson(): JSONObject = JSONObject().apply {
    put("billingPeriod", billingPeriod.iso8601)
    billingCycleCount?.let { put("billingCycleCount", it) }
    recurrenceMode.identifier?.let { put("recurrenceMode", it) }
    put("formattedPrice", price.formatted)
    put("priceAmountMicros", price.amountMicros)
    put("priceCurrencyCode", price.currencyCode)
}

internal fun JSONObject.toPricingPhase(): PricingPhase = PricingPhase(
    billingPeriod = Period.create(optString("billingPeriod")),
    recurrenceMode = RecurrenceMode.fromIdentifier(optNullableInt("recurrenceMode")),
    billingCycleCount = optNullableInt("billingCycleCount"),
    price = Price(
        formatted = optString("formattedPrice"),
        amountMicros = optLong("priceAmountMicros"),
        currencyCode = optString("priceCurrencyCode"),
    ),
)

internal fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

internal fun JSONObject.stringMap(): Map<String, String> =
    keysSequence().mapNotNull { key -> optNullableString(key)?.let { key to it } }.toMap()
