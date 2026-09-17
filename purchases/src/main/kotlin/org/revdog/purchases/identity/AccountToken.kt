package org.revdog.purchases.identity

/**
 * `account_token` → Play 的 `obfuscatedAccountId`。
 *
 * **真相源与 iOS 完全相同**：`account_token` 是**服务端签发**的 32 位无连字符小写 hex
 * （契约 §2.2 ⟦决策 21⟧），SDK 从缓存的 `CustomerInfo` 里读，不自己生成、不从 appUserID 派生。
 * iOS 侧 `IdentityManager.accountTokenToUUIDString` 把它补上连字符塞进 Apple 的
 * `appAccountToken`（那边类型是 `UUID`，非 UUID 形状塞不进去）；Android 的
 * `obfuscatedAccountId` 接受任意 ≤64 字符的字符串，所以**原样发 32hex** ——
 * 两端发出去的是同一个令牌的两种形状，后端 `customers.account_token` 存的就是这个 32hex，
 * Play 的 RTDN / Developer API 回显时可以直接反查（`google-play-plan.md` §4 归户三级第一级）。
 *
 * **偏离 RC**：RC 发 `appUserID.sha256()`（`BillingWrapper.kt:968-1034`）。那个值后端**认不出来**
 * —— 它是客户端单方面算的哈希，与我方任何一张表都对不上。服务端签发的令牌才有归户价值。
 *
 * 校验不过（没缓存 / 形状不对）时返回 `null` = **不设** `obfuscatedAccountId`
 * （与 iOS 的 best-effort 一致）。归属权威始终在后端的 `purchaseToken ↔ app_user_id` 链上，
 * 这个字段只是辅助线索，绝不能因为它缺失就阻断购买。
 */
internal object AccountToken {

    private const val TOKEN_LENGTH = 32

    // UUID 形状 8-4-4-4-12 的切分点（与 iOS `accountTokenToUUIDString` 逐位一致）。
    private const val GROUP_1_END = 8
    private const val GROUP_2_END = 12
    private const val GROUP_3_END = 16
    private const val GROUP_4_END = 20

    /** 32 位无连字符**小写** hex。大写一律判不合法（与 iOS `!$0.isUppercase` 同口径）。 */
    fun isValid(token32: String?): Boolean =
        token32 != null &&
            token32.length == TOKEN_LENGTH &&
            token32.all { it in '0'..'9' || it in 'a'..'f' }

    /** Play 的 `obfuscatedAccountId`：就是令牌原文。 */
    fun toObfuscatedAccountId(token32: String?): String? = token32?.takeIf { isValid(it) }

    /**
     * 与 iOS `IdentityManager.accountTokenToUUIDString` **逐位同算法**的 UUID 形状（8-4-4-4-12）。
     *
     * Android 的购买链路不用它，保留它只为一件事：
     * 单测拿**同一个输入**比对两端**同一个输出**，防止哪天一端悄悄换了派生方式。
     */
    fun toUuidString(token32: String?): String? {
        val token = token32?.takeIf { isValid(it) } ?: return null
        return listOf(
            token.substring(0, GROUP_1_END),
            token.substring(GROUP_1_END, GROUP_2_END),
            token.substring(GROUP_2_END, GROUP_3_END),
            token.substring(GROUP_3_END, GROUP_4_END),
            token.substring(GROUP_4_END, TOKEN_LENGTH),
        ).joinToString(separator = "-")
    }
}
