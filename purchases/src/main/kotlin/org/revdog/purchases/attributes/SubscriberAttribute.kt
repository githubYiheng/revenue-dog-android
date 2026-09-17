package org.revdog.purchases.attributes

import org.json.JSONObject
import org.revdog.purchases.common.objects
import org.revdog.purchases.common.optNullableInt
import org.revdog.purchases.common.optNullableString

// 结构对照 RC `subscriberattributes/SubscriberAttribute.kt` +
// `common/subscriberattributes/SpecialSubscriberAttributes.kt` + `subscriberattributes/backendHelpers.kt`。
//
// 契约对齐（`docs/plan/api-contract-v1.md` §2.4，服务端 `workers/api/src/attributes.ts` 与
// `receipts-shared.ts` 的 `applyInlineAttributes` 为准）：
// - 上行 `{"attributes": {<key>: {"value": string, "updated_at_ms": int64}}}`
// - **空串 = 删除**（服务端存 NULL 墓碑）。`POST /v1/receipts` 的搭车通道
//   （`applyInlineAttributes`）虽然也认 JSON `null`，但我方两条通道**统一编码成空串**，
//   与 iOS `SubscriberAttributeWire` 逐字一致 —— 免得同一个删除意图在两条路上有两种线上形状。
// - LWW 按 `updated_at_ms`（服务端 UPSERT 条件是 `excluded.updated_at_ms >= 库中值`）。

/**
 * 保留属性键（`$` 前缀）。契约 §2.4 的 35 个原文键 + 附录 A 决策 12 的归因通用键。
 *
 * 命名空间常量而不是 enum：它不是对外可扩展枚举（`ForbiddenPublicEnum` 与此无关），
 * 但仍然只有一个拼写来源 —— 键名拼错在服务端是整批 400。
 * 与 iOS `SubscriberAttributeKeys` **逐项同名同值**。
 */
internal object SubscriberAttributeKeys {

    // 契约 §2.4 保留键全集（RC 原文）
    const val DISPLAY_NAME: String = "\$displayName"
    const val APNS_TOKENS: String = "\$apnsTokens"
    const val FCM_TOKENS: String = "\$fcmTokens"
    const val ATT_CONSENT_STATUS: String = "\$attConsentStatus"
    const val CLEVERTAP_ID: String = "\$clevertapId"
    const val IDFA: String = "\$idfa"
    const val IDFV: String = "\$idfv"
    const val GPS_AD_ID: String = "\$gpsAdId"
    const val ANDROID_ID: String = "\$androidId"
    const val AMAZON_AD_ID: String = "\$amazonAdId"
    const val ADJUST_ID: String = "\$adjustId"
    const val AMPLITUDE_DEVICE_ID: String = "\$amplitudeDeviceId"
    const val AMPLITUDE_USER_ID: String = "\$amplitudeUserId"
    const val APPSFLYER_ID: String = "\$appsflyerId"
    const val BRAZE_ALIAS_NAME: String = "\$brazeAliasName"
    const val BRAZE_ALIAS_LABEL: String = "\$brazeAliasLabel"
    const val FB_ANON_ID: String = "\$fbAnonId"
    const val MPARTICLE_ID: String = "\$mparticleId"
    const val ONESIGNAL_ID: String = "\$onesignalId"
    const val AIRSHIP_CHANNEL_ID: String = "\$airshipChannelId"
    const val ITERABLE_USER_ID: String = "\$iterableUserId"
    const val ITERABLE_CAMPAIGN_ID: String = "\$iterableCampaignId"
    const val ITERABLE_TEMPLATE_ID: String = "\$iterableTemplateId"
    const val FIREBASE_APP_INSTANCE_ID: String = "\$firebaseAppInstanceId"
    const val MIXPANEL_DISTINCT_ID: String = "\$mixpanelDistinctId"
    const val KOCHAVA_DEVICE_ID: String = "\$kochavaDeviceId"
    const val TENJIN_ID: String = "\$tenjinId"
    const val IP: String = "\$ip"
    const val EMAIL: String = "\$email"
    const val PHONE_NUMBER: String = "\$phoneNumber"
    const val POSTHOG_USER_ID: String = "\$posthogUserId"
    const val DEVICE_VERSION: String = "\$deviceVersion"
    const val APPLE_REFUND_HANDLING_PREFERENCE: String = "\$appleRefundHandlingPreference"
    const val CUSTOMERIO_ID: String = "\$customerioId"
    const val APPSTACK_ID: String = "\$appstackId"

    // 归因通用键（契约附录 A 决策 12）
    const val MEDIA_SOURCE: String = "\$mediaSource"
    const val CAMPAIGN: String = "\$campaign"
    const val AD_GROUP: String = "\$adGroup"
    const val AD: String = "\$ad"
    const val KEYWORD: String = "\$keyword"
    const val CREATIVE: String = "\$creative"

    /** 全表（单测用它与 iOS / 契约对账）。 */
    val ALL: List<String> = listOf(
        DISPLAY_NAME, APNS_TOKENS, FCM_TOKENS, ATT_CONSENT_STATUS, CLEVERTAP_ID, IDFA, IDFV, GPS_AD_ID,
        ANDROID_ID, AMAZON_AD_ID, ADJUST_ID, AMPLITUDE_DEVICE_ID, AMPLITUDE_USER_ID, APPSFLYER_ID,
        BRAZE_ALIAS_NAME, BRAZE_ALIAS_LABEL, FB_ANON_ID, MPARTICLE_ID, ONESIGNAL_ID, AIRSHIP_CHANNEL_ID,
        ITERABLE_USER_ID, ITERABLE_CAMPAIGN_ID, ITERABLE_TEMPLATE_ID, FIREBASE_APP_INSTANCE_ID,
        MIXPANEL_DISTINCT_ID, KOCHAVA_DEVICE_ID, TENJIN_ID, IP, EMAIL, PHONE_NUMBER, POSTHOG_USER_ID,
        DEVICE_VERSION, APPLE_REFUND_HANDLING_PREFERENCE, CUSTOMERIO_ID, APPSTACK_ID,
        MEDIA_SOURCE, CAMPAIGN, AD_GROUP, AD, KEYWORD, CREATIVE,
    )

    /** `$` 开头即保留键（与服务端 `attributes.ts` 的判定逐字一致）。 */
    fun isReserved(key: String): Boolean = key.startsWith("$")
}

/**
 * 键 / 值约束（契约 §2.4 原文 + 服务端常量）。
 *
 * **端上先挡**的理由：服务端对非法键**整批 400**，一个拼错的自定义键会把同批的合法属性
 * 一起拖下水（iOS 侧同款前置校验）。
 */
internal object SubscriberAttributeLimits {

    /** 每客户最多 50 个**非空**自定义属性（不含保留键）。 */
    const val MAX_CUSTOM_ATTRIBUTES: Int = 50

    /** value ≤ 500 字符。 */
    const val MAX_VALUE_LENGTH: Int = 500

    /** 自定义键：字母开头、≤ 40 字符、`[A-Za-z0-9_-]`。 */
    const val MAX_KEY_LENGTH: Int = 40

    @Suppress("ReturnCount")
    fun isValidCustomKey(key: String): Boolean {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return false
        val first = key.first()
        if (!(first in 'a'..'z' || first in 'A'..'Z')) return false
        return key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    }

    /** 保留键放行（只要 `$` 后面还有内容），自定义键按规则。 */
    fun isValidKey(key: String): Boolean =
        if (SubscriberAttributeKeys.isReserved(key)) key.length > 1 else isValidCustomKey(key)
}

/**
 * 一条本地缓冲的属性。结构对照 RC `SubscriberAttribute`（`key` / `value` / `set_time` / `is_synced`），
 * 与 iOS `SubscriberAttribute` 同字段。
 *
 * [value] 为 `null` = **墓碑**（删除意图）。上行一律编码成空串。
 */
internal class SubscriberAttribute(
    val key: String,
    val value: String?,
    /** LWW 时间戳（毫秒 epoch）。上行 `updated_at_ms` 就是它。 */
    val updatedAtMs: Long,
    val isSynced: Boolean = false,
) {

    val isTombstone: Boolean get() = value == null

    fun copy(value: String? = this.value, updatedAtMs: Long = this.updatedAtMs, isSynced: Boolean = this.isSynced) =
        SubscriberAttribute(key, value, updatedAtMs, isSynced)

    fun toJson(): JSONObject = JSONObject().apply {
        put(JSON_KEY, key)
        if (value == null) put(JSON_VALUE, JSONObject.NULL) else put(JSON_VALUE, value)
        put(JSON_UPDATED_AT, updatedAtMs)
        put(JSON_IS_SYNCED, isSynced)
    }

    /** 上行形状（契约 §2.4）：墓碑 → 空串。 */
    fun toWireJson(): JSONObject = JSONObject().apply {
        put(WIRE_VALUE, value ?: "")
        put(WIRE_UPDATED_AT, updatedAtMs)
    }

    override fun toString(): String =
        "SubscriberAttribute(key=$key, tombstone=$isTombstone, updatedAtMs=$updatedAtMs, isSynced=$isSynced)"

    companion object {
        const val JSON_KEY: String = "key"
        const val JSON_VALUE: String = "value"
        const val JSON_UPDATED_AT: String = "updated_at_ms"
        const val JSON_IS_SYNCED: String = "is_synced"

        const val WIRE_VALUE: String = "value"
        const val WIRE_UPDATED_AT: String = "updated_at_ms"

        /** 解析不出来（key 缺失 / 形状不对）→ `null`，坏一条不影响其它条。 */
        fun fromJson(json: JSONObject): SubscriberAttribute? {
            val key = json.optNullableString(JSON_KEY) ?: return null
            return SubscriberAttribute(
                key = key,
                value = if (json.isNull(JSON_VALUE)) null else json.optNullableString(JSON_VALUE),
                updatedAtMs = json.optLong(JSON_UPDATED_AT),
                isSynced = json.optBoolean(JSON_IS_SYNCED),
            )
        }
    }
}

/** 上行 map（`{"attributes": {...}}` 的内层）。同键取最后一条（本地存储天然一键一条，这里只是防御）。 */
internal fun Collection<SubscriberAttribute>.toWireJson(): JSONObject = JSONObject().apply {
    forEach { put(it.key, it.toWireJson()) }
}

/**
 * 一条逐键属性错误。契约 §2.4 的 400 响应体（平铺 `{"code": 7263, "attribute_errors": [...]}`）
 * 与 `POST /v1/receipts` 的包装形状（`attributes_error_response.attribute_errors`）共用它。
 */
internal class SubscriberAttributeError(
    val keyName: String,
    val message: String,
    val backendCode: Int? = null,
) {
    override fun toString(): String = "$keyName: $message"
}

/**
 * 解析属性错误。结构对照 RC `subscriberattributes/backendHelpers.kt` 的 `getAttributeErrors()`：
 * **两种形状都认** —— 有 `attributes_error_response` 就从它里面取（不带顶层 `code`），
 * 否则从顶层取并读顶层 `code`。
 *
 * 为什么必须解析：出错的键要被标成「已同步」，否则 SDK 每次前后台切换 / 每次购买都会把
 * 同一批非法属性重传一遍（考古 §2.9 记的就是这个后果）。
 */
internal fun JSONObject?.parseAttributeErrors(): List<SubscriberAttributeError> {
    if (this == null) return emptyList()
    val wrapped = optJSONObject(ATTRIBUTES_ERROR_RESPONSE_KEY)
    val source = wrapped ?: this
    val backendCode = if (wrapped == null) optNullableInt("code")?.takeIf { it > 0 } else null
    return source.optJSONArray(ATTRIBUTE_ERRORS_KEY)
        ?.objects()
        ?.mapNotNull { entry ->
            val keyName = entry.optNullableString("key_name") ?: return@mapNotNull null
            val message = entry.optNullableString("message") ?: return@mapNotNull null
            SubscriberAttributeError(keyName, message, backendCode)
        }
        .orEmpty()
}

internal const val ATTRIBUTES_ERROR_RESPONSE_KEY: String = "attributes_error_response"
internal const val ATTRIBUTE_ERRORS_KEY: String = "attribute_errors"
