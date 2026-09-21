package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.models.OfferPaymentMode
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.models.SubscriptionOptions
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * 「按周期折算价格」（0.1.1 新增）。
 *
 * **期望值全部搬自 RC purchases-android 10.22.1 的对应单测，逐字对照**，出处标在每个用例上：
 * - `purchases/src/test/java/com/revenuecat/purchases/utils/PriceExtensionsPricePerWeekTest.kt`
 * - `…/PriceExtensionsPricePerMonthTest.kt` · `…/PriceExtensionsPricePerYearTest.kt`
 * - `…/PriceExtensionsTest.kt`（多币种 / 非 en-US locale）
 * - `purchases/src/test/java/com/revenuecat/purchases/common/models/ParameterizedPricingPhaseTest.kt`
 *   （`amountMicros` 逐位对齐）
 *
 * 口径提醒：换算走 **period 常量**（1 年 = 365 / 7 ≈ 52.142857 周），不是日历周数；
 * 金额向零截断；`formatted` 按币种小数位 FLOOR。
 */
@RunWith(RobolectricTestRunner::class)
class PricePerPeriodTest {

    private val us: Locale = Locale.US

    // region 构造器

    private fun price(dollars: String, currencyCode: String = "USD"): Price {
        val micros = (dollars.toBigDecimal().movePointRight(MICRO_DIGITS)).toLong()
        return Price("$$dollars", micros, currencyCode)
    }

    private fun phase(
        iso8601: String,
        price: Price,
        recurrenceMode: RecurrenceMode = RecurrenceMode.INFINITE_RECURRING,
        billingCycleCount: Int? = null,
    ) = PricingPhase(Period.create(iso8601), recurrenceMode, billingCycleCount, price)

    /** 单一全价阶段的订阅商品：`StoreProduct.price` / `period` 就是 base plan 的价格与周期。 */
    private fun subscription(iso8601: String, price: Price): StoreProduct {
        val base = SubscriptionOption(
            productId = "sub_premium",
            basePlanId = "base",
            offerId = null,
            pricingPhases = listOf(phase(iso8601, price)),
            tags = emptyList(),
            offerToken = "token",
        )
        val options = SubscriptionOptions(listOf(base))
        return StoreProduct(
            productId = "sub_premium",
            basePlanId = "base",
            type = ProductType.SUBS,
            price = price,
            name = "premium",
            title = "premium (base)",
            description = "desc",
            period = Period.create(iso8601),
            subscriptionOptions = options,
            defaultOption = options.defaultOffer,
        )
    }

    private fun inApp(price: Price): StoreProduct = StoreProduct(
        productId = "coins_100",
        basePlanId = null,
        type = ProductType.INAPP,
        price = price,
        name = "coins",
        title = "coins",
        description = "desc",
        period = null,
        subscriptionOptions = null,
        defaultOption = null,
    )

    // endregion

    // region RC 金标准表（formatted）

    @Test
    fun `pricePerWeek 与 RC PriceExtensionsPricePerWeekTest 的期望值逐条一致`() {
        listOf(
            Triple("1", "P1D", "$7.00"),
            Triple("2", "P14D", "$1.00"),
            Triple("10", "P1W", "$10.00"),
            Triple("10", "P2W", "$5.00"),
            Triple("14.99", "P1M", "$3.44"),
            Triple("30", "P2M", "$3.45"),
            Triple("40", "P3M", "$3.06"),
            Triple("120", "P1Y", "$2.30"),
            Triple("50", "P1Y", "$0.95"),
            Triple("29.99", "P1Y", "$0.57"),
            Triple("720", "P3Y", "$4.60"),
        ).forEach { (dollars, iso, expected) ->
            val product = subscription(iso, price(dollars))
            assertThat(product.pricePerWeek(us)?.formatted)
                .describedAs("$$dollars / $iso 折成周价")
                .isEqualTo(expected)
        }
    }

    @Test
    fun `pricePerMonth 与 RC PriceExtensionsPricePerMonthTest 的期望值逐条一致`() {
        listOf(
            Triple("2", "P1D", "$60.00"),
            Triple("5", "P15D", "$10.00"),
            Triple("10", "P1W", "$43.45"),
            Triple("10", "P2W", "$21.72"),
            Triple("14.99", "P1M", "$14.99"),
            Triple("30", "P2M", "$15.00"),
            Triple("40", "P3M", "$13.33"),
            Triple("120", "P1Y", "$10.00"),
            Triple("50", "P1Y", "$4.16"),
            Triple("29.99", "P1Y", "$2.49"),
            Triple("720", "P3Y", "$20.00"),
        ).forEach { (dollars, iso, expected) ->
            val product = subscription(iso, price(dollars))
            assertThat(product.pricePerMonth(us)?.formatted)
                .describedAs("$$dollars / $iso 折成月价")
                .isEqualTo(expected)
        }
    }

    @Test
    fun `pricePerYear 与 RC PriceExtensionsPricePerYearTest 的期望值逐条一致`() {
        listOf(
            Triple("1", "P1D", "$365.00"),
            Triple("2", "P1D", "$730.00"),
            Triple("5", "P15D", "$121.66"),
            Triple("10", "P1W", "$521.42"),
            Triple("10", "P2W", "$260.71"),
            Triple("14.99", "P1M", "$179.88"),
            Triple("5", "P1M", "$60.00"),
            Triple("30", "P2M", "$180.00"),
            Triple("40", "P3M", "$160.00"),
            Triple("120", "P1Y", "$120.00"),
            Triple("29.99", "P1Y", "$29.99"),
            Triple("50", "P2Y", "$25.00"),
            Triple("720", "P3Y", "$240.00"),
        ).forEach { (dollars, iso, expected) ->
            val product = subscription(iso, price(dollars))
            assertThat(product.pricePerYear(us)?.formatted)
                .describedAs("$$dollars / $iso 折成年价")
                .isEqualTo(expected)
        }
    }

    // endregion

    // region RC 金标准表（amountMicros 逐位）

    @Test
    fun `PricingPhase 四种折算的 amountMicros 与 RC ParameterizedPricingPhaseTest 逐位一致`() {
        // BASE_PRICE = $99.99 / 99_990_000 micros（RC 同一份 fixture）。
        val base = price("99.99")
        data class Expected(val daily: Long, val weekly: Long, val monthly: Long, val yearly: Long)

        val table = mapOf(
            "P1D" to Expected(99_990_000L, 699_930_000L, 2_999_700_000L, 36_496_350_000L),
            "P1W" to Expected(14_284_285L, 99_990_000L, 434_480_357L, 5_213_764_285L),
            "P1M" to Expected(3_333_000L, 23_011_397L, 99_990_000L, 1_199_880_000L),
            "P1Y" to Expected(273_945L, 1_917_616L, 8_332_500L, 99_990_000L),
        )

        table.forEach { (iso, expected) ->
            val pricingPhase = phase(iso, base, RecurrenceMode.FINITE_RECURRING, billingCycleCount = 2)
            assertThat(pricingPhase.pricePerDay(us)?.amountMicros).describedAs("$iso → 日").isEqualTo(expected.daily)
            assertThat(pricingPhase.pricePerWeek(us)?.amountMicros).describedAs("$iso → 周").isEqualTo(expected.weekly)
            assertThat(pricingPhase.pricePerMonth(us)?.amountMicros).describedAs("$iso → 月").isEqualTo(expected.monthly)
            assertThat(pricingPhase.pricePerYear(us)?.amountMicros).describedAs("$iso → 年").isEqualTo(expected.yearly)
            // 币种原样带过来。
            assertThat(pricingPhase.pricePerWeek(us)?.currencyCode).isEqualTo("USD")
        }
    }

    @Test
    fun `PricingPhase 的 P1D 折算 formatted 带千分位（RC 同款）`() {
        val pricingPhase = phase("P1D", price("99.99"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = 2)
        assertThat(pricingPhase.pricePerMonth(us)?.formatted).isEqualTo("$2,999.70")
        assertThat(pricingPhase.pricePerYear(us)?.formatted).isEqualTo("$36,496.35")
    }

    // endregion

    // region 换算常数：按 period，不是日历

    @Test
    fun `1 年按 365 除以 7 约 52_142857 周折算，不是日历的 52 周`() {
        val annual = subscription("P1Y", price("52.00"))
        val weekly = requireNotNull(annual.pricePerWeek(us))
        // 52 / (365/7) = 0.99726…；如果按日历 52 周算会得到正好 1.00。
        assertThat(weekly.amountMicros).isEqualTo(997_260L)
        assertThat(weekly.formatted).isEqualTo("$0.99")
        assertThat(weekly.amountMicros).isNotEqualTo(1_000_000L)
    }

    @Test
    fun `1 月按 365 除以 12 除以 7 约 4_345238 周折算，不是日历的 4 周`() {
        val monthly = subscription("P1M", price("9.99"))
        val weekly = requireNotNull(monthly.pricePerWeek(us))
        assertThat(weekly.amountMicros).isEqualTo(2_299_068L)
        // 按 4 周算会是 2_497_500。
        assertThat(weekly.amountMicros).isNotEqualTo(2_497_500L)
    }

    // endregion

    // region P3M / P6M（P6M 不在 RC 的表里，按同一口径推出来）

    @Test
    fun `P3M 四种折算`() {
        val quarterly = subscription("P3M", price("40"))
        assertThat(quarterly.pricePerDay(us)?.amountMicros).isEqualTo(444_444L)
        assertThat(quarterly.pricePerWeek(us)?.amountMicros).isEqualTo(3_068_493L)
        assertThat(quarterly.pricePerMonth(us)?.amountMicros).isEqualTo(13_333_333L)
        assertThat(quarterly.pricePerYear(us)?.amountMicros).isEqualTo(160_000_000L)
        assertThat(quarterly.pricePerMonth(us)?.formatted).isEqualTo("$13.33")
    }

    @Test
    fun `P6M 四种折算`() {
        val halfYear = subscription("P6M", price("59.99"))
        assertThat(halfYear.pricePerDay(us)?.amountMicros).isEqualTo(333_277L)
        assertThat(halfYear.pricePerWeek(us)?.amountMicros).isEqualTo(2_300_986L)
        assertThat(halfYear.pricePerMonth(us)?.amountMicros).isEqualTo(9_998_333L)
        assertThat(halfYear.pricePerYear(us)?.amountMicros).isEqualTo(119_980_000L)
        assertThat(halfYear.pricePerWeek(us)?.formatted).isEqualTo("$2.30")
        assertThat(halfYear.pricePerMonth(us)?.formatted).isEqualTo("$9.99")
    }

    // endregion

    // region 多币种 / 非 en-US locale（RC PriceExtensionsTest）

    // es-ES 的金额与货币符号之间是 **NBSP（U+00A0）**，不是普通空格 —— RC 的单测源码里也是
    // `"4,99 US$"`。这里写成转义，免得下一个人把它「顺手改成」普通空格。

    @Test
    fun `USD 在 es-ES locale 下按当地格式渲染`() {
        val annual = subscription("P1Y", Price("$59.99", 59_990_000L, "USD"))
        val esES = Locale.forLanguageTag("es-ES")
        assertThat(annual.pricePerMonth(esES)?.formatted).isEqualTo("4,99 US$")
        assertThat(annual.pricePerMonth(esES)?.currencyCode).isEqualTo("USD")
    }

    @Test
    fun `EUR 在 es-ES 与 en-US 两个 locale 下的渲染都跟 RC 一致`() {
        val annual = subscription("P1Y", Price("59.99€", 59_990_000L, "EUR"))
        assertThat(annual.pricePerMonth(Locale.forLanguageTag("es-ES"))?.formatted).isEqualTo("4,99 €")
        assertThat(annual.pricePerMonth(us)?.formatted).isEqualTo("€4.99")
        assertThat(annual.pricePerMonth(us)?.currencyCode).isEqualTo("EUR")
    }

    @Test
    fun `零小数位币种（JPY）不带小数点`() {
        // Currency.getInstance("JPY").defaultFractionDigits == 0。
        val annual = subscription("P1Y", Price("￥12,000", 12_000_000_000L, "JPY"))
        val monthly = requireNotNull(annual.pricePerMonth(us))
        assertThat(monthly.amountMicros).isEqualTo(1_000_000_000L)
        assertThat(monthly.formatted).doesNotContain(".")
        assertThat(monthly.formatted).contains("1,000")
    }

    @Test
    fun `amountMicros 是未做展示舍入的折算值`() {
        val annual = subscription("P1Y", Price("$59.99", 59_990_000L, "USD"))
        val monthly = requireNotNull(annual.pricePerMonth(us))
        // RC 的断言：pricePerMonth.amountMicros == price.amountMicros / 12。
        assertThat(monthly.amountMicros).isEqualTo(59_990_000L / 12)
        assertThat(monthly.formatted).isEqualTo("$4.99")
    }

    @Test
    fun `月订折成月价就是原价本身`() {
        val monthly = subscription("P1M", Price("9.99€", 9_990_000L, "USD"))
        assertThat(monthly.pricePerMonth(us)?.amountMicros).isEqualTo(9_990_000L)
        assertThat(monthly.pricePerMonth(us)?.formatted).isEqualTo("$9.99")
    }

    // endregion

    // region formattedPricePerMonth

    @Test
    fun `formattedPricePerMonth 等价于 pricePerMonth 的 formatted`() {
        val annual = subscription("P1Y", price("59.99"))
        assertThat(annual.formattedPricePerMonth(us)).isEqualTo("$4.99")
        assertThat(annual.formattedPricePerMonth(us)).isEqualTo(annual.pricePerMonth(us)?.formatted)
    }

    @Test
    fun `formattedPricePerMonth 在一次性商品上是 null`() {
        assertThat(inApp(price("0.99")).formattedPricePerMonth(us)).isNull()
    }

    // endregion

    // region 折算不了的情形一律 null

    @Test
    fun `一次性商品（period 为 null）四种折算全 null`() {
        val product = inApp(price("0.99"))
        assertThat(product.pricePerDay(us)).isNull()
        assertThat(product.pricePerWeek(us)).isNull()
        assertThat(product.pricePerMonth(us)).isNull()
        assertThat(product.pricePerYear(us)).isNull()
    }

    @Test
    fun `周期解析不了（UNKNOWN 单位）返回 null —— 偏离 RC 的 Long MAX_VALUE 天文数字`() {
        val product = subscription("garbage", price("59.99"))
        assertThat(product.period?.unit).isEqualTo(Period.Unit.UNKNOWN)
        assertThat(product.pricePerDay(us)).isNull()
        assertThat(product.pricePerWeek(us)).isNull()
        assertThat(product.pricePerMonth(us)).isNull()
        assertThat(product.pricePerYear(us)).isNull()
        assertThat(product.formattedPricePerMonth(us)).isNull()

        val unknownPhase = phase("garbage", price("59.99"))
        assertThat(unknownPhase.pricePerWeek(us)).isNull()
        assertThat(unknownPhase.pricePerMonth(us)).isNull()
    }

    @Test
    fun `币种代码不是合法 ISO 4217 时返回 null 而不是抛 —— 付费墙不该因为一个币种崩掉`() {
        val product = subscription("P1Y", Price("¤59.99", 59_990_000L, "$"))
        assertThat(product.pricePerWeek(us)).isNull()
        assertThat(product.pricePerMonth(us)).isNull()
    }

    // endregion

    // region 默认 locale 重载（Java 侧的 @JvmOverloads 走同一条路）

    @Test
    fun `不传 locale 时取系统默认 locale，金额与显式传 locale 完全一致`() {
        val annual = subscription("P1Y", price("59.99"))
        assertThat(annual.pricePerMonth()?.amountMicros).isEqualTo(annual.pricePerMonth(us)?.amountMicros)
        assertThat(annual.pricePerMonth()?.formatted).isNotBlank()
        val pricingPhase = phase("P1Y", price("59.99"))
        assertThat(pricingPhase.pricePerWeek()?.amountMicros).isEqualTo(pricingPhase.pricePerWeek(us)?.amountMicros)
    }

    // endregion

    // region offerPaymentMode（期望值搬自 RC PricingPhaseTest）

    @Test
    fun `INFINITE_RECURRING 阶段没有 offerPaymentMode`() {
        val full = phase("P1M", price("2.99"), RecurrenceMode.INFINITE_RECURRING, billingCycleCount = null)
        assertThat(full.offerPaymentMode).isNull()
    }

    @Test
    fun `NON_RECURRING（预付费）阶段没有 offerPaymentMode`() {
        val prepaid = phase("P1M", price("2.99"), RecurrenceMode.NON_RECURRING, billingCycleCount = null)
        assertThat(prepaid.offerPaymentMode).isNull()
    }

    @Test
    fun `免费阶段且 1 个周期 是 FREE_TRIAL`() {
        val free = phase("P1M", Price("FREE", 0L, "USD"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = 1)
        assertThat(free.offerPaymentMode).isEqualTo(OfferPaymentMode.FREE_TRIAL)
    }

    @Test
    fun `收费阶段且 1 个周期 是 SINGLE_PAYMENT`() {
        val single = phase("P1M", price("2.99"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = 1)
        assertThat(single.offerPaymentMode).isEqualTo(OfferPaymentMode.SINGLE_PAYMENT)
    }

    @Test
    fun `收费阶段且 2 个周期 是 DISCOUNTED_RECURRING_PAYMENT`() {
        val recurring = phase("P1M", price("2.99"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = 2)
        assertThat(recurring.offerPaymentMode).isEqualTo(OfferPaymentMode.DISCOUNTED_RECURRING_PAYMENT)
    }

    @Test
    fun `FINITE_RECURRING 却没给 billingCycleCount 时是 null，不猜`() {
        val weird = phase("P1M", price("2.99"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = null)
        assertThat(weird.offerPaymentMode).isNull()
    }

    @Test
    fun `免费阶段优先判成 FREE_TRIAL，与周期数无关`() {
        val free = phase("P1M", Price("FREE", 0L, "USD"), RecurrenceMode.FINITE_RECURRING, billingCycleCount = 3)
        assertThat(free.offerPaymentMode).isEqualTo(OfferPaymentMode.FREE_TRIAL)
    }

    @Test
    fun `OfferPaymentMode 的 name 与 RC 的枚举常量名逐字一致`() {
        assertThat(OfferPaymentMode.FREE_TRIAL.name).isEqualTo("FREE_TRIAL")
        assertThat(OfferPaymentMode.SINGLE_PAYMENT.name).isEqualTo("SINGLE_PAYMENT")
        assertThat(OfferPaymentMode.DISCOUNTED_RECURRING_PAYMENT.name).isEqualTo("DISCOUNTED_RECURRING_PAYMENT")
        assertThat(OfferPaymentMode.ALL).hasSize(3)
        assertThat(OfferPaymentMode.FREE_TRIAL.toString()).isEqualTo("FREE_TRIAL")
    }

    // endregion

    // region Period 的换算属性

    @Test
    fun `Period 的 valueInMonths 走 period 常量`() {
        assertThat(Period.create("P1Y").valueInMonths).isEqualTo(12.0)
        assertThat(Period.create("P6M").valueInMonths).isEqualTo(6.0)
        assertThat(Period.create("P30D").valueInMonths).isEqualTo(1.0)
        // UNKNOWN 单位取 0（RC 同款），折算入口再据此返回 null。
        assertThat(Period.create("garbage").valueInMonths).isEqualTo(0.0)
    }

    // endregion

    private companion object {
        const val MICRO_DIGITS = 6
    }
}
