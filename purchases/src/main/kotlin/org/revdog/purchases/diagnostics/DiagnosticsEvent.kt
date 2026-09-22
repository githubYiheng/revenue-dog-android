package org.revdog.purchases.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import org.revdog.purchases.common.keysSequence
import org.revdog.purchases.common.optNullableString

/**
 * 一条诊断事件。wire 形状见 `docs/plan/sdk-diagnostics.md` §1.1，**与 iOS
 * `DiagnosticsEvent` 逐字段同名**（`id` / `ts_ms` / `app_user_id` / `seq` / `type` / `level` / `fields`）。
 *
 * 结构对照 RC `common/diagnostics/DiagnosticsEntry.kt`，但字段名一律以我方 wire 契约为准
 * （RC 是 `name` / `properties` / `app_session_id` / `timestamp`，那是它自己后端的形状）。
 *
 * 禁止进 `fields`（契约 §1.3 末段）：邮箱、姓名、设备名、任何 token / 密钥、请求 / 响应 body。
 * 错误只取 SDK 自己的 `error_code` / `error_class`，**绝不带 message**。
 */
@Suppress("LongParameterList") // 值类型：这 7 个字段就是 wire 契约 §1.1 的事件形状
internal class DiagnosticsEvent(
    /** SDK 生成的 uuid —— 服务端 `INSERT OR IGNORE` 的幂等键（重发不会翻倍）。 */
    val id: String,
    /** 设备时钟（ms epoch）。 */
    val tsMs: Long,
    /**
     * **记录那一刻**的身份（§6-2：下沉到每条事件，envelope 不再带）。
     * 一批事件可能横跨一次 logIn —— 用「发送时身份」会把匿名期事件整批算到登入后的人头上。
     */
    val appUserID: String?,
    /** 会话内单调序号（§6-5）：同毫秒的事件靠它排序。 */
    val seq: Long?,
    val type: String,
    val level: String,
    val fields: JSONObject,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_TS_MS, tsMs)
        appUserID?.let { put(KEY_APP_USER_ID, it) }
        seq?.let { put(KEY_SEQ, it) }
        put(KEY_TYPE, type)
        put(KEY_LEVEL, level)
        put(KEY_FIELDS, fields)
    }

    /** 序列化成一行 JSONL。 */
    fun toLine(): String = toJson().toString()

    companion object {
        const val KEY_ID: String = "id"
        const val KEY_TS_MS: String = "ts_ms"
        const val KEY_APP_USER_ID: String = "app_user_id"
        const val KEY_SEQ: String = "seq"
        const val KEY_TYPE: String = "type"
        const val KEY_LEVEL: String = "level"
        const val KEY_FIELDS: String = "fields"

        /** 单条序列化上限（契约 §1.3：服务端把超限的计入 `dropped`）。端上超限直接不入队。 */
        const val MAX_SERIALIZED_BYTES: Int = 2048

        /** `fields` 里字符串的长度上限（服务端也截，不拒 —— 两边同规则）。 */
        const val MAX_FIELD_STRING_LENGTH: Int = 200

        /** `type` 上限（契约 §1.3：≤ 64 字符 `[a-z_]`）。 */
        const val MAX_TYPE_LENGTH: Int = 64

        /**
         * 构造一条事件：`fields` 里的 `null` 直接丢掉（契约「fields 全部可选，缺失容忍」），
         * 字符串按 200 截断，不被允许的值类型（对象 / 集合里混了非字符串）**转成字符串**兜底
         * —— 服务端对形状不对的条目计 `dropped`，端上先规整比丢掉更有用。
         */
        @Suppress("LongParameterList")
        fun create(
            id: String,
            tsMs: Long,
            appUserID: String?,
            seq: Long?,
            type: String,
            level: String,
            fields: Map<String, Any?>,
        ): DiagnosticsEvent = DiagnosticsEvent(
            id = id,
            tsMs = tsMs,
            appUserID = appUserID,
            seq = seq,
            type = type.take(MAX_TYPE_LENGTH),
            level = level,
            fields = normalizeFields(fields),
        )

        /** `fields` 规整。抽成 internal 是为了让单测直接锁住「什么进得去、什么被截断」。 */
        fun normalizeFields(fields: Map<String, Any?>): JSONObject = JSONObject().apply {
            // 排序：同一个事件的序列化结果必须稳定，快照才能 diff（对照 iOS 的 `.sortedKeys`）。
            fields.toSortedMap().forEach { (key, raw) ->
                normalizeValue(raw)?.let { put(key, it) }
            }
        }

        private fun normalizeValue(raw: Any?): Any? = when (raw) {
            null -> null
            is Boolean, is Int, is Long, is Short, is Byte -> raw
            is Double -> if (raw.isFinite()) raw else null
            is Float -> if (raw.isFinite()) raw.toDouble() else null
            is String -> raw.take(MAX_FIELD_STRING_LENGTH)
            is Collection<*> -> JSONArray(
                raw.filterNotNull().map { it.toString().take(MAX_FIELD_STRING_LENGTH) },
            )
            // 枚举式的 `@Poko class`（ProductType / PurchaseState …）会走到这里：取它的 toString，
            // 这些类型的 toString 就是 rawValue。
            else -> raw.toString().take(MAX_FIELD_STRING_LENGTH)
        }

        /** 解析一行 JSONL。坏行（半截写入 / 手工改坏 / 缺必填键）→ `null`，调用方跳过。 */
        @Suppress("ReturnCount")
        fun fromLine(line: String): DiagnosticsEvent? {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
            val id = json.optNullableString(KEY_ID) ?: return null
            val type = json.optNullableString(KEY_TYPE) ?: return null
            val level = json.optNullableString(KEY_LEVEL) ?: return null
            if (!json.has(KEY_TS_MS)) return null
            return DiagnosticsEvent(
                id = id,
                tsMs = json.optLong(KEY_TS_MS),
                appUserID = json.optNullableString(KEY_APP_USER_ID),
                seq = if (json.has(KEY_SEQ)) json.optLong(KEY_SEQ) else null,
                type = type,
                level = level,
                fields = json.optJSONObject(KEY_FIELDS) ?: JSONObject(),
            )
        }
    }
}

/** `level` 三态（契约 §1.3）。 */
internal object DiagnosticsLevel {
    const val INFO: String = "info"
    const val WARN: String = "warn"
    const val ERROR: String = "error"
}

/**
 * `sdk_warning.code` 的闭集 —— 每一条都对应代码里一处既有的运行时告警。
 * 与 iOS `DiagnosticsWarningCode` 同名同值（同名的那几个）。
 */
internal object DiagnosticsWarningCode {
    /** 本地队列超限丢最旧。 */
    const val QUEUE_OVERFLOW: String = "queue_overflow"

    /** `Purchases.configure` 被重复调用。 */
    const val DUPLICATE_CONFIGURE: String = "duplicate_configure"

    /** 诊断上传自己失败：只在本地计数，成功之后补记一条（§6-13，绝不为诊断失败再发诊断请求）。 */
    const val DIAG_UPLOAD_FAILED: String = "diag_upload_failed"

    /** 属性同步被服务端确定性拒绝（iOS 坑 #127 同款）。 */
    const val ATTRIBUTES_REJECTED: String = "attributes_rejected"

    /** 属性在**端上**就被拒了（键名非法 / value > 500 / 超 50 上限）—— Android 专属。 */
    const val ATTRIBUTES_REJECTED_LOCALLY: String = "attributes_rejected_locally"

    /** offerings 拉取失败，回落缓存。 */
    const val OFFERINGS_CACHE_FALLBACK: String = "offerings_cache_fallback"
}

/**
 * **HTTP 状态码 → `error_class`** 的唯一映射（契约 §1.3）。
 * 与 iOS `DiagnosticsErrorClass` 逐条同口径 —— 服务端把它提成列做巡检不变式 18/19。
 *
 * `receipt_post` 与 `http_error` 直接用它；[DiagnosticsErrorFields.classify]（其余失败事件）
 * 在「拿到了状态码」那一档也**委派到这里** —— 0.1.3 起全 SDK 只有一套 `error_class` 词汇，
 * 同一个状态码在哪个事件上都给同一个答案。这四个值一个字都不许改：
 * jobs 不变式 18/19 与 admin `launch-sync` 的 failures 分组逐字依赖它们。
 */
internal object DiagnosticsErrorClass {
    const val NETWORK: String = "network"
    const val SERVER: String = "server"
    const val CLIENT: String = "client"
    const val AUTH: String = "auth"

    /** `null` 状态码 = 传输层错误（超时 / 断网）。 */
    fun from(statusCode: Int?): String = when {
        statusCode == null -> NETWORK
        statusCode == UNAUTHORIZED || statusCode == FORBIDDEN -> AUTH
        statusCode in CLIENT_ERROR_RANGE -> CLIENT
        statusCode in SERVER_ERROR_RANGE -> SERVER
        else -> CLIENT
    }

    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val CLIENT_ERROR_FIRST = 400
    private const val CLIENT_ERROR_LAST = 499
    private const val SERVER_ERROR_FIRST = 500
    private const val SERVER_ERROR_LAST = 599
    private val CLIENT_ERROR_RANGE = CLIENT_ERROR_FIRST..CLIENT_ERROR_LAST
    private val SERVER_ERROR_RANGE = SERVER_ERROR_FIRST..SERVER_ERROR_LAST
}

/**
 * `level` 推导表。**唯一判定点**（见 [DiagnosticsTracker] 文件头解释为什么不在调用点传 level）。
 *
 * 规则（与 iOS 各 `record` 调用点的 level 逐条对齐）：
 * - `sdk_warning` → warn；
 * - 带 `error_code` 字段 → error（`purchase_result{outcome=cancelled}` 除外：用户取消不是错误）；
 * - `receipt_post{outcome != success}` → error（付款相关失败必须最高优先级到后端）；
 * - `consume_decision{decision=missing_should_consume}` → error（契约违规，决策 B）；
 * - `consume_decision{decision=ack_self_protect}` / `purchase_pending` → warn
 *   （「钱扣了还没确认」「钱没扣但有笔在等」都是要盯的堆积信号，对位 iOS 的
 *   `finish_decision{kept}` 是 warn）；
 * - `billing_connection` / `billing_query` / `billing_purchase_update` 里 `response_code != OK` → warn；
 * - 其余 → info。
 */
internal object DiagnosticsLevels {

    private const val BILLING_OK = "OK"

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun levelFor(name: String, fields: Map<String, Any?>): String {
        if (name == DiagnosticsTracker.EVENT_SDK_WARNING) return DiagnosticsLevel.WARN
        if (name == DiagnosticsTracker.EVENT_HTTP_ERROR) return DiagnosticsLevel.ERROR

        val outcome = fields["outcome"] as? String
        if (name == DiagnosticsTracker.EVENT_RECEIPT_POST) {
            return if (outcome == DiagnosticsTracker.OUTCOME_SUCCESS) {
                DiagnosticsLevel.INFO
            } else {
                DiagnosticsLevel.ERROR
            }
        }
        if (name == DiagnosticsTracker.EVENT_PURCHASE_PENDING) return DiagnosticsLevel.WARN
        if (name == DiagnosticsTracker.EVENT_CONSUME_DECISION) {
            return when (fields["decision"] as? String) {
                DECISION_MISSING_SHOULD_CONSUME -> DiagnosticsLevel.ERROR
                DECISION_ACK_SELF_PROTECT, DECISION_SKIPPED_NOT_PURCHASED -> DiagnosticsLevel.WARN
                else -> DiagnosticsLevel.INFO
            }
        }
        if (name in BILLING_EVENTS) {
            val responseCode = fields["response_code"] as? String
            if (responseCode != null && responseCode != BILLING_OK) return DiagnosticsLevel.WARN
        }
        if (outcome == DiagnosticsTracker.OUTCOME_CANCELLED) return DiagnosticsLevel.INFO
        if (fields["error_code"] != null) return DiagnosticsLevel.ERROR
        return DiagnosticsLevel.INFO
    }

    private val BILLING_EVENTS = setOf(
        DiagnosticsTracker.EVENT_BILLING_CONNECTION,
        DiagnosticsTracker.EVENT_BILLING_QUERY,
        DiagnosticsTracker.EVENT_BILLING_PURCHASE_UPDATE,
    )

    /**
     * `consume_decision.decision` 里需要参与 level 判定的两个取值。
     * 值的真相源是 `BillingWrapper` 的同名常量 —— 这里重复声明是为了不让 diagnostics 包
     * 反向依赖 google 包（单测锁住两处一致）。
     */
    const val DECISION_MISSING_SHOULD_CONSUME: String = "missing_should_consume"
    const val DECISION_SKIPPED_NOT_PURCHASED: String = "skipped_not_purchased"
    const val DECISION_ACK_SELF_PROTECT: String = "ack_self_protect"
}

/** 一批事件的信封（契约 §1.1 + §6-5）。与 iOS `DiagnosticsUploadBody` 逐字段同名。 */
@Suppress("LongParameterList") // 值类型：这 7 个字段就是信封形状
internal class DiagnosticsEnvelope(
    val installID: String,
    val sessionID: String,
    val sandbox: Boolean,
    val isDebug: Boolean,
    val locale: String?,
    val sentAtMs: Long,
    val events: List<DiagnosticsEvent>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_INSTALL_ID, installID)
        put(KEY_SESSION_ID, sessionID)
        put(KEY_SANDBOX, sandbox)
        put(KEY_IS_DEBUG, isDebug)
        locale?.let { put(KEY_LOCALE, it) }
        put(KEY_SENT_AT_MS, sentAtMs)
        put(KEY_EVENTS, JSONArray(events.map { it.toJson() }))
    }

    companion object {
        /** wire 契约版本（§6-5）。 */
        const val SCHEMA_VERSION: Int = 1

        const val KEY_SCHEMA_VERSION: String = "schema_version"
        const val KEY_INSTALL_ID: String = "install_id"
        const val KEY_SESSION_ID: String = "session_id"
        const val KEY_SANDBOX: String = "sandbox"
        const val KEY_IS_DEBUG: String = "is_debug"
        const val KEY_LOCALE: String = "locale"
        const val KEY_SENT_AT_MS: String = "sent_at_ms"
        const val KEY_EVENTS: String = "events"

        /**
         * `storefront` **不发**（偏离 iOS）：那是 StoreKit `Storefront.countryCode`，
         * Play 侧没有对等物（真实 environment / 国家由服务端按 Play 订单判定，设计 §5）。
         * 服务端该列会是 NULL，契约允许。
         */
        const val STOREFRONT_NOT_APPLICABLE: String = "android"
    }
}

/** `202` 响应（契约 §1.2 + §6-10）。与 iOS `DiagnosticsUploadResponse` 同字段。 */
internal class DiagnosticsUploadResponse(
    val accepted: Int?,
    val dropped: Int?,
    val sampleRateInfo: Double?,
    val backoffMs: Long?,
    val disableUntilMs: Long?,
) {
    companion object {
        fun fromJson(json: JSONObject?): DiagnosticsUploadResponse {
            if (json == null) return DiagnosticsUploadResponse(null, null, null, null, null)
            return DiagnosticsUploadResponse(
                accepted = if (json.has("accepted")) json.optInt("accepted") else null,
                dropped = if (json.has("dropped")) json.optInt("dropped") else null,
                sampleRateInfo = if (json.has("sample_rate_info")) json.optDouble("sample_rate_info") else null,
                backoffMs = if (json.has("backoff_ms")) json.optLong("backoff_ms") else null,
                disableUntilMs = if (json.has("disable_until_ms")) json.optLong("disable_until_ms") else null,
            )
        }
    }
}

/** `JSONObject` → `Map`（只在测试断言与 level 推导里用，保持纯）。 */
internal fun JSONObject.toFieldMap(): Map<String, Any?> =
    keysSequence().associateWith { key -> if (isNull(key)) null else opt(key) }
