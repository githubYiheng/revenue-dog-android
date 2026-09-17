package org.revdog.purchases.models

import com.android.billingclient.api.Purchase
import dev.drewhamilton.poko.Poko
import org.revdog.purchases.ProductType
import org.revdog.purchases.ReplacementMode

/**
 * 购买状态。结构对照 RC `models/PurchaseState.kt`，取值来自 Play 的
 * `Purchase.PurchaseState`（0 / 1 / 2）。
 *
 * **[PENDING] 是本 SDK 里最危险的状态**（坑 3）：钱还没扣，权益不能发，
 * 交易不能 ack / consume，**台账也不能记** —— 记了台账，用户付款完成后这笔就永远不会被补报。
 */
@Poko
public class PurchaseState private constructor(
    public val rawValue: String,
    internal val playCode: Int,
) {

    override fun toString(): String = rawValue

    public companion object {
        /** Play 没给状态（老版本 / 应用外来源）。与 [PENDING] 同样按「不碰」处理。 */
        @JvmField public val UNSPECIFIED_STATE: PurchaseState = PurchaseState("unspecified_state", 0)

        /** 已扣款。唯一可以上报、可以发权益的状态。 */
        @JvmField public val PURCHASED: PurchaseState = PurchaseState("purchased", 1)

        /** 已受理、未扣款（现金支付 / 待家长批准 / 预付费套餐）。 */
        @JvmField public val PENDING: PurchaseState = PurchaseState("pending", 2)

        @JvmField public val ALL: List<PurchaseState> = listOf(UNSPECIFIED_STATE, PURCHASED, PENDING)

        /** 未知取值落 [UNSPECIFIED_STATE]（契约 §1.8：容忍未知）。 */
        @JvmStatic
        public fun fromPlayCode(code: Int): PurchaseState =
            ALL.firstOrNull { it.playCode == code } ?: UNSPECIFIED_STATE
    }
}

/**
 * 一笔 Play 交易。结构对照 RC `models/StoreTransaction.kt` +
 * `google/storeTransactionConversions.kt`。
 *
 * 字段分两类：
 * - **Play 给的**：`orderId` / `productIds` / `purchaseTime` / `purchaseToken` /
 *   `purchaseState` / `isAutoRenewing` / `isAcknowledged`；
 * - **我方补的**（来自购买发起时落盘的上下文，应用外购买时为 `null`）：
 *   [type]（反查不到时 `UNKNOWN`）、[subscriptionOptionId]、
 *   [presentedOfferingIdentifier]、[replacementMode]。
 *
 * [isAcknowledged] 是**发起 consumeAndSave 那一刻的快照**（设计 §8）：服务端是 ack 权威，
 * 它在返回 200 之前就可能已经 ack 过这笔，快照为 `true` 时 SDK 只记台账、不再调 ack。
 */
@Suppress("LongParameterList")
@Poko
public class StoreTransaction internal constructor(
    public val orderId: String?,
    /** **数组**：多行订阅 / add-ons 会有多个（考古 §2.3）。v1 只用第一个。 */
    public val productIds: List<String>,
    public val type: ProductType,
    public val purchaseTime: Long,
    /** 上行 `fetch_token` 发的就是它的**原文**（考古 §2.2）。 */
    public val purchaseToken: String,
    public val purchaseState: PurchaseState,
    /** 订阅才有。**侦测「在 Play 商店外取消」的唯一手段**（坑 23）。 */
    public val isAutoRenewing: Boolean?,
    public val isAcknowledged: Boolean,
    public val presentedOfferingIdentifier: String?,
    /** 购买的那个 `SubscriptionOption` 的 id（`basePlanId` 或 `basePlanId:offerId`）。 */
    public val subscriptionOptionId: String?,
    public val replacementMode: ReplacementMode?,
) {

    /** 原始 `Purchase`。ack / consume 与台账都只需要 token，这个留给排障。 */
    internal var originalGooglePurchase: Purchase? = null
        private set

    internal fun withOriginalPurchase(purchase: Purchase?): StoreTransaction =
        apply { originalGooglePurchase = purchase }
}
