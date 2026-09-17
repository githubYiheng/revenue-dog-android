package org.revdog.purchases.posting

import org.json.JSONObject
import org.revdog.purchases.InternalRevenueDogAPI
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.attributes.SubscriberAttributeError
import org.revdog.purchases.attributes.parseAttributeErrors
import org.revdog.purchases.common.keysSequence
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.networking.HTTPResult
import org.revdog.purchases.networking.RDHTTPStatusCodes

/**
 * `POST /v1/receipts` 的响应。结构对照 RC `common/networking/PostReceiptResponse.kt`。
 *
 * 除了完整 Subscriber，Android 还必须拿到
 * **`purchased_products[<product_id>].should_consume`**（考古 §2.8 / `google-play-plan.md` §5）——
 * 「这笔是不是消耗品」只有后端（唯一知道商品配置的一方）说得准。
 *
 * [shouldConsumeByProductId] 的三态是**故意的**（决策 B）：
 * | 值 | 含义 |
 * |---|---|
 * | `null`（整个字段） | 响应里没有 `purchased_products` → **契约违规** |
 * | 有 key、value 为 `null` | 有这个商品的条目但没有 `should_consume` → **契约违规** |
 * | `true` / `false` | 正常 |
 */
internal class PostReceiptResponse(
    val customerInfo: CustomerInfo,
    val shouldConsumeByProductId: Map<String, Boolean?>?,
    val body: JSONObject,
    /** 响应头 `X-Request-Id`（诊断事件的 `request_id` 用它）。 */
    val requestId: String? = null,
    /**
     * 搭车属性的逐键错误（契约 §2.4 的平铺形状包在 `attributes_error_response` 里，
     * 考古 §2.9）。出错的键必须被标成「已同步」，否则 SDK 会每次购买都把它们重传一遍。
     */
    val attributeErrors: List<SubscriberAttributeError> = emptyList(),
) {
    /**
     * 取本笔交易对应的 `should_consume`。
     *
     * v1 一次上报只带一个 token、但可能有多个 productId（多行订阅），
     * 取第一个命中的条目（对照 RC `PostReceiptHelper:121-131`）。
     */
    fun shouldConsumeFor(productIds: List<String>): Boolean? {
        val entries = shouldConsumeByProductId?.filterKeys { it in productIds }
        return if (entries.isNullOrEmpty()) null else entries.values.first()
    }
}

@OptIn(InternalRevenueDogAPI::class)
internal fun buildPostReceiptResponse(result: HTTPResult): PostReceiptResponse = PostReceiptResponse(
    customerInfo = CustomerInfoFactory.buildCustomerInfo(result),
    shouldConsumeByProductId = result.body.optJSONObject(KEY_PURCHASED_PRODUCTS)?.let { purchased ->
        purchased.keysSequence().associateWith { productId ->
            purchased.optJSONObject(productId)
                ?.takeIf { it.has(KEY_SHOULD_CONSUME) && !it.isNull(KEY_SHOULD_CONSUME) }
                ?.optBoolean(KEY_SHOULD_CONSUME)
        }
    },
    body = result.body,
    requestId = result.requestId,
    attributeErrors = result.body.parseAttributeErrors(),
)

private const val KEY_PURCHASED_PRODUCTS = "purchased_products"
private const val KEY_SHOULD_CONSUME = "should_consume"

/**
 * 上报失败的处置分类。结构对照 RC `PostReceiptErrorHandlingBehavior`，
 * 但落到我方的两个专有错误码上（设计 §1）。
 */
internal class PostReceiptErrorHandling private constructor(val rawValue: String) {
    companion object {
        /** 可重试：**不 ack、不 consume、上下文留存**，下次前台 / 连接成功时重放 → 901。 */
        val SHOULD_NOT_CONSUME: PostReceiptErrorHandling = PostReceiptErrorHandling("should_not_consume")

        /** 确定性拒绝：**ack 但不 consume**、清上下文（七分支第 ⑦ 条）→ 902。 */
        val SHOULD_BE_MARKED_SYNCED: PostReceiptErrorHandling = PostReceiptErrorHandling("should_be_marked_synced")
    }
}

/**
 * 错误分类。**与 iOS `TransactionPoster` 的 `classify` 逐条对齐**，两端不能有一条不一样：
 * 同一个后端故障在两端必须导出同一个「要不要重放」的结论。
 *
 * 可重试（→ 901）：网络层失败、5xx、**401 / 403**（密钥是可修的配置，
 * finish 掉等于把这笔付款从两边同时抹掉）、404、408、429。
 * 其余 4xx（→ 902）：后端确定性拒绝，重试不会有不同结果。
 *
 * ⚠️ 404 在 brief 的显式列表里没有，但 iOS（`case 404, 408, 429: return .retryable`）与
 * RC（`RCHTTPStatusCodes.isSynced` 把 404 排除在「已同步」之外）**两边都按可重试处理**，
 * 这里从严对齐两端。
 */
internal fun classifyPostReceiptError(responseCode: Int?): PostReceiptErrorHandling = when {
    responseCode == null -> PostReceiptErrorHandling.SHOULD_NOT_CONSUME
    RDHTTPStatusCodes.isServerError(responseCode) -> PostReceiptErrorHandling.SHOULD_NOT_CONSUME
    responseCode in RETRYABLE_CLIENT_STATUS_CODES -> PostReceiptErrorHandling.SHOULD_NOT_CONSUME
    else -> PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED
}

private val RETRYABLE_CLIENT_STATUS_CODES = setOf(
    RDHTTPStatusCodes.UNAUTHORIZED,
    RDHTTPStatusCodes.FORBIDDEN,
    RDHTTPStatusCodes.NOT_FOUND,
    REQUEST_TIMEOUT,
    RDHTTPStatusCodes.TOO_MANY_REQUESTS,
)

private const val REQUEST_TIMEOUT = 408

/**
 * 把上报失败翻译成宿主看得懂的错误（设计 §1）。
 *
 * - [PurchasesErrorCode.PurchasePendingServerConfirmation]（901）：**钱已经扣了**，
 *   服务端还没确认这笔收据。交易未完成、上下文已落盘、SDK 会重放。
 *   宿主提示「稍后到账」，**绝不引导用户重买**。
 * - [PurchasesErrorCode.PurchaseRejectedByServer]（902）：钱已经扣了，服务端确定性拒绝。
 *   不会再有权益，走客服 / 退款路径。
 */
internal fun PurchasesError.toPurchasePostingError(handling: PostReceiptErrorHandling): PurchasesError {
    val code = if (handling == PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED) {
        PurchasesErrorCode.PurchaseRejectedByServer
    } else {
        PurchasesErrorCode.PurchasePendingServerConfirmation
    }
    return PurchasesError(
        code = code,
        underlyingErrorMessage = message,
        backendCode = backendCode,
        httpStatusCode = httpStatusCode,
        requestId = requestId,
    )
}
