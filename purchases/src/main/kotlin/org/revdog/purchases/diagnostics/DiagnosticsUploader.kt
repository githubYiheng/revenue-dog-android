package org.revdog.purchases.diagnostics

import androidx.annotation.VisibleForTesting
import org.revdog.purchases.Logger
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.HTTPResult
import org.revdog.purchases.networking.RDHTTPStatusCodes
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 诊断攒批上传。结构对照 RC `common/diagnostics/DiagnosticsSynchronizer.kt`，
 * 处置矩阵逐条对齐 iOS `DiagnosticsUploader.swift`（两端必须对同一个后端故障得出同一个结论）。
 *
 * | 响应 | 队列 | 退避 |
 * |---|---|---|
 * | 2xx | 出队该批；落 `sample_rate_info`；`backoff_ms` / `disable_until_ms` 照办 | 清零 |
 * | 401 / 403 | **丢弃该批** | **停 1 小时**（key 配错了不该反复打） |
 * | 429 | 保留 | 退避（优先读 `Retry-After`，但不短于当前档位） |
 * | 5xx / 网络错误 | 保留 | 指数退避 30s 起、×2、上限 1h |
 * | 其余 4xx（含 400 / 413） | **丢弃该批** | 清零 |
 *
 * **三处偏离 RC，都是我方更强：**
 * 1. RC 连续失败 3 次就**删文件**（丢数据）；我方保留队列 + 指数退避，靠 500 条 / 256 KB 上限兜底。
 * 2. RC 4xx 立即删**整个文件**；我方只丢**该批**。
 * 3. RC 对 401/403 无特殊处理；我方停 1 小时。
 *
 * 单飞：[isUploading]（对照 RC 的 `AtomicBoolean isSyncing`）。
 * 线程：只在诊断线程上跑（`Backend.postDiagnosticsEventsBlocking` 是同步的）。
 */
internal class DiagnosticsUploader(
    private val backend: Backend,
    private val queue: DiagnosticsQueue,
    private val settings: DiagnosticsSettings,
    private val appConfig: AppConfig,
    private val sessionID: String,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    private val isUploading = AtomicBoolean(false)

    /** 退避 / 停摆的解除时刻（ms epoch）。`null` = 现在就能发。 */
    @Volatile
    @VisibleForTesting
    internal var resumeAtMs: Long? = null
        private set

    /** 当前指数退避档位（毫秒）。0 = 不在指数退避里。测试断言退避序列用。 */
    @Volatile
    @VisibleForTesting
    internal var backoffMs: Long = 0
        private set

    /** 连续失败次数（§6-13：上传失败**只在本地计数**，绝不为诊断失败再发一次诊断请求）。 */
    @Volatile
    @VisibleForTesting
    internal var consecutiveFailures: Int = 0
        private set

    val isSuspended: Boolean get() = resumeAtMs?.let { dateProvider.now().time < it } == true

    /**
     * 上传一轮。先补发盘上遗留的在飞文件，再轮转当前队列。
     *
     * @return 这一轮的结果（供 Recorder 落采样率 / 补 `diag_upload_failed`，以及测试断言）。
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun upload(): DiagnosticsUploadResult {
        if (!isUploading.compareAndSet(false, true)) {
            return DiagnosticsUploadResult(skipped = DiagnosticsUploadResult.Skip.IN_FLIGHT)
        }
        try {
            if (isSuspended) return DiagnosticsUploadResult(skipped = DiagnosticsUploadResult.Skip.BACKOFF)

            val files = queue.inflightFiles().toMutableList()
            queue.rotate()?.let { files += it }
            if (files.isEmpty()) return DiagnosticsUploadResult(skipped = DiagnosticsUploadResult.Skip.EMPTY)

            var uploaded = 0
            var dropped = 0
            var sampleRateInfo: Double? = null
            var batches = 0

            for (file in files) {
                var lines = queue.linesOf(file)
                while (lines.isNotEmpty()) {
                    if (batches >= MAX_BATCHES_PER_RUN) {
                        queue.write(lines, file)
                        return finish(uploaded, dropped, sampleRateInfo, clearBackoff = true)
                    }
                    batches++
                    val batch = queue.batch(lines, MAX_EVENTS_PER_BATCH, MAX_EVENT_BYTES_PER_BATCH)
                    if (batch.isEmpty()) break

                    when (val outcome = send(batch)) {
                        is SendOutcome.Accepted -> {
                            lines = lines.drop(batch.size)
                            uploaded += batch.size
                            outcome.response.sampleRateInfo?.let { sampleRateInfo = it }
                            outcome.response.disableUntilMs?.let { until ->
                                queue.write(lines, file)
                                val result = finish(uploaded, dropped, sampleRateInfo, clearBackoff = true, until)
                                applyDisableUntil(until)
                                return result
                            }
                            outcome.response.backoffMs?.let { ms ->
                                queue.write(lines, file)
                                val result = finish(uploaded, dropped, sampleRateInfo, clearBackoff = true)
                                applyServerBackoff(ms)
                                return result
                            }
                        }

                        is SendOutcome.DropBatch -> {
                            lines = lines.drop(batch.size)
                            dropped += batch.size
                            if (outcome.suspendForMs != null) {
                                consecutiveFailures++
                                queue.write(lines, file)
                                applySuspension(outcome.suspendForMs)
                                return DiagnosticsUploadResult(uploaded = uploaded, dropped = dropped)
                            }
                        }

                        is SendOutcome.RetryLater -> {
                            consecutiveFailures++
                            queue.write(lines, file) // 一条不丢，等退避窗口过去
                            applyBackoff(outcome.retryAfterMs)
                            return DiagnosticsUploadResult(uploaded = uploaded, dropped = dropped)
                        }
                    }
                }
                queue.delete(file)
            }
            return finish(uploaded, dropped, sampleRateInfo, clearBackoff = true)
        } finally {
            isUploading.set(false)
        }
    }

    /** 成功收尾：清退避，并把「本次成功之前连续失败了几次」交出去。 */
    @Suppress("LongParameterList") // 就是 `DiagnosticsUploadResult` 的字段，拆开更难读
    private fun finish(
        uploaded: Int,
        dropped: Int,
        sampleRateInfo: Double?,
        clearBackoff: Boolean,
        disableUntilMs: Long? = null,
    ): DiagnosticsUploadResult {
        if (clearBackoff) {
            backoffMs = 0
            resumeAtMs = null
        }
        val recovered = consecutiveFailures
        consecutiveFailures = 0
        return DiagnosticsUploadResult(
            uploaded = uploaded,
            dropped = dropped,
            sampleRateInfo = sampleRateInfo,
            disableUntilMs = disableUntilMs,
            recoveredFailureCount = recovered,
        )
    }

    // region 单批

    private sealed class SendOutcome {
        class Accepted(val response: DiagnosticsUploadResponse) : SendOutcome()

        /** 确定性错误：丢弃该批。[suspendForMs] 非空 = 同时停摆（401/403 停 1h）。 */
        class DropBatch(val suspendForMs: Long?) : SendOutcome()

        /** 暂时性错误：保留队列、退避。[retryAfterMs] 来自 429 的 `Retry-After`。 */
        class RetryLater(val retryAfterMs: Long?) : SendOutcome()
    }

    private fun send(batch: List<DiagnosticsEvent>): SendOutcome {
        val envelope = DiagnosticsEnvelope(
            installID = settings.installID(),
            sessionID = sessionID,
            sandbox = appConfig.isDebugBuild,
            isDebug = appConfig.isDebugBuild,
            locale = appConfig.languageTag,
            sentAtMs = dateProvider.now().time,
            events = batch,
        )
        val result = backend.postDiagnosticsEventsBlocking(envelope.toJson())
        val httpResult = result.getOrElse {
            // 传输层错误（超时 / 断网 / 没有 INTERNET 权限）→ 没有状态码 → 可重试。
            Logger.debug { "诊断上传网络失败，保留队列并退避：$it" }
            return SendOutcome.RetryLater(retryAfterMs = null)
        }
        return classify(httpResult, batch.size)
    }

    @Suppress("MagicNumber")
    private fun classify(result: HTTPResult, batchSize: Int): SendOutcome = when {
        result.isSuccessful() -> SendOutcome.Accepted(DiagnosticsUploadResponse.fromJson(result.body))

        result.responseCode == RDHTTPStatusCodes.UNAUTHORIZED ||
            result.responseCode == RDHTTPStatusCodes.FORBIDDEN -> {
            Logger.warn { "诊断上传鉴权失败（HTTP ${result.responseCode}），丢弃该批并停 1 小时" }
            SendOutcome.DropBatch(suspendForMs = AUTH_SUSPENSION_MS)
        }

        result.responseCode == RDHTTPStatusCodes.TOO_MANY_REQUESTS ->
            SendOutcome.RetryLater(retryAfterMs = result.retryAfterSeconds?.times(1000L))

        RDHTTPStatusCodes.isServerError(result.responseCode) -> SendOutcome.RetryLater(retryAfterMs = null)

        result.responseCode in 400..499 -> {
            Logger.debug { "诊断上传被确定性拒绝（HTTP ${result.responseCode}），丢弃该批（$batchSize 条）" }
            SendOutcome.DropBatch(suspendForMs = null)
        }

        // 3xx 之类不该出现的：当暂时性处理，别丢数据。
        else -> SendOutcome.RetryLater(retryAfterMs = null)
    }

    // endregion

    // region 退避

    private fun applyBackoff(retryAfterMs: Long?) {
        backoffMs = if (backoffMs <= 0) INITIAL_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
        // `Retry-After` 优先，但**不短于**本地退避的当前档位：服务端说「再等久点」听它的，
        // 说「马上再来」不听（诊断不值得为此打穿后端）。与 iOS 同口径。
        val delay = maxOf(retryAfterMs?.coerceAtMost(MAX_BACKOFF_MS) ?: 0L, backoffMs)
        resumeAtMs = dateProvider.now().time + delay
    }

    private fun applyServerBackoff(serverBackoffMs: Long) {
        backoffMs = serverBackoffMs.coerceIn(0L, MAX_BACKOFF_MS)
        resumeAtMs = dateProvider.now().time + backoffMs
    }

    private fun applySuspension(suspendForMs: Long) {
        backoffMs = 0
        resumeAtMs = dateProvider.now().time + suspendForMs
    }

    private fun applyDisableUntil(disableUntilMs: Long) {
        backoffMs = 0
        resumeAtMs = maxOf(disableUntilMs, resumeAtMs ?: disableUntilMs)
    }

    // endregion

    internal companion object {
        /** 退避：30s 起、×2、上限 1h（契约 §1.2）。 */
        const val INITIAL_BACKOFF_MS: Long = 30_000
        const val MAX_BACKOFF_MS: Long = 60 * 60 * 1000

        /** 401/403 的停摆时长。 */
        const val AUTH_SUSPENSION_MS: Long = 60 * 60 * 1000

        /** 单批上限：条数 100（契约 §1.3 硬限）+ 序列化 60 KB（§6-3；服务端 body 上限 64 KB，留信封余量）。 */
        const val MAX_EVENTS_PER_BATCH: Int = 100
        const val MAX_EVENT_BYTES_PER_BATCH: Int = 60 * 1024

        /** 一次 `upload()` 最多连发几批（有积压时尽量排空，但不无限占着单飞闸）。 */
        const val MAX_BATCHES_PER_RUN: Int = 20
    }
}

/** 一次 [DiagnosticsUploader.upload] 的结果。不上行、不落盘，只给 Recorder 与测试看。 */
@Suppress("LongParameterList") // 值类型：这 6 个字段就是「一轮上传发生了什么」的全部内容
internal class DiagnosticsUploadResult(
    /** 成功上传（且已出队）的事件条数。 */
    val uploaded: Int = 0,
    /** 因确定性错误被丢弃的事件条数。 */
    val dropped: Int = 0,
    /** 服务端下发的 info 采样率（有才带，由 Recorder 落盘）。 */
    val sampleRateInfo: Double? = null,
    /** 服务端下发的「到这个时刻前不记不发」（§6-9 的硬开关）。 */
    val disableUntilMs: Long? = null,
    /** 本次成功之前**连续失败过几次**（§6-13：> 0 时 Recorder 补一条 `diag_upload_failed`）。 */
    val recoveredFailureCount: Int = 0,
    /** 本次调用被跳过的原因（`null` = 真的发了）。 */
    val skipped: Skip? = null,
) {

    /** 不是 public enum（内部类型，`ForbiddenPublicEnum` 只约束公开面）。 */
    internal enum class Skip {
        /** 已有一个上传在飞。 */
        IN_FLIGHT,

        /** 处在退避 / 鉴权停摆 / 服务端禁用窗口内。 */
        BACKOFF,

        /** 队列与在飞文件都是空的。 */
        EMPTY,
    }
}
