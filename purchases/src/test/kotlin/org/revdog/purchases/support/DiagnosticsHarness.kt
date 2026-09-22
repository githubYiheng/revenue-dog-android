package org.revdog.purchases.support

import android.content.Context
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.revdog.purchases.diagnostics.DiagnosticsRecorder
import org.revdog.purchases.diagnostics.DiagnosticsScheduler
import org.revdog.purchases.diagnostics.DiagnosticsSettings
import org.revdog.purchases.diagnostics.DiagnosticsUploader
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import java.io.File
import java.util.Date

/**
 * 假调度器：**测试里不许真的睡 2 秒 / 30 秒**。
 * 把 `schedule` 的任务记下来，[runPending] 才真跑。
 */
internal class FakeDiagnosticsScheduler : DiagnosticsScheduler {

    val scheduled: MutableList<Pair<Long, Runnable>> = mutableListOf()

    override fun schedule(delayMs: Long, action: Runnable) {
        scheduled += delayMs to action
    }

    override fun cancel(action: Runnable) {
        scheduled.removeAll { it.second === action }
    }

    /** 跑掉当前排队的任务（跑的过程中新排进来的不在这一轮，避免定时任务自排队导致死循环）。 */
    fun runPending() {
        val snapshot = scheduled.toList()
        scheduled.clear()
        snapshot.forEach { it.second.run() }
    }

    fun delays(): List<Long> = scheduled.map { it.first }
}

/**
 * 诊断管线的测试装配：真实 [DiagnosticsQueue]（真文件）+ 真实 [DiagnosticsUploader]
 * + 假后端（[FakeHTTPClient]）+ 同步 dispatcher + 可拨的时钟。
 *
 * 队列目录每个 rig 一个（用例之间不串味）。
 */
internal class DiagnosticsRig(
    context: Context,
    enabled: Boolean = true,
    var nowMs: Long = FIXED_NOW_MS,
    val appUserID: String? = "user-42",
    startsPeriodicFlush: Boolean = false,
    prefsName: String = "diag-${System.nanoTime()}",
    isDebugBuild: Boolean = false,
) {

    val directory: File = File(context.cacheDir, "diagnostics-${System.nanoTime()}")

    private val dateProvider = DateProvider { Date(nowMs) }

    val appConfig: AppConfig = AppConfig(
        context = context,
        apiKey = FakeHTTPClient.TEST_API_KEY,
        baseURL = FakeHTTPClient.TEST_BASE_URL,
        purchasesAreCompletedBy = org.revdog.purchases.PurchasesAreCompletedBy.REVENUE_DOG,
        isDebugBuild = isDebugBuild,
        diagnosticsEnabled = enabled,
    ).apply { isAppBackgrounded = false }

    val httpClient: FakeHTTPClient = FakeHTTPClient(appConfig, ETagManager(context))

    val backend: Backend = Backend(httpClient, DirectDispatcher())

    val settings: DiagnosticsSettings = DiagnosticsSettings(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        FakeHTTPClient.TEST_API_KEY,
    )

    val queue: DiagnosticsQueue = DiagnosticsQueue(directory)

    val uploader: DiagnosticsUploader = DiagnosticsUploader(
        backend = backend,
        queue = queue,
        settings = settings,
        appConfig = appConfig,
        sessionID = SESSION_ID,
        dateProvider = dateProvider,
    )

    val scheduler: FakeDiagnosticsScheduler = FakeDiagnosticsScheduler()

    /** 采样用的伪随机：默认 0（<任何采样率 → 恒采样）。 */
    var randomValue: Double = 0.0

    private var idCounter = 0

    val recorder: DiagnosticsRecorder = DiagnosticsRecorder(
        queue = queue,
        uploader = uploader,
        settings = settings,
        dispatcher = DirectDispatcher(),
        scheduler = scheduler,
        appUserIDProvider = { appUserID },
        enabled = enabled,
        dateProvider = dateProvider,
        random = { randomValue },
        idProvider = { "evt-${++idCounter}" },
        startsPeriodicFlush = startsPeriodicFlush,
    )

    init {
        // 与生产 `create` 一致：`http_error` 的记录点在 HTTP 层。
        // `/v1/diagnostics/events` 自己被 `Endpoint.recordsHTTPError = false` 挡掉，
        // 所以上传失败**不会**再生出一条事件（自激是这条管线最致命的失败模式）。
        httpClient.diagnostics = recorder
    }

    val queueFile: File get() = File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)

    fun events(): List<DiagnosticsEvent> = queue.allEvents()

    fun diagnosticsRequests() = httpClient.recordedRequests.filter { it.fullURL.path == "/v1/diagnostics/events" }

    /** 202 成功响应。 */
    fun enqueueAccepted(count: Int = 1, extra: String = "") {
        httpClient.enqueue(202, """{"accepted":$count,"dropped":0$extra}""")
    }

    companion object {
        const val FIXED_NOW_MS: Long = 1_789_000_000_000L
        const val SESSION_ID: String = "session-test-1"
    }
}
