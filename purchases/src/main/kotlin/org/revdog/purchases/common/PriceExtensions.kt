package org.revdog.purchases.common

import org.revdog.purchases.InternalRevenueDogAPI
import org.revdog.purchases.Logger
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * 「按周期折算价格」的算子。结构对照 RC 10.22.1 的三个文件
 * （`utils/PriceExtensions.kt` + `utils/PriceFactory.kt` + `utils/DoubleExtensions.kt`），
 * 在我方合成这一个（我方没有 `utils` 包，同类工具都在 `common/`）。
 *
 * **口径逐字沿用 RC**，改一个字都会让折算出来的价格与 RC 对不上：
 *
 * 1. 换算走 **period 常量**，不是日历：周 = 7 天、月 = 30 天、年 = 365 天
 *    （见 `Period` 的 `DAYS_PER_*`）。所以 1 年 = 365 / 7 ≈ **52.142857 周**，
 *    **不是** 52 周;1 月 = 365 / 12 / 7 ≈ **4.345238 周**，**不是** 4 周。
 * 2. 折算 = `amountMicros / 目标周期数`，结果 `toLong()` —— **向零截断**，不是四舍五入。
 * 3. `formatted` 再按币种的 `defaultFractionDigits` 做一次 **FLOOR**（只向下，绝不向上：
 *    展示价宁可小一分，也不能把用户会被扣的钱说少了之外的方向说错）。
 * 4. 返回的 [Price] 里 `amountMicros` 是**第 2 步的未舍入值**（不是展示值反推的），
 *    `currencyCode` 原样带过来。
 *
 * **偏离 RC（两处，都是 fail-loud 方向）**：
 * - 周期单位 `UNKNOWN`（ISO 8601 解析不了）时 RC 把周期数取 0.0，接着 `amountMicros / 0.0`
 *   = `Infinity`，`toLong()` = `Long.MAX_VALUE` —— 屏幕上会出现一个天文数字的「周价」。
 *   我方在这里**返回 null**（= 无法折算），由调用方回退到总价。
 * - 币种代码不是合法 ISO 4217 时 RC 让 `Currency.getInstance` 抛 `IllegalArgumentException`
 *   （会把宿主的付费墙炸掉）。我方记一条 error 日志并**返回 null**。
 */

/** 1 单位货币 = 多少 micros。对照 RC `SharedConstants.MICRO_MULTIPLIER`。 */
private const val MICRO_MULTIPLIER = 1_000_000.0

@OptIn(InternalRevenueDogAPI::class)
internal fun Price.pricePerDay(billingPeriod: Period, locale: Locale): Price? =
    pricePerPeriod(billingPeriod.valueInDays, locale)

@OptIn(InternalRevenueDogAPI::class)
internal fun Price.pricePerWeek(billingPeriod: Period, locale: Locale): Price? =
    pricePerPeriod(billingPeriod.valueInWeeks, locale)

internal fun Price.pricePerMonth(billingPeriod: Period, locale: Locale): Price? =
    pricePerPeriod(billingPeriod.valueInMonths, locale)

@OptIn(InternalRevenueDogAPI::class)
internal fun Price.pricePerYear(billingPeriod: Period, locale: Locale): Price? =
    pricePerPeriod(billingPeriod.valueInYears, locale)

private fun Price.pricePerPeriod(units: Double, locale: Locale): Price? {
    if (units <= 0.0) {
        // 周期单位 UNKNOWN（或周期值为 0）。除下去只会得到 Infinity，不如直说折算不了。
        Logger.error { "折算价格失败：计费周期不可用（无法换算成目标周期数）。" }
        return null
    }
    val value = amountMicros / units
    return createPrice(value.toLong(), currencyCode, locale)
}

/** 对照 RC `utils/PriceFactory.createPrice`。 */
private fun createPrice(amountMicros: Long, currencyCode: String, locale: Locale): Price? {
    val currency = try {
        Currency.getInstance(currencyCode)
    } catch (e: IllegalArgumentException) {
        Logger.error(e) { "折算价格失败：不认识的币种代码 $currencyCode。" }
        return null
    }
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)

    val valueInCurrency = amountMicros / MICRO_MULTIPLIER
    val truncatedValue = valueInCurrency.roundToDecimalPlaces(digits)

    val numberFormat = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        maximumFractionDigits = digits
        minimumFractionDigits = digits
    }

    return Price(numberFormat.format(truncatedValue), amountMicros, currencyCode)
}

/**
 * 截断（FLOOR）到指定小数位。对照 RC `utils/DoubleExtensions.roundToDecimalPlaces`。
 * **只向下**：展示价不许比真实折算值大。
 */
private fun Double.roundToDecimalPlaces(decimals: Int): Double =
    BigDecimal.valueOf(this).setScale(decimals, RoundingMode.FLOOR).toDouble()
