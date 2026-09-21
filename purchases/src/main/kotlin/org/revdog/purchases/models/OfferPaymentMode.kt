package org.revdog.purchases.models

import dev.drewhamilton.poko.Poko

/**
 * offer 定价阶段的付费模式。结构对照 RC `models/OfferPaymentMode.kt`（10.22.1）。
 *
 * **偏离 RC**：RC 那边是 `public enum class OfferPaymentMode`。我方守
 * `ForbiddenPublicEnum`（设计 §1「禁 public enum」），改成 `@Poko class` + companion 常量。
 * [name] 与 RC 的枚举常量名逐字一致，宿主从 RC 迁过来时 `mode.name` 的取值不变。
 *
 * 取值只有三个、且**不带 `UNKNOWN`**：它不是从后端下行解析出来的枚举，
 * 而是由 `recurrenceMode` + `billingCycleCount` + 价格**本地推导**的（见
 * [PricingPhase.offerPaymentMode]），推不出来就是 `null`。
 */
@Poko
public class OfferPaymentMode private constructor(public val name: String) {

    override fun toString(): String = name

    public companion object {
        /** 该阶段结束之前不扣钱（免费试用）。 */
        @JvmField public val FREE_TRIAL: OfferPaymentMode = OfferPaymentMode("FREE_TRIAL")

        /** 一次性预付一段时间。 */
        @JvmField public val SINGLE_PAYMENT: OfferPaymentMode = OfferPaymentMode("SINGLE_PAYMENT")

        /** 按折扣价连续扣若干个周期。 */
        @JvmField
        public val DISCOUNTED_RECURRING_PAYMENT: OfferPaymentMode = OfferPaymentMode("DISCOUNTED_RECURRING_PAYMENT")

        @JvmField
        public val ALL: List<OfferPaymentMode> = listOf(FREE_TRIAL, SINGLE_PAYMENT, DISCOUNTED_RECURRING_PAYMENT)
    }
}
