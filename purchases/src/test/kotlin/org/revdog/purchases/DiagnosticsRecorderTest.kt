package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsLevels
import org.revdog.purchases.diagnostics.DiagnosticsRecorder
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.DiagnosticsWarningCode
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.support.DiagnosticsRig
import org.robolectric.RobolectricTestRunner

/**
 * 记录面：采样、触发规则、开关、level 推导、溢出告警。
 *
 * 触发规则来源：`sdk-diagnostics.md` §2 的四条 + §6-8（warn 与 error 同样 2s 防抖）。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsRecorderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // region 开关

    @Test
    fun `diagnosticsEnabled=false 时零 I O 零网络`() {
        val rig = DiagnosticsRig(context, enabled = false)
        rig.recorder.start()

        repeat(50) { rig.recorder.track(DiagnosticsTracker.EVENT_RECEIPT_POST, mapOf("outcome" to "retryable")) }
        rig.recorder.onAppBackgrounded()
        rig.recorder.flush()

        assertThat(rig.queueFile).doesNotExist()
        assertThat(rig.queue.count()).isZero()
        assertThat(rig.diagnosticsRequests()).isEmpty()
        assertThat(rig.scheduler.scheduled).isEmpty()
    }

    @Test
    fun `关掉诊断时 start 会清掉上一版留在盘上的队列`() {
        val enabled = DiagnosticsRig(context, enabled = true)
        enabled.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        assertThat(enabled.queue.count()).isEqualTo(1)

        // 同一个目录、这次关掉诊断。
        val disabled = DiagnosticsRig(context, enabled = false)
        val sameDirQueue = DiagnosticsQueue(enabled.directory)
        val recorder = DiagnosticsRecorder(
            queue = sameDirQueue,
            uploader = disabled.uploader,
            settings = disabled.settings,
            dispatcher = org.revdog.purchases.support.DirectDispatcher(),
            scheduler = disabled.scheduler,
            appUserIDProvider = { null },
            enabled = false,
        )
        recorder.start()

        assertThat(sameDirQueue.count()).isZero()
    }

    // endregion

    // region 触发

    @Test
    fun `攒到 20 条立刻发`() {
        val rig = DiagnosticsRig(context)
        rig.enqueueAccepted(DiagnosticsRecorder.UPLOAD_THRESHOLD)

        repeat(DiagnosticsRecorder.UPLOAD_THRESHOLD - 1) {
            rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        }
        assertThat(rig.diagnosticsRequests()).isEmpty()

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)

        assertThat(rig.diagnosticsRequests()).hasSize(1)
        assertThat(rig.queue.count()).isZero()
    }

    @Test
    fun `warn 与 error 走 2s 防抖，连着几条只发一次`() {
        val rig = DiagnosticsRig(context)

        rig.recorder.track(
            DiagnosticsTracker.EVENT_RECEIPT_POST,
            mapOf("outcome" to DiagnosticsTracker.OUTCOME_RETRYABLE),
        )
        rig.recorder.track(
            DiagnosticsTracker.EVENT_RECEIPT_POST,
            mapOf("outcome" to DiagnosticsTracker.OUTCOME_RETRYABLE),
        )

        // 还没发，只排了一次防抖（第二次把第一次取消了）。
        assertThat(rig.diagnosticsRequests()).isEmpty()
        assertThat(rig.scheduler.delays()).containsExactly(DiagnosticsRecorder.ERROR_DEBOUNCE_MS)

        rig.enqueueAccepted(2)
        rig.scheduler.runPending()

        assertThat(rig.diagnosticsRequests()).hasSize(1)
        assertThat(rig.queue.count()).isZero()
    }

    @Test
    fun `info 级事件不触发防抖`() {
        val rig = DiagnosticsRig(context)

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)

        assertThat(rig.scheduler.scheduled).isEmpty()
        assertThat(rig.diagnosticsRequests()).isEmpty()
    }

    @Test
    fun `进后台把攒着的冲出去；队列空时不发`() {
        val rig = DiagnosticsRig(context)

        rig.recorder.onAppBackgrounded()
        assertThat(rig.diagnosticsRequests()).isEmpty()

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.enqueueAccepted()
        rig.recorder.onAppBackgrounded()

        assertThat(rig.diagnosticsRequests()).hasSize(1)
    }

    @Test
    fun `前台定时：有事件才发，且自排下一轮`() {
        val rig = DiagnosticsRig(context, startsPeriodicFlush = true)
        rig.recorder.start()
        assertThat(rig.scheduler.delays()).contains(DiagnosticsRecorder.PERIODIC_INTERVAL_MS)

        // 队列空 → 不发，但仍然排下一轮。
        rig.scheduler.runPending()
        assertThat(rig.diagnosticsRequests()).isEmpty()
        assertThat(rig.scheduler.delays()).containsExactly(DiagnosticsRecorder.PERIODIC_INTERVAL_MS)

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.enqueueAccepted()
        rig.scheduler.runPending()
        assertThat(rig.diagnosticsRequests()).hasSize(1)
    }

    @Test
    fun `后台时定时任务不发（进后台那一次已经负责了）`() {
        val rig = DiagnosticsRig(context, startsPeriodicFlush = true)
        rig.recorder.appIsBackgrounded = { true }
        rig.recorder.start()
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.enqueueAccepted()

        rig.scheduler.runPending()

        assertThat(rig.diagnosticsRequests()).isEmpty()
    }

    @Test
    fun `start 时补发盘上遗留的在飞文件`() {
        val rig = DiagnosticsRig(context)
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.queue.rotate()
        rig.enqueueAccepted()

        rig.recorder.start()

        assertThat(rig.diagnosticsRequests()).hasSize(1)
        assertThat(rig.queue.inflightFiles()).isEmpty()
    }

    // endregion

    // region 采样

    @Test
    fun `采样只作用于 info；warn 与 error 不采样`() {
        val rig = DiagnosticsRig(context)
        rig.enqueueAccepted(1, extra = ""","sample_rate_info":0.5""")
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.recorder.flushNow()
        assertThat(rig.recorder.sampleRateInfo).isEqualTo(0.5)

        rig.randomValue = 0.9 // >= 0.5 → info 被采掉
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        assertThat(rig.queue.count()).isZero()

        rig.recorder.track(
            DiagnosticsTracker.EVENT_RECEIPT_POST,
            mapOf("outcome" to DiagnosticsTracker.OUTCOME_RETRYABLE),
        )
        assertThat(rig.queue.allEvents().map { it.level }).containsExactly(DiagnosticsLevel.ERROR)

        rig.randomValue = 0.1 // < 0.5 → info 进得来
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        assertThat(rig.queue.count()).isEqualTo(2)
    }

    @Test
    fun `采样率落盘，下一批生效`() {
        val rig = DiagnosticsRig(context)
        rig.enqueueAccepted(1, extra = ""","sample_rate_info":0.25""")
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.recorder.flushNow()

        assertThat(rig.settings.sampleRateInfo()).isEqualTo(0.25)
    }

    @Test
    fun `采样率为 0 时 info 全被采掉`() {
        val rig = DiagnosticsRig(context)
        rig.settings.setSampleRateInfo(0.0)
        rig.recorder.start()

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)

        assertThat(rig.queue.count()).isZero()
    }

    // endregion

    // region 服务端硬开关

    @Test
    fun `disable_until_ms 期间不记不发且盘上清空`() {
        val rig = DiagnosticsRig(context)
        val until = DiagnosticsRig.FIXED_NOW_MS + 86_400_000
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.enqueueAccepted(1, extra = ""","disable_until_ms":$until""")

        rig.recorder.flushNow()

        assertThat(rig.recorder.isTemporarilyDisabled).isTrue()
        assertThat(rig.queue.count()).isZero()
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        assertThat(rig.queue.count()).isZero()

        // 窗口过去之后恢复记录。
        rig.nowMs = until + 1
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        assertThat(rig.queue.count()).isEqualTo(1)
    }

    // endregion

    // region 溢出与自身健康

    @Test
    fun `队列溢出补一条 sdk_warning 且它自己不再触发溢出`() {
        val rig = DiagnosticsRig(context)
        // 直接把队列填满（不经 recorder，免得 20 条阈值一路触发上传）。
        repeat(DiagnosticsQueue.MAX_EVENTS) {
            rig.queue.append(
                org.revdog.purchases.diagnostics.DiagnosticsEvent.create(
                    id = "seed-$it",
                    tsMs = DiagnosticsRig.FIXED_NOW_MS,
                    appUserID = "user-42",
                    seq = it.toLong(),
                    type = DiagnosticsTracker.EVENT_SDK_CONFIGURED,
                    level = DiagnosticsLevel.INFO,
                    fields = emptyMap(),
                ),
            )
        }
        // 上传一律失败 → 事件留在在飞文件里，断言看得到落盘内容。
        rig.httpClient.defaultResponse = Result.failure(java.io.IOException("offline"))

        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)

        val persisted = rig.queue.inflightFiles().flatMap { file ->
            rig.queue.linesOf(file).mapNotNull { org.revdog.purchases.diagnostics.DiagnosticsEvent.fromLine(it) }
        } + rig.queue.allEvents()
        val warnings = persisted.filter { it.type == DiagnosticsTracker.EVENT_SDK_WARNING }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().fields.getString("code")).isEqualTo(DiagnosticsWarningCode.QUEUE_OVERFLOW)
        assertThat(warnings.single().fields.getString("detail")).isEqualTo("dropped_oldest=1")
        assertThat(warnings.single().level).isEqualTo(DiagnosticsLevel.WARN)
        // 最旧那条被丢掉了，总条数不超上限。
        assertThat(persisted.map { it.id }).doesNotContain("seed-0")
        assertThat(persisted).hasSize(DiagnosticsQueue.MAX_EVENTS)
    }

    @Test
    fun `上传恢复之后补记一条 diag_upload_failed，且诊断失败本身不发请求`() {
        val rig = DiagnosticsRig(context)
        rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED)
        rig.httpClient.enqueue(503, "")
        rig.recorder.flushNow()
        assertThat(rig.diagnosticsRequests()).hasSize(1) // 失败本身不再触发新请求

        rig.nowMs += org.revdog.purchases.diagnostics.DiagnosticsUploader.INITIAL_BACKOFF_MS
        rig.enqueueAccepted()
        rig.recorder.flushNow()

        val warnings = rig.queue.allEvents().filter { it.type == DiagnosticsTracker.EVENT_SDK_WARNING }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().fields.getString("code")).isEqualTo(DiagnosticsWarningCode.DIAG_UPLOAD_FAILED)
    }

    // endregion

    // region 字段与 level

    @Test
    fun `每条事件都带记录时刻的身份与单调 seq`() {
        val rig = DiagnosticsRig(context)

        repeat(3) { rig.recorder.track(DiagnosticsTracker.EVENT_SDK_CONFIGURED) }

        val events = rig.queue.allEvents()
        assertThat(events.map { it.appUserID }).containsOnly("user-42")
        assertThat(events.mapNotNull { it.seq }).containsExactly(1L, 2L, 3L)
        assertThat(events.map { it.tsMs }).containsOnly(DiagnosticsRig.FIXED_NOW_MS)
    }

    @Test
    fun `null 字段被丢掉、字符串截到 200`() {
        val rig = DiagnosticsRig(context)

        rig.recorder.track(
            DiagnosticsTracker.EVENT_SDK_CONFIGURED,
            mapOf("kept" to "v".repeat(500), "gone" to null),
        )

        val fields = rig.queue.allEvents().single().fields
        assertThat(fields.has("gone")).isFalse()
        assertThat(fields.getString("kept")).hasSize(200)
    }

    @Test
    fun `level 推导表`() {
        fun level(name: String, fields: Map<String, Any?> = emptyMap()) = DiagnosticsLevels.levelFor(name, fields)

        assertThat(level(DiagnosticsTracker.EVENT_SDK_CONFIGURED)).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(level(DiagnosticsTracker.EVENT_SDK_WARNING, mapOf("code" to "x")))
            .isEqualTo(DiagnosticsLevel.WARN)
        assertThat(level(DiagnosticsTracker.EVENT_HTTP_ERROR)).isEqualTo(DiagnosticsLevel.ERROR)
        assertThat(
            level(
                DiagnosticsTracker.EVENT_RECEIPT_POST,
                mapOf("outcome" to DiagnosticsTracker.OUTCOME_SUCCESS),
            ),
        ).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(
            level(
                DiagnosticsTracker.EVENT_RECEIPT_POST,
                mapOf("outcome" to DiagnosticsTracker.OUTCOME_RETRYABLE),
            ),
        ).isEqualTo(DiagnosticsLevel.ERROR)
        assertThat(level(DiagnosticsTracker.EVENT_PURCHASE_PENDING)).isEqualTo(DiagnosticsLevel.WARN)
        // 用户取消不是错误（对齐 iOS）。
        assertThat(
            level(
                DiagnosticsTracker.EVENT_PURCHASE_RESULT,
                mapOf("outcome" to DiagnosticsTracker.OUTCOME_CANCELLED, "error_code" to "purchaseCancelledError"),
            ),
        ).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(
            level(DiagnosticsTracker.EVENT_PURCHASE_RESULT, mapOf("error_code" to "storeProblemError")),
        ).isEqualTo(DiagnosticsLevel.ERROR)
        assertThat(
            level(
                DiagnosticsTracker.EVENT_CONSUME_DECISION,
                mapOf("decision" to BillingWrapper.DECISION_MISSING_SHOULD_CONSUME),
            ),
        ).isEqualTo(DiagnosticsLevel.ERROR)
        assertThat(
            level(
                DiagnosticsTracker.EVENT_CONSUME_DECISION,
                mapOf("decision" to BillingWrapper.DECISION_ACK_SELF_PROTECT),
            ),
        ).isEqualTo(DiagnosticsLevel.WARN)
        assertThat(
            level(DiagnosticsTracker.EVENT_CONSUME_DECISION, mapOf("decision" to BillingWrapper.DECISION_CONSUMED)),
        ).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(
            level(DiagnosticsTracker.EVENT_BILLING_CONNECTION, mapOf("response_code" to "OK")),
        ).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(
            level(DiagnosticsTracker.EVENT_BILLING_CONNECTION, mapOf("response_code" to "SERVICE_DISCONNECTED")),
        ).isEqualTo(DiagnosticsLevel.WARN)
    }

    @Test
    fun `level 推导用的 decision 常量与 BillingWrapper 同源`() {
        assertThat(DiagnosticsLevels.DECISION_MISSING_SHOULD_CONSUME)
            .isEqualTo(BillingWrapper.DECISION_MISSING_SHOULD_CONSUME)
        assertThat(DiagnosticsLevels.DECISION_SKIPPED_NOT_PURCHASED)
            .isEqualTo(BillingWrapper.DECISION_SKIPPED_NOT_PURCHASED)
        assertThat(DiagnosticsLevels.DECISION_ACK_SELF_PROTECT)
            .isEqualTo(BillingWrapper.DECISION_ACK_SELF_PROTECT)
    }

    @Test
    fun `warn 默认实现走 sdk_warning`() {
        val rig = DiagnosticsRig(context)

        rig.recorder.warn(DiagnosticsWarningCode.OFFERINGS_CACHE_FALLBACK, "detail=x")

        val event = rig.queue.allEvents().single()
        assertThat(event.type).isEqualTo(DiagnosticsTracker.EVENT_SDK_WARNING)
        assertThat(event.level).isEqualTo(DiagnosticsLevel.WARN)
        assertThat(event.fields.getString("code")).isEqualTo(DiagnosticsWarningCode.OFFERINGS_CACHE_FALLBACK)
        assertThat(event.fields.getString("detail")).isEqualTo("detail=x")
    }

    // endregion
}
