package org.revdog.purchases

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.ProductDetails
import dev.drewhamilton.poko.Poko
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.offerings.Package

/**
 * 升降级模式。结构对照 RC `ReplacementMode.kt` + `StoreReplacementModeConversions.kt`，
 * 但**只保留两套命名**而不是 RC 的三套（考古 §2.5）：
 *
 * - [name] = 公开名 = [wireName] = 上行 `proration_mode`；RC 的公开名是 `WITHOUT_PRORATION`
 *   但 wire 上发的是 legacy Play 名 `IMMEDIATE_WITHOUT_PRORATION`；
 * - [playBillingMode] = Billing 常量，与 RC 同。
 *
 * **偏离 RC**（设计 §5、ADR 0069 决策 4）：wire 上发干净枚举名。RC 的 `IMMEDIATE_*` 是
 * 2020 年 Play 常量的历史包袱，我方后端是新的，没有理由继承它。
 *
 * PBL 9 新增的 `KEEP_EXISTING` **本期不开**：它只对多行订阅 / add-ons 有意义，
 * 而 v1 明确不支持多行订阅（坑 19、`google-play-plan.md` §5），开了就等于凭空发明一个
 * 服务端不认识的 `proration_mode`。M3 随多行订阅一起评估。
 */
@Poko
public class ReplacementMode private constructor(
    public val name: String,
    /** 上行 `proration_mode` 的值。 */
    public val wireName: String,
    internal val playBillingMode: Int,
) {

    override fun toString(): String = name

    public companion object {
        /** 立即换、不补差价、不改续订日。 */
        @JvmField
        public val WITHOUT_PRORATION: ReplacementMode = ReplacementMode(
            "WITHOUT_PRORATION",
            "WITHOUT_PRORATION",
            SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION,
        )

        /** 立即换，把已付未用的时间折算成新套餐的时长。Play 的默认语义。 */
        @JvmField
        public val WITH_TIME_PRORATION: ReplacementMode = ReplacementMode(
            "WITH_TIME_PRORATION",
            "WITH_TIME_PRORATION",
            SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION,
        )

        /** 立即换并立刻按新套餐全价收一期。 */
        @JvmField
        public val CHARGE_FULL_PRICE: ReplacementMode = ReplacementMode(
            "CHARGE_FULL_PRICE",
            "CHARGE_FULL_PRICE",
            SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE,
        )

        /** 立即换并按差价收费，续订日不变。 */
        @JvmField
        public val CHARGE_PRORATED_PRICE: ReplacementMode = ReplacementMode(
            "CHARGE_PRORATED_PRICE",
            "CHARGE_PRORATED_PRICE",
            SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE,
        )

        /**
         * 到期才换。
         *
         * **回调挂在旧商品上**（坑 16）：Play 在 DEFERRED 下返回的交易是**旧商品**的，
         * 所以 `PendingPurchaseStore` 的 key 用旧 productId 且剥掉 `:basePlanId`。
         * RC 把这件事写了三遍，我方收敛到 [PendingPurchaseKey.normalize] 一个函数。
         */
        @JvmField
        public val DEFERRED: ReplacementMode = ReplacementMode(
            "DEFERRED",
            "DEFERRED",
            SubscriptionProductReplacementParams.ReplacementMode.DEFERRED,
        )

        @JvmField
        public val ALL: List<ReplacementMode> = listOf(
            WITHOUT_PRORATION, WITH_TIME_PRORATION, CHARGE_FULL_PRICE, CHARGE_PRORATED_PRICE, DEFERRED,
        )

        @JvmStatic
        public fun fromWireName(value: String?): ReplacementMode? =
            ALL.firstOrNull { it.wireName == value }
    }
}

/**
 * 归一化购买键。**坑 16 的唯一实现点**（决策 D）。
 *
 * Play 返回的交易里 `products` 只有裸 productId（不含 `:basePlanId`），而宿主传进来的
 * `oldProductId` 常常带着 base plan 后缀。两边不归一化，DEFERRED 升降级的回调就永远对不上。
 * Play 的 productId 字符集不含 `:`，所以无条件剥离是安全的。
 */
internal object PendingPurchaseKey {
    const val BASE_PLAN_SEPARATOR: String = ":"

    fun normalize(productId: String): String = productId.substringBefore(BASE_PLAN_SEPARATOR)
}

/**
 * 一次 `launchBillingFlow` 需要的全部商品信息（内部）。结构对照 RC `GooglePurchasingData`。
 */
@Suppress("LongParameterList") // 值类型：7 个字段就是它的全部内容，拆开只会更难读
internal class PurchasingData(
    val productId: String,
    val productType: ProductType,
    val productDetails: ProductDetails,
    /** 订阅必需；一次性商品为 `null`。 */
    val offerToken: String?,
    /** `basePlanId` 或 `basePlanId:offerId`。一次性商品为 `null`。 */
    val subscriptionOptionId: String?,
    val storeProduct: StoreProduct?,
    val subscriptionOption: SubscriptionOption?,
)

/**
 * 购买参数。结构对照 RC `PurchaseParams.kt`。
 *
 * 三个入口构造器（与 RC 同）：`Package` / `StoreProduct` / `SubscriptionOption`。
 * 前两个走商品的 [StoreProduct.defaultOption]，第三个让宿主精确指定要买哪个 offer。
 */
public class PurchaseParams internal constructor(builder: Builder) {

    internal val activity: Activity = builder.activity
    internal val purchasingData: PurchasingData? = builder.purchasingData
    internal val presentedOfferingIdentifier: String? = builder.presentedOfferingIdentifier
    internal val presentedPackageIdentifier: String? = builder.presentedPackageIdentifier

    /** 升降级：被替换的旧订阅 productId（带不带 `:basePlanId` 都行，内部会剥）。 */
    public val oldProductId: String? = builder.oldProductId

    /** 升降级模式。不传时按 Play 默认（[ReplacementMode.WITH_TIME_PRORATION] 语义）。 */
    public val replacementMode: ReplacementMode? = builder.replacementMode

    /** 欧盟个性化定价披露。不传 = 不声明。 */
    public val isPersonalizedPrice: Boolean? = builder.isPersonalizedPrice

    public class Builder private constructor(
        internal val activity: Activity,
        internal val purchasingData: PurchasingData?,
        internal val presentedOfferingIdentifier: String?,
        internal val presentedPackageIdentifier: String?,
    ) {

        /** 从 offering 的 package 买。**推荐形态** —— 归因（offering / package）自动带上。 */
        public constructor(activity: Activity, packageToPurchase: Package) : this(
            activity,
            packageToPurchase.product?.toPurchasingData(),
            packageToPurchase.offeringIdentifier,
            packageToPurchase.identifier,
        )

        /** 直接买一个商品。订阅会用它的 [StoreProduct.defaultOption]。 */
        public constructor(activity: Activity, storeProduct: StoreProduct) : this(
            activity,
            storeProduct.toPurchasingData(),
            null,
            null,
        )

        /** 精确指定订阅的某个 base plan / offer。 */
        public constructor(activity: Activity, subscriptionOption: SubscriptionOption) : this(
            activity,
            subscriptionOption.toPurchasingData(),
            null,
            null,
        )

        internal var oldProductId: String? = null
            private set
        internal var replacementMode: ReplacementMode? = null
            private set
        internal var isPersonalizedPrice: Boolean? = null
            private set

        public fun oldProductId(oldProductId: String): Builder = apply { this.oldProductId = oldProductId }

        public fun replacementMode(replacementMode: ReplacementMode): Builder =
            apply { this.replacementMode = replacementMode }

        public fun isPersonalizedPrice(isPersonalizedPrice: Boolean): Builder =
            apply { this.isPersonalizedPrice = isPersonalizedPrice }

        public fun build(): PurchaseParams = PurchaseParams(this)
    }
}

/**
 * 订阅取 [StoreProduct.defaultOption]（挑选顺序见 `SubscriptionOptions.defaultOffer`），
 * 一次性商品直接用商品本身。`productDetails` 缺失（手搭的测试商品）时返回 `null`，
 * 由编排层回 `productNotAvailableForPurchaseError` —— **不抛**。
 */
internal fun StoreProduct.toPurchasingData(): PurchasingData? {
    if (type == ProductType.SUBS) return defaultOption?.toPurchasingData()
    return productDetails?.let { details ->
        PurchasingData(
            productId = productId,
            productType = type,
            productDetails = details,
            offerToken = null,
            subscriptionOptionId = null,
            storeProduct = this,
            subscriptionOption = null,
        )
    }
}

internal fun SubscriptionOption.toPurchasingData(): PurchasingData? {
    val details = productDetails ?: return null
    return PurchasingData(
        productId = productId,
        productType = ProductType.SUBS,
        productDetails = details,
        offerToken = offerToken,
        subscriptionOptionId = id,
        storeProduct = storeProduct,
        subscriptionOption = this,
    )
}
