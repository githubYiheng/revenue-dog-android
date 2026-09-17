package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.revdog.purchases.identity.AccountToken

/**
 * `account_token` → `obfuscatedAccountId` 的派生，**与 iOS 逐位对齐**。
 *
 * 夹具输入取自 iOS 的 `M2HardeningTests.appAccountTokenWiring`
 * （`token32 = "0123456789abcdef0123456789abcdef"`），期望输出取自那条用例的断言
 * （`captured.first??.uuidString.lowercased() == "01234567-89ab-cdef-0123-456789abcdef"`）。
 *
 * 两端发的是**同一个服务端签发令牌的两种形状**：
 * - iOS：Apple 的 `appAccountToken` 类型是 `UUID`，必须补连字符；
 * - Android：Play 的 `obfuscatedAccountId` 接受任意 ≤64 字符串，原样发 32hex
 *   —— 后端 `customers.account_token` 存的就是这个形状，RTDN 回显可以直接反查。
 */
class AccountTokenTest {

    /** iOS 测试夹具里的同一个输入。 */
    private val token32 = "0123456789abcdef0123456789abcdef"

    /** iOS 那条用例断言的同一个输出。 */
    private val iosUuidShape = "01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun `同一输入派生出 iOS 同一输出（UUID 形状）`() {
        assertThat(AccountToken.toUuidString(token32)).isEqualTo(iosUuidShape)
    }

    @Test
    fun `Android 的 obfuscatedAccountId 就是令牌原文（32hex，Play 上限 64 字符）`() {
        val obfuscated = AccountToken.toObfuscatedAccountId(token32)
        assertThat(obfuscated).isEqualTo(token32)
        assertThat(obfuscated).hasSize(32)
        // 与 iOS 那一份是同一个令牌，只是少了连字符。
        assertThat(obfuscated).isEqualTo(iosUuidShape.replace("-", ""))
    }

    @Test
    fun `不合法的令牌一律不发 —— 不猜、不补零、不 sha256`() {
        // RC 发的是 `appUserID.sha256()`，那个值后端认不出来（见 AccountToken 的类注释）。
        val invalid = listOf(
            null,
            "",
            "0123456789ABCDEF0123456789ABCDEF", // 大写：与 iOS 的 `!isUppercase` 同口径
            "0123456789abcdef0123456789abcde", // 31 位
            "0123456789abcdef0123456789abcdeff", // 33 位
            "0123456789abcdef0123456789abcdeg", // 非 hex
            "01234567-89ab-cdef-0123-456789abcdef", // 已经带连字符
        )
        invalid.forEach { candidate ->
            assertThat(AccountToken.isValid(candidate)).describedAs("isValid(%s)", candidate).isFalse()
            assertThat(AccountToken.toObfuscatedAccountId(candidate)).isNull()
            assertThat(AccountToken.toUuidString(candidate)).isNull()
        }
    }
}
