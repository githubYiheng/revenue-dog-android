package org.revdog.purchases.networking

import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.common.Delay
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.posting.PostReceiptErrorHandling
import org.revdog.purchases.posting.PostReceiptResponse
import org.revdog.purchases.posting.ReceiptInfo
import org.revdog.purchases.posting.buildPostReceiptResponse
import org.revdog.purchases.posting.classifyPostReceiptError
import org.revdog.purchases.posting.toWireJson
import org.json.JSONArray
import java.io.IOException

internal typealias CustomerInfoCallback = Pair<(CustomerInfo) -> Unit, (PurchasesError, isServerError: Boolean) -> Unit>
internal typealias OfferingsCallback = Pair<(JSONObject) -> Unit, (PurchasesError, isServerError: Boolean) -> Unit>
internal typealias LogInCallbackPair = Pair<(CustomerInfo, Boolean) -> Unit, (PurchasesError) -> Unit>
internal typealias PostReceiptSuccessCallback = (PostReceiptResponse) -> Unit
internal typealias PostReceiptErrorCallback = (PurchasesError, PostReceiptErrorHandling) -> Unit
internal typealias PostReceiptCallbackPair = Pair<PostReceiptSuccessCallback, PostReceiptErrorCallback>

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

    @get:Synchronized @set:Synchronized
    @Volatile
    @VisibleForTesting
    internal var postReceiptCallbacks = mutableMapOf<CallbackCacheKey, MutableList<PostReceiptCallbackPair>>()

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

    // region POST /v1/receipts

    /**
     * 上报购买。body 形状以 `docs/plan/google-play-plan.md` §5 + `api-contract-v1.md` §2.1 为准，
     * 结构对照 RC `Backend.postReceiptData`。
     *
     * 与 RC 的差异（每条都在设计 §5 登记过）：
     * | 字段 | RC | 我方 |
     * |---|---|---|
     * | 价格 | `price`（Double，= micros / 1e6） | **`price_amount_micros`（Long）** —— Play 原生就是 micros |
     * | `price_string` / `marketplace` | 作为**请求头**发（历史遗留） | `price_string` 进 **body**；`marketplace` 不发（Amazon 专用） |
     * | `store_user_id` | 发（Amazon） | **不发** |
     * | `proration_mode` | legacy Play 名 `IMMEDIATE_*` | 干净枚举名（ADR 0069 决策 4） |
     * | `initiation_source` 第三值 | `unsynced_active_purchases` | 同（iOS 侧是 `queue`，考古 §2.6） |
     *
     * 并发去重的 key **必须把上报语义全带上**（考古 §2.13）：同一个 token 以
     * `purchase` 与以 `restore` 上报是两件不同的事（`is_restore` 影响转移判定，ADR 0046 ②），
     * 不能被合并成一次请求。
     */
    @Suppress("LongParameterList")
    fun postReceiptData(
        purchaseToken: String,
        appUserID: String,
        isRestore: Boolean,
        receiptInfo: ReceiptInfo,
        initiationSource: String,
        purchasesAreCompletedBy: PurchasesAreCompletedBy,
        appInBackground: Boolean,
        onSuccess: PostReceiptSuccessCallback,
        onError: PostReceiptErrorCallback,
    ) {
        val finishTransactions = purchasesAreCompletedBy == PurchasesAreCompletedBy.REVENUE_DOG
        val cacheKey = CallbackCacheKey(
            parts = listOf(
                purchaseToken,
                appUserID,
                isRestore.toString(),
                initiationSource,
                purchasesAreCompletedBy.rawValue,
                receiptInfo.toJson().toString(),
            ),
            appInBackground = appInBackground,
        )
        val body = receiptBody(
            purchaseToken = purchaseToken,
            appUserID = appUserID,
            isRestore = isRestore,
            receiptInfo = receiptInfo,
            initiationSource = initiationSource,
            purchasesAreCompletedBy = purchasesAreCompletedBy,
            finishTransactions = finishTransactions,
        )

        val call = object : AsyncCall() {
            override fun call(): HTTPResult = httpClient.performRequest(Endpoint.PostReceipt, body)

            override fun onCompletion(result: HTTPResult) {
                val callbacks = synchronized(this@Backend) { postReceiptCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (success, failure) ->
                    if (result.isSuccessful()) {
                        try {
                            success(buildPostReceiptResponse(result))
                        } catch (e: JSONException) {
                            Logger.error(e) { "receipts 响应解析失败" }
                            failure(
                                PurchasesError(
                                    PurchasesErrorCode.UnexpectedBackendResponseError,
                                    "receipts 响应解析失败：${e.message}",
                                ),
                                // 解析不了不代表后端没落库 —— 按**可重试**处理，绝不 finish。
                                PostReceiptErrorHandling.SHOULD_NOT_CONSUME,
                            )
                        }
                    } else {
                        failure(result.toPurchasesError(), classifyPostReceiptError(result.responseCode))
                    }
                }
            }

            override fun onError(error: PurchasesError) {
                val callbacks = synchronized(this@Backend) { postReceiptCallbacks.remove(cacheKey) } ?: return
                // 网络层失败：没有 HTTP 状态码 → 可重试。
                callbacks.forEach { (_, failure) -> failure(error, classifyPostReceiptError(null)) }
            }
        }
        synchronized(this) {
            postReceiptCallbacks.addCallback(
                call = call,
                cacheKey = cacheKey,
                functions = onSuccess to onError,
                delay = Delay.jitterOnlyIfInBackground(appInBackground),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun receiptBody(
        purchaseToken: String,
        appUserID: String,
        isRestore: Boolean,
        receiptInfo: ReceiptInfo,
        initiationSource: String,
        purchasesAreCompletedBy: PurchasesAreCompletedBy,
        finishTransactions: Boolean,
    ): JSONObject = JSONObject().apply {
        put(FETCH_TOKEN, purchaseToken)
        put(APP_USER_ID, appUserID)
        put(PRODUCT_IDS, JSONArray(receiptInfo.productIds))
        put(PLATFORM_PRODUCT_IDS, JSONArray(receiptInfo.platformProductIds.map { it.toJson() }))
        put(IS_RESTORE, isRestore)
        put(INITIATION_SOURCE, initiationSource)
        put(OBSERVER_MODE, !finishTransactions)
        put(PURCHASE_COMPLETED_BY, purchasesAreCompletedBy.rawValue)
        put(SDK_ORIGINATED, receiptInfo.sdkOriginated)
        put(PAYLOAD_VERSION, POST_RECEIPT_PAYLOAD_VERSION)
        receiptInfo.presentedOfferingIdentifier?.let { put(PRESENTED_OFFERING_IDENTIFIER, it) }
        receiptInfo.presentedPlacementIdentifier?.let { put(PRESENTED_PLACEMENT_IDENTIFIER, it) }
        receiptInfo.priceAmountMicros?.let { put(PRICE_AMOUNT_MICROS, it) }
        receiptInfo.currency?.let { put(CURRENCY, it) }
        receiptInfo.formattedPrice?.let { put(PRICE_STRING, it) }
        receiptInfo.durationIso?.let { put(NORMAL_DURATION, it) }
        receiptInfo.pricingPhases?.let { phases -> put(PRICING_PHASES, JSONArray(phases.map { it.toWireJson() })) }
        receiptInfo.replacementMode?.let { put(PRORATION_MODE, it.wireName) }
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

        // POST /v1/receipts 的 body 键（`google-play-plan.md` §5）。
        const val FETCH_TOKEN: String = "fetch_token"
        const val PRODUCT_IDS: String = "product_ids"
        const val PLATFORM_PRODUCT_IDS: String = "platform_product_ids"
        const val IS_RESTORE: String = "is_restore"
        const val INITIATION_SOURCE: String = "initiation_source"
        const val OBSERVER_MODE: String = "observer_mode"
        const val PURCHASE_COMPLETED_BY: String = "purchase_completed_by"
        const val SDK_ORIGINATED: String = "sdk_originated"
        const val PAYLOAD_VERSION: String = "payload_version"
        const val PRESENTED_OFFERING_IDENTIFIER: String = "presented_offering_identifier"
        const val PRESENTED_PLACEMENT_IDENTIFIER: String = "presented_placement_identifier"
        const val PRICE_AMOUNT_MICROS: String = "price_amount_micros"
        const val CURRENCY: String = "currency"
        const val PRICE_STRING: String = "price_string"
        const val NORMAL_DURATION: String = "normal_duration"
        const val PRICING_PHASES: String = "pricing_phases"
        const val PRORATION_MODE: String = "proration_mode"

        /**
         * 恒为 1。RC 的注释值得原样抄：**改动 POST receipt 的 payload 形状时才 +1，
         * 且必须与 iOS SDK 保持同步**（考古 §2.1）。
         */
        const val POST_RECEIPT_PAYLOAD_VERSION: Int = 1
    }
}
