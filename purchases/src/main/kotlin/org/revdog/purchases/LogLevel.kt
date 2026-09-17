package org.revdog.purchases

import android.util.Log
import dev.drewhamilton.poko.Poko

/**
 * 日志级别。结构对照 RC `LogLevel.kt`，但**不是 enum**：
 * RC 的 `LogLevel` 是 public enum，在它自己的 detekt baseline 里挂着（考古 §8.4 的 24 条遗留之一）。
 * 我方从第一天就守 `ForbiddenPublicEnum`，所以用 `@Poko class` + companion 常量。
 */
@Poko
public class LogLevel private constructor(
    /** 数值越大越安静。 */
    public val severity: Int,
    public val name: String,
) : Comparable<LogLevel> {

    override fun compareTo(other: LogLevel): Int = severity.compareTo(other.severity)

    public companion object {
        @JvmField public val VERBOSE: LogLevel = LogLevel(0, "VERBOSE")
        @JvmField public val DEBUG: LogLevel = LogLevel(1, "DEBUG")
        @JvmField public val INFO: LogLevel = LogLevel(2, "INFO")
        @JvmField public val WARN: LogLevel = LogLevel(3, "WARN")
        @JvmField public val ERROR: LogLevel = LogLevel(4, "ERROR")

        @JvmField public val ALL: List<LogLevel> = listOf(VERBOSE, DEBUG, INFO, WARN, ERROR)
    }
}

/**
 * 宿主可替换的日志出口（对照 RC `LogHandler.kt`）。
 * 存在理由：宿主常把 SDK 日志接进自家 Crashlytics / 结构化日志。
 */
public interface LogHandler {
    public fun log(level: LogLevel, message: String, throwable: Throwable?)
}

internal object AndroidLogHandler : LogHandler {
    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(Logger.TAG, message, throwable)
            LogLevel.DEBUG -> Log.d(Logger.TAG, message, throwable)
            LogLevel.INFO -> Log.i(Logger.TAG, message, throwable)
            LogLevel.WARN -> Log.w(Logger.TAG, message, throwable)
            else -> Log.e(Logger.TAG, message, throwable)
        }
    }
}

/**
 * 内部日志门面。
 *
 * 消息一律用 lambda 传：拼串在被过滤掉的级别上不该发生（RC 同款 `log(intent) { ... }` 形状）。
 * **绝不打印 apiKey、purchaseToken 原文或 app_user_id 明文以外的用户数据。**
 */
internal object Logger {

    const val TAG: String = "RevenueDog"

    @Volatile
    var logLevel: LogLevel = LogLevel.INFO

    @Volatile
    var handler: LogHandler = AndroidLogHandler

    inline fun verbose(throwable: Throwable? = null, message: () -> String) = log(LogLevel.VERBOSE, throwable, message)
    inline fun debug(throwable: Throwable? = null, message: () -> String) = log(LogLevel.DEBUG, throwable, message)
    inline fun info(throwable: Throwable? = null, message: () -> String) = log(LogLevel.INFO, throwable, message)
    inline fun warn(throwable: Throwable? = null, message: () -> String) = log(LogLevel.WARN, throwable, message)
    inline fun error(throwable: Throwable? = null, message: () -> String) = log(LogLevel.ERROR, throwable, message)

    inline fun log(level: LogLevel, throwable: Throwable? = null, message: () -> String) {
        if (level >= logLevel) {
            handler.log(level, message(), throwable)
        }
    }
}
