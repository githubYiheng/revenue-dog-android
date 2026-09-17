package org.revdog.purchases.networking

import android.content.Context
import org.revdog.purchases.Logger
import org.revdog.purchases.common.sha256Hex
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.CRC32

/**
 * 结构对照 RC `common/networking/ETagPayloadStore.kt`。
 *
 * 一个 URL 一个文件放 `cacheDir`，**payload 绝不进 SharedPreferences**：prefs 是常驻堆的
 * `HashMap`，多 MB 的 offerings 响应放进去会一直占着（坑 26，purchases-android#3628）。
 *
 * 写入 = 临时文件 + 原子 rename。**不用 androidx `AtomicFile`**（坑 27，RC 原注释）：
 * 它的 `openRead` 会删掉并发写者正在写的文件，`finishWrite` 会把 rename 失败吞掉。
 *
 * 写入**不 fsync**：改为由调用方在 [read] 时校验 [write] 返回的 CRC32。被断电截断、
 * 被中断的 rename 留成旧版本、原地损坏 —— 三种情况都读成「未命中」，然后由 `ETagManager`
 * 的 refresh 重发自愈。
 *
 * **偏离 RC**：RC 用分块 `CharsetEncoder` 流式编码（为了避开 ART 上 `CharBuffer.wrap` 的
 * 慢路径，以及多 MB payload 的额外 char[] 分配）。我方直接 `toByteArray()`：
 * 我们的 payload 只有 subscriber / offerings 两种 JSON（KB 量级），
 * 没有 RC Container 那种多 MB 响应，多写 100 行流式编码不划算。
 * 若将来 offerings 响应体量上到 MB 级，把 RC 那段搬过来。
 */
internal class ETagPayloadStore(private val directory: File) {

    constructor(context: Context) : this(File(File(context.cacheDir, VENDOR_DIRECTORY), DIRECTORY_NAME))

    /**
     * 返回 payload 的 CRC32；写失败返回 `null`。
     * **调用方绝不能为一次失败的写入落元数据** —— 那会让后续的 304 读到不存在的 payload。
     */
    fun write(urlString: String, payload: String): Long? {
        if (!directory.exists() && !directory.mkdirs()) {
            Logger.error { "无法创建 ETag payload 目录：$directory" }
            return null
        }
        val file = fileFor(urlString)
        // 临时文件从不被读者打开；写到一半崩溃只留下一个孤儿，下次写入覆盖它。
        val tempFile = File(directory, file.name + TEMP_SUFFIX)
        return try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            tempFile.outputStream().use { it.write(bytes) }
            if (tempFile.renameTo(file)) crc32Of(bytes) else null
        } catch (e: IOException) {
            Logger.error(e) { "ETag payload 落盘失败" }
            tempFile.delete()
            null
        }
    }

    /**
     * 返回 payload；未命中返回 `null`：文件不存在，或字节与 [expectedChecksum] 不符。
     *
     * 校验是**必需**的，不是可选：坏 payload 解码出来是一串 U+FFFD，而 `HTTPResult.body`
     * 会把解析失败吞掉，于是服务端继续对着这个 eTag 回 304 —— 这条缓存永远不会自愈。
     */
    fun read(urlString: String, expectedChecksum: Long): String? = try {
        val bytes = fileFor(urlString).readBytes()
        if (crc32Of(bytes) == expectedChecksum) String(bytes, Charsets.UTF_8) else null
    } catch (@Suppress("SwallowedException") e: FileNotFoundException) {
        // 这个 URL 没有 payload：普通的缓存未命中，不是错误。
        null
    } catch (e: IOException) {
        Logger.error(e) { "读取 ETag payload 失败" }
        null
    }

    fun clear() {
        if (!directory.exists()) return
        directory.deleteRecursively()
    }

    private fun fileFor(urlString: String): File = File(directory, urlString.sha256Hex())

    private fun crc32Of(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private companion object {
        const val VENDOR_DIRECTORY = "RevenueDog"
        const val DIRECTORY_NAME = "etag_payloads"
        const val TEMP_SUFFIX = ".tmp"
    }
}
