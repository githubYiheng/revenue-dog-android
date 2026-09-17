package org.revdog.purchases.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.Store

/**
 * 结构对照 RC `common/Config.kt`。
 *
 * 版本号**硬编码**（不走 BuildConfig）：BuildConfig 是 public 类，会漏进 metalava 基线。
 * `ConfigVersionTest` 把这两个常量与 `gradle.properties` / 版本目录对账，改一个不改另一个会红。
 */
internal object Config {
    /** SDK 版本，`X-Version` 头的值。必须 == `gradle.properties` 的 `VERSION_NAME`。 */
    const val FRAMEWORK_VERSION: String = "0.1.0"

    /** `X-Billing-Client-Sdk-Version` 头的值。必须 == 版本目录的 `billingClient`。 */
    const val BILLING_CLIENT_VERSION: String = "9.1.0"

    /** 原生集成。混合框架（Flutter / RN）宿主会覆写。 */
    const val PLATFORM_FLAVOR_NATIVE: String = "native"
}

/**
 * 混合框架标记（对照 RC `PlatformInfo`）。原生宿主永远是 `native` / `null`。
 */
internal class PlatformInfo(
    val flavor: String = Config.PLATFORM_FLAVOR_NATIVE,
    val version: String? = null,
)

/**
 * 进程级运行时配置（结构对照 RC `common/AppConfig.kt`）。
 *
 * `isAppBackgrounded` 是**可变**的：`X-Is-Backgrounded` 必须在发请求那一刻求值。
 */
@Suppress("LongParameterList")
internal class AppConfig(
    context: Context,
    val apiKey: String,
    val baseURL: String,
    val purchasesAreCompletedBy: PurchasesAreCompletedBy,
    val platformInfo: PlatformInfo = PlatformInfo(),
    val store: Store = Store.PLAY_STORE,
    val isDebugBuild: Boolean,
    val diagnosticsEnabled: Boolean,
) {

    /**
     * **application context**（`PurchasesConfiguration` 已经取过 `applicationContext`，
     * 这里不会泄漏 Activity）。属性采集（`$androidId` / `$gpsAdId`）要用它。
     */
    val applicationContext: Context = context.applicationContext

    val packageName: String = context.packageName

    /** `X-Client-Version` = 宿主 `versionName`。取不到时发哨兵 `unknown`（服务端约定：哨兵不写库）。 */
    val versionName: String = context.packageVersionName() ?: UNKNOWN_SENTINEL

    /**
     * `X-Client-Build-Version` = 宿主 `longVersionCode`。
     * **偏离 RC**：RC Android 从不发这个头（考古 §2.7），但我方 `customers.last_seen_app_build`
     * 依赖它（契约 §2.2），不发这一列在 Android 侧就永远是空。
     */
    val versionCode: String = context.packageVersionCode()?.toString() ?: UNKNOWN_SENTINEL

    /** `X-Client-Locale`：设备当前语言标签（考古 §2.7 标「新增」的四个头之一）。 */
    val languageTag: String? = context.languageTag()

    /** `X-Platform-Version`：**API level 整数串**（RC 同款；iOS 那边是 OS 版本串）。 */
    val platformVersion: String = Build.VERSION.SDK_INT.toString()

    val platformDevice: String = Build.MODEL ?: UNKNOWN_SENTINEL

    val platformBrand: String = Build.BRAND ?: UNKNOWN_SENTINEL

    /** SDK 是否负责 ack / consume。observer 模式下 `X-Observer-Mode-Enabled: true`。 */
    val finishTransactions: Boolean
        get() = purchasesAreCompletedBy == PurchasesAreCompletedBy.REVENUE_DOG

    @Volatile
    var isAppBackgrounded: Boolean = true

    private companion object {
        const val UNKNOWN_SENTINEL = "unknown"
    }
}

private fun Context.packageVersionName(): String? = runCatchingPackageInfo { it.versionName }

private fun Context.packageVersionCode(): Long? = runCatchingPackageInfo { info ->
    @Suppress("DEPRECATION")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}

private fun <T> Context.runCatchingPackageInfo(extract: (android.content.pm.PackageInfo) -> T?): T? = try {
    extract(packageManager.getPackageInfo(packageName, 0))
} catch (@Suppress("SwallowedException") e: PackageManager.NameNotFoundException) {
    null
}

private fun Context.languageTag(): String? {
    val locales = resources.configuration.locales
    return if (locales.isEmpty) null else locales[0]?.toLanguageTag()
}
