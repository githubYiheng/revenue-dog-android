package org.revdog.purchases.networking

import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.common.Delay
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import java.io.IOException

internal typealias CustomerInfoCallback = Pair<(CustomerInfo) -> Unit, (PurchasesError, isServerError: Boolean) -> Unit>
internal typealias OfferingsCallback = Pair<(JSONObject) -> Unit, (PurchasesError, isServerError: Boolean) -> Unit>
internal typealias LogInCallbackPair = Pair<(CustomerInfo, Boolean) -> Unit, (PurchasesError) -> Unit>

/**
 * 端点门面 + **并发去重**。结构对照 RC `common/Backend.kt`。
 *
 * 每个端点一张 callbacks map：同 key 的第二次调用不再发请求，而是挂到已经在飞的那一次上
 * （RC 的 `addCallback`，考古 §2.13）。20 行解决重复请求浪费配额与响应乱序。
 */
internal class Backend(
    private val httpClient: HTTPClient,
    private val dispatcher: Dispatcher,
) {

    /** 前后台维度也要进 key：后台请求带抖动，前台不带，两者不能复用同一次在飞的请求。 */
    internal data class CallbackCacheKey(val parts: List<String>, val appInBackground: Boolean)

    @get:Synchronized @set:Synchronized
    @Volatile
    @VisibleForTesting
    internal var customerInfoCallbacks = mutableMapOf<CallbackCacheKey, MutableList<CustomerInfoCallback>>()

    @get:Synchronized @set:Synchronized
    @Volatile
    @VisibleForTesting
    internal var offeringsCallbacks = mutableMapOf<CallbackCacheKey, MutableList<OfferingsCallback>>()

    @get:Synchronized @set:Synchronized
    @Volatile
    @VisibleForTesting
    internal var identifyCallbacks = mutableMapOf<CallbackCacheKey, MutableList<LogInCallbackPair>>()

    fun close() {
        dispatcher.close()
    }

    fun clearCaches() {
        httpClient.clearCaches()
    }

    // region GET /v1/subscribers/{id}

    fun getCustomerInfo(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo) -> Unit,
        onError: (PurchasesError, isServerError: Boolean) -> Unit,
    ) {
        val endpoint = Endpoint.GetCustomerInfo(appUserID)
        val cacheKey = CallbackCacheKey(listOf(endpoint.path), appInBackground)
        val call = object : AsyncCall() {
            override fun call(): HTTPResult = httpClient.performRequest(endpoint)

            override fun onCompletion(result: HTTPResult) {
                val callbacks = synchronized(this@Backend) { customerInfoCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (success, failure) ->
                    if (result.isSuccessful()) {
                        result.buildCustomerInfoOrError(success, failure)
                    } else {
                        failure(result.toPurchasesError(), RDHTTPStatusCodes.isServerError(result.responseCode))
                    }
                }
            }

            override fun onError(error: PurchasesError) {
                val callbacks = synchronized(this@Backend) { customerInfoCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (_, failure) -> failure(error, false) }
            }
        }
        synchronized(this) {
            customerInfoCallbacks.addCallback(
                call = call,
                cacheKey = cacheKey,
                functions = onSuccess to onError,
                delay = Delay.jitterOnlyIfInBackground(appInBackground),
            )
        }
    }

    // endregion

    // region GET /v1/subscribers/{id}/offerings

    /**
     * 只把**原始 JSON** 交给上层：商品详情填充需要先解析出 productId 列表再查 Play，
     * 解析与填充都在 `OfferingsManager` 里做（对照 RC `OfferingsFactory`）。
     */
    fun getOfferings(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (JSONObject) -> Unit,
        onError: (PurchasesError, isServerError: Boolean) -> Unit,
    ) {
        val endpoint = Endpoint.GetOfferings(appUserID)
        val cacheKey = CallbackCacheKey(listOf(endpoint.path), appInBackground)
        val call = object : AsyncCall() {
            override fun call(): HTTPResult = httpClient.performRequest(endpoint)

            override fun onCompletion(result: HTTPResult) {
                val callbacks = synchronized(this@Backend) { offeringsCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (success, failure) ->
                    if (result.isSuccessful()) {
                        success(result.body)
                    } else {
                        failure(result.toPurchasesError(), RDHTTPStatusCodes.isServerError(result.responseCode))
                    }
                }
            }

            override fun onError(error: PurchasesError) {
                val callbacks = synchronized(this@Backend) { offeringsCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (_, failure) -> failure(error, false) }
            }
        }
        synchronized(this) {
            offeringsCallbacks.addCallback(
                call = call,
                cacheKey = cacheKey,
                functions = onSuccess to onError,
                delay = Delay.jitterOnlyIfInBackground(appInBackground),
            )
        }
    }

    // endregion

    // region POST /v1/subscribers/identify

    /**
     * logIn。body 形状以服务端 `workers/api/src/identify.ts` 为准：
     * `{app_user_id, new_app_user_id}`（`install_id` 属归因链路，M3 再带）。
     * `created` = HTTP **201**（契约 §2.2 同款语义）。
     */
    fun logIn(
        appUserID: String,
        newAppUserID: String,
        onSuccessHandler: (CustomerInfo, Boolean) -> Unit,
        onErrorHandler: (PurchasesError) -> Unit,
    ) {
        val cacheKey = CallbackCacheKey(listOf(appUserID, newAppUserID), appInBackground = false)
        val call = object : AsyncCall() {
            override fun call(): HTTPResult = httpClient.performRequest(
                endpoint = Endpoint.LogIn,
                body = JSONObject().apply {
                    put(APP_USER_ID, appUserID)
                    put(NEW_APP_USER_ID, newAppUserID)
                },
            )

            override fun onCompletion(result: HTTPResult) {
                if (!result.isSuccessful()) {
                    onError(result.toPurchasesError())
                    return
                }
                val callbacks = synchronized(this@Backend) { identifyCallbacks.remove(cacheKey) } ?: return
                val created = result.responseCode == RDHTTPStatusCodes.CREATED
                callbacks.forEach { (success, failure) ->
                    if (result.body.length() > 0) {
                        try {
                            success(CustomerInfoFactory.buildCustomerInfo(result), created)
                        } catch (e: JSONException) {
                            failure(
                                PurchasesError(
                                    PurchasesErrorCode.UnexpectedBackendResponseError,
                                    "identify 响应解析失败：${e.message}",
                                ),
                            )
                        }
                    } else {
                        failure(PurchasesError(PurchasesErrorCode.UnexpectedBackendResponseError, "identify 响应为空"))
                    }
                }
            }

            override fun onError(error: PurchasesError) {
                val callbacks = synchronized(this@Backend) { identifyCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (_, failure) -> failure(error) }
            }
        }
        synchronized(this) {
            identifyCallbacks.addCallback(call, cacheKey, onSuccessHandler to onErrorHandler)
        }
    }

    // endregion

    private fun HTTPResult.buildCustomerInfoOrError(
        onSuccess: (CustomerInfo) -> Unit,
        onError: (PurchasesError, Boolean) -> Unit,
    ) {
        try {
            onSuccess(CustomerInfoFactory.buildCustomerInfo(this))
        } catch (e: JSONException) {
            Logger.error(e) { "CustomerInfo 解析失败" }
            onError(
                PurchasesError(
                    PurchasesErrorCode.UnexpectedBackendResponseError,
                    "CustomerInfo 解析失败：${e.message}",
                ),
                false,
            )
        }
    }

    /**
     * 同 key 合并。结构对照 RC `Backend.addCallback`：
     * 没有在飞的请求就真发一次，有就把回调挂上去。
     */
    private fun <K, F> MutableMap<K, MutableList<F>>.addCallback(
        call: AsyncCall,
        cacheKey: K,
        functions: F,
        delay: Delay = Delay.NONE,
    ) {
        if (!containsKey(cacheKey)) {
            this[cacheKey] = mutableListOf(functions)
            dispatcher.enqueue(call, delay)
        } else {
            Logger.debug { "同一请求已在进行中，合并回调：$cacheKey" }
            this[cacheKey]?.add(functions)
        }
    }

    /**
     * 结构对照 RC `Dispatcher.AsyncCall`：网络异常在这里统一翻译成 [PurchasesError]，
     * 绝不让它逃到 `Dispatcher` 的「rethrow 到主线程」那条路上去。
     */
    internal abstract inner class AsyncCall : Runnable {

        @Throws(JSONException::class, IOException::class)
        abstract fun call(): HTTPResult

        open fun onError(error: PurchasesError) = Unit
        open fun onCompletion(result: HTTPResult) = Unit

        override fun run() {
            try {
                onCompletion(call())
            } catch (e: JSONException) {
                onError(PurchasesError(PurchasesErrorCode.UnexpectedBackendResponseError, e.message))
            } catch (e: IOException) {
                onError(PurchasesError(PurchasesErrorCode.NetworkError, e.message))
            } catch (e: SecurityException) {
                // 坑 33：宿主关掉 INTERNET 权限时是 SecurityException，不是 IOException。
                onError(
                    PurchasesError(
                        PurchasesErrorCode.ConfigurationError,
                        "缺少 INTERNET 权限？${e.message}",
                    ),
                )
            }
        }
    }

    internal companion object {
        const val APP_USER_ID: String = "app_user_id"
        const val NEW_APP_USER_ID: String = "new_app_user_id"
    }
}
