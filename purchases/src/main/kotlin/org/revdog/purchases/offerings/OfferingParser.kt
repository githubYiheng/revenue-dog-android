package org.revdog.purchases.offerings

import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.common.objects
import org.revdog.purchases.common.optNullableString
import org.revdog.purchases.models.StoreProduct

/**
 * `GET /v1/subscribers/{id}/offerings` 响应 → [Offerings]。
 * 结构对照 RC `common/OfferingParser.kt` + `common/GoogleOfferingParser.kt`。
 */
internal object OfferingParser {

    private const val KEY_CURRENT_OFFERING_ID = "current_offering_id"
    private const val KEY_OFFERINGS = "offerings"
    private const val KEY_IDENTIFIER = "identifier"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_PLATFORM_PRODUCT_IDENTIFIER = "platform_product_identifier"
    private const val KEY_PLATFORM_PRODUCT_PLAN_IDENTIFIER = "platform_product_plan_identifier"

    /** 从响应里抽出需要向 Play 查询的 productId 集合。 */
    fun productIdsToQuery(offeringsJson: JSONObject): Set<String> =
        offeringsJson.optJSONArray(KEY_OFFERINGS).orEmptyObjects()
            .flatMap { it.optJSONArray(KEY_PACKAGES).orEmptyObjects() }
            .mapNotNull { it.optNullableString(KEY_PLATFORM_PRODUCT_IDENTIFIER) }
            .toSet()

    /**
     * @param productsById Play 查回来的商品，按 `productId` 分组
     *   （同一个 productId 可能有多个 base plan → 多个 [StoreProduct]）。
     * @param notFoundProductIds 查询阶段就确定查不到的 productId。
     */
    fun createOfferings(
        offeringsJson: JSONObject,
        productsById: Map<String, List<StoreProduct>>,
        notFoundProductIds: List<String>,
    ): Offerings {
        val currentOfferingID = offeringsJson.optNullableString(KEY_CURRENT_OFFERING_ID)
        val unmatched = mutableListOf<String>()

        val offerings = offeringsJson.optJSONArray(KEY_OFFERINGS).orEmptyObjects()
            .mapNotNull { createOffering(it, productsById, unmatched) }
            .associateBy { it.identifier }

        return Offerings(
            all = offerings,
            currentOfferingIdentifier = currentOfferingID,
            notFoundProductIds = (notFoundProductIds + unmatched).distinct(),
        )
    }

    private fun createOffering(
        offeringJson: JSONObject,
        productsById: Map<String, List<StoreProduct>>,
        unmatched: MutableList<String>,
    ): Offering? {
        val offeringIdentifier = offeringJson.optNullableString(KEY_IDENTIFIER) ?: return null
        val packages = offeringJson.optJSONArray(KEY_PACKAGES).orEmptyObjects()
            .mapNotNull { createPackage(it, offeringIdentifier, productsById, unmatched) }

        if (packages.isEmpty()) {
            Logger.warn { "offering $offeringIdentifier 没有任何 package" }
        }
        return Offering(
            identifier = offeringIdentifier,
            serverDescription = offeringJson.optString(KEY_DESCRIPTION),
            availablePackages = packages,
        )
    }

    @Suppress("ReturnCount")
    private fun createPackage(
        packageJson: JSONObject,
        offeringIdentifier: String,
        productsById: Map<String, List<StoreProduct>>,
        unmatched: MutableList<String>,
    ): Package? {
        val packageIdentifier = packageJson.optNullableString(KEY_IDENTIFIER) ?: return null
        val productIdentifier = packageJson.optNullableString(KEY_PLATFORM_PRODUCT_IDENTIFIER) ?: return null
        val planIdentifier = packageJson.optNullableString(KEY_PLATFORM_PRODUCT_PLAN_IDENTIFIER)

        val product = findMatchingProduct(productsById, productIdentifier, planIdentifier)
        if (product == null) {
            Logger.warn {
                "package $packageIdentifier 在 Play 上找不到对应商品（$productIdentifier" +
                    (planIdentifier?.let { ":$it" } ?: "") + "）"
            }
            unmatched += productIdentifier
        }

        return Package(
            identifier = packageIdentifier,
            packageType = PackageType.fromIdentifier(packageIdentifier),
            offeringIdentifier = offeringIdentifier,
            platformProductIdentifier = productIdentifier,
            platformProductPlanIdentifier = planIdentifier,
            product = product,
        )
    }

    /**
     * 匹配规则（结构对照 RC `GoogleOfferingParser.findMatchingProduct`）：
     *
     * - **有 `platform_product_plan_identifier`** → 订阅：按 `productId:basePlanId` 精确匹配。
     *   Google 的「商品」是二元组，光有 productId 不足以定位一个可购买项（考古 §2.3）。
     * - **没有** → 可能是一次性商品，也可能是配漏了的订阅。只有当这个 productId 恰好
     *   只对应一个 `INAPP` 商品时才认；否则宁可不匹配，也不猜。
     */
    @Suppress("ReturnCount")
    private fun findMatchingProduct(
        productsById: Map<String, List<StoreProduct>>,
        productIdentifier: String,
        planIdentifier: String?,
    ): StoreProduct? {
        val candidates = productsById[productIdentifier] ?: return null
        if (planIdentifier == null) {
            return candidates.singleOrNull()?.takeIf { it.type == org.revdog.purchases.ProductType.INAPP }
        }
        val composedId = "$productIdentifier${StoreProduct.ID_SEPARATOR}$planIdentifier"
        return candidates.firstOrNull { it.id == composedId }
    }

    private fun org.json.JSONArray?.orEmptyObjects(): List<JSONObject> = this?.objects() ?: emptyList()
}
