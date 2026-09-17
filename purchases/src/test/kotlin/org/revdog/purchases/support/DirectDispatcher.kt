package org.revdog.purchases.support

import org.revdog.purchases.common.Delay
import org.revdog.purchases.common.Dispatcher
import java.util.concurrent.Executors

/**
 * 在**当前线程**直接执行的 dispatcher。
 *
 * 单测里不要真的异步：回调要在断言之前跑完，否则测试只能靠 sleep / latch 碰运气。
 * 生产路径的并发正确性由 `synchronized` 与 `Backend` 的 callbacks map 保证，
 * 那些是靠代码结构而不是靠测试调度器保证的。
 */
internal class DirectDispatcher : Dispatcher(Executors.newSingleThreadScheduledExecutor(), null) {

    override fun enqueue(command: Runnable, delay: Delay) {
        command.run()
    }

    override fun close() = Unit

    override fun isClosed(): Boolean = false
}

/**
 * 把任务攒起来，`runAll()` 才真跑。
 *
 * 用它才能观察到 `Backend` 的**并发去重**：同步执行的 dispatcher 下第一次调用在第二次
 * 开始前就已经完成、cache key 已被移除，合并行为根本不会发生。
 */
internal class DeferredDispatcher : Dispatcher(Executors.newSingleThreadScheduledExecutor(), null) {

    private val queued: MutableList<Runnable> = mutableListOf()

    override fun enqueue(command: Runnable, delay: Delay) {
        queued += command
    }

    fun runAll() {
        val toRun = queued.toList()
        queued.clear()
        toRun.forEach { it.run() }
    }

    override fun close() = Unit

    override fun isClosed(): Boolean = false
}
