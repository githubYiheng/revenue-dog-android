package org.revdog.purchases.support

import android.content.Context
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.HTTPClient
import org.revdog.purchases.networking.HTTPRequest
import org.revdog.purchases.networking.HTTPResult
import java.io.IOException
import java.util.Date

/**
 * 假后端。**只覆写 IO 那一层** —— URL 拼接与请求头全集仍然走生产代码
 * （`HTTPClient.buildRequest`），所以 [recordedRequests] 里的东西就是真正会发出去的东西。
 * 出站请求快照测的因此是生产路径，不是测试替身自己编的。
 *
 * 对照 iOS `MockTransport` + `RequestSnapshot` 的分工。
 */
internal class FakeHTTPClient(
    appConfig: AppConfig,
    eTagManager: ETagManager,
) : HTTPClient(appConfig, eTagManager) {

    val recordedRequests: MutableList<HTTPRequest> = mutableListOf()

    /** 按调用顺序出队；队列空了就用 [defaultResponse]。 */
    private val queuedResponses: ArrayDeque<Result<HTTPResult>> = ArrayDeque()

    var defaultResponse: Result<HTTPResult> = Result.success(
        response(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}"""),
    )

    fun enqueue(result: HTTPResult) {
        queuedResponses.addLast(Result.success(result))
    }

    fun enqueue(responseCode: Int, payload: String, requestDate: Date? = null, eTag: String? = null) {
        enqueue(response(responseCode, payload, requestDate, eTag))
    }

    fun enqueueIOException(message: String = "network down") {
        queuedResponses.addLast(Result.failure(IOException(message)))
    }

    /**
     * 抛任意异常。M4 故障注入用：**超时与断网必须分别注入** ——
     * `SocketTimeoutException` 是 `IOException` 的子类，两者在我方代码里走同一条
     * `networkError` 分支，但这条等价关系正是需要被测试锁住的东西（改动 catch 分支时
     * 很容易把 `SocketTimeoutException` 漏进「未捕获异常 → rethrow 到主线程」那条路）。
     */
    fun enqueueThrowable(throwable: Throwable) {
        queuedResponses.addLast(Result.failure(throwable))
    }

    /**
     * 在「请求已记录、响应还没返回」之间插一脚。
     * 用来观察**在飞期间**的行为（诊断上传的单飞闸就靠它测）。
     */
    var beforeResponse: (() -> Unit)? = null

    override fun executeRequest(request: HTTPRequest): HTTPResult {
        recordedRequests += request
        beforeResponse?.invoke()
        val result = queuedResponses.removeFirstOrNull() ?: defaultResponse
        return result.getOrElse { throw it }
    }

    companion object {

        fun response(
            responseCode: Int,
            payload: String,
            requestDate: Date? = null,
            eTag: String? = null,
        ): HTTPResult = HTTPResult(
            responseCode = responseCode,
            payload = payload,
            origin = HTTPResult.Origin.BACKEND,
            requestDate = requestDate,
            requestId = "req-test",
            serverIsRetryable = null,
            retryAfterSeconds = null,
            eTagHeaderValue = eTag,
        )

        fun appConfig(
            context: Context,
            apiKey: String = TEST_API_KEY,
            baseURL: String = TEST_BASE_URL,
            purchasesAreCompletedBy: PurchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
        ): AppConfig = AppConfig(
            context = context,
            apiKey = apiKey,
            baseURL = baseURL,
            purchasesAreCompletedBy = purchasesAreCompletedBy,
            isDebugBuild = false,
            diagnosticsEnabled = true,
        ).apply { isAppBackgrounded = false }

        const val TEST_API_KEY: String = "pk_test_key"
        const val TEST_BASE_URL: String = "https://api.revdog.test"
    }
}
