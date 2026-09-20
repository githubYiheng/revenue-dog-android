package org.revdog.purchases.customerinfo

import android.os.Handler
import android.os.Looper
import org.revdog.purchases.CacheFetchPolicy
import org.revdog.purchases.Logger
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.UpdatedCustomerInfoListener
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.CacheDurations
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.DeliveryOrigin
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.posting.PostPendingTransactionsHelper
import org.revdog.purchases.posting.SyncPendingPurchaseResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CustomerInfo 的缓存与通知。结构对照 RC `CustomerInfoUpdateHandler.kt`。
 *
 * 两道守卫（考古 §6.5 必抄第 20 项）：
 * 1. **身份守卫**：上一个用户的迟到响应绝不通知 listener。
 *    logIn 之后旧身份的请求才回来，如果照发，宿主会把旧用户的权益当成新用户的。
 * 2. **去重**：与上次发出的 CustomerInfo 相同就不重复回调。
 */
internal class CustomerInfoUpdateHandler(
    private val deviceCache: DeviceCache,
    private val identityManager: IdentityManager,
    private val mainDispatcher: MainDispatcher,
) {

    @Volatile
    private var lastSentCustomerInfo: CustomerInfo? = null

    private val listenerLock = Any()

    var updatedCustomerInfoListener: UpdatedCustomerInfoListener? = null
        @Synchronized get
        set(value) {
            synchronized(listenerLock) { field = value }
            afterSetListener(value)
        }

    /** 额外的内部订阅者（`customerInfoFlow` 的来源）。 */
    @Volatile
    var internalObserver: ((CustomerInfo) -> Unit)? = null

    fun cacheAndNotifyListeners(customerInfo: CustomerInfo, appUserID: String) {
        deviceCache.cacheCustomerInfo(appUserID, customerInfo)
        notifyListeners(customerInfo, appUserID)
    }

    @Suppress("ReturnCount")
    fun notifyListeners(customerInfo: CustomerInfo, appUserID: String) {
        val currentAppUserID = identityManager.currentAppUserID
        if (appUserID != currentAppUserID) {
            Logger.debug { "不通知监听器：这份 CustomerInfo 属于上一个身份（$appUserID ≠ $currentAppUserID）" }
            return
        }
        // Flow 走自己的通道：它用 `distinctUntilChanged` 去重，与 listener 的去重互不干扰。
        val observer = internalObserver
        if (observer != null) mainDispatcher.dispatch { observer(customerInfo) }

        // listener 的去重严格照抄 RC：**只有在 listener 存在时才推进 `lastSentCustomerInfo`**。
        // 不这么做的话，宿主在第一次刷新之后才挂 listener 就永远收不到那份缓存 ——
        // 「挂上即收到当前状态」是这个 API 的全部意义。
        val listener = synchronized(listenerLock) { updatedCustomerInfoListener } ?: return
        if (lastSentCustomerInfo == customerInfo) return
        lastSentCustomerInfo = customerInfo
        // 锁内取出、锁外调用（三条防死锁铁律之一，与 iOS 同款）。
        mainDispatcher.dispatch { listener.onReceived(customerInfo) }
    }

    /** 挂上 listener 时立刻把缓存里的那份发一次（RC 同款，宿主不必自己先拉一遍）。 */
    private fun afterSetListener(listener: UpdatedCustomerInfoListener?) {
        if (listener == null) return
        val appUserID = identityManager.currentAppUserID
        deviceCache.getCachedCustomerInfo(appUserID)?.let { notifyListeners(it, appUserID) }
    }

    fun resetLastSent() {
        lastSentCustomerInfo = null
    }
}

/**
 * `getCustomerInfo(fetchPolicy)` 的四态实现。
 * 结构对照 RC `CustomerInfoHelper.kt`（去掉离线权益分支 —— 我方没有离线权益计算）。
 *
 * **每一条「要发网络请求」的路径都先补报一轮待同步购买**（RC `CustomerInfoHelper.kt:88-109`）：
 * 先把端上还没上报成功的购买送出去，后端的权益才是全的。不这么做的话，
 * 「买成功但上报前进程被杀」的那笔会一直到下一次连接成功 / 回前台才被发现，
 * 期间宿主主动拉到的 CustomerInfo 里没有它 —— 用户付了钱看不到权益。
 */
@Suppress("LongParameterList")
internal class CustomerInfoManager(
    private val backend: Backend,
    private val deviceCache: DeviceCache,
    private val updateHandler: CustomerInfoUpdateHandler,
    private val mainDispatcher: MainDispatcher,
    private val postPendingTransactionsHelper: PostPendingTransactionsHelper,
    private val dateProvider: DateProvider = DefaultDateProvider(),
    /** 「N 毫秒后跑一下」。生产 = 主线程 Handler；测试注入手动触发的替身。 */
    private val scheduleTimeout: (delayMs: Long, action: () -> Unit) -> Unit = { delayMs, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
    },
) {

    /**
     * @param onSuccess 第二个参数是这份 CustomerInfo 的**来源**（M4 新增，
     * `customer_info_fetch.cache_hit` 由它推导 —— 只有这一层分得清）。
     */
    fun getCustomerInfo(
        appUserID: String,
        fetchPolicy: CacheFetchPolicy,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        Logger.debug { "获取 CustomerInfo（policy=$fetchPolicy）" }
        when (fetchPolicy) {
            CacheFetchPolicy.CACHE_ONLY -> getCacheOnly(appUserID, onSuccess, onError)
            CacheFetchPolicy.FETCH_CURRENT ->
                postPendingPurchasesAndFetch(appUserID, appInBackground, onSuccess, onError)
            CacheFetchPolicy.CACHED_OR_FETCHED ->
                getCachedOrFetched(appUserID, appInBackground, onSuccess, onError)
            CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT ->
                getNotStaleCachedOrCurrent(appUserID, appInBackground, onSuccess, onError)
            else -> getCachedOrFetched(appUserID, appInBackground, onSuccess, onError)
        }
    }

    fun cachedCustomerInfo(appUserID: String): CustomerInfo? = deviceCache.getCachedCustomerInfo(appUserID)

    /** `invalidateCustomerInfoCache()`：抹掉时间戳即可，内容留着供离线使用。 */
    fun invalidateCustomerInfoCache(appUserID: String) {
        deviceCache.clearCustomerInfoCacheTimestamp(appUserID)
    }

    private fun getCacheOnly(
        appUserID: String,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        val cached = deviceCache.getCachedCustomerInfo(appUserID)
        mainDispatcher.dispatch {
            if (cached != null) {
                onSuccess(cached, DeliveryOrigin.CACHE)
            } else {
                onError(PurchasesError(PurchasesErrorCode.CustomerInfoError, "缓存中没有 CustomerInfo"))
            }
        }
    }

    private fun getCachedOrFetched(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        val cached = deviceCache.getCachedCustomerInfo(appUserID)
        if (cached == null) {
            postPendingPurchasesAndFetch(appUserID, appInBackground, onSuccess, onError)
            return
        }
        mainDispatcher.dispatch { onSuccess(cached, DeliveryOrigin.CACHE) }
        if (isStale(appUserID, appInBackground)) {
            Logger.debug { "CustomerInfo 缓存已过期，后台刷新" }
            postPendingPurchasesAndFetch(appUserID, appInBackground, onSuccess = { _, _ -> }, onError = {})
        }
    }

    private fun getNotStaleCachedOrCurrent(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        val cached = deviceCache.getCachedCustomerInfo(appUserID)
        if (cached != null && !isStale(appUserID, appInBackground)) {
            mainDispatcher.dispatch { onSuccess(cached, DeliveryOrigin.CACHE) }
            return
        }
        postPendingPurchasesAndFetch(appUserID, appInBackground, onSuccess, onError)
    }

    /**
     * 先补报待同步购买，再决定要不要真的发 `GET /v1/subscribers`
     * （结构对照 RC `CustomerInfoHelper.postPendingPurchasesAndFetchCustomerInfo`，130-183 行）。
     *
     * - **补报真的送出去了东西并拿回了 CustomerInfo** → 就交付那份，**不再多发一次 GET**
     *   （它比 GET 还新：包含刚补报的那笔）。缓存与 listener 已经由
     *   `PostReceiptHelper.performPostReceipt` 的成功分支 `cacheAndNotifyListeners` 过了，
     *   这里不重复写（RC 同）；
     * - **没有待补报 / 补报出错 / 自动补报被关** → 照原样走 [fetchAndCache]。
     *
     * `cache_hit` 口径：由补报交付的这一份也是刚从网络拿的，记 [DeliveryOrigin.NETWORK]（= `false`）。
     */
    private fun postPendingPurchasesAndFetch(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        // **偏离 RC：顺带补报有 [SYNC_BEFORE_FETCH_TIMEOUT_MS] 的兜底。** 补报第一步是 `queryPurchases`，
        // BillingClient 没连上时它只会排队；连接若卡在可重试错误的退避里（封顶 15 分钟），
        // RC 的写法会让宿主的 `getCustomerInfo` 回调跟着挂到连上为止。权益查询不能被 Play 的连接状态
        // 劫持：超时就直接走 GET（= 补上这一步之前的行为），迟到的补报结果不再交付 ——
        // 它自己的成功分支已经 `cacheAndNotifyListeners` 过，listener 照样会收到。
        val settled = AtomicBoolean(false)
        scheduleTimeout(SYNC_BEFORE_FETCH_TIMEOUT_MS) {
            if (settled.compareAndSet(false, true)) {
                Logger.warn { "取 CustomerInfo 前的补报 ${SYNC_BEFORE_FETCH_TIMEOUT_MS}ms 没有结果，直接走 GET" }
                fetchAndCache(appUserID, appInBackground, onSuccess, onError)
            }
        }
        postPendingTransactionsHelper.syncPendingPurchaseQueue(
            appUserID = appUserID,
            skipIfAutoSyncDisabled = true,
        ) { result ->
            if (!settled.compareAndSet(false, true)) return@syncPendingPurchaseQueue
            when (result) {
                is SyncPendingPurchaseResult.Success -> {
                    Logger.debug { "补报待同步购买已经带回 CustomerInfo，直接交付、不再发 GET" }
                    mainDispatcher.dispatch { onSuccess(result.customerInfo, DeliveryOrigin.NETWORK) }
                }

                is SyncPendingPurchaseResult.Error,
                SyncPendingPurchaseResult.NoPendingPurchasesToSync,
                SyncPendingPurchaseResult.AutoSyncDisabled,
                -> fetchAndCache(appUserID, appInBackground, onSuccess, onError)
            }
        }
    }

    private fun fetchAndCache(
        appUserID: String,
        appInBackground: Boolean,
        onSuccess: (CustomerInfo, DeliveryOrigin) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        // 反直觉但必须（对照 RC `getCustomerInfoFetchOnly`）：**先把时间戳设成现在再发请求**，
        // 这样并发调用看到「缓存是新的」就不会重复发起；失败时再抹掉。
        deviceCache.setCustomerInfoCacheTimestampToNow(appUserID)
        backend.getCustomerInfo(
            appUserID = appUserID,
            appInBackground = appInBackground,
            onSuccess = { customerInfo ->
                updateHandler.cacheAndNotifyListeners(customerInfo, appUserID)
                mainDispatcher.dispatch { onSuccess(customerInfo, DeliveryOrigin.NETWORK) }
            },
            onError = { error, isServerError ->
                deviceCache.clearCustomerInfoCacheTimestamp(appUserID)
                // 后端 5xx：忽略 TTL 直接供 stale 缓存（设计 §4，v1 最小离线方案）。
                val cached = if (isServerError) deviceCache.getCachedCustomerInfo(appUserID) else null
                mainDispatcher.dispatch {
                    if (cached != null) {
                        Logger.warn { "后端 5xx，供给过期缓存：$error" }
                        onSuccess(cached, DeliveryOrigin.STALE_FALLBACK)
                    } else {
                        onError(error)
                    }
                }
            },
        )
    }

    internal companion object {
        /** 取 CustomerInfo 前那轮补报的最长等待。正常是一次本机 IPC + 至多一次上报，远小于它。 */
        const val SYNC_BEFORE_FETCH_TIMEOUT_MS: Long = 5_000L
    }

    private fun isStale(appUserID: String, appInBackground: Boolean): Boolean = CacheDurations.isStale(
        deviceCache.getCustomerInfoCachesLastUpdated(appUserID),
        appInBackground,
        dateProvider.now(),
    )
}
