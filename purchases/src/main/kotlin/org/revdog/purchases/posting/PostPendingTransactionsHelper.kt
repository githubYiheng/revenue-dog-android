package org.revdog.purchases.posting

import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreTransaction

/** 一次补报的结果（对照 RC `SyncPendingPurchaseResult`）。 */
internal sealed class SyncPendingPurchaseResult {
    class Success(val customerInfo: CustomerInfo) : SyncPendingPurchaseResult()
    class Error(val error: PurchasesError) : SyncPendingPurchaseResult()
    object NothingToSync : SyncPendingPurchaseResult()
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
    private val deviceCache: DeviceCache,
    private val billing: BillingWrapper,
    private val dispatcher: Dispatcher,
    private val postTransactionsHelper: PostTransactionsHelper,
    private val postReceiptHelper: PostReceiptHelper,
) {

    fun syncPendingPurchaseQueue(
        appUserID: String,
        isRestore: Boolean = false,
        callback: ((SyncPendingPurchaseResult) -> Unit)? = null,
    ) {
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
            postRemaining(appUserID, isRestore, pendingTokens, callback, fallback = null)
            return
        }

        Logger.debug {
            "补报 ${transactionsToSync.size} 笔（新增 ${newHashes.size} / 续订状态变更 ${changedHashes.size}）"
        }
        // 「攒齐全部结果、任一失败就整体报错」（对照 RC `callCompletionFromResults`）。
        val errors = mutableListOf<PurchasesError>()
        var completed = 0
        val onOneDone = {
            completed++
            if (completed == transactionsToSync.size) {
                postRemaining(appUserID, isRestore, pendingTokens, callback, fallback = errors.firstOrNull())
            }
        }
        postTransactionsHelper.postTransactions(
            transactions = transactionsToSync,
            isRestore = isRestore,
            appUserID = appUserID,
            initiationSource = InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
            sdkOriginated = false,
            onTransactionSuccess = { _, _ -> onOneDone() },
            onTransactionError = { _, error ->
                errors += error
                onOneDone()
            },
        )
    }

    /** 无论上一步成功失败，都要再补一轮「有本地上下文但 Play 看不到」的交易（考古 §3.4 第 ⑤ 步）。 */
    private fun postRemaining(
        appUserID: String,
        isRestore: Boolean,
        pendingTokens: Set<String>,
        callback: ((SyncPendingPurchaseResult) -> Unit)?,
        fallback: PurchasesError?,
    ) {
        postReceiptHelper.postRemainingCachedTransactionMetadata(
            appUserID = appUserID,
            isRestore = isRestore,
            pendingTransactionTokens = pendingTokens,
            onNoTransactionsToSync = {
                callback?.invoke(
                    fallback?.let { SyncPendingPurchaseResult.Error(it) } ?: SyncPendingPurchaseResult.NothingToSync,
                )
            },
            onError = { error -> callback?.invoke(SyncPendingPurchaseResult.Error(fallback ?: error)) },
            onSuccess = { customerInfo ->
                callback?.invoke(
                    fallback?.let { SyncPendingPurchaseResult.Error(it) }
                        ?: SyncPendingPurchaseResult.Success(customerInfo),
                )
            },
        )
    }
}
