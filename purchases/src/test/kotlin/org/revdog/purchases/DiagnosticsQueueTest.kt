package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 本地 JSONL 队列：容量轮转、坏行跳过、文件轮转（inflight）、按字节切批。
 *
 * 口径来源：`docs/plan/sdk-diagnostics.md` §2（500 条 / 256 KB）与 §6-8（轮转），
 * 与 iOS `DiagnosticsTests` 的同名用例一一对应。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsQueueTest {

    private lateinit var directory: File
    private lateinit var queue: DiagnosticsQueue

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "revdog-diag-${System.nanoTime()}")
        queue = DiagnosticsQueue(directory)
    }

    private fun event(index: Int, fields: Map<String, Any?> = emptyMap()): DiagnosticsEvent =
        DiagnosticsEvent.create(
            id = "evt-$index",
            tsMs = 1_789_000_000_000L + index,
            appUserID = "user-42",
            seq = index.toLong(),
            type = "sdk_configured",
            level = DiagnosticsLevel.INFO,
            fields = fields,
        )

    @Test
    fun `入队与读回是同一批事件`() {
        repeat(3) { queue.append(event(it)) }

        assertThat(queue.count()).isEqualTo(3)
        assertThat(queue.allEvents().map { it.id }).containsExactly("evt-0", "evt-1", "evt-2")
        assertThat(File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)).exists()
    }

    @Test
    fun `超过 500 条时丢最旧并如实报数`() {
        repeat(DiagnosticsQueue.MAX_EVENTS) { queue.append(event(it)) }
        assertThat(queue.count()).isEqualTo(DiagnosticsQueue.MAX_EVENTS)

        val dropped = queue.append(event(9999))

        assertThat(dropped).isEqualTo(1)
        assertThat(queue.count()).isEqualTo(DiagnosticsQueue.MAX_EVENTS)
        val ids = queue.allEvents().map { it.id }
        assertThat(ids.first()).isEqualTo("evt-1") // evt-0 被丢掉了
        assertThat(ids.last()).isEqualTo("evt-9999")
    }

    @Test
    fun `超过 256KB 时丢最旧`() {
        // 每条 ~1.7KB（fields 里的字符串会被截到 200 字符，所以要靠多个键把单条撑大），
        // 塞到超字节上限但远不到 500 条。
        val padding = (1..8).associate { "detail_$it" to "x".repeat(200) }
        var dropped = 0
        repeat(220) { dropped += queue.append(event(it, padding)) }

        assertThat(dropped).isGreaterThan(0)
        assertThat(queue.count()).isLessThan(220)
        assertThat(queue.bytes()).isLessThanOrEqualTo(DiagnosticsQueue.MAX_BYTES)
        // 留下的一定是**最新**那些。
        assertThat(queue.allEvents().last().id).isEqualTo("evt-219")
    }

    @Test
    fun `单条超过 2KB 直接不入队`() {
        // fields 里的字符串会被截到 200 字符，所以要用很多个键才能把单条撑过 2KB。
        val fat = (1..40).associate { "key_$it" to "v".repeat(200) }

        val dropped = queue.append(event(1, fat))

        assertThat(dropped).isEqualTo(0)
        assertThat(queue.count()).isZero()
    }

    @Test
    fun `坏行跳过不崩且被重写掉`() {
        queue.append(event(1))
        val file = File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)
        file.appendText("{\"半截写入\":\n")
        file.appendText("这根本不是 json\n")
        queue.append(event(2))

        val reloaded = DiagnosticsQueue(directory)
        assertThat(reloaded.allEvents().map { it.id }).containsExactly("evt-1", "evt-2")
        assertThat(reloaded.corruptedLinesSkipped).isEqualTo(2)
        // 重写之后盘上只剩好行。
        assertThat(file.readLines().filter { it.isNotBlank() }).hasSize(2)
    }

    @Test
    fun `缺必填键的行算坏行`() {
        assertThat(DiagnosticsEvent.fromLine("""{"ts_ms":1,"type":"a","level":"info"}""")).isNull()
        assertThat(DiagnosticsEvent.fromLine("""{"id":"x","type":"a","level":"info"}""")).isNull()
        assertThat(DiagnosticsEvent.fromLine("""{"id":"x","ts_ms":1,"level":"info"}""")).isNull()
        assertThat(DiagnosticsEvent.fromLine("""{"id":"x","ts_ms":1,"type":"a","level":"info"}""")).isNotNull()
    }

    @Test
    fun `轮转把队列改名成 inflight 且新事件写进全新队列`() {
        repeat(2) { queue.append(event(it)) }

        val inflight = queue.rotate()

        assertThat(inflight).isNotNull
        assertThat(inflight!!.name).startsWith(DiagnosticsQueue.INFLIGHT_PREFIX)
        assertThat(queue.count()).isZero()
        assertThat(File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)).doesNotExist()
        assertThat(queue.linesOf(inflight)).hasSize(2)

        // 在飞期间新入队的事件**绝不会**被这一批的成功给截掉。
        queue.append(event(99))
        assertThat(queue.allEvents().map { it.id }).containsExactly("evt-99")
        assertThat(queue.inflightFiles()).containsExactly(inflight)
    }

    @Test
    fun `空队列轮转返回 null`() {
        assertThat(queue.rotate()).isNull()
    }

    @Test
    fun `clear 清空队列与在飞文件`() {
        queue.append(event(1))
        val inflight = queue.rotate()
        queue.append(event(2))

        queue.clear()

        assertThat(queue.count()).isZero()
        assertThat(File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)).doesNotExist()
        assertThat(inflight).isNotNull
        assertThat(inflight!!.exists()).isFalse()
    }

    @Test
    fun `切批同时受条数与字节双上限约束`() {
        val lines = (1..10).map { event(it).toLine() }

        assertThat(queue.batch(lines, maxCount = 3, maxBytes = 1_000_000).map { it.id })
            .containsExactly("evt-1", "evt-2", "evt-3")
        // 字节上限只够一条：也必须发一条（不然队首永久卡住）。
        assertThat(queue.batch(lines, maxCount = 100, maxBytes = 1)).hasSize(1)
    }

    @Test
    fun `加载时把超限的历史文件裁到位`() {
        val file = File(directory, DiagnosticsQueue.QUEUE_FILE_NAME)
        directory.mkdirs()
        file.writeText((1..DiagnosticsQueue.MAX_EVENTS + 10).joinToString("\n") { event(it).toLine() } + "\n")

        val reloaded = DiagnosticsQueue(directory)

        assertThat(reloaded.count()).isEqualTo(DiagnosticsQueue.MAX_EVENTS)
        assertThat(reloaded.allEvents().first().id).isEqualTo("evt-11")
    }
}
