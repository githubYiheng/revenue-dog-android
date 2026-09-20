package org.revdog.example

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.revdog.purchases.PurchaseParams
import org.revdog.purchases.Purchases
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.ReplacementMode
import org.revdog.purchases.Store
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.getCustomerInfoWith
import org.revdog.purchases.getOfferingsWith
import org.revdog.purchases.logInWith
import org.revdog.purchases.logOutWith
import org.revdog.purchases.offerings.Offering
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

    /** 逐 package 购买按钮的容器。每次 offerings 成功都**整片重建**（`removeAllViews`），不累加。 */
    private lateinit var packageButtons: LinearLayout

    /** 替换模式选择器：第 0 项是「不替换」，其后按 [ReplacementMode.ALL] 的顺序一一对应。 */
    private lateinit var replacementModeSpinner: Spinner

    /** 上一次拉到的 offerings，purchase 从这里取第一个可买的 package。 */
    private var offerings: Offerings? = null

    /**
     * 最近一次 customerInfo（所有回调都经 [logCustomerInfo] 落到这里）。
     * 替换购买要的「被替换的旧订阅」只从它取 —— 取不到就写明并按全新购买走，**不猜**。
     */
    private var lastCustomerInfo: CustomerInfo? = null

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

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        appUserIdInput = EditText(this).apply {
            hint = "appUserID（logIn 用）"
            setText(BuildConfig.REVENUEDOG_APP_USER_ID)
        }
        controls.addView(appUserIdInput)

        controls.addView(button("offerings（拉取并列出 package）") { loadOfferings() })
        controls.addView(label("替换模式（D6；选了才带 oldProductId + replacementMode）"))
        replacementModeSpinner = replacementModeSpinner()
        controls.addView(replacementModeSpinner)
        controls.addView(button("purchase（买 current offering 的第一个 package）") { purchaseFirstPackage() })
        packageButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(packageButtons)
        renderPackageButtons(null)
        controls.addView(button("restore（恢复购买）") { restore() })
        controls.addView(button("sync（只上报，不碰 Billing）") { sync() })
        controls.addView(button("customerInfo（强制联网刷一次）") { refreshCustomerInfo() })
        controls.addView(button("logIn") { logIn() })
        controls.addView(button("logOut") { logOut() })
        controls.addView(button("设置订阅者属性 + 立即同步") { setAttributes() })
        controls.addView(diagnosticsToggle())

        // 控件区与日志区各占半屏、各自滚动：逐 package 按钮一来，按钮总数就超出一屏了。
        root.addView(halfScreen(controls))

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        root.addView(halfScreen(logView))
        return root
    }

    private fun halfScreen(content: View): ScrollView = ScrollView(this).apply {
        addView(content)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
        ).apply { weight = 1f }
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun label(text: String): TextView = TextView(this).apply { this.text = text }

    /**
     * 选项 = 「不替换（全新购买）」+ [ReplacementMode.ALL]（SDK 公开支持的全部替换模式）。
     * 默认停在第 0 项：**不选就是全新购买**，绝不静默带上替换参数。
     */
    private fun replacementModeSpinner(): Spinner = Spinner(this).apply {
        val items = listOf(NO_REPLACEMENT) + ReplacementMode.ALL.map { it.name }
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(0)
    }

    /** 选中的替换模式；`null` = 第 0 项「不替换」。 */
    private fun selectedReplacementMode(): ReplacementMode? =
        ReplacementMode.ALL.getOrNull(replacementModeSpinner.selectedItemPosition - 1)

    /**
     * 逐 package 按钮区（D3 要买一次性商品、D6 要换 base plan，只有「买第一个 package」都做不到）。
     * Play 上查不到的 package 也渲染出来，但置灰并写明原因 —— 比「按钮凭空少一个」好排查。
     */
    private fun renderPackageButtons(offering: Offering?) = runOnUiThread {
        packageButtons.removeAllViews()
        val packages = offering?.availablePackages.orEmpty()
        if (packages.isEmpty()) {
            packageButtons.addView(label("（还没拉到 current offering 的 package —— 先点 offerings）"))
        }
        packages.forEach { pkg ->
            val productId = pkg.platformProductIdentifier +
                (pkg.platformProductPlanIdentifier?.let { ":$it" } ?: "")
            val product = pkg.product
            if (product == null) {
                packageButtons.addView(
                    button("${pkg.identifier} / $productId —— 商店里查不到") { }
                        .apply { isEnabled = false },
                )
            } else {
                packageButtons.addView(
                    button("买 ${pkg.identifier} / $productId / ${product.price.formatted}") { purchase(pkg) },
                )
            }
        }
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
        // 所有拿到 customerInfo 的路径（含 listener 推送）都走这里，替换购买读的就是这一份。
        lastCustomerInfo = customerInfo
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
                renderPackageButtons(result.current)
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
        purchase(target)
    }

    private fun purchase(target: Package) {
        val builder = PurchaseParams.Builder(this, target)
        applyReplacement(builder)
        log("购买 ${target.identifier}（${target.product?.id}）")
        Purchases.sharedInstance.purchaseWith(
            purchaseParams = builder.build(),
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

    /**
     * 选了替换模式、且手上有一笔活跃的 Play 订阅时，才把
     * `oldProductId` + `replacementMode` 带上（D6）。
     *
     * 旧订阅**只**从最近一次 customerInfo 取。取不到就在日志里写明原因并按全新购买走 ——
     * 拿 offerings 里的某个订阅顶上是猜，猜错的后果是给用户换错套餐。
     */
    private fun applyReplacement(builder: PurchaseParams.Builder) {
        val mode = selectedReplacementMode() ?: return
        val oldProductId = replacedSubscriptionId(mode) ?: return
        builder.oldProductId(oldProductId).replacementMode(mode)
        log("替换购买：旧订阅 $oldProductId，模式 ${mode.name}")
    }

    /** 被替换的旧订阅 productId；拿不到时返回 `null` 并把原因写进日志区。 */
    private fun replacedSubscriptionId(mode: ReplacementMode): String? {
        val info = lastCustomerInfo
        // activeSubscriptions 的每一项必定是 subscriptions 的一个 key（对不上商品的 entitlement
        // 会被 SDK 整条丢弃），所以能直接查 store，只取 Play 商店的那笔。实测我方后端给的是
        // `productId:basePlanId` 形态 —— PurchaseParams 内部会剥 `:basePlanId`，原样传即可。
        val oldProductId = info?.let { snapshot ->
            snapshot.activeSubscriptions.firstOrNull { snapshot.subscriptions[it]?.store == Store.PLAY_STORE }
        }
        if (oldProductId == null) {
            val why = if (info == null) {
                "还没有任何 customerInfo（先点一次 customerInfo）"
            } else {
                "最近一次 customerInfo 里没有活跃的 Play 订阅" +
                    "（activeSubscriptions=${info.activeSubscriptions}，subscriptions=${info.subscriptions.keys}）"
            }
            log("选了 ${mode.name} 但拿不到被替换的旧订阅：$why —— 按全新购买走")
        }
        return oldProductId
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
        const val NO_REPLACEMENT = "不替换（全新购买）"
    }
}
