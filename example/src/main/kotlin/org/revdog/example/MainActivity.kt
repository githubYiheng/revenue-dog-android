package org.revdog.example

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.revdog.purchases.PurchaseParams
import org.revdog.purchases.Purchases
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.getCustomerInfoWith
import org.revdog.purchases.getOfferingsWith
import org.revdog.purchases.logInWith
import org.revdog.purchases.logOutWith
import org.revdog.purchases.offerings.Offerings
import org.revdog.purchases.offerings.Package
import org.revdog.purchases.purchaseWith
import org.revdog.purchases.restorePurchasesWith
import org.revdog.purchases.syncPurchasesWith

/**
 * 最小手测面板。形态照 RC `examples/purchase-tester` 裁剪到真机清单需要的七个动作：
 * **offerings / purchase / restore / sync / logIn / logOut / 属性**，加一个诊断开关。
 *
 * 界面全部用代码搭（没有 layout xml、没有 AppCompat / Material / navigation）：
 * 这个 app 的用途是**跑通 SDK 的调用面**与**给 R8 一个真实的调用方**，
 * 每多一个依赖就多一份「到底是谁的 ProGuard 规则救了场」的不确定性。
 */
@Suppress("TooManyFunctions") // 一个按钮一个动作；合并只会让手测面板更难读
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var appUserIdInput: EditText

    /** 上一次拉到的 offerings，purchase 从这里取第一个可买的 package。 */
    private var offerings: Offerings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        if (!Purchases.isConfigured) {
            log("SDK 没有 configure：local.properties 里缺 REVENUEDOG_API_KEY（见 example/README.md）")
            return
        }
        log("已 configure，appUserID = ${Purchases.sharedInstance.appUserID}")
        Purchases.sharedInstance.customerInfoListener()
    }

    // region 界面

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }

        appUserIdInput = EditText(this).apply {
            hint = "appUserID（logIn 用）"
            setText(BuildConfig.REVENUEDOG_APP_USER_ID)
        }
        root.addView(appUserIdInput)

        root.addView(button("offerings（拉取并列出 package）") { loadOfferings() })
        root.addView(button("purchase（买 current offering 的第一个 package）") { purchaseFirstPackage() })
        root.addView(button("restore（恢复购买）") { restore() })
        root.addView(button("sync（只上报，不碰 Billing）") { sync() })
        root.addView(button("customerInfo（强制联网刷一次）") { refreshCustomerInfo() })
        root.addView(button("logIn") { logIn() })
        root.addView(button("logOut") { logOut() })
        root.addView(button("设置订阅者属性 + 立即同步") { setAttributes() })
        root.addView(diagnosticsToggle())

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        root.addView(scroll)
        return root
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun diagnosticsToggle(): CheckBox = CheckBox(this).apply {
        text = "诊断开启（改完需要杀进程重启才生效）"
        isChecked = DemoPreferences.diagnosticsEnabled(this@MainActivity)
        setOnCheckedChangeListener { _, checked ->
            DemoPreferences.setDiagnosticsEnabled(this@MainActivity, checked)
            log("诊断开关已存为 $checked —— 它是 configure 期决定的，重启进程后生效")
        }
    }

    private fun log(message: String) {
        runOnUiThread { logView.append(message + "\n\n") }
    }

    private fun logError(label: String, error: PurchasesError) {
        log("$label 失败：code=${error.code.code}(${error.code.name}) requestId=${error.requestId}")
    }

    private fun logCustomerInfo(label: String, customerInfo: CustomerInfo) {
        log(
            "$label 成功\n" +
                "  active 权益：${customerInfo.entitlements.active.keys}\n" +
                "  订阅：${customerInfo.subscriptions.keys}\n" +
                "  requestDate：${customerInfo.requestDate}",
        )
    }

    // endregion

    // region 动作

    private fun Purchases.customerInfoListener() {
        updatedCustomerInfoListener = org.revdog.purchases.UpdatedCustomerInfoListener { customerInfo ->
            logCustomerInfo("customerInfo 推送", customerInfo)
        }
    }

    private fun loadOfferings() {
        Purchases.sharedInstance.getOfferingsWith(
            onError = { logError("offerings", it) },
            onSuccess = { result ->
                offerings = result
                val lines = result.current?.availablePackages?.joinToString("\n") { pkg ->
                    "  ${pkg.identifier} → ${pkg.platformProductIdentifier}" +
                        (pkg.platformProductPlanIdentifier?.let { ":$it" } ?: "") +
                        " / ${pkg.product?.price?.formatted ?: "商店里查不到"}"
                }
                log(
                    "offerings 成功，current=${result.currentOfferingIdentifier}\n" +
                        (lines ?: "  （没有 current offering）") +
                        "\n  Play 上查不到的商品：${result.notFoundProductIds}",
                )
            },
        )
    }

    private fun purchaseFirstPackage() {
        val target: Package? = offerings?.current?.availablePackages?.firstOrNull { it.product != null }
        if (target == null) {
            log("先点 offerings；或者 current offering 里没有任何能买的 package")
            return
        }
        log("购买 ${target.identifier}（${target.product?.id}）")
        Purchases.sharedInstance.purchaseWith(
            purchaseParams = PurchaseParams.Builder(this, target).build(),
            onError = { error, userCancelled ->
                if (userCancelled) log("用户取消了购买") else logError("purchase", error)
            },
            onSuccess = { result ->
                if (result.isPending) {
                    log("购买待处理（现金支付 / 待家长批准）：**不要发权益**，等 SDK 自动补报")
                } else {
                    logCustomerInfo("purchase", result.customerInfo)
                }
            },
        )
    }

    private fun restore() {
        Purchases.sharedInstance.restorePurchasesWith(
            onError = { logError("restore", it) },
            onSuccess = { logCustomerInfo("restore", it) },
        )
    }

    private fun sync() {
        Purchases.sharedInstance.syncPurchasesWith(
            onError = { logError("sync", it) },
            onSuccess = { logCustomerInfo("sync", it) },
        )
    }

    private fun refreshCustomerInfo() {
        Purchases.sharedInstance.getCustomerInfoWith(
            fetchPolicy = org.revdog.purchases.CacheFetchPolicy.FETCH_CURRENT,
            onError = { logError("customerInfo", it) },
            onSuccess = { logCustomerInfo("customerInfo", it) },
        )
    }

    private fun logIn() {
        val appUserID = appUserIdInput.text.toString().trim()
        if (appUserID.isEmpty()) {
            log("logIn 需要一个非空 appUserID")
            return
        }
        Purchases.sharedInstance.logInWith(
            appUserID = appUserID,
            onError = { logError("logIn", it) },
            onSuccess = { customerInfo, created ->
                log("logIn 成功（created=$created），当前身份 ${Purchases.sharedInstance.appUserID}")
                logCustomerInfo("logIn", customerInfo)
            },
        )
    }

    private fun logOut() {
        Purchases.sharedInstance.logOutWith(
            onError = { logError("logOut", it) },
            onSuccess = {
                log("logOut 成功，新匿名身份 ${Purchases.sharedInstance.appUserID}")
                logCustomerInfo("logOut", it)
            },
        )
    }

    private fun setAttributes() {
        val rejected = Purchases.sharedInstance.setAttributes(
            mapOf(
                "example_tier" to "gold",
                "example_cleared" to null,
            ),
        )
        Purchases.sharedInstance.setDisplayName("Example Tester")
        Purchases.sharedInstance.collectDeviceIdentifiers()
        Purchases.sharedInstance.syncAttributes()
        log("属性已写入并触发同步；端上被拒的键：$rejected")
    }

    // endregion

    private companion object {
        const val PADDING = 24
    }
}
