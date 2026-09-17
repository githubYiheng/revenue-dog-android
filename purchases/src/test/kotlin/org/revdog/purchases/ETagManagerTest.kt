package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.ETagPayloadStore
import org.revdog.purchases.networking.HTTPRequest
import org.revdog.purchases.networking.HTTPResult
import org.revdog.purchases.networking.RDHTTPStatusCodes
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class ETagManagerTest {

    private lateinit var context: Context
    private lateinit var payloadDirectory: File
    private lateinit var payloadStore: ETagPayloadStore
    private lateinit var eTagManager: ETagManager

    private val url = "https://api.revdog.test/v1/subscribers/user-42"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        payloadDirectory = File(context.cacheDir, "etag-test-${System.nanoTime()}")
        payloadStore = ETagPayloadStore(payloadDirectory)
        eTagManager = ETagManager(
            context = context,
            payloadStore = payloadStore,
        )
        eTagManager.clearCaches()
    }

    // region 请求头

    @Test
    fun `没有缓存时 eTag 发空串`() {
        val headers = eTagManager.getETagHeaders(url)
        assertThat(headers[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
        assertThat(headers[HTTPRequest.LAST_REFRESH_TIME_HEADER_NAME]).isNull()
    }

    @Test
    fun `存过之后请求头带上 eTag 与 last-refresh-time`() {
        store(payload = """{"a":1}""", eTag = "etag-1")

        val headers = eTagManager.getETagHeaders(url)
        assertThat(headers[HTTPRequest.ETAG_HEADER_NAME]).isEqualTo("etag-1")
        assertThat(headers[HTTPRequest.LAST_REFRESH_TIME_HEADER_NAME]).isNotNull()
    }

    @Test
    fun `refreshETag 时不带 eTag —— 那是「本地读不出来，重来一次」的信号`() {
        store(payload = """{"a":1}""", eTag = "etag-1")
        val headers = eTagManager.getETagHeaders(url, refreshETag = true)
        assertThat(headers[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
    }

    // endregion

    // region 304 复用

    @Test
    fun `304 命中本地 payload 并标记来源为 CACHE`() {
        store(payload = """{"cached":true}""", eTag = "etag-1")

        val result = eTagManager.getHTTPResultFromCacheOrBackend(
            resultFromBackend = notModified(),
            eTagHeader = "etag-1",
            urlString = url,
            refreshETag = false,
        )

        assertThat(result).isNotNull
        assertThat(result!!.payload).isEqualTo("""{"cached":true}""")
        assertThat(result.origin).isEqualTo(HTTPResult.Origin.CACHE)
    }

    @Test
    fun `304 但 payload 文件被清掉时返回 null，让调用方重发`() {
        store(payload = """{"cached":true}""", eTag = "etag-1")
        payloadStore.clear()

        val result = eTagManager.getHTTPResultFromCacheOrBackend(
            resultFromBackend = notModified(),
            eTagHeader = "etag-1",
            urlString = url,
            refreshETag = false,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `payload 的 CRC 校验失败时当未命中`() {
        store(payload = """{"cached":true}""", eTag = "etag-1")

        // 原地篡改 payload 文件：CRC 对不上 → 必须当未命中，而不是把乱码当数据发出去。
        val files = requireNotNull(payloadDirectory.listFiles()).filter { it.isFile }
        assertThat(files).hasSize(1)
        files.first().writeText("""{"tampered":true}""")

        assertThat(eTagManager.getStoredResult(url)).isNull()
    }

    @Test
    fun `第二次 refresh 仍未命中时直接用后端响应，不会无限重发`() {
        val result = eTagManager.getHTTPResultFromCacheOrBackend(
            resultFromBackend = notModified(),
            eTagHeader = "etag-1",
            urlString = url,
            refreshETag = true,
        )
        assertThat(result).isNotNull
        assertThat(result!!.responseCode).isEqualTo(RDHTTPStatusCodes.NOT_MODIFIED)
    }

    // endregion

    // region 什么不缓存

    @Test
    fun `5xx 不进缓存`() {
        eTagManager.storeBackendResultIfNoError(url, backend(500, """{"message":"boom"}"""), "etag-1")
        assertThat(eTagManager.getStoredResult(url)).isNull()
    }

    @Test
    fun `304 本身不进缓存`() {
        eTagManager.storeBackendResultIfNoError(url, notModified(), "etag-1")
        assertThat(eTagManager.getStoredResult(url)).isNull()
    }

    @Test
    fun `4xx 也会被缓存（它是确定性响应）`() {
        eTagManager.storeBackendResultIfNoError(url, backend(404, """{"message":"nope"}"""), "etag-1")
        assertThat(eTagManager.getStoredResult(url)).isNotNull
    }

    // endregion

    @Test
    fun `clearCaches 同时清元数据与 payload`() {
        store(payload = """{"a":1}""", eTag = "etag-1")
        assertThat(eTagManager.getStoredResult(url)).isNotNull

        eTagManager.clearCaches()

        assertThat(eTagManager.getStoredResult(url)).isNull()
        assertThat(eTagManager.getETagHeaders(url)[HTTPRequest.ETAG_HEADER_NAME]).isEmpty()
    }

    @Test
    fun `payload store 的读写往返带 CRC`() {
        val checksum = requireNotNull(payloadStore.write(url, "hello-世界"))
        assertThat(payloadStore.read(url, checksum)).isEqualTo("hello-世界")
        // 校验和不符一律未命中。
        assertThat(payloadStore.read(url, checksum + 1)).isNull()
        // 没写过的 URL 是普通未命中，不是错误。
        assertThat(payloadStore.read("https://other", checksum)).isNull()
    }

    private fun store(payload: String, eTag: String) {
        eTagManager.storeBackendResultIfNoError(url, backend(200, payload), eTag)
    }

    private fun backend(code: Int, payload: String) = HTTPResult(
        responseCode = code,
        payload = payload,
        origin = HTTPResult.Origin.BACKEND,
        requestDate = Date(0),
        requestId = null,
        serverIsRetryable = null,
        retryAfterSeconds = null,
        eTagHeaderValue = "etag-1",
    )

    private fun notModified() = backend(RDHTTPStatusCodes.NOT_MODIFIED, "")
}
