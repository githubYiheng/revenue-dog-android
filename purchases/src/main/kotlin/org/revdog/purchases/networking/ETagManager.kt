package org.revdog.purchases.networking

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.isAndroidNOrNewer
import java.util.Date

/**
 * ETag 缓存的元数据。**payload 不在这里**，在 [ETagPayloadStore] 的文件里。
 * 结构对照 RC `ETagCacheMetadata`。
 */
internal class ETagCacheMetadata(
    val eTag: String,
    val lastRefreshTime: Date?,
    val responseCode: Int,
    val requestDate: Date?,
    /**
     * payload 的 CRC32，读的时候校验。
     *
     * **放在元数据里而不是 payload 文件里**（RC 原注释）：`storeResult` 先写文件再提交 prefs，
     * 一个自描述的文件在两步之间崩溃后**仍然能通过自校验**，于是新字节被挂在上一条 eTag 下发出去。
     */
    val payloadChecksum: Long,
) {

    fun serialize(): String = JSONObject().apply {
        put(KEY_ETAG, eTag)
        lastRefreshTime?.let { put(KEY_LAST_REFRESH_TIME, it.time) }
        put(KEY_RESPONSE_CODE, responseCode)
        requestDate?.let { put(KEY_REQUEST_DATE, it.time) }
        put(KEY_PAYLOAD_CHECKSUM, payloadChecksum)
    }.toString()

    fun toHTTPResult(payload: String): HTTPResult = HTTPResult(
        responseCode = responseCode,
        payload = payload,
        origin = HTTPResult.Origin.CACHE,
        requestDate = requestDate,
        requestId = null,
        serverIsRetryable = null,
        retryAfterSeconds = null,
    )

    companion object {
        private const val KEY_ETAG = "eTag"
        private const val KEY_LAST_REFRESH_TIME = "lastRefreshTime"
        private const val KEY_RESPONSE_CODE = "responseCode"
        private const val KEY_REQUEST_DATE = "requestDate"
        private const val KEY_PAYLOAD_CHECKSUM = "payloadChecksum"

        /** 解析不了（含旧格式）一律返回 `null` → 当作未命中，而不是拿未校验的数据去顶。 */
        @Suppress("SwallowedException")
        fun deserialize(serialized: String): ETagCacheMetadata? = try {
            val json = JSONObject(serialized)
            ETagCacheMetadata(
                eTag = json.getString(KEY_ETAG),
                lastRefreshTime = json.optLong(KEY_LAST_REFRESH_TIME, -1L).takeIf { it != -1L }?.let { Date(it) },
                responseCode = json.getInt(KEY_RESPONSE_CODE),
                requestDate = json.optLong(KEY_REQUEST_DATE, -1L).takeIf { it != -1L }?.let { Date(it) },
                payloadChecksum = json.getLong(KEY_PAYLOAD_CHECKSUM),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * ETag 协商缓存。结构对照 RC `common/networking/ETagManager.kt`。
 *
 * prefs 文件名**带版本号**（`…_etags_v1`）：格式演进时旧文件**不加载直接删**
 * （API 24+ 的 `deleteSharedPreferences`），避免把旧格式的多 MB 值读进堆（坑 26）。
 * 这条纪律从第一天就有 —— 新 SDK 没有历史包袱，但缓存格式一定会演进。
 */
internal class ETagManager(
    context: Context,
    private val prefs: Lazy<SharedPreferences> = lazy { initializeSharedPreferences(context) },
    private val dateProvider: DateProvider = DefaultDateProvider(),
    private val payloadStore: ETagPayloadStore = ETagPayloadStore(context),
) {

    /**
     * 请求侧的两个头。**没有缓存时 eTag 发空串**（RC 同款）：
     * 服务端据此区分「没带过 eTag」与「带了但过期」。
     */
    fun getETagHeaders(urlString: String, refreshETag: Boolean = false): Map<String, String?> {
        val metadata = if (refreshETag) null else getStoredMetadata(urlString)
        return mapOf(
            HTTPRequest.ETAG_HEADER_NAME to metadata?.eTag.orEmpty(),
            HTTPRequest.LAST_REFRESH_TIME_HEADER_NAME to metadata?.lastRefreshTime?.time?.toString(),
        )
    }

    /**
     * 响应侧。返回 `null` 有且只有一种含义：**收到 304 但本地读不出来**，
     * 调用方必须带 `refreshETag = true` 整个重发一次（RC 的 `ETAG_RETRYING_CALL`）。
     */
    @Suppress("ReturnCount")
    fun getHTTPResultFromCacheOrBackend(
        resultFromBackend: HTTPResult,
        eTagHeader: String?,
        urlString: String,
        refreshETag: Boolean,
    ): HTTPResult? {
        if (eTagHeader == null) return resultFromBackend

        if (shouldUseCachedVersion(resultFromBackend.responseCode)) {
            val stored = getStoredResult(urlString)
            return when {
                stored != null -> stored
                // 第二次还读不到就认了，直接把后端这次的 304 给上层（避免无限重发）。
                refreshETag -> {
                    Logger.warn { "ETag 已重试过一次仍未命中本地缓存，直接返回后端响应" }
                    resultFromBackend
                }
                else -> null
            }
        }

        storeBackendResultIfNoError(urlString, resultFromBackend, eTagHeader)
        return resultFromBackend
    }

    fun shouldUseCachedVersion(responseCode: Int): Boolean = responseCode == RDHTTPStatusCodes.NOT_MODIFIED

    @Suppress("ReturnCount")
    fun getStoredResult(urlString: String): HTTPResult? {
        val serialized = prefs.value.getString(urlString, null) ?: return null
        val metadata = ETagCacheMetadata.deserialize(serialized) ?: return null
        // 无锁读：与一次并发写入赛跑时 checksum 会失败，代价是一次多余的未命中 + 一次 refresh 重发。
        // 没有这道校验，坏条目永远不会自愈 —— `HTTPResult.body` 吞掉解析失败，
        // 服务端则继续对着这个 eTag 回 304。
        val payload = payloadStore.read(urlString, metadata.payloadChecksum) ?: return null
        return metadata.toHTTPResult(payload)
    }

    fun storeBackendResultIfNoError(urlString: String, resultFromBackend: HTTPResult, eTagInResponse: String) {
        if (shouldStoreBackendResult(resultFromBackend)) {
            storeResult(urlString, resultFromBackend, eTagInResponse)
        }
    }

    /**
     * logOut / 身份切换时必须调（考古 §6.4）：不清 ETag，新匿名用户会命中**上一个用户**的 304。
     */
    @Synchronized
    fun clearCaches() {
        // 先清元数据：内存里的清除是立刻生效的，读者从此必然未命中。
        // 在异步 flush 之前崩溃只会留下「有 payload 没元数据」，那是能自愈的未命中。
        prefs.value.edit().clear().apply()
        payloadStore.clear()
    }

    /**
     * payload 文件先写、写成功了才提交元数据：**有元数据就一定有它的 payload**。
     */
    @Synchronized
    private fun storeResult(urlString: String, result: HTTPResult, eTag: String) {
        val payloadChecksum = payloadStore.write(urlString, result.payload) ?: return
        val metadata = ETagCacheMetadata(
            eTag = eTag,
            lastRefreshTime = dateProvider.now(),
            responseCode = result.responseCode,
            requestDate = result.requestDate,
            payloadChecksum = payloadChecksum,
        )
        prefs.value.edit().putString(urlString, metadata.serialize()).apply()
    }

    private fun getStoredMetadata(urlString: String): ETagCacheMetadata? =
        prefs.value.getString(urlString, null)?.let { ETagCacheMetadata.deserialize(it) }

    /** 304、5xx 一律不缓存（RC 同款）。 */
    private fun shouldStoreBackendResult(result: HTTPResult): Boolean =
        result.responseCode != RDHTTPStatusCodes.NOT_MODIFIED &&
            result.responseCode < RDHTTPStatusCodes.ERROR

    companion object {
        private const val PREFERENCES_FILE_SUFFIX = "_preferences_etags_v1"

        fun initializeSharedPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences("${context.packageName}$PREFERENCES_FILE_SUFFIX", Context.MODE_PRIVATE)

        /**
         * 预留：格式演进到 v2 时，在这里把 v1 文件**不加载直接删**（对照 RC
         * `deleteLegacyPreferencesFile`），避免把旧格式的大 value 读进堆。
         */
        @Suppress("unused")
        fun deleteLegacyPreferencesFile(context: Context, legacySuffix: String) {
            if (!isAndroidNOrNewer()) return
            try {
                context.deleteSharedPreferences("${context.packageName}$legacySuffix")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // 尽力而为的清理：这里失败绝不能弄坏缓存本身。
                Logger.error(e) { "删除旧版 ETag prefs 文件失败" }
            }
        }
    }
}
