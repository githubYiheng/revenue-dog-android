package org.revdog.purchases.support

import org.json.JSONObject
import org.junit.Assert.fail
import org.revdog.purchases.networking.HTTPRequest
import java.io.File

/**
 * 出站请求 JSON 快照（设计 §7，与 iOS `RequestSnapshot.swift` 同做法）。
 *
 * **上行契约变成可 diff 的产物**：改了请求头或 body，diff 会在 code review 里被看见，
 * 而不是等到线上才发现某个头没发。
 *
 * 录制（**仅本地**）：`RECORD_SNAPSHOTS=true ./gradlew :purchases:testDebugUnitTest`
 * CI 不设这个变量 → **只比对，快照缺失直接失败**（等价 RC 的录制模式锁 `never`）。
 */
internal object RequestSnapshot {

    private const val RECORD_ENV_KEY = "RECORD_SNAPSHOTS"

    private val isRecording: Boolean
        get() = System.getenv(RECORD_ENV_KEY)?.lowercase() in setOf("1", "true", "yes")

    /** 每次运行都会变、或跟宿主/设备绑定的头 —— 一律剔除，否则快照没法 diff。 */
    private val volatileHeaders = setOf(
        "x-platform-version",
        "x-platform-device",
        "x-platform-brand",
        "x-client-version",
        "x-client-build-version",
        "x-client-bundle-id",
        "x-client-locale",
        "x-kotlin-version",
        "x-rd-last-refresh-time",
    )

    /** 保留「有没有发」这个事实，但不把值写进仓库。 */
    private val redactedHeaders = setOf("authorization")

    private val snapshotsDirectory: File
        get() = File("src/test/resources/snapshots")

    fun assertMatches(request: HTTPRequest, name: String) {
        val actual = normalized(request)
        val file = File(snapshotsDirectory, "$name.json")

        if (!file.exists()) {
            if (isRecording) {
                file.parentFile?.mkdirs()
                file.writeText(actual)
                fail("已录制新快照 $name.json —— 请检查内容并提交，然后重跑测试。")
            } else {
                fail(
                    """
                    缺少请求快照 $name.json。
                    本地录制：$RECORD_ENV_KEY=true ./gradlew :purchases:testDebugUnitTest
                    CI 不允许自动录制 —— 上行契约必须是显式提交的产物。
                    实际内容：
                    $actual
                    """.trimIndent(),
                )
            }
            return
        }

        val expected = file.readText()
        if (expected == actual) return

        if (isRecording) {
            file.writeText(actual)
            fail("快照 $name.json 已更新 —— 请 review diff 后提交。")
            return
        }
        fail(
            """
            出站请求与快照 $name.json 不一致（上行契约变了？）。
            —— 期望 ——
            $expected
            —— 实际 ——
            $actual
            如为有意变更：$RECORD_ENV_KEY=true ./gradlew :purchases:testDebugUnitTest 重新录制。
            """.trimIndent(),
        )
    }

    private fun normalized(request: HTTPRequest): String {
        val headers = JSONObject()
        request.headers.toSortedMap().forEach { (name, value) ->
            val lowered = name.lowercase()
            if (lowered in volatileHeaders) return@forEach
            headers.put(name, if (lowered in redactedHeaders) "<redacted>" else value)
        }
        val payload = JSONObject().apply {
            put("method", request.method)
            put("path", request.fullURL.path)
            put("headers", headers)
            put("body", request.body ?: JSONObject.NULL)
        }
        return payload.toString(2) + "\n"
    }
}
