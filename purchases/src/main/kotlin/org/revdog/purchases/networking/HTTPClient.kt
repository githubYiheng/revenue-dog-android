package org.revdog.purchases.networking

import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.Config
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.filterNotNullValues
import org.revdog.purchases.diagnostics.DiagnosticsErrorClass
import org.revdog.purchases.diagnostics.DiagnosticsErrorFields
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.Date

/**
 * 结构对照 RC `common/HTTPClient.kt`。
 *
 * **没有 OkHttp / Retrofit / Gson**（考古 §8.1）：`java.net.HttpURLConnection` + `org.json`。
 * 一个 IAP SDK 不该把 HTTP 栈的版本冲突塞给宿主。
 *
 * **HTTP 层不做重试**（考古 §2.12）。失败靠「下次前台 / 下次连接成功时重跑」兜底。
 * 我方比 RC 多读两个头 —— 服务端 `Is-Retryable`（显式 `false` 一票否决）与 `Retry-After`，
 * 但**解释权在上层**：HTTPClient 只把它们带上来，不自己睡觉重发
 * （与 iOS `RetryPolicy` 的分工一致：那边由 `HTTPClient` 执行，是因为它是 async 的；
 * 这边是同步调用，睡在 Dispatcher 线程上会堵住整条队列）。
 *
 * `open` + `executeRequest` 可覆写：测试注入假后端的唯一入口
 * （`PurchasesConfiguration.Builder.httpClientOverride`）。真实头拼装仍然走 [buildRequest]，
 * 所以出站请求快照测的是**生产路径**。
 */
internal open class HTTPClient(
    private val appConfig: AppConfig,
    private val eTagManager: ETagManager,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    /**
     * 客户端诊断（`sdk-diagnostics.md` §1.3：`http_error` 的**唯一**记录点）。
     *
     * **后置注入**，由 `PurchasesFactory.create` 在任何请求发生前赋一次
     * （对照 iOS `HTTPClient.setDiagnostics`）：Recorder 的 uploader 反过来要用本 client 发
     * `/v1/diagnostics/events`，构造期闭不了环。赋值之前记录点是哑的 ——
     * 那正好是「configure 还没跑完、一个请求都发不出去」的那段时间。
     */
    var diagnostics: DiagnosticsTracker? = null

    /**
     * 同步发一次请求。
     *
     * @throws IOException 网络层失败（调用方在 `Dispatcher.AsyncCall` 里被 catch 成 `networkError`）。
     */
    @Throws(IOException::class)
    @Suppress("ReturnCount")
    fun performRequest(
        endpoint: Endpoint,
        body: JSONObject? = null,
        refreshETag: Boolean = false,
    ): HTTPResult {
        val request = buildRequest(endpoint, body, refreshETag)
        Logger.debug { "→ ${request.method} ${endpoint.diagnosticsPath}" }
        val startedAtMs = dateProvider.now().time
        val result = try {
            executeRequest(request)
        } catch (e: IOException) {
            // 没有响应：`status` 缺省，靠异常类名分 timeout / network。
            trackHTTPError(endpoint, result = null, throwable = e, startedAtMs = startedAtMs)
            throw e
        }
        trackHTTPError(endpoint, result, throwable = null, startedAtMs = startedAtMs)

        if (!endpoint.usesETag) return result

        val eTagHeader = result.eTagHeaderValue
        val fromCacheOrBackend = eTagManager.getHTTPResultFromCacheOrBackend(
            resultFromBackend = result,
            eTagHeader = eTagHeader,
            urlString = request.fullURL.toString(),
            refreshETag = refreshETag,
        )
        if (fromCacheOrBackend != null) return fromCacheOrBackend

        // 收到 304 但本地 payload 读不出来（文件被清 / CRC 不符）→ 整个重发一次，这次不带 eTag。
        Logger.debug { "ETag 未命中本地缓存，重发 ${endpoint.diagnosticsPath}" }
        return performRequest(endpoint, body, refreshETag = true)
    }

    /**
     * 拼出完整请求（URL + 全部头 + body）。**独立出来是为了让出站契约成为可 diff 的产物**：
     * 快照测试直接比对它的输出（对照 iOS `RequestSnapshot`）。
     */
    @VisibleForTesting
    internal fun buildRequest(endpoint: Endpoint, body: JSONObject?, refreshETag: Boolean): HTTPRequest {
        val fullURL = URL(appConfig.baseURL.trimEnd('/') + endpoint.path)
        return HTTPRequest(
            fullURL = fullURL,
            headers = getHeaders(endpoint, fullURL, refreshETag),
            body = body,
        )
    }

    /**
     * 请求头全集（考古 §2.7 + 契约 §1.3）。
     *
     * 与 RC 的差异有两处，都在设计 §5 登记过：
     * - **多发** `X-Client-Build-Version`：RC Android 从不发它，而我方 `last_seen_app_build` 靠它。
     * - `X-Is-Sandbox` 我方按 `isDebugBuild` 粗判；RC 只在 TEST_STORE 下发 `true`。
     *   Play 没有 iOS 那种设备级 sandbox 标志，真实 environment 由服务端按 Play 订单判定
     *   （契约 §1.3：服务端读这个头只作参考）。**待核实**：G3 沙盒实测后对齐。
     */
    private fun getHeaders(endpoint: Endpoint, fullURL: URL, refreshETag: Boolean): Map<String, String> {
        val headers = mutableMapOf<String, String?>(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer ${appConfig.apiKey}",
            "X-Platform" to if (endpoint.sendsPlatformHeader) PLATFORM_ANDROID else null,
            "X-Platform-Flavor" to appConfig.platformInfo.flavor,
            "X-Platform-Flavor-Version" to appConfig.platformInfo.version,
            "X-Platform-Version" to appConfig.platformVersion,
            "X-Platform-Device" to appConfig.platformDevice,
            "X-Platform-Brand" to appConfig.platformBrand,
            "X-Version" to Config.FRAMEWORK_VERSION,
            "X-Client-Version" to appConfig.versionName,
            "X-Client-Build-Version" to appConfig.versionCode,
            "X-Client-Bundle-ID" to appConfig.packageName,
            "X-Client-Locale" to appConfig.languageTag,
            "X-Kotlin-Version" to KotlinVersion.CURRENT.toString(),
            "X-Billing-Client-Sdk-Version" to Config.BILLING_CLIENT_VERSION,
            "X-Observer-Mode-Enabled" to (!appConfig.finishTransactions).toString(),
            "X-Is-Sandbox" to appConfig.isDebugBuild.toString(),
            "X-Is-Backgrounded" to appConfig.isAppBackgrounded.toString(),
            "X-Is-Debug-Build" to appConfig.isDebugBuild.toString(),
        )
        if (endpoint.usesETag) {
            headers += eTagManager.getETagHeaders(fullURL.toString(), refreshETag)
        }
        return headers.filterNotNullValues()
    }

    /**
     * 真正的 IO。测试覆写这一个方法就能换掉整个后端，而请求头仍然是生产路径拼出来的。
     */
    @Throws(IOException::class)
    @VisibleForTesting
    internal open fun executeRequest(request: HTTPRequest): HTTPResult {
        val connection = openConnection(request)
        return try {
            val responseCode = connection.responseCode
            val payload = getInputStream(connection)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            HTTPResult(
                responseCode = responseCode,
                payload = payload,
                origin = HTTPResult.Origin.BACKEND,
                requestDate = connection.requestDateHeader(),
                requestId = connection.getHeaderField(HTTPResult.REQUEST_ID_HEADER_NAME),
                serverIsRetryable = connection.isRetryableHeader(),
                retryAfterSeconds = connection.retryAfterHeader(),
                eTagHeaderValue = connection.eTagHeader(),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun clearCaches() {
        eTagManager.clearCaches()
    }

    /**
     * `http_error`（契约 §1.3）。**发射点只有这一个** —— 散到各调用方去写，
     * 迟早会有一个端点漏记，或者把 `app_user_id` 原样拼进 `path`。
     * `path` 取 [Endpoint.diagnosticsPath]：`app_user_id` 段已经换成 `*`，也没有查询串。
     *
     * [result] 为 `null` = 传输层失败（根本没有响应）：`status` / `request_id` / `backend_code`
     * 全部缺省（契约「fields 全部可选，缺失容忍」），`error_class` 只能靠 [throwable] 的
     * 类名分 `timeout` / `network`。
     *
     * **2xx 与 304 都不记**：304 是 ETag 协商命中，是缓存正常工作的样子，不是错误
     * （iOS 没有 ETagManager，那边的 `recordHTTPAttempt` 因此没有这一条分支）。
     * 本地 payload 读不出来时上面会带 `refreshETag = true` 整个重发一次 ——
     * 那一次是真请求，走的是同一条记录路径。
     */
    @Suppress("ReturnCount")
    private fun trackHTTPError(endpoint: Endpoint, result: HTTPResult?, throwable: Throwable?, startedAtMs: Long) {
        if (!endpoint.recordsHTTPError) return
        if (result != null && (result.isSuccessful() || result.responseCode == RDHTTPStatusCodes.NOT_MODIFIED)) return
        val tracker = diagnostics ?: return
        val errorClass = if (result != null) {
            DiagnosticsErrorClass.from(result.responseCode)
        } else {
            DiagnosticsErrorFields.classifyTransport(throwable)
        }
        tracker.track(
            DiagnosticsTracker.EVENT_HTTP_ERROR,
            mapOf(
                "path" to endpoint.diagnosticsPath,
                DiagnosticsErrorFields.KEY_STATUS to result?.responseCode,
                "request_id" to result?.requestId,
                DiagnosticsErrorFields.KEY_ERROR_CLASS to errorClass,
                DiagnosticsErrorFields.KEY_BACKEND_CODE to result?.backendErrorCode,
                "duration_ms" to (dateProvider.now().time - startedAtMs),
            ),
        )
    }

    private fun openConnection(request: HTTPRequest): HttpURLConnection =
        (request.fullURL.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            // readTimeout 保持默认 0（无限），与 RC 一致：我方后端在 Cloudflare 边缘上，
            // 一个短 readTimeout 会把「慢但会成功」的收据上报变成「本地超时 + 重放」。
            request.headers.forEach { (key, value) -> addRequestProperty(key, value) }
            request.body?.let { body ->
                doOutput = true
                requestMethod = "POST"
                writeFully(buffer(outputStream), body.toString())
            }
        }

    /**
     * 坑 28：某些设备上读 `errorStream` 会抛 NPE（purchases-android#2606）。
     * 包成 IOException 交给上层的 `networkError`，而不是让它炸穿到主线程。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun getInputStream(connection: HttpURLConnection): InputStream? = try {
        connection.inputStream
    } catch (e: Exception) {
        when (e) {
            is IllegalArgumentException, is IOException -> try {
                connection.errorStream
            } catch (npe: NullPointerException) {
                throw IOException("读取 errorStream 时抛 NPE（设备特定问题）", npe)
            }
            else -> throw e
        }
    }

    private fun buffer(outputStream: OutputStream): BufferedWriter = BufferedWriter(OutputStreamWriter(outputStream))

    @Throws(IOException::class)
    private fun writeFully(writer: BufferedWriter, body: String) {
        writer.write(body)
        writer.flush()
    }

    private companion object {
        const val PLATFORM_ANDROID = "android"
        const val CONNECT_TIMEOUT_MS = 30_000
    }
}

/**
 * 坑 41：请求时间头可能不是数字。`toLongOrNull` 让它降级成「没有日期」——
 * 在这里抛异常会逃过请求路径上所有的 catch（它们只覆盖 IOException 那一族），
 * 最后被 Dispatcher 在主线程重抛，把一次响应头异常变成一次崩溃。
 */
private fun URLConnection.requestDateHeader(): Date? =
    HTTPResult.REQUEST_TIME_HEADER_NAMES
        .firstNotNullOfOrNull { getHeaderField(it)?.takeIf { v -> v.isNotBlank() } }
        ?.toLongOrNull()
        ?.let { Date(it) }

private fun URLConnection.eTagHeader(): String? =
    HTTPResult.ETAG_RESPONSE_HEADER_NAMES.firstNotNullOfOrNull { getHeaderField(it) }

private fun URLConnection.isRetryableHeader(): Boolean? =
    when (getHeaderField(HTTPResult.IS_RETRYABLE_HEADER_NAME)?.trim()?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

/** 只支持 delta-seconds（保守；HTTP-date 形式忽略），与 iOS 同口径。 */
private fun URLConnection.retryAfterHeader(): Long? =
    getHeaderField(HTTPResult.RETRY_AFTER_HEADER_NAME)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
