package org.revdog.purchases.diagnostics

import android.content.SharedPreferences
import org.revdog.purchases.caching.DeviceCache
import java.util.UUID

/**
 * 诊断的两个持久化标量。结构对照 RC `common/diagnostics/DiagnosticsHelper.kt`
 * （它存的是「连续失败次数」，我方的失败计数只活在内存里 —— 见 [DiagnosticsUploader]）。
 *
 * - `install_id`（`sdk-diagnostics.md` §6-1）：**首次 configure 生成并持久化**，同设备恒定。
 *   服务端对缺失 / 空的 `install_id` 回 400，而 400 是确定性 4xx → 会被丢批 →
 *   整台设备的诊断静默消失。所以这里的 getter **永不返回空**：没有就当场生成并落盘。
 * - `sample_rate_info`（契约 §1.2）：服务端下发的 info 级采样率，端上持久化、下一批生效。
 *
 * **偏离 RC**：RC 给诊断单开一个 SharedPreferences 文件
 * （`com_revenuecat_purchases_<pkg>_preferences_diagnostics`）。我方复用主 prefs 文件、
 * 靠 key 前缀隔离 —— 少一个文件、少一次 `getSharedPreferences` 的磁盘 IO，
 * 而 RC 单开文件的理由（`deleteSharedPreferences` 整体清诊断状态）我方用不上。
 */
internal class DiagnosticsSettings(
    private val preferences: SharedPreferences,
    apiKey: String,
) {

    private val prefix = "${DeviceCache.SHARED_PREFERENCES_PREFIX}$apiKey.diagnostics"
    private val installIdKey = "$prefix.installId"
    private val sampleRateKey = "$prefix.sampleRateInfo"

    /** 同设备恒定的安装标识。没有就生成（永不返回空串）。 */
    @Synchronized
    fun installID(): String {
        preferences.getString(installIdKey, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString().lowercase()
        preferences.edit().putString(installIdKey, generated).apply()
        return generated
    }

    /** `null` = 服务端还没下发过，按 1.0（不采样）处理。 */
    @Synchronized
    fun sampleRateInfo(): Double? =
        if (preferences.contains(sampleRateKey)) {
            preferences.getFloat(sampleRateKey, DEFAULT_SAMPLE_RATE.toFloat()).toDouble()
        } else {
            null
        }

    @Synchronized
    fun setSampleRateInfo(value: Double) {
        preferences.edit().putFloat(sampleRateKey, value.coerceIn(0.0, 1.0).toFloat()).apply()
    }

    internal companion object {
        const val DEFAULT_SAMPLE_RATE: Double = 1.0
    }
}
