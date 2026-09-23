package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.recordExternalEvent
import org.revdog.purchases.diagnostics.recordExternalWarning
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.DiagnosticsRig
import org.revdog.purchases.support.OrchestratorHarness
import org.robolectric.RobolectricTestRunner

/**
 * 混合框架插件的记诊断入口（0.2.0，主代理裁定 10）：`recordDiagnosticsEvent` / `recordDiagnosticsWarning`。
 *
 * 事件名按契约硬限 `^[a-z_]{1,64}$` 校验，不合规打 warn 丢弃、不抛；字段原样透传给既有管线。
 */
@RunWith(RobolectricTestRunner::class)
class ExternalDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = OrchestratorHarness(context, BillingHarness())
        // 编排层 init 会记一条 sdk_configured；这里只看插件记的那些。
        harness.diagnostics.events.clear()
    }

    @Test
    fun `事件名与字段原样透传给 tracker`() {
        val properties = mapOf("option" to "waits_for_log_in_before_sync", "count" to 2, "missing" to null)

        harness.orchestrator.recordDiagnosticsEvent("hybrid_option_ignored", properties)

        assertThat(harness.diagnostics.names()).containsExactly("hybrid_option_ignored")
        assertThat(harness.diagnostics.named("hybrid_option_ignored").single()).isEqualTo(properties)
    }

    @Test
    fun `事件名不合契约时丢弃且不抛`() {
        val invalid = listOf(
            "",
            "Hybrid",
            "hybrid-event",
            "hybrid.event",
            "event1",
            "事件",
            "a".repeat(65),
        )
        invalid.forEach { harness.orchestrator.recordDiagnosticsEvent(it, mapOf("k" to "v")) }

        assertThat(harness.diagnostics.events).isEmpty()
    }

    @Test
    fun `64 个字符的事件名是合法上限`() {
        val name = "a".repeat(64)
        harness.orchestrator.recordDiagnosticsEvent(name, emptyMap())
        assertThat(harness.diagnostics.names()).containsExactly(name)
    }

    @Test
    fun `warning 变成 sdk_warning{code, detail}`() {
        harness.orchestrator.recordDiagnosticsWarning("hybrid_field_fallback", "original_purchase_date")
        harness.orchestrator.recordDiagnosticsWarning("hybrid_package_dropped", null)

        val warnings = harness.diagnostics.named(DiagnosticsTracker.EVENT_SDK_WARNING)
        assertThat(warnings).containsExactly(
            mapOf("code" to "hybrid_field_fallback", "detail" to "original_purchase_date"),
            mapOf("code" to "hybrid_package_dropped", "detail" to null),
        )
    }

    @Test
    fun `真实管线：事件进队列，未知事件名按兜底规则推 level`() {
        val rig = DiagnosticsRig(context, enabled = true)

        rig.recorder.recordExternalEvent("hybrid_offering_dropped", mapOf("offering_id" to "default"))
        rig.recorder.recordExternalWarning("hybrid_duplicate_configure", "same_config")

        val events = rig.events()
        assertThat(events.map { it.type }).containsExactly("hybrid_offering_dropped", DiagnosticsTracker.EVENT_SDK_WARNING)
        assertThat(events[0].level).isEqualTo(DiagnosticsLevel.INFO)
        assertThat(events[0].fields.getString("offering_id")).isEqualTo("default")
        assertThat(events[1].level).isEqualTo(DiagnosticsLevel.WARN)
        assertThat(events[1].fields.getString("code")).isEqualTo("hybrid_duplicate_configure")
        assertThat(events[1].fields.getString("detail")).isEqualTo("same_config")
    }

    @Test
    fun `诊断关闭时是 no-op`() {
        val rig = DiagnosticsRig(context, enabled = false)
        rig.recorder.start()

        rig.recorder.recordExternalEvent("hybrid_option_ignored", mapOf("option" to "x"))
        rig.recorder.recordExternalWarning("hybrid_field_fallback", null)

        assertThat(rig.queue.count()).isZero()
        assertThat(rig.queueFile).doesNotExist()
    }
}
