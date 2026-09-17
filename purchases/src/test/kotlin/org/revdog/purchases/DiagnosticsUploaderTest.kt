package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsUploadResult
import org.revdog.purchases.diagnostics.DiagnosticsUploader
import org.revdog.purchases.support.DiagnosticsRig
import org.robolectric.RobolectricTestRunner

/**
 * 攒批上传：切批、单飞、退避矩阵、4xx 丢批、401 停 1h、服务端下发的三个控制字段。
 *
 * 处置矩阵的真相源是 `sdk-diagnostics.md` §1.2 + §6-9/§6-10，**与 iOS
 * `DiagnosticsUploader` 逐条对齐** —— 同一个后端故障在两端必须导出同一个结论。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsUploaderTest {

    private lateinit var context: Context
    private lateinit var rig: DiagnosticsRig

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rig = DiagnosticsRig(context)
    }

    private fun fill(count: Int, level: String = DiagnosticsLevel.INFO) {
        repeat(count) { index ->
            rig.queue.append(
                DiagnosticsEvent.create(
                    id = "evt-$index",
                    tsMs = DiagnosticsRig.FIXED_NOW_MS + index,
                    appUserID = "user-42",
                    seq = index.toLong(),
                    type = "sdk_configured",
                    level = level,
                    fields = mapOf("i" to index),
                ),
            )
        }
    }

    @Test
    fun `202 之后整批出队且在飞文件被删`() {
        fill(3)
        rig.enqueueAccepted(3)

        val result = rig.uploader.upload()

        assertThat(result.uploaded).isEqualTo(3)
        assertThat(result.dropped).isZero()
        assertThat(rig.queue.count()).isZero()
        assertThat(rig.queue.inflightFiles()).isEmpty()
        assertThat(rig.diagnosticsRequests()).hasSize(1)
    }

    @Test
    fun `空队列不发请求`() {
        val result = rig.uploader.upload()

        assertThat(result.skipped).isEqualTo(DiagnosticsUploadResult.Skip.EMPTY)
        assertThat(rig.diagnosticsRequests()).isEmpty()
    }

    @Test
    fun `按条数切批，一次 upload 连发多批`() {
        fill(DiagnosticsUploader.MAX_EVENTS_PER_BATCH + 5)
        repeat(2) { rig.enqueueAccepted() }

        val result = rig.uploader.upload()

        assertThat(rig.diagnosticsRequests()).hasSize(2)
        assertThat(result.uploaded).isEqualTo(DiagnosticsUploader.MAX_EVENTS_PER_BATCH + 5)
        assertThat(rig.queue.count()).isZero()
    }

    @Test
    fun `单飞：上传在飞期间的第二次调用直接跳过`() {
        fill(2)
        // 在假后端的 IO 里递归调 upload —— 模拟「上传还没回来又被触发一次」。
        var nested: DiagnosticsUploadResult? = null
        rig.httpClient.beforeResponse = { nested = rig.uploader.upload() }
        rig.enqueueAccepted(2)

        rig.uploader.upload()

        assertThat(nested?.skipped).isEqualTo(DiagnosticsUploadResult.Skip.IN_FLIGHT)
        assertThat(rig.diagnosticsRequests()).hasSize(1)
    }

    @Test
    fun `5xx 保留队列并按 30s 起的指数退避`() {
        fill(2)
        rig.httpClient.enqueue(503, """{"message":"unavailable"}""")

        rig.uploader.upload()

        // 一条不丢（留在在飞文件里，下一轮继续发）。
        val inflight = rig.queue.inflightFiles()
        assertThat(inflight).hasSize(1)
        assertThat(rig.queue.linesOf(inflight.first())).hasSize(2)
        assertThat(rig.uploader.backoffMs).isEqualTo(DiagnosticsUploader.INITIAL_BACKOFF_MS)
        assertThat(rig.uploader.isSuspended).isTrue()

        // 退避窗口内不发。
        assertThat(rig.uploader.upload().skipped).isEqualTo(DiagnosticsUploadResult.Skip.BACKOFF)
        assertThat(rig.diagnosticsRequests()).hasSize(1)

        // 窗口过去 → 再失败一次 → 退避 ×2。
        rig.nowMs += DiagnosticsUploader.INITIAL_BACKOFF_MS
        rig.httpClient.enqueue(503, "")
        rig.uploader.upload()
        assertThat(rig.uploader.backoffMs).isEqualTo(DiagnosticsUploader.INITIAL_BACKOFF_MS * 2)
        assertThat(rig.uploader.consecutiveFailures).isEqualTo(2)
    }

    @Test
    fun `网络层失败也保留队列并退避`() {
        fill(1)
        rig.httpClient.enqueueIOException()

        rig.uploader.upload()

        assertThat(rig.queue.linesOf(rig.queue.inflightFiles().first())).hasSize(1)
        assertThat(rig.uploader.backoffMs).isEqualTo(DiagnosticsUploader.INITIAL_BACKOFF_MS)
    }

    @Test
    fun `恢复成功后清退避并如实报出连续失败次数`() {
        fill(1)
        rig.httpClient.enqueue(503, "")
        rig.uploader.upload()
        rig.nowMs += DiagnosticsUploader.INITIAL_BACKOFF_MS
        rig.enqueueAccepted()

        val result = rig.uploader.upload()

        assertThat(result.recoveredFailureCount).isEqualTo(1)
        assertThat(rig.uploader.backoffMs).isZero()
        assertThat(rig.uploader.isSuspended).isFalse()
        assertThat(rig.uploader.consecutiveFailures).isZero()
    }

    @Test
    fun `4xx 丢批且不退避`() {
        fill(2)
        rig.httpClient.enqueue(413, """{"code":7413,"message":"payload too large"}""")

        val result = rig.uploader.upload()

        assertThat(result.dropped).isEqualTo(2)
        assertThat(result.uploaded).isZero()
        assertThat(rig.queue.count()).isZero()
        assertThat(rig.queue.inflightFiles()).isEmpty()
        assertThat(rig.uploader.isSuspended).isFalse()
    }

    @Test
    fun `401 丢批并停一小时`() {
        fill(2)
        rig.httpClient.enqueue(401, """{"code":7401}""")

        val result = rig.uploader.upload()

        assertThat(result.dropped).isEqualTo(2)
        assertThat(rig.uploader.isSuspended).isTrue()
        rig.nowMs += DiagnosticsUploader.AUTH_SUSPENSION_MS - 1
        assertThat(rig.uploader.isSuspended).isTrue()
        rig.nowMs += 1
        assertThat(rig.uploader.isSuspended).isFalse()
    }

    @Test
    fun `429 读 Retry-After 但不短于当前退避档位`() {
        fill(1)
        rig.httpClient.enqueue(
            org.revdog.purchases.support.FakeHTTPClient.response(429, "").let { base ->
                org.revdog.purchases.networking.HTTPResult(
                    responseCode = 429,
                    payload = "",
                    origin = base.origin,
                    requestDate = null,
                    requestId = "req-429",
                    serverIsRetryable = null,
                    retryAfterSeconds = 7200, // 2h，超过 1h 上限 → 被夹到 1h
                    eTagHeaderValue = null,
                )
            },
        )

        rig.uploader.upload()

        assertThat(rig.queue.linesOf(rig.queue.inflightFiles().first())).hasSize(1)
        assertThat(rig.uploader.resumeAtMs).isEqualTo(rig.nowMs + DiagnosticsUploader.MAX_BACKOFF_MS)
    }

    @Test
    fun `202 带 sample_rate_info 时回传给调用方`() {
        fill(1)
        rig.enqueueAccepted(1, extra = ""","sample_rate_info":0.25""")

        val result = rig.uploader.upload()

        assertThat(result.sampleRateInfo).isEqualTo(0.25)
    }

    @Test
    fun `202 带 backoff_ms 时按它退避且剩余事件留着`() {
        fill(DiagnosticsUploader.MAX_EVENTS_PER_BATCH + 1)
        rig.enqueueAccepted(DiagnosticsUploader.MAX_EVENTS_PER_BATCH, extra = ""","backoff_ms":3600000""")

        val result = rig.uploader.upload()

        assertThat(result.uploaded).isEqualTo(DiagnosticsUploader.MAX_EVENTS_PER_BATCH)
        assertThat(rig.diagnosticsRequests()).hasSize(1) // 第二批没发
        assertThat(rig.queue.linesOf(rig.queue.inflightFiles().first())).hasSize(1)
        assertThat(rig.uploader.isSuspended).isTrue()
    }

    @Test
    fun `202 带 disable_until_ms 时回传该时刻`() {
        fill(1)
        val until = DiagnosticsRig.FIXED_NOW_MS + 86_400_000
        rig.enqueueAccepted(1, extra = ""","disable_until_ms":$until""")

        val result = rig.uploader.upload()

        assertThat(result.disableUntilMs).isEqualTo(until)
        assertThat(rig.uploader.isSuspended).isTrue()
    }

    @Test
    fun `启动时先补发遗留的在飞文件`() {
        fill(2)
        val leftover = rig.queue.rotate()
        assertThat(leftover).isNotNull
        // 新队列里又攒了一条。
        fill(1)
        repeat(2) { rig.enqueueAccepted() }

        val result = rig.uploader.upload()

        assertThat(result.uploaded).isEqualTo(3)
        assertThat(rig.queue.inflightFiles()).isEmpty()
        assertThat(leftover!!.exists()).isFalse()
    }

    @Test
    fun `上行信封字段与契约一致且不重复系统信息`() {
        fill(1)
        rig.enqueueAccepted()

        rig.uploader.upload()

        val body = requireNotNull(rig.diagnosticsRequests().single().body)
        assertThat(body.getInt("schema_version")).isEqualTo(1)
        assertThat(body.getString("install_id")).isNotBlank()
        assertThat(body.getString("session_id")).isEqualTo(DiagnosticsRig.SESSION_ID)
        assertThat(body.has("sandbox")).isTrue()
        assertThat(body.has("is_debug")).isTrue()
        assertThat(body.getLong("sent_at_ms")).isEqualTo(DiagnosticsRig.FIXED_NOW_MS)
        // §6-2：`app_user_id` **不在信封里**，下沉到每条事件。
        assertThat(body.has("app_user_id")).isFalse()
        // 系统信息只走请求头（契约 §1.1）。
        assertThat(body.has("platform")).isFalse()
        assertThat(body.has("sdk_version")).isFalse()
        val events = body.getJSONArray("events")
        assertThat(events.length()).isEqualTo(1)
        assertThat(events.getJSONObject(0).getString("app_user_id")).isEqualTo("user-42")
        assertThat(events.getJSONObject(0).has("seq")).isTrue()

        val headers = rig.diagnosticsRequests().single().headers
        assertThat(headers["Authorization"]).isEqualTo("Bearer ${org.revdog.purchases.support.FakeHTTPClient.TEST_API_KEY}")
        assertThat(headers["X-Platform"]).isEqualTo("android")
        assertThat(headers["X-Version"]).isNotNull()
    }

    @Test
    fun `install_id 跨实例稳定且永不为空`() {
        val first = rig.settings.installID()
        assertThat(first).isNotBlank()
        assertThat(rig.settings.installID()).isEqualTo(first)
    }
}
