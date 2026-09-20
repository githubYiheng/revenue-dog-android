package org.revdog.purchases.support

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasingData
import org.revdog.purchases.caching.PendingPurchase
import org.revdog.purchases.common.sha1
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.ReplaceProductInfo
import org.revdog.purchases.google.usecase.QueryProductDetailsResponse
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction

/** 一次 `makePurchaseAsync` 的入参快照。 */
internal class LaunchedPurchase(
    val obfuscatedAccountId: String?,
    val purchasingData: PurchasingData,
    val replaceProductInfo: ReplaceProductInfo?,
    val isPersonalizedPrice: Boolean?,
)

/** 一次 `consumeAndSave` 的入参快照（七分支的断言对象）。 */
internal class ConsumeAndSaveCall(
    val purchasesAreCompletedBy: PurchasesAreCompletedBy,
    val purchase: StoreTransaction,
    val shouldConsume: Boolean?,
    val deterministicallyRejected: Boolean,
)

/**
 * `BillingWrapper` 的可编程替身。
 *
 * 编排层的测试**不该**再去测 Billing 层的连接与退避（那些由 `BillingWrapperTest` 与
 * `ConsumeAndSaveTest` 用真实 wrapper + mock 的 `BillingClient` 覆盖）。
 * 这里只需要能：喂购买回调、喂 queryPurchases 结果、记下 launch 与 consumeAndSave 的入参。
 */
@Suppress("TooManyFunctions")
internal class BillingHarness {

    val wrapper: BillingWrapper = mockk(relaxed = true)

    var updatedListener: BillingWrapper.BillingPurchasesUpdatedListener? = null
        private set

    var stateListener: BillingWrapper.StateListener? = null
        private set

    private var contextProvider: ((String) -> PendingPurchase?)? = null

    val launched: MutableList<LaunchedPurchase> = mutableListOf()
    val consumeAndSaveCalls: MutableList<ConsumeAndSaveCall> = mutableListOf()
    val queriedProductTypes: MutableList<ProductType> = mutableListOf()

    /** `queryPurchases` 的返回值，key = `sha1(token)`。 */
    var purchasesOnDevice: List<StoreTransaction> = emptyList()
    var queryPurchasesError: PurchasesError? = null

    /** `true` = `queryPurchases` 永不回调（模拟 BillingClient 卡在重连退避里、待办一直排队）。 */
    var queryPurchasesNeverResponds: Boolean = false

    /** `queryProductDetailsAsync` 的返回值。 */
    var subscriptionProducts: List<StoreProduct> = emptyList()
    var inAppProducts: List<StoreProduct> = emptyList()
    var productQueryError: PurchasesError? = null

    /** `isFeatureSupported` 的返回值（`null` = 判定不了）。 */
    var featureSupported: Boolean? = true

    /** 升降级时 `findPurchaseForProductId` 的返回值。 */
    var oldPurchase: StoreTransaction? = null

    /** A8 用：`acknowledge` / `consumePurchase` 的调用记录。 */
    val acknowledgedTokens: MutableList<String> = mutableListOf()
    val consumedTokens: MutableList<String> = mutableListOf()

    /** `false` 时 `acknowledge` 不回调成功（模拟 Play 侧 ack 失败）。 */
    var acknowledgeSucceeds: Boolean = true

    init {
        every { wrapper.purchasesUpdatedListener = any() } answers { updatedListener = firstArg() }
        every { wrapper.stateListener = any() } answers { stateListener = firstArg() }
        every { wrapper.purchaseContextProvider = any() } answers { contextProvider = firstArg() }

        every { wrapper.isFeatureSupported(any()) } answers { featureSupported }

        every { wrapper.makePurchaseAsync(any(), any(), any(), any(), any()) } answers {
            launched += LaunchedPurchase(
                obfuscatedAccountId = secondArg(),
                purchasingData = thirdArg(),
                replaceProductInfo = arg(3),
                isPersonalizedPrice = arg(4),
            )
        }

        every { wrapper.queryPurchases(any(), any()) } answers {
            val onSuccess = firstArg<(Map<String, StoreTransaction>) -> Unit>()
            val onError = secondArg<(PurchasesError) -> Unit>()
            if (!queryPurchasesNeverResponds) {
                queryPurchasesError?.let { onError(it) }
                    ?: onSuccess(purchasesOnDevice.associateBy { it.purchaseToken.sha1() })
            }
        }

        every { wrapper.findPurchaseForProductId(any(), any(), any()) } answers {
            val onCompletion = secondArg<(StoreTransaction) -> Unit>()
            val onError = thirdArg<(PurchasesError) -> Unit>()
            oldPurchase?.let { onCompletion(it) }
                ?: onError(PurchasesError(org.revdog.purchases.PurchasesErrorCode.PurchaseInvalidError, "无旧订阅"))
        }

        every { wrapper.queryProductDetailsAsync(any(), any(), any(), any()) } answers {
            val type = firstArg<ProductType>()
            queriedProductTypes += type
            val onReceive = thirdArg<(QueryProductDetailsResponse) -> Unit>()
            val onError = arg<org.revdog.purchases.PurchasesErrorCallback>(3)
            val error = productQueryError
            if (error != null) {
                onError.onError(error)
            } else {
                val products = if (type == ProductType.SUBS) subscriptionProducts else inAppProducts
                onReceive(QueryProductDetailsResponse(products, emptyList()))
            }
        }

        // A8 直接调 `acknowledge`（不经 consumeAndSave）：ack 成功才回调，失败什么都不回。
        every { wrapper.acknowledge(any(), any()) } answers {
            val token = firstArg<String>()
            val onAcknowledged = secondArg<(String) -> Unit>()
            acknowledgedTokens += token
            if (acknowledgeSucceeds) onAcknowledged(token)
        }

        every { wrapper.consumePurchase(any(), any()) } answers {
            val token = firstArg<String>()
            val onConsumed = secondArg<(String) -> Unit>()
            consumedTokens += token
            onConsumed(token)
        }

        every { wrapper.consumeAndSave(any(), any(), any(), any()) } answers {
            consumeAndSaveCalls += ConsumeAndSaveCall(
                purchasesAreCompletedBy = firstArg(),
                purchase = secondArg(),
                shouldConsume = thirdArg(),
                deterministicallyRejected = arg(3),
            )
        }
    }

    /** 模拟 Play 回调一批购买。 */
    fun deliverPurchases(vararg transactions: StoreTransaction) {
        updatedListener?.onPurchasesUpdated(transactions.toList())
            ?: error("编排层还没挂 purchasesUpdatedListener")
    }

    fun deliverPurchaseFailure(error: PurchasesError, userCancelled: Boolean = false) {
        updatedListener?.onPurchasesFailedToUpdate(error, userCancelled)
            ?: error("编排层还没挂 purchasesUpdatedListener")
    }

    /** 模拟 BillingClient（重）连接成功 —— 补报的触发点。 */
    fun deliverConnected() {
        stateListener?.onConnected() ?: error("编排层还没挂 stateListener")
    }

    /** 编排层注入的上下文查询口子（决策 D：表只有一份）。 */
    fun contextFor(productId: String): PendingPurchase? = contextProvider?.invoke(productId)

    val activity: Activity = mockk(relaxed = true)
}
