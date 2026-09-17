package org.revdog.purchases.attributes

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.revdog.purchases.Logger
import org.revdog.purchases.common.filterNotNullValues

/**
 * `collectDeviceIdentifiers()` 采集的那几个键。
 * 结构对照 RC `google/attribution/GoogleDeviceIdentifiersFetcher.kt`。
 *
 * **偏离 RC（被迫，设计 §2「整块不建」的延伸）**：RC 编译期依赖
 * `com.google.android.gms:play-services-ads-identifier` 来调 `AdvertisingIdClient`。
 * 我方**不加这个依赖** —— 一个 IAP SDK 不该把广告 SDK 拖进每个宿主的依赖图
 * （也不该让所有宿主的 Play Console 数据安全表因为我们多一项「广告 ID」）。
 * 代价：`$gpsAdId` 只能**反射**去拿，宿主自己已经引了 ads-identifier 才拿得到，
 * 否则这个键就不采集。失败一律静默降级（这本来就是 best-effort 的归因辅助键）。
 *
 * 采集清单：
 * | 键 | 来源 | 没有依赖时 |
 * |---|---|---|
 * | `$gpsAdId` | 反射 `AdvertisingIdClient.getAdvertisingIdInfo` | **不采集** |
 * | `$androidId` | `Settings.Secure.ANDROID_ID`（无需权限；Android 8+ 起按 app+用户隔离） | 照常 |
 * | `$ip` | 常量 `"true"` —— 让服务端从请求 IP 回填（RC 同款哨兵） | 照常 |
 * | `$deviceVersion` | 常量 `"true"` —— 同上（RC 同款哨兵） | 照常 |
 *
 * ⚠️ **必须在后台线程调用**：`AdvertisingIdClient.getAdvertisingIdInfo` 是阻塞 IO，
 * 在主线程上调它 GMS 自己会抛 `IllegalStateException`。
 */
internal object DeviceIdentifiers {

    /** 没有 `AD_ID` 权限（Android 13+）或用户关了个性化广告时 GMS 回的全零 UUID —— 不是 ID。 */
    private const val NO_PERMISSION_ADVERTISING_ID = "00000000-0000-0000-0000-000000000000"

    private const val ADVERTISING_ID_CLIENT = "com.google.android.gms.ads.identifier.AdvertisingIdClient"

    fun collect(context: Context): Map<String, String> = mapOf(
        SubscriberAttributeKeys.GPS_AD_ID to advertisingId(context),
        SubscriberAttributeKeys.ANDROID_ID to androidId(context),
        SubscriberAttributeKeys.IP to SERVER_FILLED_SENTINEL,
        SubscriberAttributeKeys.DEVICE_VERSION to SERVER_FILLED_SENTINEL,
    ).filterNotNullValues()

    /**
     * 反射拿 GAID。宿主没引 ads-identifier（`ClassNotFoundException`）、GMS 不可用、
     * 超时、IO 失败、被 R8 裁掉 —— 全部落到同一个结论：**没有这个键**。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun advertisingId(context: Context): String? = try {
        val clientClass = Class.forName(ADVERTISING_ID_CLIENT)
        val info = clientClass.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
            ?: return null
        val infoClass = info.javaClass
        val limitAdTracking = infoClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
        if (limitAdTracking) {
            Logger.debug { "用户开了「限制广告跟踪」，不采集 \$gpsAdId" }
            return null
        }
        val id = infoClass.getMethod("getId").invoke(info) as? String
        when {
            id.isNullOrEmpty() -> null
            id == NO_PERMISSION_ADVERTISING_ID -> {
                Logger.warn {
                    "拿到的广告 ID 是全零值：宿主大概没在 manifest 声明 " +
                        "com.google.android.gms.permission.AD_ID（Android 13+ 必需）。不采集 \$gpsAdId"
                }
                null
            }
            else -> id
        }
    } catch (e: ClassNotFoundException) {
        Logger.debug { "宿主未引入 play-services-ads-identifier，跳过 \$gpsAdId（${e.message}）" }
        null
    } catch (e: Throwable) {
        // GooglePlayServicesNotAvailableException / TimeoutException / IOException / NPE /
        // NoSuchMethodError / InvocationTargetException 全在这里收口 —— 一个归因辅助键
        // 绝不值得把宿主搞崩（RC 那边是逐个异常类型 catch，我方走反射只能收口在 Throwable）。
        Logger.debug { "读取广告 ID 失败，跳过 \$gpsAdId：$e" }
        null
    }

    /**
     * `Settings.Secure.ANDROID_ID`。空串 / 全零一律当没有。
     *
     * 契约 §2.4 把它标成 deprecated，但仍在保留键表里；Android 8 起它按
     * (app 签名, 用户) 隔离，实际上就是一个「同设备同 app 恒定」的安装标识。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun androidId(context: Context): String? = try {
        @Suppress("HardwareIds")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it.trimStart('0').isNotEmpty() }
    } catch (e: Throwable) {
        // 定制 ROM 上读 Settings.Secure 抛过 SecurityException / NPE。
        Logger.debug { "读取 ANDROID_ID 失败，跳过 \$androidId：$e" }
        null
    }

    /** RC 同款哨兵：值为 `"true"` 表示「服务端自己从请求里回填」。 */
    const val SERVER_FILLED_SENTINEL: String = "true"

    /** 仅供日志 / 诊断，不上行。 */
    fun deviceVersionLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
}
