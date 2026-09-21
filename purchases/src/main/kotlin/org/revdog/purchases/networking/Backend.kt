package org.revdog.purchases.networking

import androidx.annotation.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.common.Delay
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.common.sha1
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
internal typealias AttributesSuccessCallback = (HTTPResult) -> Unit
internal typealias AttributesErrorCallback = (PurchasesError, HTTPResult?) -> Unit
internal typealias AttributesCallbackPair = Pair<AttributesSuccessCallback, AttributesErrorCallback>

/**
 * 端点门面 + **并发去重**。结构对照 RC `common/Backend.kt`。
 *
 * 每个端点一张 callbacks map：同 key 的第二次调用不再发请求，而是挂到已经在飞的那一次上
 * （RC 的 `addCallback`，考古 §2.13）。20 行解决重复请求浪费配额与响应乱序。
 */
// 函数数 = 端点数 × 2（每个端点一个入口 + 一个 body 拼装）。按端点拆类只会让
// callbacks map 的去重纪律散到几个文件里（RC 也是一个 Backend 打天下）。
@Suppress("TooManyFunctions")
internal class Backend(
    private val httpClient: HTTPClient,
    private val dispatcher: Dispatcher,
) {

    /** 前后台维度也要进 key：后台请求带抖动，前台不带，两者不能复用同一次在飞的请求。 */
    internal data class CallbackCacheKey(val parts: List<String>, val appInBackground: Boolean) {

        /**
         * 进日志的脱敏形态。
         *
         * `parts` 里装着 **purchaseToken 原文 + app_user_id + 整个 receiptInfo JSON**
         * （见 `postReceiptData`），直接 `$cacheKey` 打出去等于把购买凭据写进 logcat。
         * 这里只留 sha1 前 8 位 —— 足够肉眼判断「是不是同一个 key 被合并了」，
         * 又不泄露任何原文（与 `BillingWrapper` 打 token 的做法同款）。
         */
        val redacted: String
            get() = "CallbackCacheKey(sha1=${parts.joinToString(separator = "|").sha1().take(KEY_LOG_PREFIX_LENGTH)}" +
                ", background=$appInBackground)"

        /**
         * data class 默认的 `toString()` 会把 `parts` 原样吐出来。**覆盖掉**，
         * 让「以后有人手滑写了 `$cacheKey`」也泄不出东西（相等性语义不受影响）。
         */
        override fun toString(): String = redacted

        private companion object {
            const val KEY_LOG_PREFIX_LENGTH = 8
        }
    }

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

    @get:Synchronized @set:Synchronized
    @Volatile
    @VisibleForTesting
    internal var attributesCallbacks = mutableMapOf<CallbackCacheKey, MutableList<AttributesCallbackPair>>()

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
        /** 搭车的待同步属性（契约 §2.1 的 `attributes`，语义同 §2.4）。没有就不发这个键。 */
        attributes: JSONObject? = null,
        /** A8 的 `acknowledged_by`（设计 §3 A8）：目前只有一个取值 `sdk_timeout`。 */
        acknowledgedBy: String? = null,
        onSuccess: PostReceiptSuccessCallback,
        onError: PostReceiptErrorCallback,
    ) {
        val finishTransactions = purchasesAreCompletedBy == PurchasesAreCompletedBy.REVENUE_DOG
        val cacheKey = CallbackCacheKey(
            parts = listOfNotNull(
                purchaseToken,
                appUserID,
                isRestore.toString(),
                initiationSource,
                purchasesAreCompletedBy.rawValue,
                receiptInfo.toJson().toString(),
                // 搭车属性与 A8 标记都改变了这次请求的**内容**，不能与不带它们的那次合并。
                attributes?.toString(),
                acknowledgedBy,
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
            attributes = attributes,
            acknowledgedBy = acknowledgedBy,
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

    // region POST /v1/subscribers/{id}/attributes

    /**
     * 属性同步（契约 §2.4）。结构对照 RC `SubscriberAttributesPoster` + `Backend` 的
     * `performRequest`；语义分类（哪种失败算「后端已经拿到了」）在
     * [org.revdog.purchases.attributes.SubscriberAttributesPoster] 里，本方法只管 HTTP。
     *
     * 并发去重的 key 带上属性内容本身：同一批属性重复触发（前后台抖动）合并成一次请求，
     * 但内容变了就必须真发一次。
     */
    fun postSubscriberAttributes(
        appUserID: String,
        attributes: JSONObject,
        appInBackground: Boolean,
        onSuccess: AttributesSuccessCallback,
        onError: AttributesErrorCallback,
    ) {
        val endpoint = Endpoint.PostAttributes(appUserID)
        val cacheKey = CallbackCacheKey(listOf(endpoint.path, attributes.toString()), appInBackground)
        val body = JSONObject().put(ATTRIBUTES, attributes)
        val call = object : AsyncCall() {
            override fun call(): HTTPResult = httpClient.performRequest(endpoint, body)

            override fun onCompletion(result: HTTPResult) {
                val callbacks = synchronized(this@Backend) { attributesCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (success, failure) ->
                    if (result.isSuccessful()) success(result) else failure(result.toPurchasesError(), result)
                }
            }

            override fun onError(error: PurchasesError) {
                val callbacks = synchronized(this@Backend) { attributesCallbacks.remove(cacheKey) } ?: return
                callbacks.forEach { (_, failure) -> failure(error, null) }
            }
        }
        synchronized(this) {
            attributesCallbacks.addCallback(
                call = call,
                cacheKey = cacheKey,
                functions = onSuccess to onError,
                delay = Delay.jitterOnlyIfInBackground(appInBackground),
            )
        }
    }

    // endregion

    // region POST /v1/diagnostics/events

    /**
     * 诊断攒批上传（`sdk-diagnostics.md` §1）。**同步执行、不走 callbacks map、不走 Dispatcher**
     * —— 三处都与其它端点不同，理由各自独立：
     *
     * - **同步**：调用方（`DiagnosticsUploader`）本来就跑在专用诊断线程上（对照 RC 的
     *   `revenuecat-events-thread`），而且它要按「一批成功再发下一批」的顺序推进，
     *   回调式在这里只会把顺序拆散。
     * - **不去重**：上传器自己有单飞闸（`AtomicBoolean`），不会有第二个在飞。
     * - **原始状态码**：处置矩阵按 HTTP 状态码分叉（401/403 停 1h、429 读 `Retry-After`、
     *   其余 4xx 丢批、5xx 退避），[HTTPResult.toPurchasesError] 会把 413 / 429 / 503
     *   揉成同几个 code，那正好是这里最不能丢的信息（与 iOS `performUnchecked` 同做法）。
     *
     * @return 成功拿到响应（**不论状态码**）就是 `success`；网络层失败是 `failure`。
     */
    fun postDiagnosticsEventsBlocking(body: JSONObject): Result<HTTPResult> = runCatching {
        httpClient.performRequest(Endpoint.PostDiagnosticsEvents, body)
    }

    // endregion

    @Suppress("LongParameterList")
    private fun receiptBody(
        purchaseToken: String,
        appUserID: String,
        isRestore: Boolean,
        receiptInfo: ReceiptInfo,
        initiationSource: String,
        purchasesAreCompletedBy: PurchasesAreCompletedBy,
        finishTransactions: Boolean,
        attributes: JSONObject?,
        acknowledgedBy: String?,
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
        attributes?.takeIf { it.length() > 0 }?.let { put(ATTRIBUTES, it) }
        acknowledgedBy?.let { put(ACKNOWLEDGED_BY, it) }
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
    private fun <F> MutableMap<CallbackCacheKey, MutableList<F>>.addCallback(
        call: AsyncCall,
        cacheKey: CallbackCacheKey,
        functions: F,
        delay: Delay = Delay.NONE,
    ) {
        if (!containsKey(cacheKey)) {
            this[cacheKey] = mutableListOf(functions)
            dispatcher.enqueue(call, delay)
        } else {
            // **打 redacted 不打 cacheKey 本身**：key 里有 purchaseToken 原文与 app_user_id。
            Logger.debug { "同一请求已在进行中，合并回调：${cacheKey.redacted}" }
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
        const val ATTRIBUTES: String = "attributes"

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
        const val ACKNOWLEDGED_BY: String = "acknowledged_by"

        /** [ACKNOWLEDGED_BY] 的唯一取值（设计 §3 A8）。 */
        const val ACKNOWLEDGED_BY_SDK_TIMEOUT: String = "sdk_timeout"

        /**
         * 恒为 1。RC 的注释值得原样抄：**改动 POST receipt 的 payload 形状时才 +1，
         * 且必须与 iOS SDK 保持同步**（考古 §2.1）。
         */
        const val POST_RECEIPT_PAYLOAD_VERSION: Int = 1
    }
}
