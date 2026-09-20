package org.revdog.purchases

import com.android.billingclient.api.InAppMessageParams
import dev.drewhamilton.poko.Poko

/**
 * Play in-app message（订阅扣款失败时 Google 官方的挽回提示）的类别。
 * 结构对照 RC `models/InAppMessageType.kt`。
 *
 * **偏离 RC（形态）**：RC 是 `public enum class`，那在我方的 `ForbiddenPublicEnum` 下过不了门禁
 * （PBL 以后加一个 category，宿主里穷尽的 `when` 当场编译不过）。照我方既有惯例
 * （[ReplacementMode] / [CacheFetchPolicy] / [LogLevel]）用 `@Poko class` + companion 的
 * `@JvmField` 常量，Java 侧 `InAppMessageType.BILLING_ISSUES` 的写法与 enum 完全一致。
 *
 * **只有一个值**：PBL 9.1 的 `InAppMessageParams.InAppMessageCategoryId` 只定义了
 * `TRANSACTIONAL`（另一个 `UNKNOWN_IN_APP_MESSAGE_CATEGORY_ID` 是哨兵，不是可展示的类别）。
 * RC 也只映射这一个 —— 没有「RC 有而 PBL 9.1 没有」的类别需要处理。
 */
@Poko
public class InAppMessageType private constructor(
    public val name: String,
    /** 传给 `InAppMessageParams.addInAppMessageCategoryToShow` 的 Billing 常量。 */
    internal val categoryId: Int,
) {

    override fun toString(): String = name

    public companion object {
        /**
         * 扣款问题。用户的订阅续费被拒（卡过期 / 余额不足）时，Play 会弹一条 snackbar
         * 告诉用户并给出修复入口 —— 这是 Google 官方的挽回通道，不展示 = 白白流失。
         */
        @JvmField
        public val BILLING_ISSUES: InAppMessageType =
            InAppMessageType("BILLING_ISSUES", InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)

        /** 全部类别。自动展示与 [Purchases.showInAppMessagesIfNeeded] 的默认值都是它。 */
        @JvmField
        public val ALL: List<InAppMessageType> = listOf(BILLING_ISSUES)
    }
}
