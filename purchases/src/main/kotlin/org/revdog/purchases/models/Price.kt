package org.revdog.purchases.models

import dev.drewhamilton.poko.Poko
import kotlin.math.roundToInt

/**
 * 价格。结构对照 RC `models/Price.kt`。
 *
 * **金额一律 `amountMicros`（Long）**：Play 原生就给 micros，没有精度问题。
 * 上行也发 micros（设计 §5 的「偏离 RC 的 Double」—— RC 发 `amountMicros / 1e6` 的 Double，
 * 那是把无损的整数硬转成有损的浮点）。
 */
@Poko
public class Price(
    /** 本地化价格串，例如 `"$4.99"`。展示用，**不要拿它算钱**。 */
    public val formatted: String,
    public val amountMicros: Long,
    public val currencyCode: String,
)

/**
 * 计费周期。结构对照 RC `models/Period.kt`（含它的 ISO 8601 解析正则与换算表）。
 *
 * **偏离 RC**：RC 的 `Period.Unit` 是 public enum（挂在它自己的 detekt baseline 里）。
 * 我方守 `ForbiddenPublicEnum`，改成 `@Poko class` + companion 常量。
 */
@Poko
public class Period(
    public val value: Int,
    public val unit: Unit,
    /** ISO 8601 原文，例如 `P1M`。上行 `pricing_phases[].billingPeriod` 发的就是它。 */
    public val iso8601: String,
) {

    @Poko
    public class Unit private constructor(public val rawValue: String) {

        override fun toString(): String = rawValue

        public companion object {
            @JvmField public val DAY: Unit = Unit("day")
            @JvmField public val WEEK: Unit = Unit("week")
            @JvmField public val MONTH: Unit = Unit("month")
            @JvmField public val YEAR: Unit = Unit("year")

            /** Play 给了我们还不认识的单位。 */
            @JvmField public val UNKNOWN: Unit = Unit("unknown")

            @JvmField public val ALL: List<Unit> = listOf(DAY, WEEK, MONTH, YEAR, UNKNOWN)
        }
    }

    public companion object {
        /**
         * 从 ISO 8601 串构造。SDK 内部会替你构造，公开出来只为了测试与文案计算。
         * 解析不了返回 `value = 0, unit = UNKNOWN`（**不抛**）—— 一个没见过的周期
         * 不该让整个 offerings 挂掉。
         */
        @JvmStatic
        public fun create(iso8601: String): Period {
            val (value, unit) = parse(iso8601)
            return Period(value, unit, iso8601)
        }

        // 周期换算用的近似表（RC 同款：周=7 天、月=30 天、年=365 天）。
        private const val DAYS_PER_WEEK = 7.0
        private const val DAYS_PER_MONTH = 30.0
        private const val DAYS_PER_YEAR = 365.0
        private const val MONTHS_PER_YEAR = 12.0
        private const val WEEKS_PER_YEAR = DAYS_PER_YEAR / DAYS_PER_WEEK
        private const val WEEKS_PER_MONTH = DAYS_PER_YEAR / MONTHS_PER_YEAR / DAYS_PER_WEEK

        // `java.time.Duration.parse` 要 API 26，minSdk 24 用不了 —— 与 RC 同样的理由手写正则。
        private val ISO_8601_REGEX =
            "^P(?!\$)(\\d+(?:\\.\\d+)?Y)?(\\d+(?:\\.\\d+)?M)?(\\d+(?:\\.\\d+)?W)?(\\d+(?:\\.\\d+)?D)?\$".toRegex()

        // 四段解构（年/月/周/日）是 ISO 8601 正则的形状，不是可以拆小的东西。
        @Suppress("ReturnCount", "DestructuringDeclarationWithTooManyEntries")
        private fun parse(iso8601: String): Pair<Int, Unit> {
            val match = ISO_8601_REGEX.matchEntire(iso8601) ?: return 0 to Unit.UNKNOWN
            val (year, month, week, day) = match.destructured
            val toInt = { part: String -> part.dropLast(1).toIntOrNull() ?: 0 }
            val yearInt = toInt(year)
            val monthInt = toInt(month)
            val weekInt = toInt(week)
            val dayInt = toInt(day)

            // 取**最小**的非零单位，把更大的单位折算进来（RC 同款）。
            val smallestUnit = when {
                dayInt > 0 -> Unit.DAY
                weekInt > 0 -> Unit.WEEK
                monthInt > 0 -> Unit.MONTH
                yearInt > 0 -> Unit.YEAR
                else -> return 0 to Unit.UNKNOWN
            }
            val value = when (smallestUnit) {
                Unit.YEAR -> yearInt.toDouble()
                Unit.MONTH -> yearInt * MONTHS_PER_YEAR + monthInt
                Unit.WEEK -> yearInt * WEEKS_PER_YEAR + monthInt * WEEKS_PER_MONTH + weekInt
                else -> yearInt * DAYS_PER_YEAR + monthInt * DAYS_PER_MONTH + weekInt * DAYS_PER_WEEK + dayInt
            }
            return value.roundToInt() to smallestUnit
        }

        internal fun daysIn(unit: Unit): Int = when (unit) {
            Unit.DAY -> 1
            Unit.WEEK -> DAYS_PER_WEEK.toInt()
            Unit.MONTH -> DAYS_PER_MONTH.toInt()
            Unit.YEAR -> DAYS_PER_YEAR.toInt()
            else -> 0
        }
    }
}

/**
 * 计费阶段的重复模式。直接沿用 Google 的 `ProductDetails.RecurrenceMode` 整数（1/2/3）。
 * 上行 `pricing_phases[].recurrenceMode` 发的就是 [identifier]。
 */
@Poko
public class RecurrenceMode private constructor(
    public val identifier: Int?,
    public val name: String,
) {

    override fun toString(): String = name

    public companion object {
        /** 无限续订到取消为止。 */
        @JvmField public val INFINITE_RECURRING: RecurrenceMode = RecurrenceMode(1, "INFINITE_RECURRING")

        /** 固定周期数后结束。 */
        @JvmField public val FINITE_RECURRING: RecurrenceMode = RecurrenceMode(2, "FINITE_RECURRING")

        /** 不重复。 */
        @JvmField public val NON_RECURRING: RecurrenceMode = RecurrenceMode(3, "NON_RECURRING")

        @JvmField public val UNKNOWN: RecurrenceMode = RecurrenceMode(null, "UNKNOWN")

        @JvmField
        public val ALL: List<RecurrenceMode> = listOf(INFINITE_RECURRING, FINITE_RECURRING, NON_RECURRING, UNKNOWN)

        @JvmStatic
        public fun fromIdentifier(identifier: Int?): RecurrenceMode =
            ALL.firstOrNull { it.identifier != null && it.identifier == identifier } ?: UNKNOWN
    }
}

/**
 * 一个计费阶段。结构对照 RC `models/PricingPhase.kt`。
 *
 * Android 表达「免费试用 / 折扣期」的**唯一方式**就是 pricing phases 的序列
 * （考古 §2.4：没有 iOS 的 `payment_mode` / `introductory_price` 等任何一个字段）。
 */
@Poko
public class PricingPhase(
    public val billingPeriod: Period,
    public val recurrenceMode: RecurrenceMode,
    /** 该阶段重复几个周期。`INFINITE_RECURRING` / `NON_RECURRING` 下为 `null`。 */
    public val billingCycleCount: Int?,
    public val price: Price,
)
