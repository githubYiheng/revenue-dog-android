package org.revdog.purchases.common

import android.os.Handler
import android.os.Looper
import org.revdog.purchases.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 请求抖动（结构对照 RC `common/Dispatcher.kt` 的 `Delay`）。
 *
 * **不是重试退避**，是抖动：后台 App 会同时醒来打垮后端（坑 34）。
 */
internal enum class Delay(val minDelay: Duration, val maxDelay: Duration) {
    NONE(Duration.ZERO, Duration.ZERO),
    DEFAULT(Duration.ZERO, JITTER_DELAY),
    LONG(JITTER_DELAY, JITTER_LONG_DELAY),
    ;

    companion object {
        /**
         * 前台请求是在响应用户或宿主刚刚做的事，不能被抖动拖住；后台请求一律抖开
         * （RC 原注释：「Backgrounded apps tend to wake up together…」）。
         */
        fun jitterOnlyIfInBackground(appInBackground: Boolean): Delay = if (appInBackground) DEFAULT else NONE
    }
}

private val JITTER_DELAY = 5.seconds
private val JITTER_LONG_DELAY = 10.seconds

/**
 * 后台单线程执行器（结构对照 RC `Dispatcher`）。
 *
 * 铁律（坑 39）：后台线程抛出的异常**不许被静默吞掉** —— catch 住之后 rethrow 到主线程，
 * 让它像未捕获异常一样可见。
 */
internal open class Dispatcher(
    private val executorService: ExecutorService,
    private val mainHandler: Handler? = Handler(Looper.getMainLooper()),
) {

    open fun enqueue(command: Runnable, delay: Delay = Delay.NONE) {
        synchronized(executorService) {
            if (executorService.isShutdown) return
            val commandHandlingExceptions = Runnable {
                try {
                    command.run()
                } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                    Logger.error(e) { "后台任务抛出异常，rethrow 到主线程以免被静默吞掉" }
                    mainHandler?.post { throw e }
                }
            }
            if (delay != Delay.NONE && executorService is ScheduledExecutorService) {
                val delayToApply =
                    (delay.minDelay.inWholeMilliseconds..delay.maxDelay.inWholeMilliseconds).random()
                executorService.schedule(commandHandlingExceptions, delayToApply, TimeUnit.MILLISECONDS)
            } else {
                executorService.submit(commandHandlingExceptions)
            }
        }
    }

    open fun close() {
        synchronized(executorService) { executorService.shutdownNow() }
    }

    open fun isClosed(): Boolean = synchronized(executorService) { executorService.isShutdown }
}

/**
 * 主线程回调的统一出口（对照 RC 三处重复的同名私有函数）。
 *
 * `mainHandler` 可空：Flutter / RN 宿主里 `Handler(Looper.getMainLooper())` 真的会是 null
 * （坑 38，purchases-flutter#408），所有用它的地方都要有兜底。
 */
internal class MainDispatcher(private val mainHandler: Handler? = Handler(Looper.getMainLooper())) {

    fun dispatch(action: () -> Unit) {
        val handler = mainHandler ?: Handler(Looper.getMainLooper())
        if (Thread.currentThread() != handler.looper.thread) {
            handler.post(action)
        } else {
            action()
        }
    }
}
