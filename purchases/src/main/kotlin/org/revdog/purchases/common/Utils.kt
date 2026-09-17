package org.revdog.purchases.common

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 结构对照 RC `common/utils.kt` + `utils/`。

/**
 * **每次都新建 `MessageDigest`，这是故意的**（坑 37）：`MessageDigest` 不是线程安全的，
 * 共享实例在两个线程同时摘要时会把内部状态搅坏。
 */
private fun String.hashWith(algorithm: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }

/** purchaseToken 的本地台账 key（考古 §2.2：RC 用 `sha1(token)`）。 */
internal fun String.sha1(): String = hashWith("SHA-1")

internal fun String.sha256Hex(): String = hashWith("SHA-256")

internal fun <K, V> Map<K, V?>.filterNotNullValues(): Map<K, V> =
    mutableMapOf<K, V>().also { new -> forEach { (k, v) -> if (v != null) new[k] = v } }

internal fun isAndroidNOrNewer(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

// region JSON 容错读取
//
// 坑 42：下行的一切枚举与可选字段解析都必须容错 —— 后端加值不能摔老 SDK。

internal fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotEmpty() } else null

internal fun JSONObject.optNullableInt(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null

/** ISO 8601 → [Date]；缺失 / null / 解析不了一律 `null`，**不抛**。 */
internal fun JSONObject.optDate(name: String): Date? = optNullableString(name)?.let { Iso8601Utils.parseOrNull(it) }

internal fun JSONObject.keysSequence(): Sequence<String> = keys().asSequence()

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

// endregion

/**
 * ISO 8601 解析。
 *
 * **偏离 RC**：RC 抄了 Gson 的 `ISO8601Utils`（282 行 Java），因为它 minSdk 23 要支持一堆变体。
 * 我方契约 §1.7 把下行日期格式**冻结**成两种形状（秒精度 `…Z` 与毫秒精度 `…Z`），
 * 所以用两个固定的 `SimpleDateFormat` 就够，不背那 282 行。
 * 解析失败返回 `null` 而不是抛：一个坏日期不该让整个 CustomerInfo 解不出来。
 */
internal object Iso8601Utils {

    private const val SECONDS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    private const val MILLIS_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    private val formats = ThreadLocal.withInitial {
        listOf(MILLIS_PATTERN, SECONDS_PATTERN).map { pattern ->
            SimpleDateFormat(pattern, Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        }
    }

    fun parseOrNull(value: String): Date? {
        val candidate = value.trim().removeSuffix("+00:00").let { if (it.endsWith("Z")) it else it + "Z" }
        formats.get()?.forEach { format ->
            val position = ParsePosition(0)
            val parsed = format.parse(candidate, position)
            if (parsed != null && position.index == candidate.length) return parsed
        }
        return null
    }

    fun format(date: Date): String =
        SimpleDateFormat(SECONDS_PATTERN, Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)
}

/** 可注入的「现在」。单测里把时间钉死，才谈得上测 grace 与 TTL。 */
internal fun interface DateProvider {
    fun now(): Date
}

internal class DefaultDateProvider : DateProvider {
    override fun now(): Date = Date()
}

/**
 * 一次 CustomerInfo / offerings 交付的**来源**。
 *
 * M4 新增：`customer_info_fetch.cache_hit` / `offerings_fetch.cache_hit`（契约 §1.3）只有 Manager
 * 层知道 —— 缓存命中时根本没有发过请求，编排层看到的只是一个成功回调，分辨不出来。
 * M3 期间因此少发了这个字段（`PurchasesOrchestrator.trackFetch` 的「待核实 / M4 补」）；
 * 现在由两个 Manager 在交付时一并带上来源，编排层只做翻译。
 *
 * 三态而不是一个 Boolean：**「后端挂了回落缓存」与「正常命中缓存」在排障时完全是两件事** ——
 * 前者说明这台设备此刻拿到的是过期数据（offerings 会额外记一条
 * `sdk_warning{offerings_cache_fallback}`），后者只是省了一次请求。
 */
internal enum class DeliveryOrigin {
    /** 本次是真的打了后端并成功。 */
    NETWORK,

    /** 命中未过期（或过期但按策略先交付）的缓存。 */
    CACHE,

    /** 后端失败，回落到盘上/内存里的旧数据（stale）。 */
    STALE_FALLBACK,
    ;

    /** 契约 §1.3 的 `cache_hit`：只要不是刚从网络拿的，就算命中缓存。 */
    val cacheHit: Boolean get() = this != NETWORK
}
