package org.revdog.purchases.posting

import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.PurchasesException
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.caching.PostedTransactionContext
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.networking.Backend
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * `POST /v1/receipts` 的执行者 + `consumeAndSave` 七分支的驱动者。
 * 结构对照 RC `PostReceiptHelper.kt`。
 *
 * 铁律 A2（设计 §3）：**后端 200 之前不 ack 不 consume**。这条的全部实现就在
 * [performPostReceipt] 的 onSuccess / onError 两个分支里，别处不许再碰 Billing 的完成动作。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class PostReceiptHelper(
    private val appConfig: AppConfig,
    private val backend: Backend,
    private val billing: BillingWrapper,
    private val customerInfoUpdateHandler: CustomerInfoUpdateHandler,
    private val deviceCache: DeviceCache,
    private val pendingPurchases: PendingPurchaseStore,
    private val diagnostics: DiagnosticsTracker,
) {

    /**
     * 上报一笔交易，然后按后端下发的 `should_consume` 走七分支。
     *
     * `purchase` / `restore` 两条路都走它 —— 它们的差别只有 `initiation_source` 与 `is_restore`
     * （契约 §2.1 的转移语义，ADR 0046 ②）。
     */
    fun postTransactionAndConsumeIfNeeded(
        purchase: StoreTransaction,
        storeProduct: StoreProduct?,
        subscriptionOptionsForProductIds: Map<String, SubscriptionOption>?,
        isRestore: Boolean,
        appUserID: String,
        initiationSource: String,
        sdkOriginated: Boolean,
        onSuccess: (StoreTransaction, CustomerInfo) -> Unit,
        onError: (StoreTransaction, PurchasesError) -> Unit,
    ) {
        if (skipIfNotPurchased(purchase) { error -> onError(purchase, error) }) return

        val receiptInfo = ReceiptInfo.from(
            transaction = purchase,
            storeProduct = storeProduct,
            subscriptionOptionsForProductIds = subscriptionOptionsForProductIds,
            sdkOriginated = sdkOriginated,
        )
        performPostReceipt(
            appUserID = appUserID,
            purchaseToken = purchase.purchaseToken,
            isRestore = isRestore,
            receiptInfo = receiptInfo,
            initiationSource = initiationSource,
            onSuccess = { response, context ->
                // 「这笔是不是消耗品」的决定权在后端（考古 §9.1 必抄第 2 项）。
                billing.consumeAndSave(
                    purchasesAreCompletedBy = context.purchasesAreCompletedBy,
                    purchase = purchase,
                    shouldConsume = response.shouldConsumeFor(purchase.productIds),
                    deterministicallyRejected = false,
                )
                onSuccess(purchase, response.customerInfo)
            },
            onError = { error, handling, context ->
                if (handling == PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED) {
                    // 七分支第 ⑦ 条：确定性 4xx → **ack 但不 consume**。
                    billing.consumeAndSave(
                        purchasesAreCompletedBy = context.purchasesAreCompletedBy,
                        purchase = purchase,
                        shouldConsume = null,
                        deterministicallyRejected = true,
                    )
                }
                onError(purchase, error.toPurchasePostingError(handling))
            },
        )
    }

    /**
     * 只上报、**完全不碰 Billing 的完成动作**，成功即记台账（避免重复上报）。
     * `syncPurchases` 走它（设计 §3 A6）。结构对照 RC `postTokenWithoutConsuming`。
     */
    fun postTokenWithoutConsuming(
        purchase: StoreTransaction,
        appUserID: String,
        initiationSource: String,
        isRestore: Boolean,
        onSuccess: (CustomerInfo) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        if (skipIfNotPurchased(purchase, onError)) return

        val receiptInfo = ReceiptInfo(
            productIds = purchase.productIds,
            platformProductIds = purchase.productIds.map { PlatformProductId(it) },
            purchaseTime = purchase.purchaseTime,
            sdkOriginated = false,
        )
        performPostReceipt(
            appUserID = appUserID,
            purchaseToken = purchase.purchaseToken,
            isRestore = isRestore,
            receiptInfo = receiptInfo,
            initiationSource = initiationSource,
            onSuccess = { response, _ ->
                deviceCache.addSuccessfullyPostedToken(purchase.purchaseToken, purchase.isAutoRenewing)
                onSuccess(response.customerInfo)
            },
            onError = { error, handling, _ ->
                if (handling == PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED) {
                    deviceCache.addSuccessfullyPostedToken(purchase.purchaseToken, purchase.isAutoRenewing)
                }
                onError(error.toPurchasePostingError(handling))
            },
        )
    }

    /**
     * 补报「本地有上下文、但 `queryPurchases` 看不到」的交易。
     * 结构对照 RC `postRemainingCachedTransactionMetadata`（考古 §9.1 必抄第 8 项）。
     *
     * 为什么必须有这一步：`queryPurchases` 只返回**活跃订阅 + 未消耗的一次性商品**。
     * 一笔「已 consume 但上报失败」的消耗品在那里**查不到**，只能靠落盘的上报上下文补报。
     *
     * @param pendingTransactionTokens 当前处于 `PENDING` 的 token，**必须排除**（铁律 A3）：
     * 它们的钱还没扣，上报了等于把一笔不存在的收入报上去。
     */
    fun postRemainingCachedTransactionMetadata(
        appUserID: String,
        isRestore: Boolean,
        pendingTransactionTokens: Set<String>,
        onNoTransactionsToSync: () -> Unit,
        onError: (PurchasesError) -> Unit,
        onSuccess: (CustomerInfo) -> Unit,
    ) {
        val toSync = pendingPurchases.allPostContexts().filterNot { it.token in pendingTransactionTokens }
        if (toSync.isEmpty()) {
            onNoTransactionsToSync()
            return
        }
        Logger.debug { "补报 ${toSync.size} 笔有本地上下文但 queryPurchases 看不到的交易" }
        val results = ConcurrentLinkedQueue<Result<CustomerInfo>>()
        toSync.forEach { context ->
            performPostReceipt(
                appUserID = appUserID,
                purchaseToken = context.token,
                isRestore = isRestore,
                receiptInfo = context.receiptInfo,
                initiationSource = InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
                onSuccess = { response, _ ->
                    // 这条路上没有 StoreTransaction（Play 已经看不到这笔了），
                    // 只能记台账 —— 台账才是 Android 版的 finish。
                    deviceCache.addSuccessfullyPostedToken(context.token)
                    results.add(Result.success(response.customerInfo))
                    completeWhenAllDone(toSync.size, results, onError, onSuccess)
                },
                onError = { error, handling, _ ->
                    results.add(Result.failure(PurchasesException(error.toPurchasePostingError(handling))))
                    completeWhenAllDone(toSync.size, results, onError, onSuccess)
                },
            )
        }
    }

    /**
     * 「攒齐全部结果后，任一失败就整体报错，全成功才用最后一个的 CustomerInfo」
     * （结构对照 RC `callTransactionMetadataCompletionFromResults`）。
     */
    private fun completeWhenAllDone(
        expected: Int,
        results: ConcurrentLinkedQueue<Result<CustomerInfo>>,
        onError: (PurchasesError) -> Unit,
        onSuccess: (CustomerInfo) -> Unit,
    ) {
        if (results.size != expected) return
        val snapshot = results.toList()
        val failure = snapshot.firstOrNull { it.isFailure }
        if (failure != null) {
            onError((failure.exceptionOrNull() as? PurchasesException)?.error ?: unknownPostError())
            return
        }
        snapshot.lastOrNull()?.getOrNull()?.let(onSuccess)
    }

    /**
     * 真正发一次 `POST /v1/receipts`。
     *
     * 上下文处理（考古 §5.4，三条一起）：**缓存优先**（同 token 的第二次上报用第一次的归因）、
     * **只在 `purchase` 时落盘**、**成功或确定性 4xx 才清**。
     */
    private fun performPostReceipt(
        appUserID: String,
        purchaseToken: String,
        isRestore: Boolean,
        receiptInfo: ReceiptInfo,
        initiationSource: String,
        onSuccess: (PostReceiptResponse, PostedTransactionContext) -> Unit,
        onError: (PurchasesError, PostReceiptErrorHandling, PostedTransactionContext) -> Unit,
    ) {
        val context = pendingPurchases.getOrPutPostContext(
            token = purchaseToken,
            receiptInfo = receiptInfo,
            initiationSource = initiationSource,
            purchasesAreCompletedBy = appConfig.purchasesAreCompletedBy,
        )
        backend.postReceiptData(
            purchaseToken = purchaseToken,
            appUserID = appUserID,
            isRestore = isRestore,
            // 用落盘的那一份：宿主在购买之后改了 offering / 价格 / 完成者模式，补报仍按购买当时。
            receiptInfo = context.receiptInfo,
            initiationSource = initiationSource,
            purchasesAreCompletedBy = context.purchasesAreCompletedBy,
            appInBackground = appConfig.isAppBackgrounded,
            onSuccess = { response ->
                pendingPurchases.clearPostContext(purchaseToken)
                customerInfoUpdateHandler.cacheAndNotifyListeners(response.customerInfo, appUserID)
                diagnostics.track(
                    DiagnosticsTracker.EVENT_RECEIPT_POST,
                    mapOf(
                        "initiation_source" to initiationSource,
                        "outcome" to DiagnosticsTracker.OUTCOME_SUCCESS,
                        "is_restore" to isRestore,
                    ),
                )
                onSuccess(response, context)
            },
            onError = { error, handling ->
                if (handling == PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED) {
                    pendingPurchases.clearPostContext(purchaseToken)
                } else {
                    Logger.warn { "receipts 上报失败（可重试），上下文留存等下次前台重放：$error" }
                }
                diagnostics.track(
                    DiagnosticsTracker.EVENT_RECEIPT_POST,
                    mapOf(
                        "initiation_source" to initiationSource,
                        "outcome" to if (handling == PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED) {
                            DiagnosticsTracker.OUTCOME_REJECTED
                        } else {
                            DiagnosticsTracker.OUTCOME_RETRYABLE
                        },
                        "error_code" to error.code.name,
                        "http_status" to error.httpStatusCode,
                    ),
                )
                onError(error, handling, context)
            },
        )
    }

    /**
     * 上报前的最后一道闸（坑 3）：**只有 `PURCHASED` 的交易能上报**。
     *
     * 正常链路里两个调用方都已经把 `PENDING` 挡在外面（编排层回 pending 结果、
     * 补报路径排除 PENDING token），这里是纵深防御。
     *
     * 错误码用 `operationAlreadyInProgressError` 而不是 RC 的 `PaymentPendingError`：
     * 我方两端共用的 `sdk/error-codes.json` 里**没有** pending 这一位，
     * 而新增码位要两端同批改（登记为给 M3 的待办）。语义上「付款还在进行中」也站得住。
     */
    private fun skipIfNotPurchased(purchase: StoreTransaction, onError: (PurchasesError) -> Unit): Boolean {
        if (purchase.purchaseState == PurchaseState.PURCHASED) return false
        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_PENDING,
            mapOf(
                "purchase_state" to purchase.purchaseState.rawValue,
                "source" to "post_receipt_guard",
            ),
        )
        val error = PurchasesError(
            PurchasesErrorCode.OperationAlreadyInProgressError,
            "交易状态为 ${purchase.purchaseState}，尚未扣款：不上报、不 ack、不 consume、不记台账",
        )
        Logger.error { error.toString() }
        onError(error)
        return true
    }

    private fun unknownPostError(): PurchasesError =
        PurchasesError(PurchasesErrorCode.UnknownError, "补报失败但没有拿到具体错误")
}
