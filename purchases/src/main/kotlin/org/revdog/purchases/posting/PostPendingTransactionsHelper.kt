package org.revdog.purchases.posting

import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreTransaction

/** 一次补报的结果（四态逐个对照 RC `SyncPendingPurchaseResult`）。 */
internal sealed class SyncPendingPurchaseResult {
    class Success(val customerInfo: CustomerInfo) : SyncPendingPurchaseResult()
    class Error(val error: PurchasesError) : SyncPendingPurchaseResult()
    object NoPendingPurchasesToSync : SyncPendingPurchaseResult()

    /** 自动补报被关掉（RC 的 `dangerousSettings.autoSyncPurchases = false`，我方见下方参数说明）。 */
    object AutoSyncDisabled : SyncPendingPurchaseResult()
}

/**
 * **前台恢复 / 连接成功时的补报**。结构对照 RC `PostPendingTransactionsHelper.kt`
 * （考古 §3.4 的完整逻辑 + §5.5 的三条链路）。
 *
 * 三条链路缺一不可：
 * 1. **`queryPurchases` 差集**：Play 还认的交易 − 本地台账 = 要补报的
 *    → 覆盖「购买成功但上报前进程被杀」；
 * 2. **`isAutoRenewing` diff**：台账里有、Play 也有，但续订状态翻了 → 重报
 *    → 覆盖「在 Play 商店外取消订阅」（坑 23，这是**唯一**侦测手段）；
 * 3. **`postRemainingCachedTransactionMetadata`**：本地有上报上下文但 `queryPurchases` 看不到
 *    （已 consume / 已过期）→ 单独补报，并**排除当前 PENDING 的 token**。
 *
 * 顺序也是有讲究的：先 `cleanPreviouslySentTokens` 清掉已不活跃的台账条目，
 * 再算差集；`saveAutoRenewingStatus` 只写**没被判为变更**的那些，
 * 变更的那几笔留给上报成功后的 `consumeAndSave` 去写（否则这一轮就把差异抹掉了，下一轮不会再报）。
 */
@Suppress("LongParameterList")
internal class PostPendingTransactionsHelper(
    private val appConfig: AppConfig,
    private val deviceCache: DeviceCache,
    private val billing: BillingWrapper,
    private val dispatcher: Dispatcher,
    private val postTransactionsHelper: PostTransactionsHelper,
    private val postReceiptHelper: PostReceiptHelper,
) {

    /**
     * @param skipIfAutoSyncDisabled **只有「联网取 CustomerInfo 之前顺带的那一轮」传 `true`**。
     * `purchasesAreCompletedBy = my_app` 时宿主自管交易完成、自己挑同步时机
     * （公开面是 `syncPurchases()`），这条顺带链路就让位给原来的 `GET /v1/subscribers`
     * —— 对照 RC `SyncPendingPurchaseResult.AutoSyncDisabled`。
     *
     * **显式触发点（BillingClient 连接成功 / 回前台 / `ITEM_ALREADY_OWNED`）不受它影响**：
     * 我方 `my_app` 模式的语义是「照常上报，只是不 ack / consume」（见 `Purchases.purchase` 的告警），
     * 与 RC 那个「整个 SDK 不自动同步」的开关不是一回事，不能顺手合并。
     */
    fun syncPendingPurchaseQueue(
        appUserID: String,
        isRestore: Boolean = false,
        skipIfAutoSyncDisabled: Boolean = false,
        callback: ((SyncPendingPurchaseResult) -> Unit)? = null,
    ) {
        if (skipIfAutoSyncDisabled && appConfig.purchasesAreCompletedBy != PurchasesAreCompletedBy.REVENUE_DOG) {
            Logger.debug { "purchasesCompletedBy = my_app：跳过取 CustomerInfo 前顺带的那轮补报" }
            callback?.invoke(SyncPendingPurchaseResult.AutoSyncDisabled)
            return
        }
        Logger.debug { "开始补报待同步购买" }
        dispatcher.enqueue(
            Runnable {
                billing.queryPurchases(
                    onSuccess = { purchasesByHashedToken ->
                        handleQueried(appUserID, isRestore, purchasesByHashedToken, callback)
                    },
                    onError = { error ->
                        Logger.error { "补报前查询购买失败：$error" }
                        callback?.invoke(SyncPendingPurchaseResult.Error(error))
                    },
                )
            },
        )
    }

    private fun handleQueried(
        appUserID: String,
        isRestore: Boolean,
        purchasesByHashedToken: Map<String, StoreTransaction>,
        callback: ((SyncPendingPurchaseResult) -> Unit)?,
    ) {
        deviceCache.cleanPreviouslySentTokens(purchasesByHashedToken.keys)

        val autoRenewingByHash = purchasesByHashedToken.mapValues { (_, transaction) -> transaction.isAutoRenewing }
        val newHashes = deviceCache.hashedTokensNotInCache(purchasesByHashedToken.keys)
        val changedHashes = deviceCache.hashedTokensWithAutoRenewingChange(autoRenewingByHash)
        // 变更的那几笔**不在这里写**：等它上报成功后由 consumeAndSave 写，否则差异会被这一轮抹掉。
        deviceCache.saveAutoRenewingStatus(autoRenewingByHash - changedHashes)

        val transactionsToSync = (newHashes + changedHashes)
            .mapNotNull { purchasesByHashedToken[it] }
            .distinctBy { it.purchaseToken }
            // PENDING 的钱还没扣，一笔都不能报（坑 3）。
            .filter { it.purchaseState == PurchaseState.PURCHASED }

        val pendingTokens = purchasesByHashedToken.values
            .filter { it.purchaseState == PurchaseState.PENDING }
            .map { it.purchaseToken }
            .toSet()

        if (transactionsToSync.isEmpty()) {
            postRemaining(appUserID, isRestore, pendingTokens, callback, fallback = null, syncedInfo = null)
            return
        }

        Logger.debug {
            "补报 ${transactionsToSync.size} 笔（新增 ${newHashes.size} / 续订状态变更 ${changedHashes.size}）"
        }
        // 「攒齐全部结果、任一失败就整体报错，全成功就用最后一个的 CustomerInfo」
        // （对照 RC `callCompletionFromResults`）。
        val errors = mutableListOf<PurchasesError>()
        var lastCustomerInfo: CustomerInfo? = null
        var completed = 0
        val onOneDone = {
            completed++
            if (completed == transactionsToSync.size) {
                postRemaining(
                    appUserID = appUserID,
                    isRestore = isRestore,
                    pendingTokens = pendingTokens,
                    callback = callback,
                    fallback = errors.firstOrNull(),
                    syncedInfo = lastCustomerInfo.takeIf { errors.isEmpty() },
                )
            }
        }
        postTransactionsHelper.postTransactions(
            transactions = transactionsToSync,
            isRestore = isRestore,
            appUserID = appUserID,
            initiationSource = InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
            sdkOriginated = false,
            onTransactionSuccess = { _, customerInfo ->
                lastCustomerInfo = customerInfo
                onOneDone()
            },
            onTransactionError = { _, error ->
                errors += error
                onOneDone()
            },
        )
    }

    /**
     * 无论上一步成功失败，都要再补一轮「有本地上下文但 Play 看不到」的交易（考古 §3.4 第 ⑤ 步）。
     *
     * @param fallback 差集那一轮的第一个错误（有就整体算失败）。
     * @param syncedInfo 差集那一轮**全部成功**时最后一笔带回来的 CustomerInfo。
     * 它必须一路传到这里：残留轮没东西可报时要交付的就是它 —— 丢掉它，
     * 调用方就会以为「什么都没同步」，白白再发一次 `GET /v1/subscribers`
     * （九宫格逐格对照 RC `PostPendingTransactionsHelper` 的三个 `postRemainingCachedTransactionMetadata`）。
     */
    private fun postRemaining(
        appUserID: String,
        isRestore: Boolean,
        pendingTokens: Set<String>,
        callback: ((SyncPendingPurchaseResult) -> Unit)?,
        fallback: PurchasesError?,
        syncedInfo: CustomerInfo?,
    ) {
        postReceiptHelper.postRemainingCachedTransactionMetadata(
            appUserID = appUserID,
            isRestore = isRestore,
            pendingTransactionTokens = pendingTokens,
            onNoTransactionsToSync = {
                callback?.invoke(
                    when {
                        fallback != null -> SyncPendingPurchaseResult.Error(fallback)
                        syncedInfo != null -> SyncPendingPurchaseResult.Success(syncedInfo)
                        else -> SyncPendingPurchaseResult.NoPendingPurchasesToSync
                    },
                )
            },
            onError = { error -> callback?.invoke(SyncPendingPurchaseResult.Error(fallback ?: error)) },
            // 残留轮成功 = 确实有东西被同步上去了，这份 CustomerInfo 最新（RC 同：此处不看 fallback）。
            onSuccess = { customerInfo -> callback?.invoke(SyncPendingPurchaseResult.Success(customerInfo)) },
        )
    }
}
