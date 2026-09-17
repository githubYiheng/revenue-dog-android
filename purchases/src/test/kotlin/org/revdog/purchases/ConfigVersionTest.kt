package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.revdog.purchases.common.Config

/**
 * 版本号对账。
 *
 * `Config` 里的两个常量是**硬编码**的（对照 RC `Config.frameworkVersion`，不走 BuildConfig：
 * BuildConfig 是 public 类，会漏进 metalava 基线）。硬编码的代价就是会写歪，
 * 所以由 Gradle 把构建期的真相源（`gradle.properties` 与版本目录）注入进来对账。
 */
class ConfigVersionTest {

    @Test
    fun `FRAMEWORK_VERSION 与 gradle_properties 的 VERSION_NAME 一致`() {
        val expected = requireNotNull(System.getProperty("revdog.versionName"))
        assertThat(Config.FRAMEWORK_VERSION)
            .describedAs("改了 gradle.properties 的 VERSION_NAME 就要同步 Config.FRAMEWORK_VERSION")
            .isEqualTo(expected)
    }

    @Test
    fun `BILLING_CLIENT_VERSION 与版本目录里的 billingClient 一致`() {
        val expected = requireNotNull(System.getProperty("revdog.billingClientVersion"))
        assertThat(Config.BILLING_CLIENT_VERSION)
            .describedAs("升 Play Billing 版本时要同步 Config.BILLING_CLIENT_VERSION（它是 X-Billing-Client-Sdk-Version 的值）")
            .isEqualTo(expected)
    }

    @Test
    fun `PBL 基线是 9_x（ADR 0069 决策 3）`() {
        assertThat(Config.BILLING_CLIENT_VERSION).startsWith("9.")
    }
}
