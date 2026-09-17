package org.revdog.purchases.posting

import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.PurchasesException
import org.revdog.purchases.attributes.SubscriberAttribute
import org.revdog.purchases.attributes.SubscriberAttributesManager
import org.revdog.purchases.attributes.toWireJson
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.caching.PostedTransactionContext
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsErrorClass
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
    private val attributesManager: SubscriberAttributesManager,
    private val dateProvider: DateProvider = DefaultDateProvider(),
    /**
     * A8 的自保阈值。可注入**只为测试**（24h 的真实等待没法在单测里跑）。
     * 生产永远是 [ACK_SELF_PROTECT_THRESHOLD_MS]。
     */
    private val ackSelfProtectThresholdMs: Long = ACK_SELF_PROTECT_THRESHOLD_MS,
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
     *
     * 本方法还承担另外两件 M3 的事：
     * - **属性搭车**（设计 §5 / 契约 §2.1 的 `attributes`）：把当前身份的待同步属性挂在这次上报里，
     *   200 之后按响应的 `attributes_error_response` 标已同步（出错的键也标，否则会反复重传）；
     * - **A8 超时自保 ack**：可重试失败且首次上报已过 24h → 先 ack 防 Google 自动退款。
     */
    @Suppress("LongMethod")
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
        // 搭车属性在**发请求之前**取一份快照：标已同步时要用同一份（值或时间戳被新 setter 改过的
        // 条目必须留在待发队列，见 `SubscriberAttributesCache.markSynced`）。
        val pendingAttributes: List<SubscriberAttribute> = attributesManager.unsyncedAttributes(appUserID)
        val startedAtMs = dateProvider.now().time
        backend.postReceiptData(
            purchaseToken = purchaseToken,
            appUserID = appUserID,
            isRestore = isRestore,
            // 用落盘的那一份：宿主在购买之后改了 offering / 价格 / 完成者模式，补报仍按购买当时。
            receiptInfo = context.receiptInfo,
            initiationSource = initiationSource,
            purchasesAreCompletedBy = context.purchasesAreCompletedBy,
            appInBackground = appConfig.isAppBackgrounded,
            attributes = pendingAttributes.takeIf { it.isNotEmpty() }?.toWireJson(),
            acknowledgedBy = Backend.ACKNOWLEDGED_BY_SDK_TIMEOUT.takeIf { context.ackSelfProtected },
            onSuccess = { response ->
                pendingPurchases.clearPostContext(purchaseToken)
                attributesManager.markSyncedAfterReceiptPost(appUserID, pendingAttributes, response.attributeErrors)
                customerInfoUpdateHandler.cacheAndNotifyListeners(response.customerInfo, appUserID)
                diagnostics.track(
                    DiagnosticsTracker.EVENT_RECEIPT_POST,
                    mapOf(
                        "initiation_source" to initiationSource,
                        "outcome" to DiagnosticsTracker.OUTCOME_SUCCESS,
                        "is_restore" to isRestore,
                        "status" to RECEIPT_POST_SUCCESS_STATUS,
                        "request_id" to response.requestId,
                        "duration_ms" to (dateProvider.now().time - startedAtMs),
                        "attributes_sent" to pendingAttributes.size,
                        "attribute_error_count" to response.attributeErrors.size,
                        "acknowledged_by_sdk" to context.ackSelfProtected,
                    ),
                )
                onSuccess(response, context)
            },
            onError = { error, handling ->
                val retryable = handling != PostReceiptErrorHandling.SHOULD_BE_MARKED_SYNCED
                if (retryable) {
                    Logger.warn { "receipts 上报失败（可重试），上下文留存等下次前台重放：$error" }
                } else {
                    pendingPurchases.clearPostContext(purchaseToken)
                }
                diagnostics.track(
                    DiagnosticsTracker.EVENT_RECEIPT_POST,
                    mapOf(
                        "initiation_source" to initiationSource,
                        "outcome" to if (retryable) {
                            DiagnosticsTracker.OUTCOME_RETRYABLE
                        } else {
                            DiagnosticsTracker.OUTCOME_REJECTED
                        },
                        "error_code" to error.code.name,
                        // `status` 是 wire 契约里的名字（服务端把它提成列做巡检不变式 18/19）。
                        // `http_status` 是 M2 留下的别名，保留以免打断既有查询。
                        "status" to error.httpStatusCode,
                        "http_status" to error.httpStatusCode,
                        "error_class" to DiagnosticsErrorClass.from(error.httpStatusCode),
                        "request_id" to error.requestId,
                        "duration_ms" to (dateProvider.now().time - startedAtMs),
                        "attributes_sent" to pendingAttributes.size,
                    ),
                )
                // A8 只在**可重试**失败上触发：确定性 4xx 已经走七分支第 ⑦ 条 ack 掉了。
                if (retryable) maybeSelfProtectAcknowledge(purchaseToken)
                onError(error, handling, context)
            },
        )
    }

    /**
     * **A8 ack 超时自保**（设计 §3 A8，偏离 RC；决策 A 的选项 b）。
     *
     * 前提：服务端是 ack 权威（设计 §8）。这里只是最后兜底 —— 后端连续不可用时，
     * Google 会在**第 3 天**自动退款并撤销权益，而 ack 是「对 Google 说这笔我收到了」，
     * 与「权益是否成立」是两件事。所以：
     *
     * 1. 只在 `purchasesCompletedBy = revenue_dog` 时做（`my_app` 模式下 SDK 绝不碰 Billing）；
     * 2. 只在**首次上报**起 [ackSelfProtectThresholdMs]（24h）之后做；
     * 3. 先 `queryPurchases` 看这笔的 `isAcknowledged` —— **服务端可能已经代为 ack 过了**；
     * 4. 没 ack 过才 `acknowledge`，**绝不 consume**（consume 掉就再也补报不了了）；
     * 5. 成功 → 上下文标 `ackSelfProtected`（落盘），后续上报带 `acknowledged_by=sdk_timeout`；
     * 6. ack 失败 → **什么都不标、上下文不清**，下次可重试失败时再试一轮。
     *
     * Play 上查不到这个 token（已 consume / 已过期）→ 没什么可 ack 的，直接跳过。
     */
    @Suppress("ReturnCount")
    private fun maybeSelfProtectAcknowledge(purchaseToken: String) {
        if (appConfig.purchasesAreCompletedBy != PurchasesAreCompletedBy.REVENUE_DOG) return
        val context = pendingPurchases.postContext(purchaseToken) ?: return
        if (context.ackSelfProtected) return
        val elapsed = dateProvider.now().time - context.firstAttemptAtMs
        if (elapsed < ackSelfProtectThresholdMs) return

        Logger.warn {
            "这笔购买首次上报已过 ${elapsed / MILLIS_PER_HOUR}h 仍未被后端确认：先自保 ack 防 Google 自动退款（A8）"
        }
        billing.queryPurchases(
            onSuccess = { byHashedToken ->
                val transaction = byHashedToken.values.firstOrNull { it.purchaseToken == purchaseToken }
                if (transaction == null) {
                    Logger.debug { "A8：Play 上已看不到这笔购买，无需 ack" }
                    return@queryPurchases
                }
                if (transaction.isAcknowledged) {
                    // 服务端已经代为 ack 了（设计 §8）。只标记，让后端知道这笔的 ack 状态不是它这一次给的。
                    trackSelfProtect(BillingWrapper.DECISION_ACK_SELF_PROTECT_ALREADY_ACKED, elapsed)
                    pendingPurchases.markAckSelfProtected(purchaseToken)
                    return@queryPurchases
                }
                trackSelfProtect(BillingWrapper.DECISION_ACK_SELF_PROTECT, elapsed)
                billing.acknowledge(purchaseToken) {
                    // 只有 ack **真的成功**才落这个标记（`acknowledge` 失败时这个回调不会被调）。
                    pendingPurchases.markAckSelfProtected(purchaseToken)
                    Logger.info { "A8：自保 ack 成功，后续上报会带 acknowledged_by=sdk_timeout" }
                }
            },
            onError = { error -> Logger.warn { "A8：查询购买失败，本轮不自保 ack：$error" } },
        )
    }

    private fun trackSelfProtect(decision: String, elapsedMs: Long) {
        diagnostics.track(
            DiagnosticsTracker.EVENT_CONSUME_DECISION,
            mapOf(
                "decision" to decision,
                "elapsed_hours" to elapsedMs / MILLIS_PER_HOUR,
                "threshold_hours" to ackSelfProtectThresholdMs / MILLIS_PER_HOUR,
            ),
        )
    }

    /**
     * 上报前的最后一道闸（坑 3）：**只有 `PURCHASED` 的交易能上报**。
     *
     * 正常链路里两个调用方都已经把 `PENDING` 挡在外面（编排层回 pending 结果、
     * 补报路径排除 PENDING token），这里是纵深防御。
     *
     * 错误码 `paymentPendingError`（20，与 RC `PaymentPendingError` **同位**）：
     * M2 期间它还不在 `sdk/error-codes.json` 里，临时借用了 `operationAlreadyInProgressError`；
     * M3 补上了这一位，语义因此对齐两端与 RC。
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
            PurchasesErrorCode.PaymentPendingError,
            "交易状态为 ${purchase.purchaseState}，尚未扣款：不上报、不 ack、不 consume、不记台账",
        )
        Logger.error { error.toString() }
        onError(error)
        return true
    }

    private fun unknownPostError(): PurchasesError =
        PurchasesError(PurchasesErrorCode.UnknownError, "补报失败但没有拿到具体错误")

    internal companion object {
        /**
         * A8 的自保阈值 = **24 小时**（设计 §3 A8 / 考古 §9.3 决策 A）。
         * Google 的 ack 期限是 3 天，24h 留足两轮前台重试的余量。
         */
        const val ACK_SELF_PROTECT_THRESHOLD_MS: Long = 24L * 60L * 60L * 1000L

        /** 诊断字段用小时，别在日志里堆毫秒。 */
        const val MILLIS_PER_HOUR: Long = 60L * 60L * 1000L

        /** `receipt_post` 成功时的 `status`。后端 2xx 里只可能是 200（201 属 identify）。 */
        const val RECEIPT_POST_SUCCESS_STATUS: Int = 200
    }
}
