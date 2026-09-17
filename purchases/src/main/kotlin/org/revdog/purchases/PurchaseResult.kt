package org.revdog.purchases

import dev.drewhamilton.poko.Poko
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.models.StoreTransaction

/**
 * 一次购买的结果。与 iOS `PurchaseResult` 同形。
 *
 * 三种终态：
 * | 形态 | [storeTransaction] | [isPending] | 含义 |
 * |---|---|---|---|
 * | 成功 | 非空 | false | 已扣款、后端已确认、交易已完成（ack / consume 视商品而定） |
 * | 待处理 | 非空 | **true** | Play 受理了但**没扣款**（现金支付 / 待家长批准）。**不发权益、不报错**，等 Play 转态后 SDK 自动补报 |
 * | 取消 / 失败 | —— | —— | 走 [PurchaseCallback.onError] |
 */
@Poko
public class PurchaseResult internal constructor(
    public val customerInfo: CustomerInfo,
    public val storeTransaction: StoreTransaction?,
    public val isPending: Boolean,
)
