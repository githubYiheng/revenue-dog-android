package org.revdog.purchases.customerinfo

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
 * 结构对照 RC `CustomerInfoHelper.kt`（去掉离线权益与补报分支 —— 那些是 M2/M3）。
 */
internal class CustomerInfoManager(
    private val backend: Backend,
    private val deviceCache: DeviceCache,
    private val updateHandler: CustomerInfoUpdateHandler,
    private val mainDispatcher: MainDispatcher,
    private val dateProvider: DateProvider = DefaultDateProvider(),
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
            CacheFetchPolicy.FETCH_CURRENT -> fetchAndCache(appUserID, appInBackground, onSuccess, onError)
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
            fetchAndCache(appUserID, appInBackground, onSuccess, onError)
            return
        }
        mainDispatcher.dispatch { onSuccess(cached, DeliveryOrigin.CACHE) }
        if (isStale(appUserID, appInBackground)) {
            Logger.debug { "CustomerInfo 缓存已过期，后台刷新" }
            fetchAndCache(appUserID, appInBackground, onSuccess = { _, _ -> }, onError = {})
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
        fetchAndCache(appUserID, appInBackground, onSuccess, onError)
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

    private fun isStale(appUserID: String, appInBackground: Boolean): Boolean = CacheDurations.isStale(
        deviceCache.getCustomerInfoCachesLastUpdated(appUserID),
        appInBackground,
        dateProvider.now(),
    )
}
