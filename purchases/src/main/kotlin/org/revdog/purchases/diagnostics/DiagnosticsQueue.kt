package org.revdog.purchases.diagnostics

import androidx.annotation.VisibleForTesting
import org.revdog.purchases.Logger
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 诊断事件的本地 JSONL 队列。
 * 结构对照 RC `common/diagnostics/DiagnosticsFileHelper.kt` + `common/FileHelper.kt`，
 * 口径（上限 / 轮转 / 坏行）对齐 iOS `DiagnosticsQueue.swift`。
 *
 * - 位置：`context.filesDir/RevenueDog/diagnostics/queue.jsonl`
 *   （考古 §7.5：Android 对位就是 `filesDir`，**绝不用 `getExternalFilesDir`** —— 那是外部可读的）。
 * - 上限 **500 条 / 256 KB**（我方口径；RC 只按字节，500 KB 直接整个删）：超限丢**最旧**，
 *   调用方补一条 `sdk_warning{queue_overflow}`（那条本身不再触发溢出告警，否则会自激）。
 * - **坏行跳过不崩**：半截写入 / 手工改坏的行在加载时被丢掉并重写文件。
 * - **轮转**（`sdk-diagnostics.md` §6-8）：上传不是「读队首 N 条、成功后删 N 条」——
 *   那会在上传在飞期间把新入队的事件一起截掉。改成把 `queue.jsonl` **改名**成
 *   `inflight-<uuid>.jsonl` 再发；新事件写进一份全新的 `queue.jsonl`；在飞文件只有整批成功才删。
 *   进程被杀 / 上传失败留下的在飞文件，下次 `configure` 时先补发
 *   （服务端按事件 `id` `INSERT OR IGNORE`，重发不会翻倍）。
 *
 * 线程：**全部方法 `@Synchronized`**，且约定只在诊断线程上调用（RC 同款约定）。
 * 锁是纵深防御 —— 宿主的 `close()` 与定时上传可能来自别的线程。
 */
@Suppress("TooManyFunctions")
internal class DiagnosticsQueue(
    private val directory: File,
) {

    private val queueFile: File get() = File(directory, QUEUE_FILE_NAME)

    /** 本进程内跳过过的坏行数（诊断用，不上行）。 */
    @Volatile
    var corruptedLinesSkipped: Int = 0
        private set

    // region 读

    @Synchronized
    fun count(): Int = loadLines().size

    @Synchronized
    fun bytes(): Int = loadLines().sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }

    /** 全量读（测试与排障用）。 */
    @Synchronized
    fun allEvents(): List<DiagnosticsEvent> = loadLines().mapNotNull { DiagnosticsEvent.fromLine(it) }

    // endregion

    // region 写

    /**
     * 入队一条事件。
     *
     * @return 因超限被丢弃的**最旧**事件条数（0 = 未溢出）。调用方据此补 `queue_overflow`。
     */
    @Synchronized
    fun append(event: DiagnosticsEvent): Int {
        val line = event.toLine()
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        if (lineBytes > DiagnosticsEvent.MAX_SERIALIZED_BYTES) {
            // 服务端会把超 2 KB 的条目计入 dropped —— 端上就别发了。
            Logger.debug { "诊断事件超过 ${DiagnosticsEvent.MAX_SERIALIZED_BYTES} 字节，已丢弃（type=${event.type}）" }
            return 0
        }

        val lines = loadLines().toMutableList()
        lines += line
        var bytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        var dropped = 0
        while (lines.size > MAX_EVENTS || bytes > MAX_BYTES) {
            val oldest = lines.removeAt(0)
            bytes -= oldest.toByteArray(Charsets.UTF_8).size + 1
            dropped++
        }

        if (dropped > 0) {
            write(lines, queueFile)
        } else {
            appendToDisk(line)
        }
        cachedLines = lines
        return dropped
    }

    /**
     * 清空队列并删文件。`diagnosticsEnabled = false` 的语义：**不记不发、清空**。
     * 在飞文件一并删 —— 关掉诊断就不该再有任何东西留在盘上。
     */
    @Synchronized
    fun clear() {
        cachedLines = emptyList()
        queueFile.delete()
        inflightFiles().forEach { it.delete() }
    }

    // endregion

    // region 轮转（§6-8）

    /**
     * 把当前队列文件改名成 `inflight-<uuid>.jsonl`，返回在飞文件；队列为空时返回 `null`。
     *
     * 改名是原子的：改名之后进来的事件写进一份全新的 `queue.jsonl`，与在飞的那一批彻底隔离 ——
     * 上传成功删掉的只会是已经发出去的那些。
     */
    @Synchronized
    fun rotate(): File? {
        val lines = loadLines()
        if (lines.isEmpty()) return null
        val target = File(directory, "$INFLIGHT_PREFIX${UUID.randomUUID().toString().lowercase()}$INFLIGHT_SUFFIX")
        val moved = runCatching { ensureDirectory() && queueFile.exists() && queueFile.renameTo(target) }
            .getOrDefault(false)
        if (!moved) {
            // 内存镜像里有、盘上没有（写盘失败过），或跨文件系统改名失败：直接把镜像落到在飞文件。
            write(lines, target)
            queueFile.delete()
        }
        cachedLines = emptyList()
        return target
    }

    /** 盘上遗留的在飞文件，按文件名排序保证补发顺序稳定。 */
    @Synchronized
    fun inflightFiles(): List<File> = (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.startsWith(INFLIGHT_PREFIX) && it.name.endsWith(INFLIGHT_SUFFIX) }
        .sortedBy { it.name }

    // endregion

    // region 文件级工具（在飞文件用；不碰内存镜像）

    /** 读一个文件的所有**好行**（坏行跳过）。 */
    fun linesOf(file: File): List<String> = readLines(file).first

    fun write(lines: List<String>, file: File) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        runCatching {
            ensureDirectory()
            file.writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        }.onFailure { Logger.debug { "诊断队列写入失败：$it" } }
    }

    fun delete(file: File) {
        file.delete()
    }

    /**
     * 从一组行里切出一批（条数 + 字节双上限）。
     * 单条超上限也**至少发一条**，避免永久卡住队首（与 iOS `DiagnosticsQueue.batch` 同款）。
     */
    fun batch(lines: List<String>, maxCount: Int, maxBytes: Int): List<DiagnosticsEvent> {
        val result = mutableListOf<DiagnosticsEvent>()
        var used = 0
        for (line in lines) {
            val size = line.toByteArray(Charsets.UTF_8).size
            val full = result.size >= maxCount || (result.isNotEmpty() && used + size > maxBytes)
            if (full) break
            DiagnosticsEvent.fromLine(line)?.let { event ->
                used += size
                result += event
            }
        }
        return result
    }

    // endregion

    // region 磁盘

    /** 内存镜像：入队时避免每次都整文件读一遍。`null` = 尚未从盘上加载。 */
    @Volatile
    private var cachedLines: List<String>? = null

    private fun loadLines(): List<String> {
        cachedLines?.let { return it }
        val (lines, skipped) = readLines(queueFile)
        corruptedLinesSkipped += skipped
        // 超限的历史文件（换过上限 / 手工塞进来的）在加载时就裁到位。
        val trimmed = lines.toMutableList()
        var bytes = trimmed.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        var didTrim = false
        while (trimmed.size > MAX_EVENTS || bytes > MAX_BYTES) {
            val oldest = trimmed.removeAt(0)
            bytes -= oldest.toByteArray(Charsets.UTF_8).size + 1
            didTrim = true
        }
        if (skipped > 0 || didTrim) {
            Logger.debug { "诊断队列加载时跳过 $skipped 条坏行，裁剪=$didTrim" }
            write(trimmed, queueFile)
        }
        cachedLines = trimmed
        return trimmed
    }

    /** @return 好行 + 跳过的坏行数。 */
    @Suppress("ReturnCount")
    private fun readLines(file: File): Pair<List<String>, Int> {
        if (!file.exists()) return emptyList<String>() to 0
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            Logger.debug { "诊断队列读取失败，按空处理：$it" }
            return emptyList<String>() to 0
        }
        var skipped = 0
        val good = mutableListOf<String>()
        raw.split('\n').forEach { line ->
            if (line.isBlank()) return@forEach
            if (DiagnosticsEvent.fromLine(line) != null) good += line else skipped++
        }
        return good to skipped
    }

    private fun appendToDisk(line: String) {
        runCatching {
            ensureDirectory()
            queueFile.appendText("$line\n", Charsets.UTF_8)
        }.onFailure { Logger.debug { "诊断队列追加失败：$it" } }
    }

    @Throws(IOException::class)
    private fun ensureDirectory(): Boolean = directory.exists() || directory.mkdirs()

    // endregion

    internal companion object {
        /** 上限（设计 `sdk-diagnostics.md` §2，与 iOS 同值）。 */
        const val MAX_EVENTS: Int = 500
        const val MAX_BYTES: Int = 256 * 1024

        const val QUEUE_FILE_NAME: String = "queue.jsonl"
        const val INFLIGHT_PREFIX: String = "inflight-"
        const val INFLIGHT_SUFFIX: String = ".jsonl"

        /** `filesDir` 下的相对目录（对照 RC `RevenueCat/diagnostics/`）。 */
        const val RELATIVE_DIRECTORY: String = "RevenueDog/diagnostics"

        @VisibleForTesting
        fun directoryIn(filesDir: File): File = File(filesDir, RELATIVE_DIRECTORY)
    }
}
