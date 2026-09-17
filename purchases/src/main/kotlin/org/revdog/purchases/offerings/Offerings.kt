package org.revdog.purchases.offerings

import dev.drewhamilton.poko.Poko
import org.revdog.purchases.models.StoreProduct

/**
 * 包类型。后端下发的 package identifier 带 `$rc_` 前缀（契约 §2.3 ⟦决策11⟧ 尚未改名，
 * **保持与 RC 一致**，宿主从 RC 迁移时不用改代码）。
 */
@Poko
public class PackageType private constructor(public val identifier: String?, public val name: String) {

    override fun toString(): String = name

    public companion object {
        @JvmField public val UNKNOWN: PackageType = PackageType(null, "UNKNOWN")

        /** 自定义标识的 package。 */
        @JvmField public val CUSTOM: PackageType = PackageType(null, "CUSTOM")

        @JvmField public val LIFETIME: PackageType = PackageType("\$rc_lifetime", "LIFETIME")
        @JvmField public val ANNUAL: PackageType = PackageType("\$rc_annual", "ANNUAL")
        @JvmField public val SIX_MONTH: PackageType = PackageType("\$rc_six_month", "SIX_MONTH")
        @JvmField public val THREE_MONTH: PackageType = PackageType("\$rc_three_month", "THREE_MONTH")
        @JvmField public val TWO_MONTH: PackageType = PackageType("\$rc_two_month", "TWO_MONTH")
        @JvmField public val MONTHLY: PackageType = PackageType("\$rc_monthly", "MONTHLY")
        @JvmField public val WEEKLY: PackageType = PackageType("\$rc_weekly", "WEEKLY")

        @JvmField
        public val KNOWN: List<PackageType> =
            listOf(LIFETIME, ANNUAL, SIX_MONTH, THREE_MONTH, TWO_MONTH, MONTHLY, WEEKLY)

        /** 认不出来的一律 [CUSTOM]（RC 同款）。 */
        @JvmStatic
        public fun fromIdentifier(identifier: String): PackageType =
            KNOWN.firstOrNull { it.identifier == identifier } ?: CUSTOM
    }
}

/**
 * 一个可展示的购买项。结构对照 RC `Package.kt`。
 *
 * **偏离 RC**：[product] 可空。RC 在 `createPackage` 里把查不到商品的 package **整个丢掉**，
 * 于是「后端配了、Play 上没有」这件事对宿主完全不可见，只在日志里留一行。
 * 我方保留 package、把 `product` 置空，并把这些 productId 收进
 * [Offerings.notFoundProductIds] —— 宿主能看见「这个档位配了但商店没有」，
 * 排障不必去翻 logcat（设计 §1 的 `offerings_fetch.not_found_product_ids` 同源）。
 */
@Poko
public class Package internal constructor(
    public val identifier: String,
    public val packageType: PackageType,
    public val offeringIdentifier: String,
    /** 后端下发的 `platform_product_identifier`。 */
    public val platformProductIdentifier: String,
    /** 后端下发的 `platform_product_plan_identifier`（Google base plan id）。 */
    public val platformProductPlanIdentifier: String?,
    /** Play 查回来的商品详情。**查不到时为 `null`**。 */
    public val product: StoreProduct?,
)

/** 一组 package。结构对照 RC `Offering.kt`。 */
@Poko
public class Offering internal constructor(
    public val identifier: String,
    public val serverDescription: String,
    public val availablePackages: List<Package>,
) {
    public operator fun get(packageIdentifier: String): Package? =
        availablePackages.firstOrNull { it.identifier == packageIdentifier }

    public fun getPackage(packageType: PackageType): Package? =
        availablePackages.firstOrNull { it.packageType == packageType }

    public val lifetime: Package? get() = getPackage(PackageType.LIFETIME)
    public val annual: Package? get() = getPackage(PackageType.ANNUAL)
    public val monthly: Package? get() = getPackage(PackageType.MONTHLY)
    public val weekly: Package? get() = getPackage(PackageType.WEEKLY)
}

/** 结构对照 RC `Offerings.kt`。 */
@Poko
public class Offerings internal constructor(
    public val all: Map<String, Offering>,
    public val currentOfferingIdentifier: String?,
    /**
     * 后端配了、但 Play 上查不到的 productId。
     * 空列表 = 一切正常；非空 = **Play Console 与后端商品目录不一致**，需要人看。
     */
    public val notFoundProductIds: List<String>,
) {
    public val current: Offering? get() = currentOfferingIdentifier?.let { all[it] }

    public operator fun get(identifier: String): Offering? = all[identifier]
}
