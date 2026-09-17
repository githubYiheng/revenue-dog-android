package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 错误码表与 **两端唯一真相源** `sdk/error-codes.json` 的对账。
 *
 * 存在理由（考古 §9.1 必抄第 16 项）：RC 的错误码是从一个独立仓库生成的。
 * 我方不生成代码，改成「手写 + 单测对账」—— 效果一样，少一个构建期依赖。
 * iOS 侧的同款对账测试由主代理接上（本切片只负责 Android 这一半）。
 */
class ErrorCodesContractTest {

    private val json: JSONObject by lazy {
        // 路径由 Gradle 注入（`revdog.errorCodesJson`）：真相源在 sdk/error-codes.json，
        // 不在 Gradle 项目树里，靠相对路径找它太脆。
        val path = requireNotNull(System.getProperty("revdog.errorCodesJson")) {
            "缺少系统属性 revdog.errorCodesJson —— 见 purchases/build.gradle.kts"
        }
        val file = File(path)
        assertThat(file).exists()
        JSONObject(file.readText())
    }

    @Test
    fun `码位表与 error-codes_json 逐项一致`() {
        val expected = json.getJSONArray("codes").let { array ->
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                entry.getInt("code") to entry.getString("name")
            }
        }
        val actual = PurchasesErrorCode.ALL.map { it.code to it.name }

        assertThat(actual)
            .describedAs("PurchasesErrorCode.ALL 必须与 sdk/error-codes.json 逐项一致（含顺序）")
            .isEqualTo(expected)
    }

    @Test
    fun `域名与 iOS 一致`() {
        assertThat(json.getString("domain")).isEqualTo("com.revenuedog.sdk")
    }

    @Test
    fun `码位没有重复`() {
        val codes = PurchasesErrorCode.ALL.map { it.code }
        assertThat(codes).doesNotHaveDuplicates()
        val names = PurchasesErrorCode.ALL.map { it.name }
        assertThat(names).doesNotHaveDuplicates()
    }

    @Test
    fun `未知码位降级成 unknownError 但保留原始数值`() {
        val unknown = PurchasesErrorCode.fromCode(9999)
        assertThat(unknown.code).isEqualTo(9999)
        assertThat(unknown.name).isEqualTo(PurchasesErrorCode.UnknownError.name)
    }

    @Test
    fun `已知码位原样返回同一个实例语义`() {
        assertThat(PurchasesErrorCode.fromCode(901)).isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
        assertThat(PurchasesErrorCode.fromCode(902)).isEqualTo(PurchasesErrorCode.PurchaseRejectedByServer)
    }

    @Test
    fun `与 iOS 共有的关键码位对齐`() {
        // 抽查几个最容易手抖写错的：RC 的 13 / 18-19 / 21-22 / 27 是空缺，我方也必须空缺。
        // **20 在 M3 被占用**（paymentPendingError，与 RC `PaymentPendingError` 同位）。
        val codes = PurchasesErrorCode.ALL.map { it.code }.toSet()
        assertThat(codes).doesNotContain(13, 18, 19, 21, 22, 27, 30, 31, 32, 33, 34)
        assertThat(codes).contains(0, 1, 2, 3, 10, 14, 20, 23, 28, 35, 900, 901, 902)
    }

    @Test
    fun `pending 码位与 RC 同位同名`() {
        assertThat(PurchasesErrorCode.PaymentPendingError.code).isEqualTo(20)
        assertThat(PurchasesErrorCode.PaymentPendingError.name).isEqualTo("paymentPendingError")
        assertThat(PurchasesErrorCode.fromCode(20)).isEqualTo(PurchasesErrorCode.PaymentPendingError)
    }
}
