package org.revdog.purchases.attributes

import org.revdog.purchases.PurchasesError
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.RDHTTPStatusCodes

/**
 * `POST /v1/subscribers/{app_user_id}/attributes` 的语义层。
 * 结构对照 RC `subscriberattributes/SubscriberAttributesPoster.kt`。
 *
 * 它只负责一件事：把一次失败翻译成「**后端到底有没有拿到这批属性**」。
 *
 * | 失败 | didBackendGetAttributes | 后果 |
 * |---|---|---|
 * | 5xx | `false` | 保持未同步，下次时机重发 |
 * | 404 | `false` | 同上（customer 还没建出来，重试会成功） |
 * | 网络层失败（没有状态码） | `false` | 同上 |
 * | 其余 4xx（400 / 401 / 403 / 413 / 422 …） | **`true`** | **标记已同步**，不再重传 |
 *
 * 最后一行是 RC 的原话「all 4xx (except 404) are considered as successfully synced …
 * continuing to retry won't yield any different results」，与 iOS 坑 #127 逐条同口径：
 * 属性 400 多半是键名 / 值非法，重试永远不会变好，继续挂在待发队列只会每次前后台都白发一遍。
 *
 * ⚠️ 与 `POST /v1/receipts` 的分类表（`classifyPostReceiptError`）**不是同一张，不要合并**：
 * 那边 401/403 必须按可重试处理（把一笔已扣款的交易 finish 掉等于丢单，ADR 0023）；
 * 这边 401/403 是「key 配错了」，属性重传一万次也不会进库。
 */
internal class SubscriberAttributesPoster(
    private val backend: Backend,
) {

    fun postSubscriberAttributes(
        appUserID: String,
        attributes: Collection<SubscriberAttribute>,
        appInBackground: Boolean,
        onSuccess: () -> Unit,
        onError: (
            error: PurchasesError,
            didBackendGetAttributes: Boolean,
            attributeErrors: List<SubscriberAttributeError>,
        ) -> Unit,
    ) {
        backend.postSubscriberAttributes(
            appUserID = appUserID,
            attributes = attributes.toWireJson(),
            appInBackground = appInBackground,
            onSuccess = { onSuccess() },
            onError = { error, result ->
                val responseCode = result?.responseCode
                val didBackendGetAttributes = when {
                    responseCode == null -> false
                    RDHTTPStatusCodes.isServerError(responseCode) -> false
                    responseCode == RDHTTPStatusCodes.NOT_FOUND -> false
                    else -> true
                }
                onError(error, didBackendGetAttributes, result?.body.parseAttributeErrors())
            },
        )
    }
}
