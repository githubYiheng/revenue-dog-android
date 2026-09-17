package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.SubscriptionOptions
import org.revdog.purchases.support.StoreProductBuilders

class ModelsTest {

    // region Period（ISO 8601）

    @Test
    fun `常见周期解析`() {
        assertThat(Period.create("P1W")).isEqualTo(Period(1, Period.Unit.WEEK, "P1W"))
        assertThat(Period.create("P1M")).isEqualTo(Period(1, Period.Unit.MONTH, "P1M"))
        assertThat(Period.create("P3M")).isEqualTo(Period(3, Period.Unit.MONTH, "P3M"))
        assertThat(Period.create("P6M")).isEqualTo(Period(6, Period.Unit.MONTH, "P6M"))
        assertThat(Period.create("P1Y")).isEqualTo(Period(1, Period.Unit.YEAR, "P1Y"))
        assertThat(Period.create("P7D")).isEqualTo(Period(7, Period.Unit.DAY, "P7D"))
    }

    @Test
    fun `取最小的非零单位并把更大的单位折算进来`() {
        // P1Y6M → 以「月」为单位 = 18 个月。
        assertThat(Period.create("P1Y6M").unit).isEqualTo(Period.Unit.MONTH)
        assertThat(Period.create("P1Y6M").value).isEqualTo(18)
    }

    @Test
    fun `解析不了的周期落 UNKNOWN 而不是抛 —— 一个没见过的周期不该让整次 offerings 挂掉`() {
        val unknown = Period.create("garbage")
        assertThat(unknown.unit).isEqualTo(Period.Unit.UNKNOWN)
        assertThat(unknown.value).isZero()
        // iso8601 原样保留：上行 pricing_phases 要发它，不能在端上被改写。
        assertThat(unknown.iso8601).isEqualTo("garbage")
    }

    // endregion

    // region RecurrenceMode

    @Test
    fun `RecurrenceMode 沿用 Google 的整数标识`() {
        assertThat(RecurrenceMode.fromIdentifier(1)).isEqualTo(RecurrenceMode.INFINITE_RECURRING)
        assertThat(RecurrenceMode.fromIdentifier(2)).isEqualTo(RecurrenceMode.FINITE_RECURRING)
        assertThat(RecurrenceMode.fromIdentifier(3)).isEqualTo(RecurrenceMode.NON_RECURRING)
        assertThat(RecurrenceMode.fromIdentifier(null)).isEqualTo(RecurrenceMode.UNKNOWN)
        assertThat(RecurrenceMode.fromIdentifier(99)).isEqualTo(RecurrenceMode.UNKNOWN)
    }

    // endregion

    // region SubscriptionOption 的阶段划分（坑 15 / RC 的 dropLast(1)）

    @Test
    fun `只有一个 pricing phase 就是 base plan`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        assertThat(base.isBasePlan).isTrue()
        assertThat(base.id).isEqualTo("monthly")
    }

    @Test
    fun `offer 的 id 是 basePlanId 冒号 offerId`() {
        val offer = StoreProductBuilders.subscriptionOption(
            "sub",
            "monthly",
            offerId = "trial",
            pricingPhases = listOf(
                StoreProductBuilders.pricingPhase("P1W", amountMicros = 0),
                StoreProductBuilders.pricingPhase("P1M"),
            ),
        )
        assertThat(offer.id).isEqualTo("monthly:trial")
        assertThat(offer.isBasePlan).isFalse()
    }

    @Test
    fun `freePhase 先 dropLast(1) 再找零价阶段`() {
        val withTrial = StoreProductBuilders.subscriptionOption(
            "sub",
            "monthly",
            offerId = "trial",
            pricingPhases = listOf(
                StoreProductBuilders.pricingPhase("P1W", amountMicros = 0),
                StoreProductBuilders.pricingPhase("P1M", amountMicros = 4_990_000),
            ),
        )
        assertThat(withTrial.freePhase?.billingPeriod?.iso8601).isEqualTo("P1W")
        assertThat(withTrial.introPhase).isNull()
        assertThat(withTrial.fullPricePhase?.price?.amountMicros).isEqualTo(4_990_000)
    }

    @Test
    fun `全价阶段为零价的商品不会被当成「全程免费试用」`() {
        // 只有一个零价阶段 → dropLast(1) 之后为空 → freePhase 必须是 null。
        val free = StoreProductBuilders.subscriptionOption(
            "sub",
            "monthly",
            pricingPhases = listOf(StoreProductBuilders.pricingPhase("P1M", amountMicros = 0)),
        )
        assertThat(free.freePhase).isNull()
    }

    @Test
    fun `introPhase 是第一个非零的非全价阶段`() {
        val withIntro = StoreProductBuilders.subscriptionOption(
            "sub",
            "monthly",
            offerId = "intro",
            pricingPhases = listOf(
                StoreProductBuilders.pricingPhase("P1M", amountMicros = 990_000),
                StoreProductBuilders.pricingPhase("P1M", amountMicros = 4_990_000),
            ),
        )
        assertThat(withIntro.introPhase?.price?.amountMicros).isEqualTo(990_000)
        assertThat(withIntro.freePhase).isNull()
    }

    @Test
    fun `NON_RECURRING 的全价阶段 = 预付费套餐`() {
        val prepaid = StoreProductBuilders.subscriptionOption(
            "sub",
            "prepaid",
            pricingPhases = listOf(
                StoreProductBuilders.pricingPhase("P1M", recurrenceMode = RecurrenceMode.NON_RECURRING),
            ),
        )
        assertThat(prepaid.isPrepaid).isTrue()
    }

    // endregion

    // region defaultOffer 的挑选顺序（照抄 RC）

    private fun trial(offerId: String, trialPeriod: String) = StoreProductBuilders.subscriptionOption(
        "sub",
        "monthly",
        offerId = offerId,
        pricingPhases = listOf(
            StoreProductBuilders.pricingPhase(trialPeriod, amountMicros = 0),
            StoreProductBuilders.pricingPhase("P1M"),
        ),
    )

    private fun intro(offerId: String, amountMicros: Long, tags: List<String> = emptyList()) =
        StoreProductBuilders.subscriptionOption(
            "sub",
            "monthly",
            offerId = offerId,
            pricingPhases = listOf(
                StoreProductBuilders.pricingPhase("P1M", amountMicros = amountMicros),
                StoreProductBuilders.pricingPhase("P1M"),
            ),
            tags = tags,
        )

    @Test
    fun `优先挑最长的免费试用`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        val options = SubscriptionOptions(listOf(base, trial("short", "P3D"), trial("long", "P1M")))
        assertThat(options.defaultOffer?.offerId).isEqualTo("long")
    }

    @Test
    fun `没有免费试用时挑最便宜的 intro`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        val options = SubscriptionOptions(
            listOf(base, intro("pricey", 3_000_000), intro("cheap", 1_000_000)),
        )
        assertThat(options.defaultOffer?.offerId).isEqualTo("cheap")
    }

    @Test
    fun `带忽略 tag 的 offer 被排除`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        val ignored = intro("ignored", 1_000_000, tags = listOf(SubscriptionOptions.IGNORE_OFFER_TAG))
        val options = SubscriptionOptions(listOf(base, ignored, intro("normal", 3_000_000)))
        assertThat(options.defaultOffer?.offerId).isEqualTo("normal")
    }

    @Test
    fun `什么 offer 都没有时回落 base plan`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        assertThat(SubscriptionOptions(listOf(base)).defaultOffer).isEqualTo(base)
    }

    @Test
    fun `没有 base plan 时 defaultOffer 为 null`() {
        assertThat(SubscriptionOptions(listOf(trial("t", "P3D"))).defaultOffer).isNull()
    }

    @Test
    fun `withTag 能筛出打了 tag 的 offer`() {
        val base = StoreProductBuilders.subscriptionOption("sub", "monthly")
        val tagged = intro("promo", 1_000_000, tags = listOf("black-friday"))
        val options = SubscriptionOptions(listOf(base, tagged))
        assertThat(options.withTag("black-friday").map { it.offerId }).containsExactly("promo")
    }

    // endregion

    @Test
    fun `StoreProduct 的 id 是 productId 冒号 basePlanId`() {
        val subscription = StoreProductBuilders.subscription("sub_premium", "monthly-base")
        assertThat(subscription.id).isEqualTo("sub_premium:monthly-base")

        // 一次性商品没有 base plan，id 就是裸 productId。
        assertThat(StoreProductBuilders.inApp("coins_100").id).isEqualTo("coins_100")
    }
}
