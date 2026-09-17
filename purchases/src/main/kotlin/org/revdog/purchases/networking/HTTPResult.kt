package org.revdog.purchases.networking

import org.json.JSONObject
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import java.net.URL
import java.util.Date

/**
 * 一次出站请求的完整描述。**快照测试比对的就是它**（去掉易变头之后）。
 * 结构对照 RC `common/networking/HTTPRequest.kt`。
 */
internal class HTTPRequest(
    val fullURL: URL,
    val headers: Map<String, String>,
    val body: JSONObject?,
) {
    val method: String get() = if (body == null) "GET" else "POST"

    companion object {
        const val ETAG_HEADER_NAME: String = "X-RevenueDog-ETag"
        const val LAST_REFRESH_TIME_HEADER_NAME: String = "X-RD-Last-Refresh-Time"
    }
}

/**
 * 一次请求的结果。结构对照 RC `HTTPResult`。
 *
 * `origin` 区分「后端刚给的」与「ETag 304 命中本地的」—— 诊断与测试都要看这个。
 */
@Suppress("LongParameterList")
internal class HTTPResult(
    val responseCode: Int,
    val payload: String,
    val origin: Origin,
    /** `X-RevenueDog-Request-Time` / `X-RevenueCat-Request-Time`，3 天 grace 的参照时间来源。 */
    val requestDate: Date?,
    /** 服务端 `X-Request-Id`，排障唯一线索。 */
    val requestId: String?,
    /** 服务端 `Is-Retryable` 头：显式 `false` 一票否决重试（契约 §1.3 / iOS 设计 §5）。 */
    val serverIsRetryable: Boolean?,
    /** 服务端 `Retry-After`（秒）。 */
    val retryAfterSeconds: Long?,
    /** 响应里的 ETag。有值才说明这个端点参与协商缓存。 */
    val eTagHeaderValue: String? = null,
) {

    enum class Origin { BACKEND, CACHE }

    val body: JSONObject by lazy {
        // 空体 / 坏体不抛：非 2xx 的响应经常根本不是 JSON。
        runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
    }

    fun isSuccessful(): Boolean = RDHTTPStatusCodes.isSuccessful(responseCode)

    /** 契约 §1.4 的错误体：`{"code": 7243, "message": "..."}`。 */
    val backendErrorCode: Int? get() = if (body.has("code")) body.optInt("code") else null

    private val backendErrorMessage: String? get() = body.optString("message").takeIf { it.isNotEmpty() }

    fun toPurchasesError(): PurchasesError {
        val code = when {
            responseCode == RDHTTPStatusCodes.UNAUTHORIZED || responseCode == RDHTTPStatusCodes.FORBIDDEN ->
                PurchasesErrorCode.InvalidCredentialsError
            responseCode == RDHTTPStatusCodes.NOT_FOUND -> PurchasesErrorCode.InvalidAppUserIdError
            responseCode == RDHTTPStatusCodes.BAD_REQUEST -> PurchasesErrorCode.UnexpectedBackendResponseError
            RDHTTPStatusCodes.isServerError(responseCode) -> PurchasesErrorCode.UnknownBackendError
            else -> PurchasesErrorCode.UnknownBackendError
        }
        return PurchasesError(
            code = code,
            underlyingErrorMessage = backendErrorMessage ?: "HTTP $responseCode",
            backendCode = backendErrorCode,
            httpStatusCode = responseCode,
            requestId = requestId,
        )
    }

    companion object {
        const val REQUEST_ID_HEADER_NAME: String = "X-Request-Id"

        /**
         * 服务端时间头。契约 ⟦决策3⟧ 尚未定名 —— 与 iOS 同款保守做法：两个名字都读，优先自家品牌名。
         */
        val REQUEST_TIME_HEADER_NAMES: List<String> = listOf("X-RevenueDog-Request-Time", "X-RevenueCat-Request-Time")
        val ETAG_RESPONSE_HEADER_NAMES: List<String> = listOf("X-RevenueDog-ETag", "X-RevenueCat-ETag", "ETag")
        const val IS_RETRYABLE_HEADER_NAME: String = "Is-Retryable"
        const val RETRY_AFTER_HEADER_NAME: String = "Retry-After"
    }
}

/**
 * 结构对照 RC `common/networking/RCHTTPStatusCodes.kt`。
 *
 * RC 那句注释值得原样抄过来：所有 4xx（404 除外）都算「已同步」，因为那是客户端错误，
 * 继续重试不会有不同结果，**只会杀死熊猫**。
 */
internal object RDHTTPStatusCodes {
    const val SUCCESS: Int = 200
    const val CREATED: Int = 201
    const val NOT_MODIFIED: Int = 304
    const val BAD_REQUEST: Int = 400
    const val UNAUTHORIZED: Int = 401
    const val FORBIDDEN: Int = 403
    const val NOT_FOUND: Int = 404
    const val TOO_MANY_REQUESTS: Int = 429
    const val ERROR: Int = 500

    fun isSuccessful(responseCode: Int): Boolean = responseCode in SUCCESS until MULTIPLE_CHOICES

    fun isServerError(responseCode: Int): Boolean = responseCode >= ERROR

    /** 4xx（404 除外）= 客户端错误，重试无意义，按「已同步」处理。 */
    fun isSynced(responseCode: Int): Boolean =
        !isServerError(responseCode) && responseCode != NOT_FOUND

    private const val MULTIPLE_CHOICES = 300
}
