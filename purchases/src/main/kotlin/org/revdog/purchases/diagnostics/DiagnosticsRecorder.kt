package org.revdog.purchases.diagnostics

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.VisibleForTesting
import org.revdog.purchases.Logger
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.Dispatcher
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 延迟执行 + 可取消。抽成接口只为一件事：**测试里不许真的睡 30 秒**。
 * 生产实现 [HandlerDiagnosticsScheduler] 就是诊断线程上的一个 `Handler`。
 */
internal interface DiagnosticsScheduler {
    fun schedule(delayMs: Long, action: Runnable)
    fun cancel(action: Runnable)
}

internal class HandlerDiagnosticsScheduler(private val handler: Handler) : DiagnosticsScheduler {
    override fun schedule(delayMs: Long, action: Runnable) {
        handler.postDelayed(action, delayMs)
    }

    override fun cancel(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

/**
 * 诊断记录入口 + 采样 + 上传触发。
 * 结构对照 RC `common/diagnostics/DiagnosticsTracker.kt`（记录）+ `DiagnosticsSynchronizer`（触发），
 * 触发规则与采样对齐 iOS `DiagnosticsRecorder.swift`。
 *
 * **触发上传（四条，任一命中即发，`sdk-diagnostics.md` §2）**：
 * 1. 队列 ≥ 20 条；
 * 2. 前台每 30s 定时（**有事件才发**）；
 * 3. 进后台时；
 * 4. 任一 `warn` / `error` 级事件入队后 2s 防抖（§6-8：「付了钱确认不了」不能等下一个 30s）。
 *
 * **采样**：`sample_rate_info` 由服务端下发、端上持久化，**只作用于 info**；warn / error 恒 100%。
 *
 * **开关**：`diagnosticsEnabled = false` → 不记不发、清空盘上残留，**此后零 I/O 零网络**。
 *
 * **线程**：`track()` 可以从任何线程调（编排 / 业务 HTTP / billing 线程都会调它）。
 * 它只做三件廉价的事 —— 取 level、取时间与身份、采样判定 —— 然后把「建事件 + 落盘 + 触发上传」
 * 整段丢到**专用诊断线程**（对照 RC 的 `revenuecat-events-thread`）。
 * 磁盘 IO 与网络因此永远不在主线程、也不在 billing 线程上。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class DiagnosticsRecorder(
    private val queue: DiagnosticsQueue,
    private val uploader: DiagnosticsUploader,
    private val settings: DiagnosticsSettings,
    /** 专用诊断线程。所有磁盘 IO 与上传都在它上面串行执行。 */
    private val dispatcher: Dispatcher,
    private val scheduler: DiagnosticsScheduler,
    /** **记录那一刻**的身份（§6-2）。一批事件可能横跨一次 logIn，所以身份必须逐条取。 */
    private val appUserIDProvider: () -> String?,
    private val enabled: Boolean,
    private val dateProvider: DateProvider = DefaultDateProvider(),
    private val random: () -> Double = { Math.random() },
    private val idProvider: () -> String = { UUID.randomUUID().toString().lowercase() },
    private val startsPeriodicFlush: Boolean = true,
    /**
     * 本实例**自己建**的诊断线程（生产装配会传；测试注入同步 dispatcher 时为 `null`）。
     * [close] 要把它退掉 —— 与 `BillingWrapper.ownedBackgroundThread` 同纪律，
     * 不然重复 `configure` 每次都漏一条线程。
     */
    private val ownedThread: HandlerThread? = null,
) : DiagnosticsTracker {

    /** 会话内单调序号（§6-5）：同毫秒的事件靠它排序。 */
    private val seq = AtomicLong(0)

    /** info 采样率。默认 1.0 = 不采样。 */
    @Volatile
    @VisibleForTesting
    internal var sampleRateInfo: Double = DiagnosticsSettings.DEFAULT_SAMPLE_RATE
        private set

    /**
     * 服务端硬开关（§6-9 的 `disable_until_ms`）：该时刻前**不记不发**。
     *
     * 只活在本进程内 —— 重启后最多多发一批，服务端再回一次 202 就又停了，不值得为它落盘
     * （与 iOS 同款决定）。
     */
    @Volatile
    private var disabledUntilMs: Long? = null

    @Volatile
    private var didStart = false

    @Volatile
    private var closed = false

    // 两个定时任务都只做一件事：**把活丢回诊断线程**。
    // 调度器自己跑在一条 Handler 线程上（生产装配），磁盘 IO 与网络一律不在它上面做 ——
    // 这样「队列与上传只被一条线程串行碰」这条纪律在定时路径上也成立。
    private val debounceRunnable = Runnable { dispatcher.enqueue(Runnable { flushNow() }) }
    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (closed) return
            dispatcher.enqueue(
                Runnable {
                    // 前台才发（后台由 [onAppBackgrounded] 那一次负责），且**有事件才发**。
                    if (!closed && !appIsBackgrounded() && queue.count() > 0) flushNow()
                },
            )
            if (!closed) scheduler.schedule(PERIODIC_INTERVAL_MS, this)
        }
    }

    /** 由编排层喂进来（`AppConfig.isAppBackgrounded`）。 */
    @Volatile
    var appIsBackgrounded: () -> Boolean = { false }

    // region 生命周期

    /** `configure` 时调一次。 */
    fun start() {
        if (didStart) return
        didStart = true
        if (!enabled) {
            // 关掉诊断 → 盘上不该再留东西（上一版开着的时候写下的那些也一并清）。
            dispatcher.enqueue(Runnable { queue.clear() })
            Logger.debug { "诊断已关闭（diagnosticsEnabled = false），本地队列已清空" }
            return
        }
        dispatcher.enqueue(
            Runnable {
                settings.sampleRateInfo()?.let { sampleRateInfo = it.coerceIn(0.0, 1.0) }
                // §6-8：先补发上次留下的在飞文件（进程被杀 / 上传失败）。
                if (queue.inflightFiles().isNotEmpty()) flushNow()
            },
        )
        if (startsPeriodicFlush) scheduler.schedule(PERIODIC_INTERVAL_MS, periodicRunnable)
    }

    fun close() {
        closed = true
        scheduler.cancel(debounceRunnable)
        scheduler.cancel(periodicRunnable)
        dispatcher.close()
        // quitSafely：让已经排进去的落盘任务跑完再退 looper（同 BillingWrapper）。
        ownedThread?.quitSafely()
    }

    // endregion

    // region 记录

    @Suppress("ReturnCount")
    override fun track(name: String, properties: Map<String, Any?>) {
        Logger.log(org.revdog.purchases.LogLevel.VERBOSE) { "diagnostics[$name] $properties" }
        if (!enabled || closed) return
        if (isTemporarilyDisabled) return

        val level = DiagnosticsLevels.levelFor(name, properties)
        // 采样只作用于 info；warn / error 恒 100%（契约 §1.2）。
        if (level == DiagnosticsLevel.INFO && sampleRateInfo < 1.0) {
            if (sampleRateInfo <= 0.0 || random() >= sampleRateInfo) return
        }

        // 时间与身份在**调用点**取；建事件 / 落盘 / 触发上传在诊断线程上做。
        val tsMs = dateProvider.now().time
        val appUserID = runCatching { appUserIDProvider() }.getOrNull()
        dispatcher.enqueue(
            Runnable {
                val dropped = queue.append(newEvent(name, level, properties, tsMs, appUserID))
                if (dropped > 0) recordQueueOverflow(dropped, appUserID)
                afterRecord(level)
            },
        )
    }

    /**
     * 队列溢出告警。**这一条自己不再触发溢出告警**（否则会自激成无限告警），
     * 也不参与 [afterRecord] 的防抖 —— 它跟着那条把队列撑满的事件一起走。
     */
    private fun recordQueueOverflow(dropped: Int, appUserID: String?) {
        queue.append(
            newEvent(
                name = DiagnosticsTracker.EVENT_SDK_WARNING,
                level = DiagnosticsLevel.WARN,
                properties = mapOf(
                    "code" to DiagnosticsWarningCode.QUEUE_OVERFLOW,
                    "detail" to "dropped_oldest=$dropped",
                ),
                tsMs = dateProvider.now().time,
                appUserID = appUserID,
            ),
        )
    }

    private fun newEvent(
        name: String,
        level: String,
        properties: Map<String, Any?>,
        tsMs: Long,
        appUserID: String?,
    ): DiagnosticsEvent = DiagnosticsEvent.create(
        id = idProvider(),
        tsMs = tsMs,
        appUserID = appUserID,
        seq = seq.incrementAndGet(),
        type = name,
        level = level,
        fields = properties,
    )

    private fun afterRecord(level: String) {
        when {
            queue.count() >= UPLOAD_THRESHOLD -> {
                scheduler.cancel(debounceRunnable)
                flushNow()
            }
            // §6-8：warn 与 error 同样触发 2s 防抖 —— 连着炸出来的错误合成一次上传。
            level != DiagnosticsLevel.INFO -> {
                scheduler.cancel(debounceRunnable)
                scheduler.schedule(ERROR_DEBOUNCE_MS, debounceRunnable)
            }
        }
    }

    // endregion

    // region 上传触发

    /** 进后台：把攒着的事件冲出去。 */
    fun onAppBackgrounded() {
        if (!enabled || closed) return
        dispatcher.enqueue(Runnable { if (queue.count() > 0) flushNow() })
    }

    /** 立刻尝试上传（内部有单飞闸与退避）。**必须在诊断线程上调用。** */
    @VisibleForTesting
    @Suppress("ReturnCount")
    internal fun flushNow(): DiagnosticsUploadResult {
        if (!enabled || isTemporarilyDisabled) {
            return DiagnosticsUploadResult(skipped = DiagnosticsUploadResult.Skip.BACKOFF)
        }
        val result = uploader.upload()
        result.sampleRateInfo?.let { rate ->
            val clamped = rate.coerceIn(0.0, 1.0)
            if (clamped != sampleRateInfo) {
                sampleRateInfo = clamped
                settings.setSampleRateInfo(clamped)
                Logger.debug { "诊断 info 采样率更新为 $clamped" }
            }
        }
        result.disableUntilMs?.let { until ->
            disabledUntilMs = until
            queue.clear() // 服务端说了停，盘上就不该再留着
            Logger.info { "诊断被服务端临时关闭至 $until" }
            return result
        }
        // §6-13：上传失败**从不**触发新的诊断请求，只在本地计数；恢复之后补记一条告警。
        if (result.recoveredFailureCount > 0) {
            queue.append(
                newEvent(
                    name = DiagnosticsTracker.EVENT_SDK_WARNING,
                    level = DiagnosticsLevel.WARN,
                    properties = mapOf(
                        "code" to DiagnosticsWarningCode.DIAG_UPLOAD_FAILED,
                        "detail" to "consecutive=${result.recoveredFailureCount}",
                    ),
                    tsMs = dateProvider.now().time,
                    appUserID = runCatching { appUserIDProvider() }.getOrNull(),
                ),
            )
        }
        return result
    }

    /** 从任意线程要求刷一次（宿主 / 编排层用）。 */
    fun flush() {
        if (!enabled || closed) return
        dispatcher.enqueue(Runnable { flushNow() })
    }

    // endregion

    val isTemporarilyDisabled: Boolean
        get() = disabledUntilMs?.let { dateProvider.now().time < it } == true

    @VisibleForTesting
    internal fun queuedEvents(): List<DiagnosticsEvent> = queue.allEvents()

    internal companion object {
        /** 队列达到这个条数立刻发。 */
        const val UPLOAD_THRESHOLD: Int = 20

        /** warn / error 级事件的防抖窗口。 */
        const val ERROR_DEBOUNCE_MS: Long = 2_000

        /** 前台定时上传间隔。 */
        const val PERIODIC_INTERVAL_MS: Long = 30_000

        /** 诊断线程名。带我方前缀，方便在 ANR trace 里一眼认出来（对照 RC `revenuecat-events-thread`）。 */
        const val THREAD_NAME: String = "revdog-diagnostics"
    }
}
