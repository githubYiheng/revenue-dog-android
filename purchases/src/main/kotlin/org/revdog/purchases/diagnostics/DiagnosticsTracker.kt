package org.revdog.purchases.diagnostics

import org.revdog.purchases.LogLevel
import org.revdog.purchases.Logger

/**
 * 客户端诊断的记录面。**M1 只有接口 + no-op 实现**，管线（JSONL 队列 / 攒批上传 /
 * 采样 / 退避）是 M3（设计 §9 里程碑 3）。
 *
 * 现在就把接口钉下来的理由：记录点分散在编排、网络、Billing 三层，
 * M3 补管线时不该再去改那些调用点。
 *
 * wire 契约与 iOS 共用 `docs/plan/sdk-diagnostics.md`；Android 额外的 Billing 事件见设计 §2：
 * `billing_connection` · `billing_query` · `billing_purchase_update` · `consume_decision`。
 *
 * 纪律（与 iOS 逐字一致）：**`error_code` 永远是字符串、永远不带 message**；
 * `app_user_id` 这类可能是宿主 uid 的值不进字段。
 */
internal interface DiagnosticsTracker {

    fun track(name: String, properties: Map<String, Any?> = emptyMap())

    companion object {
        // M3 会用到的事件名，先钉在这里免得两端各起一套。
        const val EVENT_SDK_CONFIGURED: String = "sdk_configured"
        const val EVENT_DUPLICATE_CONFIGURE: String = "duplicate_configure"
        const val EVENT_IDENTITY_LOGIN: String = "identity_login"
        const val EVENT_IDENTITY_LOGOUT: String = "identity_logout"
        const val EVENT_CUSTOMER_INFO_FETCH: String = "customer_info_fetch"
        const val EVENT_OFFERINGS_FETCH: String = "offerings_fetch"
        const val EVENT_HTTP_ERROR: String = "http_error"

        /** Android 专属：Play Billing 链路（考古 §7.5「必须补」）。 */
        const val EVENT_BILLING_CONNECTION: String = "billing_connection"
        const val EVENT_BILLING_QUERY: String = "billing_query"
        const val EVENT_BILLING_PURCHASE_UPDATE: String = "billing_purchase_update"
        const val EVENT_CONSUME_DECISION: String = "consume_decision"
    }
}

/**
 * `diagnosticsEnabled = false`、或 M1 期间的实现：**什么都不做**。
 *
 * 不是空壳 —— 它把事件降级成 VERBOSE 日志，这样 M1 期间在真机上排障仍然看得到记录点，
 * 而 release 构建里被日志级别过滤掉，零开销。
 */
internal object NoOpDiagnosticsTracker : DiagnosticsTracker {
    override fun track(name: String, properties: Map<String, Any?>) {
        Logger.log(LogLevel.VERBOSE) { "diagnostics[$name] $properties" }
    }
}
