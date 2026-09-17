package org.revdog.example

import android.app.Application
import android.content.Context
import org.revdog.purchases.LogLevel
import org.revdog.purchases.Purchases
import org.revdog.purchases.PurchasesConfiguration

/**
 * `Purchases.configure` 的位置：**`Application.onCreate`**（对照 RC
 * `examples/purchase-tester/MainApplication.kt`）。
 *
 * 为什么必须这么早：SDK 在 `configure` 里挂 `onPurchasesUpdated` 监听并发起 BillingClient 连接，
 * 而应用外购买（Play 商店里重订）与「上次没上报成功的交易」都只在连接成功之后才补得回来。
 * 挂在某个 Activity 里 = 用户没走到那个页面就丢单。
 *
 * **这个 app 里没有任何真实 key**：key 从 `local.properties` 经 BuildConfig 注入
 * （见 `example/README.md`）。没配 key 时**不 configure**，MainActivity 会把这件事写在屏幕上 ——
 * 不 configure 比拿空 key 去打后端更清楚。
 */
class DemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val apiKey = BuildConfig.REVENUEDOG_API_KEY
        if (apiKey.isBlank()) return

        val builder = PurchasesConfiguration.Builder(this, apiKey)
            .logLevel(if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO)
            // 诊断开关是 configure 期决定的，所以这个偏好项改完要重启进程才生效（UI 上写了）。
            .diagnosticsEnabled(DemoPreferences.diagnosticsEnabled(this))
            .appUserID(BuildConfig.REVENUEDOG_APP_USER_ID.takeIf { it.isNotBlank() })

        BuildConfig.REVENUEDOG_BASE_URL.takeIf { it.isNotBlank() }?.let { builder.baseURL(it) }

        Purchases.configure(builder.build())
    }
}

/** 手测 app 自己的偏好项。**与 SDK 的缓存无关**，只是 UI 记住上次的选择。 */
object DemoPreferences {

    private const val FILE_NAME = "revdog-example"
    private const val KEY_DIAGNOSTICS = "diagnostics_enabled"

    fun diagnosticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIAGNOSTICS, true)

    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIAGNOSTICS, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
