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
