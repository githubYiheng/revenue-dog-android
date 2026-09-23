package org.revdog.purchases.diagnostics

import org.revdog.purchases.LogLevel
import org.revdog.purchases.Logger

/**
 * 客户端诊断的记录面。M3 起真实实现是 [DiagnosticsRecorder]（JSONL 队列 + 攒批上传 + 采样 + 退避），
 * [NoOpDiagnosticsTracker] 只在 `diagnosticsEnabled = false` 时用。
 *
 * **接口与事件名在 M2 就钉死了，M3 只换实现、不改调用点**（`PurchaseDiagnosticsTest` 锁住）：
 * 记录点分散在编排、网络、Billing 三层，换管线时不该去动那些地方。
 *
 * 事件的 `level` **不在调用点传**，由 [DiagnosticsLevels.levelFor] 按 `(name, fields)` 推导
 * （偏离 iOS：那边每个 `record` 调用显式带 level）。这么做的唯一理由就是上面那条铁律 ——
 * 给 [track] 加参数会改掉 M2 已经锁死的全部调用点。推导规则集中在一处，单测直接锁它。
 *
 * wire 契约与 iOS 共用 `docs/plan/sdk-diagnostics.md`；Android 额外的 Billing 事件见设计 §2。
 *
 * 纪律（与 iOS 逐字一致）：**`error_code` 永远是字符串、永远不带 message**；
 * purchaseToken / apiKey / 邮箱姓名等一律不进字段；`app_user_id` 由管线逐条填
 * （**记录那一刻**的身份，`sdk-diagnostics.md` §6-2），调用点不传。
 */
internal interface DiagnosticsTracker {

    fun track(name: String, properties: Map<String, Any?> = emptyMap())

    /**
     * `sdk_warning` 的统一入口（对照 iOS `DiagnosticsRecorder.warn`）。
     *
     * 有默认实现，所以加它不会动到任何既有实现类。
     */
    fun warn(code: String, detail: String? = null) {
        track(EVENT_SDK_WARNING, mapOf("code" to code, "detail" to detail))
    }

    companion object {
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

        // M2 购买闭环的打点。M3 只换实现（真实队列 + 攒批上传），事件名与字段不再动。
        const val EVENT_PURCHASE_STARTED: String = "purchase_started"
        const val EVENT_PURCHASE_RESULT: String = "purchase_result"
        const val EVENT_RECEIPT_POST: String = "receipt_post"
        const val EVENT_PURCHASE_PENDING: String = "purchase_pending"
        /**
         * `restore` / `sync`（**M4 改名**：M2/M3 期间叫 `restore_purchases` / `sync_purchases`）。
         *
         * 改名的理由只有一条：`sdk-diagnostics.md` §1.3 的契约名与 iOS 发的都是 `restore` / `sync`，
         * 两端事件名不一致会让 admin 的时间线与巡检聚合按平台分裂 —— 同一件事在后台要能用同一个
         * `type` 查出来。首个 tag 之前改掉，不留兼容别名（线上没有历史数据要兼容）。
         */
        const val EVENT_RESTORE: String = "restore"
        const val EVENT_SYNC: String = "sync"

        // M3 新增。
        /** 契约 §1.3 的 `sdk_warning`（`code` 取值见 [DiagnosticsWarningCode]）。 */
        const val EVENT_SDK_WARNING: String = "sdk_warning"

        /** 属性同步一次尝试的结束（Android 专属；iOS 那边只有 `sdk_warning{attributes_rejected}`）。 */
        const val EVENT_ATTRIBUTES_SYNC: String = "attributes_sync"

        /** [EVENT_PURCHASE_RESULT] 的 outcome 取值。 */
        const val OUTCOME_COMPLETED: String = "completed"
        const val OUTCOME_PENDING: String = "pending"
        const val OUTCOME_CANCELLED: String = "cancelled"
        const val OUTCOME_FAILED: String = "failed"

        /** [EVENT_RECEIPT_POST] 的 outcome 取值（与 901 / 902 的分类一一对应）。 */
        const val OUTCOME_SUCCESS: String = "success"
        const val OUTCOME_RETRYABLE: String = "retryable"
        const val OUTCOME_REJECTED: String = "rejected"
    }
}

/**
 * `diagnosticsEnabled = false` 时的实现：**什么都不做**。
 *
 * 不是空壳 —— 它把事件降级成 VERBOSE 日志，这样关掉诊断之后在真机上排障仍然看得到记录点，
 * 而 release 构建里被日志级别过滤掉，**零 I/O、零网络**。
 */
internal object NoOpDiagnosticsTracker : DiagnosticsTracker {
    override fun track(name: String, properties: Map<String, Any?>) {
        Logger.log(LogLevel.VERBOSE) { "diagnostics[$name] $properties" }
    }
}

/**
 * 外部（混合框架插件）记的事件名必须满足契约硬限 `type` ≤ 64 字符 `[a-z_]`
 * （`docs/plan/sdk-diagnostics.md` §1.3，[DiagnosticsEvent.MAX_TYPE_LENGTH]）。
 */
private val EXTERNAL_EVENT_NAME_REGEX = Regex("^[a-z_]{1,${DiagnosticsEvent.MAX_TYPE_LENGTH}}$")

/**
 * 混合框架插件记诊断的唯一入口（`Purchases.recordDiagnosticsEvent`，主代理裁定 10；
 * 对照 iOS `@_spi` 的 `recordEvent`）。
 *
 * - 事件名不合契约 → `Logger.warn` 并**丢弃**，不抛：插件的一条坏打点不该变成宿主的崩溃；
 * - 合规 → 原样交给 [DiagnosticsTracker.track]：字段截断、`null` 剔除、level 推导
 *   （[DiagnosticsLevels.levelFor]，未知事件名按既有兜底规则）全在既有管线里，这里不重复；
 * - 诊断关闭时 tracker 本身就是 no-op（`DiagnosticsRecorder.track` 的 `enabled` 守卫）。
 */
internal fun DiagnosticsTracker.recordExternalEvent(name: String, properties: Map<String, Any?>) {
    if (!EXTERNAL_EVENT_NAME_REGEX.matches(name)) {
        Logger.warn { "诊断事件名不合契约（要求 ^[a-z_]{1,64}$），已丢弃：$name" }
        return
    }
    track(name, properties)
}

/** 混合框架插件记 `sdk_warning{code, detail}`（`Purchases.recordDiagnosticsWarning`）。 */
internal fun DiagnosticsTracker.recordExternalWarning(code: String, detail: String?) {
    warn(code, detail)
}
