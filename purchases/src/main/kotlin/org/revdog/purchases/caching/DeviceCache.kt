package org.revdog.purchases.caching

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.InternalRevenueDogAPI
import org.revdog.purchases.Logger
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.sha1
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import java.util.Date

/**
 * 一个 SharedPreferences 打天下。结构对照 RC `common/caching/DeviceCache.kt`。
 *
 * 纪律：
 * - **prefs 文件名带版本号**（`…_v1`）。RC 是被多 MB ETag payload 撑爆堆之后才加的
 *   （坑 26），我方从第一天就有。
 * - CustomerInfo 缓存条目带 `schema_version`，**版本不符直接当未命中**（考古 §5.2）。
 * - 缓存的 offerings 存**原始响应字符串**，不是重新序列化的 JSONObject（坑 31：
 *   重新序列化会要一块好几倍响应大小的连续内存，低堆设备 OOM）。
 * - key 里带 apiKey 前缀：同一个宿主接多个 app 时互不串味。
 */
@Suppress("TooManyFunctions")
internal class DeviceCache(
    private val preferences: SharedPreferences,
    apiKey: String,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    constructor(context: Context, apiKey: String) : this(
        context.getSharedPreferences("${context.packageName}$PREFERENCES_FILE_SUFFIX", Context.MODE_PRIVATE),
        apiKey,
    )

    private val apiKeyPrefix: String = "$SHARED_PREFERENCES_PREFIX$apiKey"

    @VisibleForTesting
    internal val appUserIDCacheKey: String = "$apiKeyPrefix.appUserID"

    @VisibleForTesting
    internal val tokensCacheKey: String = "$apiKeyPrefix.tokens"

    private val offeringsResponseCacheKey: String = "$apiKeyPrefix.offeringsResponse"
    private val offeringsLastUpdatedCacheKey: String = "$apiKeyPrefix.offeringsLastUpdated"
    private val customerInfoLastUpdatedBaseKey: String = "$apiKeyPrefix.customerInfoLastUpdated"

    // region appUserID

    fun getCachedAppUserID(): String? = preferences.getString(appUserIDCacheKey, null)

    fun cacheAppUserID(appUserID: String) {
        preferences.edit().putString(appUserIDCacheKey, appUserID).apply()
    }

    /**
     * 身份切换时清掉旧身份的一切痕迹（对照 RC `clearCachesForAppUserID`）。
     * **ETag 不在这里清** —— 那是 `Backend.clearCaches()` 的事（考古 §6.4），
     * 漏了它新匿名用户会命中上一个用户的 304。
     */
    fun clearCachesForAppUserID(appUserID: String) {
        preferences.edit()
            .remove(customerInfoCacheKey(appUserID))
            .remove(customerInfoLastUpdatedCacheKey(appUserID))
            .remove(offeringsResponseCacheKey)
            .remove(offeringsLastUpdatedCacheKey)
            .apply()
        clearOfferingsMemoryCache()
    }

    // endregion

    // region CustomerInfo

    @VisibleForTesting
    internal fun customerInfoCacheKey(appUserID: String): String = "$apiKeyPrefix.customerInfo.$appUserID"

    @VisibleForTesting
    internal fun customerInfoLastUpdatedCacheKey(appUserID: String): String =
        "$customerInfoLastUpdatedBaseKey.$appUserID"

    @OptIn(InternalRevenueDogAPI::class)
    @Suppress("ReturnCount")
    fun getCachedCustomerInfo(appUserID: String): CustomerInfo? {
        val json = preferences.getString(customerInfoCacheKey(appUserID), null) ?: return null
        return try {
            val cached = JSONObject(json)
            val schemaVersion = cached.optInt(CUSTOMER_INFO_SCHEMA_VERSION_KEY, -1)
            if (schemaVersion != CUSTOMER_INFO_SCHEMA_VERSION) {
                // 格式演进的唯一安全做法：版本不符当没缓存，重新拉一次。
                Logger.debug { "CustomerInfo 缓存 schema_version=$schemaVersion 与当前不符，视为未命中" }
                return null
            }
            val requestDate = cached.optLong(CUSTOMER_INFO_REQUEST_DATE_KEY).takeIf { it > 0 }?.let { Date(it) }
            // 元字段是我方塞进去的，解析前先摘掉，别让它们混进 rawData。
            cached.remove(CUSTOMER_INFO_SCHEMA_VERSION_KEY)
            cached.remove(CUSTOMER_INFO_REQUEST_DATE_KEY)
            CustomerInfoFactory.buildCustomerInfo(cached, requestDate, loadedFromCache = true)
        } catch (@Suppress("SwallowedException") e: JSONException) {
            Logger.warn { "CustomerInfo 缓存解析失败，视为未命中" }
            null
        }
    }

    @OptIn(InternalRevenueDogAPI::class)
    @Synchronized
    fun cacheCustomerInfo(appUserID: String, info: CustomerInfo) {
        val jsonObject = info.rawData.also {
            it.put(CUSTOMER_INFO_SCHEMA_VERSION_KEY, CUSTOMER_INFO_SCHEMA_VERSION)
            it.put(CUSTOMER_INFO_REQUEST_DATE_KEY, info.requestDate.time)
        }
        preferences.edit()
            .putString(customerInfoCacheKey(appUserID), jsonObject.toString())
            .putLong(customerInfoLastUpdatedCacheKey(appUserID), dateProvider.now().time)
            .apply()
    }

    fun getCustomerInfoCachesLastUpdated(appUserID: String): Date? =
        preferences.getLong(customerInfoLastUpdatedCacheKey(appUserID), 0L).takeIf { it > 0 }?.let { Date(it) }

    /**
     * `invalidateCustomerInfoCache()` 的落点：把时间戳抹掉即可，缓存内容留着
     * （离线时 `CACHED_OR_FETCHED` 仍然能供出 stale 数据，与 iOS「失效代」同效果）。
     */
    fun clearCustomerInfoCacheTimestamp(appUserID: String) {
        preferences.edit().remove(customerInfoLastUpdatedCacheKey(appUserID)).apply()
    }

    /**
     * 反直觉但必须（对照 RC `getCustomerInfoFetchOnly`）：**先把时间戳设成现在，再发请求**。
     * 这样并发调用看到「缓存是新的」就不会重复发起；请求失败时再抹掉时间戳。
     */
    fun setCustomerInfoCacheTimestampToNow(appUserID: String) {
        preferences.edit()
            .putLong(customerInfoLastUpdatedCacheKey(appUserID), dateProvider.now().time)
            .apply()
    }

    // endregion

    // region offerings

    /** 磁盘上存原始响应串（坑 31）。 */
    @Synchronized
    fun cacheOfferingsResponse(response: JSONObject) {
        preferences.edit()
            .putString(offeringsResponseCacheKey, response.toString())
            .putLong(offeringsLastUpdatedCacheKey, dateProvider.now().time)
            .apply()
    }

    @Synchronized
    fun getCachedOfferingsResponse(): JSONObject? =
        preferences.getString(offeringsResponseCacheKey, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

    fun getOfferingsCachesLastUpdated(): Date? =
        preferences.getLong(offeringsLastUpdatedCacheKey, 0L).takeIf { it > 0 }?.let { Date(it) }

    /**
     * offerings 的**内存**缓存：磁盘上只存后端下发的那份 JSON（不冻价格），
     * 填好 ProductDetails 的成品只活在内存里（与 iOS「缓存的 offerings 在读取时补齐」同策略）。
     */
    @Volatile
    private var offeringsMemoryCache: Any? = null

    @Synchronized
    fun <T> getOfferingsMemoryCache(): T? {
        @Suppress("UNCHECKED_CAST")
        return offeringsMemoryCache as? T
    }

    @Synchronized
    fun cacheOfferingsInMemory(offerings: Any) {
        offeringsMemoryCache = offerings
    }

    @Synchronized
    fun clearOfferingsMemoryCache() {
        offeringsMemoryCache = null
    }

    // endregion

    // region token 台账（M2 用，M1 先把存储 API 建好）
    //
    // 台账才是 Android 版的 `finish()`（考古 §3.6）：key = `sha1(purchaseToken)`，
    // value 记 `isAutoRenewing`。后者是**侦测「在 Play 商店外取消订阅」的唯一手段**（坑 23）：
    // queryPurchases 回来的 isAutoRenewing 与台账不符 → 重报这一笔让后端去 Google 复核，
    // 而不是跑一次把所有交易都以 RESTORE 重报的 syncPurchases。

    @Synchronized
    fun getPreviouslySentHashedTokens(): Set<String> = getTokenMap().keys

    @Synchronized
    fun addSuccessfullyPostedToken(token: String, isAutoRenewing: Boolean? = null) {
        val hashedToken = token.sha1()
        val current = getTokenMap().toMutableMap()
        val existing = current[hashedToken]
        when {
            existing == null -> {
                current[hashedToken] = TokenEntry(isAutoRenewing)
                saveTokenMap(current)
            }
            isAutoRenewing != null && existing.isAutoRenewing != isAutoRenewing -> {
                current[hashedToken] = TokenEntry(isAutoRenewing)
                saveTokenMap(current)
            }
        }
    }

    /** 清掉已不活跃的台账条目（已消耗的 inapp、已过期的订阅）。 */
    @Synchronized
    fun cleanPreviouslySentTokens(hashedTokens: Set<String>) {
        saveTokenMap(getTokenMap().filterKeys { it in hashedTokens })
    }

    /** 台账里没有的活跃购买 = 需要补报的（考古 §5.5 链路 1）。 */
    @Synchronized
    fun hashedTokensNotInCache(hashedTokens: Set<String>): Set<String> =
        hashedTokens - getPreviouslySentHashedTokens()

    /** `isAutoRenewing` 与台账不符的（考古 §5.5 链路 3）。 */
    @Synchronized
    fun hashedTokensWithAutoRenewingChange(current: Map<String, Boolean?>): Set<String> {
        val tokenMap = getTokenMap()
        return current.filter { (hash, isAutoRenewing) ->
            val cached = tokenMap[hash]
            cached?.isAutoRenewing != null && isAutoRenewing != null && cached.isAutoRenewing != isAutoRenewing
        }.keys
    }

    @Synchronized
    fun saveAutoRenewingStatus(current: Map<String, Boolean?>) {
        val map = getTokenMap().toMutableMap()
        var changed = false
        current.forEach { (hash, isAutoRenewing) ->
            val existing = map[hash]
            if (existing != null && isAutoRenewing != null && existing.isAutoRenewing != isAutoRenewing) {
                map[hash] = TokenEntry(isAutoRenewing)
                changed = true
            }
        }
        if (changed) saveTokenMap(map)
    }

    internal class TokenEntry(val isAutoRenewing: Boolean?)

    /** 内存副本：避免每次读都反序列化一遍 JSON。写入时同步失效。 */
    @Volatile
    private var tokenMapCache: Map<String, TokenEntry>? = null

    private fun getTokenMap(): Map<String, TokenEntry> {
        tokenMapCache?.let { return it }
        val loaded = preferences.getString(tokensCacheKey, null)
            ?.let { json ->
                runCatching {
                    val obj = JSONObject(json)
                    obj.keys().asSequence().associateWith { key ->
                        val entry = obj.optJSONObject(key)
                        TokenEntry(
                            if (entry != null && entry.has(TOKEN_IS_AUTO_RENEWING_KEY) &&
                                !entry.isNull(TOKEN_IS_AUTO_RENEWING_KEY)
                            ) {
                                entry.getBoolean(TOKEN_IS_AUTO_RENEWING_KEY)
                            } else {
                                null
                            },
                        )
                    }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
        tokenMapCache = loaded
        return loaded
    }

    private fun saveTokenMap(tokenMap: Map<String, TokenEntry>) {
        val json = JSONObject().apply {
            tokenMap.forEach { (hash, entry) ->
                put(
                    hash,
                    JSONObject().apply {
                        if (entry.isAutoRenewing != null) put(TOKEN_IS_AUTO_RENEWING_KEY, entry.isAutoRenewing)
                    },
                )
            }
        }
        preferences.edit().putString(tokensCacheKey, json.toString()).apply()
        tokenMapCache = tokenMap
    }

    // endregion

    internal companion object {
        /** prefs 文件名带版本号 —— 从第一天就有的纪律（坑 26）。 */
        const val PREFERENCES_FILE_SUFFIX: String = "_preferences_revenuedog_v1"
        const val SHARED_PREFERENCES_PREFIX: String = "org.revdog.purchases."

        /** CustomerInfo 缓存格式版本。**改了解析形状就必须 +1**。 */
        const val CUSTOMER_INFO_SCHEMA_VERSION: Int = 1

        const val CUSTOMER_INFO_SCHEMA_VERSION_KEY: String = "schema_version"
        const val CUSTOMER_INFO_REQUEST_DATE_KEY: String = "customer_info_request_date"
        const val TOKEN_IS_AUTO_RENEWING_KEY: String = "isAutoRenewing"
    }
}
