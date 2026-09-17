package org.revdog.purchases

import dev.drewhamilton.poko.Poko

// 本文件全是「可扩展的对外枚举」。一律 `@Poko class` + companion 的 `@JvmField val` 常量
// （设计 §1「禁 public enum」，detekt 规则 ForbiddenPublicEnum 强制）：
// 后端 / 商店随时可能加值，宿主里穷尽的 `when` 不能因此编译不过。
// 解析一律 **大小写不敏感 + 未知落 UNKNOWN**（契约 §1.8、坑 42）。

/** 商店。REST 平面小写（契约 §1.8）。与 iOS `Store` 逐项对齐。 */
@Poko
public class Store private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        @JvmField public val APP_STORE: Store = Store("app_store")
        @JvmField public val MAC_APP_STORE: Store = Store("mac_app_store")
        @JvmField public val PLAY_STORE: Store = Store("play_store")
        @JvmField public val AMAZON: Store = Store("amazon")
        @JvmField public val STRIPE: Store = Store("stripe")
        @JvmField public val PROMOTIONAL: Store = Store("promotional")
        @JvmField public val ROKU: Store = Store("roku")
        @JvmField public val PADDLE: Store = Store("paddle")
        @JvmField public val UNKNOWN: Store = Store("unknown")

        @JvmField
        public val ALL: List<Store> =
            listOf(APP_STORE, MAC_APP_STORE, PLAY_STORE, AMAZON, STRIPE, PROMOTIONAL, ROKU, PADDLE, UNKNOWN)

        @JvmStatic
        public fun fromString(value: String?): Store =
            ALL.firstOrNull { it.rawValue == value?.lowercase() } ?: UNKNOWN
    }
}

/** 周期类型。REST 平面小写（契约 §1.8）。`PREPAID` 是 Google 独有（考古 §5.3）。 */
@Poko
public class PeriodType private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        @JvmField public val NORMAL: PeriodType = PeriodType("normal")
        @JvmField public val TRIAL: PeriodType = PeriodType("trial")
        @JvmField public val INTRO: PeriodType = PeriodType("intro")
        @JvmField public val PREPAID: PeriodType = PeriodType("prepaid")

        @JvmField public val ALL: List<PeriodType> = listOf(NORMAL, TRIAL, INTRO, PREPAID)

        /** 未知值落 [NORMAL]（对照 RC `optPeriodType`：`else -> PeriodType.NORMAL`）。 */
        @JvmStatic
        public fun fromString(value: String?): PeriodType =
            ALL.firstOrNull { it.rawValue == value?.lowercase() } ?: NORMAL
    }
}

/** 所有权。契约 §1.8：**大写** `PURCHASED` / `FAMILY_SHARED`。 */
@Poko
public class OwnershipType private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        @JvmField public val PURCHASED: OwnershipType = OwnershipType("PURCHASED")
        @JvmField public val FAMILY_SHARED: OwnershipType = OwnershipType("FAMILY_SHARED")
        @JvmField public val UNKNOWN: OwnershipType = OwnershipType("UNKNOWN")

        @JvmField public val ALL: List<OwnershipType> = listOf(PURCHASED, FAMILY_SHARED, UNKNOWN)

        @JvmStatic
        public fun fromString(value: String?): OwnershipType =
            ALL.firstOrNull { it.rawValue == value?.uppercase() } ?: UNKNOWN
    }
}

/**
 * 商品类型。诊断上报时翻译成 iOS 口径（考古 §7.2：`SUBS → AUTO_RENEWABLE_SUBSCRIPTION`、
 * `INAPP → NON_SUBSCRIPTION`），后端只认一套枚举。
 */
@Poko
public class ProductType private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        @JvmField public val SUBS: ProductType = ProductType("subs")
        @JvmField public val INAPP: ProductType = ProductType("inapp")
        @JvmField public val UNKNOWN: ProductType = ProductType("unknown")

        @JvmField public val ALL: List<ProductType> = listOf(SUBS, INAPP, UNKNOWN)

        @JvmStatic
        public fun fromString(value: String?): ProductType =
            ALL.firstOrNull { it.rawValue == value?.lowercase() } ?: UNKNOWN
    }
}

/**
 * 缓存读取策略。四态与 iOS `FetchPolicy`、RC `CacheFetchPolicy` **逐字对应**（考古 §5.3）。
 */
@Poko
public class CacheFetchPolicy private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        /** 只读缓存；没有就报 `customerInfoError`，不发请求。 */
        @JvmField public val CACHE_ONLY: CacheFetchPolicy = CacheFetchPolicy("cache_only")

        /** 忽略缓存，一定联网拿最新；失败即报错。 */
        @JvmField public val FETCH_CURRENT: CacheFetchPolicy = CacheFetchPolicy("fetch_current")

        /** 缓存未过期就用缓存；过期或没有就联网。**不会**返回过期数据。 */
        @JvmField public val NOT_STALE_CACHED_OR_CURRENT: CacheFetchPolicy =
            CacheFetchPolicy("not_stale_cached_or_current")

        /** （默认）有缓存就用（即使过期）；没有才联网；过期时后台顺带刷一次。 */
        @JvmField public val CACHED_OR_FETCHED: CacheFetchPolicy = CacheFetchPolicy("cached_or_fetched")

        /**
         * 默认策略。
         *
         * Java 侧叫 `getDefault()` —— `default` 是 Java 关键字，方法名叫它编译不过。
         */
        @JvmStatic
        @JvmName("getDefault")
        public fun default(): CacheFetchPolicy = CACHED_OR_FETCHED
    }
}

/**
 * 谁负责完成交易（acknowledge / consume）。对照 RC `PurchasesAreCompletedBy`。
 *
 * [MY_APP] = RC 的 observer 模式：SDK **绝不** ack / consume，只记台账避免重复上报（考古 §3.6 ④⑥）。
 */
@Poko
public class PurchasesAreCompletedBy private constructor(public val rawValue: String) {

    override fun toString(): String = rawValue

    public companion object {
        @JvmField public val REVENUE_DOG: PurchasesAreCompletedBy = PurchasesAreCompletedBy("revenue_dog")
        @JvmField public val MY_APP: PurchasesAreCompletedBy = PurchasesAreCompletedBy("my_app")
    }
}
