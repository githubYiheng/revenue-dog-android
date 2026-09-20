package org.revdog.purchases

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.revdog.purchases.attributes.SubscriberAttributesCache
import org.revdog.purchases.attributes.SubscriberAttributesManager
import org.revdog.purchases.attributes.SubscriberAttributesPoster
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchaseStore
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.DefaultDateProvider
import org.revdog.purchases.common.DeliveryOrigin
import org.revdog.purchases.common.Dispatcher
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoManager
import org.revdog.purchases.customerinfo.CustomerInfoUpdateHandler
import org.revdog.purchases.diagnostics.DiagnosticsQueue
import org.revdog.purchases.diagnostics.DiagnosticsRecorder
import org.revdog.purchases.diagnostics.DiagnosticsSettings
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.diagnostics.DiagnosticsUploader
import org.revdog.purchases.diagnostics.DiagnosticsWarningCode
import org.revdog.purchases.diagnostics.HandlerDiagnosticsScheduler
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.ReplaceProductInfo
import org.revdog.purchases.identity.AccountToken
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.identity.PurchasesErrorHolder
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.networking.HTTPClient
import org.revdog.purchases.offerings.Offerings
import org.revdog.purchases.offerings.OfferingsManager
import org.revdog.purchases.posting.InitiationSource
import org.revdog.purchases.posting.PlatformProductId
import org.revdog.purchases.posting.PostPendingTransactionsHelper
import org.revdog.purchases.posting.PostReceiptHelper
import org.revdog.purchases.posting.PostTransactionsHelper
import org.revdog.purchases.posting.ReceiptInfo
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 唯一编排入口。结构对照 RC `PurchasesOrchestrator.kt`。
 *
 * **`init` 的顺序是有讲究的**（考古 §1.3，四条纪律）：
 * 1. **身份先于一切** —— 后面一切请求都要 appUserID。
 * 2. **listener 先于 `startConnection()`** —— 连接成功回调可能同步到达。
 * 3. 生命周期观察者最后注册（它的回调里会调 BillingClient）。
 * 4. 连接在后台线程发起（防 ANR）。
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class PurchasesOrchestrator(
    private val appConfig: AppConfig,
    private val identityManager: IdentityManager,
    private val backend: Backend,
    private val customerInfoManager: CustomerInfoManager,
    private val updateHandler: CustomerInfoUpdateHandler,
    private val offeringsManager: OfferingsManager,
    private val billing: BillingWrapper,
    private val mainDispatcher: MainDispatcher,
    private val diagnostics: DiagnosticsTracker,
    private val pendingPurchases: PendingPurchaseStore,
    private val postReceiptHelper: PostReceiptHelper,
    private val postTransactionsHelper: PostTransactionsHelper,
    private val postPendingTransactionsHelper: PostPendingTransactionsHelper,
    private val attributesManager: SubscriberAttributesManager,
    configuredAppUserID: String?,
    private val observeProcessLifecycle: Boolean = true,
    /** 关掉诊断时为 `null`（那时 [diagnostics] 是 no-op）。生命周期钩子要用它刷队列。 */
    private val diagnosticsRecorder: DiagnosticsRecorder? = null,
    private val dateProvider: DateProvider = DefaultDateProvider(),
) {

    private val customerInfoMutableFlow = MutableSharedFlow<CustomerInfo>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** `distinctUntilChanged`：与 listener 那条通道同口径的去重（相同状态不重复发）。 */
    val customerInfoFlow: Flow<CustomerInfo> = customerInfoMutableFlow.asSharedFlow().distinctUntilChanged()

    /** 进程内第一次回前台（RC `state.firstTimeInForeground`）：那一次无条件刷新 CustomerInfo。 */
    private val firstTimeInForeground = AtomicBoolean(true)

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = onAppForegrounded()

        override fun onStop(owner: LifecycleOwner) = onAppBackgrounded()
    }

    /**
     * 回前台（结构对照 RC `PurchasesOrchestrator.onAppForegrounded`）。
     *
     * **CustomerInfo 刷新**（2026-09-20 真机发现缺这一步后补上）：进程内首次回前台无条件拉一次，
     * 之后只在缓存过期（前台 5 分钟）时拉 —— 与 RC `shouldRefreshCustomerInfo` 同口径。没有它，
     * 只靠 `UpdatedCustomerInfoListener` / `customerInfoFlow` 判权益的宿主（RC 官方推荐写法之一）
     * 冷启后拿到的永远是盘上那份旧快照：已过期的订阅继续放行，别的设备上买的也进不来。
     * 失败只记日志与诊断、不打扰宿主（RC 同款）；成功经 `cacheAndNotifyListeners` 推给 listener。
     */
    internal fun onAppForegrounded() {
        val firstTime = firstTimeInForeground.getAndSet(false)
        setAppBackgrounded(false)
        refreshCustomerInfoOnForeground(firstTime)
        // 前台恢复是补报的两个触发点之一（考古 §3.4）。
        syncPendingPurchaseQueue()
        // 属性同步时机之一（设计 §5）：回前台。
        syncAttributes()
    }

    internal fun onAppBackgrounded() {
        setAppBackgrounded(true)
        // 进后台：属性与诊断都要在进程可能被冻结之前冲一次。
        syncAttributes()
        diagnosticsRecorder?.onAppBackgrounded()
    }

    private fun refreshCustomerInfoOnForeground(firstTime: Boolean) {
        // 首次：强拉；其余：未过期就用缓存（不发请求、不重复推 listener），过期才拉。
        val policy = if (firstTime) CacheFetchPolicy.FETCH_CURRENT else CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT
        getCustomerInfo(
            policy,
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = Unit
                override fun onError(error: PurchasesError) {
                    Logger.warn { "回前台刷新 CustomerInfo 失败（沿用缓存）：$error" }
                }
            },
        )
    }

    init {
        // ① 身份先于一切。
        identityManager.configure(configuredAppUserID)

        // ② listener 先挂再连接。
        billing.purchasesUpdatedListener = object : BillingWrapper.BillingPurchasesUpdatedListener {
            override fun onPurchasesUpdated(transactions: List<StoreTransaction>) {
                handlePurchasesUpdated(transactions)
            }

            override fun onPurchasesFailedToUpdate(error: PurchasesError, userCancelled: Boolean) {
                handlePurchasesFailedToUpdate(error, userCancelled)
            }
        }
        // 决策 D：上下文表只有一份，在 `PendingPurchaseStore` 里；Billing 层只读。
        billing.purchaseContextProvider = { productId -> pendingPurchases.contextFor(productId) }
        billing.stateListener = BillingWrapper.StateListener {
            Logger.debug { "BillingClient 已连接" }
            // 补报的**唯一可靠触发点**：每次（重）连接成功都跑一遍（考古 §3.4）。
            syncPendingPurchaseQueue()
        }
        billing.appInBackground = appConfig.isAppBackgrounded
        billing.startConnection()

        updateHandler.internalObserver = { customerInfo -> customerInfoMutableFlow.tryEmit(customerInfo) }

        // ③ 生命周期观察者最后注册，且必须在主线程（`ProcessLifecycleOwner` 的要求）。
        if (observeProcessLifecycle) {
            mainDispatcher.dispatch {
                runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }
                    .onFailure { Logger.warn { "注册进程生命周期观察者失败，前台补报改为只靠 onConnected：$it" } }
            }
        }

        diagnosticsRecorder?.appIsBackgrounded = { appConfig.isAppBackgrounded }
        diagnosticsRecorder?.start()

        // 字段与 iOS `sdk_configured` 逐项对齐（契约 §1.3）：`log_level` /
        // `purchases_completed_by` / `has_app_user_id` / `diagnostics_enabled`。
        // iOS 还有 `waits_for_login_before_sync` / `identity_gated` —— 那是身份门控
        // （ADR 0046/0047）的开关，Android 侧没有这个模式，因此不发。
        // `is_anonymous` 是 Android 多发的一项（`has_app_user_id` 说的是「宿主传没传」，
        // 这一项说的是「解析之后到底是不是匿名」，影子期宿主注入 RC 匿名 id 时两者会不同）。
        diagnostics.track(
            DiagnosticsTracker.EVENT_SDK_CONFIGURED,
            mapOf(
                "log_level" to Logger.logLevel.name,
                "purchases_completed_by" to appConfig.purchasesAreCompletedBy.rawValue,
                "has_app_user_id" to (configuredAppUserID != null),
                "diagnostics_enabled" to appConfig.diagnosticsEnabled,
                "is_anonymous" to identityManager.currentUserIsAnonymous(),
            ),
        )
    }

    // region 身份

    val appUserID: String get() = identityManager.currentAppUserID

    val isAnonymous: Boolean get() = identityManager.currentUserIsAnonymous()

    /**
     * logIn。新旧 id 相同时**完全不调后端**（RC 同款早返回，ADR 0048 第 2 条同源）：
     * 宿主每次启动都调 `logIn(同一个 uid)` 是常态，不该每次都打一发 identify。
     */
    fun logIn(newAppUserID: String, callback: LogInCallback) {
        if (newAppUserID == identityManager.currentAppUserID) {
            Logger.debug { "logIn 的 id 与当前身份相同，直接供 CustomerInfo" }
            getCustomerInfo(
                CacheFetchPolicy.default(),
                object : ReceiveCustomerInfoCallback {
                    override fun onReceived(customerInfo: CustomerInfo) = callback.onReceived(customerInfo, false)
                    override fun onError(error: PurchasesError) = callback.onError(error)
                },
            )
            return
        }
        val oldAppUserID = identityManager.currentAppUserID
        // 属性同步时机之一（设计 §5）：**logIn 合并之前**先把旧身份的待发属性刷出去。
        // 合并之后旧身份在服务端已经是别名了，那时再发会落到新 customer 名下（口径不一致）。
        syncAttributes()
        identityManager.logIn(
            newAppUserID = newAppUserID,
            onSuccess = { customerInfo, created ->
                // 刚才那一轮没发成功的，跟着身份搬到新 id（RC `copyUnsyncedSubscriberAttributes`）。
                attributesManager.copyUnsyncedAttributes(from = oldAppUserID, to = newAppUserID)
                updateHandler.resetLastSent()
                updateHandler.notifyListeners(customerInfo, newAppUserID)
                // 身份换了，offerings 也要按新身份重拉（不同用户可能命中不同 offering）。
                offeringsManager.getOfferings(
                    appUserID = newAppUserID,
                    appInBackground = appConfig.isAppBackgrounded,
                    fetchCurrent = true,
                    onError = { Logger.warn { "logIn 后刷新 offerings 失败：$it" } },
                    onSuccess = { _, _ -> },
                )
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGIN,
                    mapOf("created" to created),
                )
                // 搬过去的那些在新身份下还没落库，立刻再刷一轮（设计 §5「logIn 合并前后」）。
                syncAttributes()
                mainDispatcher.dispatch { callback.onReceived(customerInfo, created) }
            },
            onError = { error ->
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGIN,
                    mapOf("error_code" to error.code.name),
                )
                mainDispatcher.dispatch { callback.onError(error) }
            },
        )
    }

    /**
     * logOut。**偏离 RC 的纯本地实现**（ADR 0046–0048，设计 §1）：
     *
     * ① 匿名态守卫 + 生成**不落盘**的候选（纯函数，不改任何状态）；
     * ② 拿候选打 `GET /v1/subscribers/{candidate}`（服务端 get-or-create）；
     * ③ **仅当 ② 成功**才落盘身份切换、清缓存、发通知；
     * ④ ② 失败：身份、缓存、流**一个字节都不动**，原样把错误抛给宿主。
     *
     * 不这么做的后果：一旦离线，设备就被留在一个**后端从没见过**的匿名 ID 上，身份分裂。
     */
    fun logOut(callback: ReceiveCustomerInfoCallback) {
        // 与 logIn 同理：切身份之前把旧身份的待发属性刷出去。
        syncAttributes()
        val candidateResult = identityManager.candidateAnonymousAppUserID()
        val candidate = candidateResult.getOrElse { throwable ->
            val error = (throwable as? PurchasesErrorHolder)?.error
                ?: PurchasesError(PurchasesErrorCode.UnknownError, throwable.message)
            mainDispatcher.dispatch { callback.onError(error) }
            return
        }

        backend.getCustomerInfo(
            appUserID = candidate,
            appInBackground = appConfig.isAppBackgrounded,
            onSuccess = { customerInfo ->
                // 提交段。先落身份（落的就是服务端刚 get-or-create 的那个 ID），再动缓存与流。
                identityManager.commitLogOut(candidate)
                updateHandler.resetLastSent()
                updateHandler.cacheAndNotifyListeners(customerInfo, candidate)
                diagnostics.track(DiagnosticsTracker.EVENT_IDENTITY_LOGOUT)
                mainDispatcher.dispatch { callback.onReceived(customerInfo) }
            },
            onError = { error, _ ->
                diagnostics.track(
                    DiagnosticsTracker.EVENT_IDENTITY_LOGOUT,
                    mapOf("error_code" to error.code.name),
                )
                mainDispatcher.dispatch { callback.onError(error) }
            },
        )
    }

    // endregion

    // region CustomerInfo

    var updatedCustomerInfoListener: UpdatedCustomerInfoListener?
        get() = updateHandler.updatedCustomerInfoListener
        set(value) {
            updateHandler.updatedCustomerInfoListener = value
        }

    val cachedCustomerInfo: CustomerInfo? get() = customerInfoManager.cachedCustomerInfo(appUserID)

    fun getCustomerInfo(fetchPolicy: CacheFetchPolicy, callback: ReceiveCustomerInfoCallback) {
        val startedAtMs = dateProvider.now().time
        customerInfoManager.getCustomerInfo(
            appUserID = appUserID,
            fetchPolicy = fetchPolicy,
            appInBackground = appConfig.isAppBackgrounded,
            onSuccess = { customerInfo, origin ->
                trackFetch(
                    event = DiagnosticsTracker.EVENT_CUSTOMER_INFO_FETCH,
                    startedAtMs = startedAtMs,
                    policy = fetchPolicy.rawValue,
                    origin = origin,
                    error = null,
                )
                callback.onReceived(customerInfo)
            },
            onError = { error ->
                trackFetch(
                    event = DiagnosticsTracker.EVENT_CUSTOMER_INFO_FETCH,
                    startedAtMs = startedAtMs,
                    policy = fetchPolicy.rawValue,
                    origin = null,
                    error = error,
                )
                callback.onError(error)
            },
        )
    }

    /**
     * 公开 callback 的诊断收口（契约 §1.3 的 `customer_info_fetch` / `offerings_fetch`）。
     *
     * `cache_hit`（M4 补齐）由 Manager 交付时给出的 [DeliveryOrigin] 推导 —— 缓存命中时
     * 根本没有请求，只有 Manager 分得清（M3 期间这个字段因此缺着）。失败时为 `null`（没交付，
     * 谈不上命中与否），**不填 false**：否则「后端挂了而且缓存也空」会与「网络成功」混在一条查询里。
     *
     * 成功路径仍然没有 `status` / `request_id`：那要把 `HTTPResult` 从 `Backend` 一路穿到
     * Manager 的成功回调上，而成功的 `status` 恒为 200、`request_id` 只在排障失败时有用
     * （失败路径已经带了两者）。这是有意不做，不是遗漏。
     */
    private fun trackFetch(
        event: String,
        startedAtMs: Long,
        policy: String?,
        origin: DeliveryOrigin?,
        error: PurchasesError?,
    ) {
        diagnostics.track(
            event,
            mapOf(
                "policy" to policy,
                "cache_hit" to origin?.cacheHit,
                "duration_ms" to (dateProvider.now().time - startedAtMs),
                "error_code" to error?.code?.name,
                "status" to error?.httpStatusCode,
                "request_id" to error?.requestId,
            ),
        )
    }

    fun invalidateCustomerInfoCache() {
        customerInfoManager.invalidateCustomerInfoCache(appUserID)
    }

    // endregion

    // region Offerings

    fun getOfferings(callback: ReceiveOfferingsCallback) {
        val startedAtMs = dateProvider.now().time
        offeringsManager.getOfferings(
            appUserID = appUserID,
            appInBackground = appConfig.isAppBackgrounded,
            onError = { error ->
                trackFetch(
                    event = DiagnosticsTracker.EVENT_OFFERINGS_FETCH,
                    startedAtMs = startedAtMs,
                    policy = null,
                    origin = null,
                    error = error,
                )
                callback.onError(error)
            },
            onSuccess = { offerings, origin ->
                diagnostics.track(
                    DiagnosticsTracker.EVENT_OFFERINGS_FETCH,
                    mapOf(
                        "cache_hit" to origin.cacheHit,
                        "duration_ms" to (dateProvider.now().time - startedAtMs),
                        "count" to offerings.all.size,
                        "not_found_product_ids" to offerings.notFoundProductIds.take(NOT_FOUND_IDS_LIMIT),
                    ),
                )
                // 「后端挂了、付费墙用的是盘上旧数据」必须自己冒个泡：价格可能已经变了
                // （契约 §1.3 的 `sdk_warning`，code 与 iOS 同名）。
                if (origin == DeliveryOrigin.STALE_FALLBACK) {
                    diagnostics.warn(
                        DiagnosticsWarningCode.OFFERINGS_CACHE_FALLBACK,
                        "count=${offerings.all.size}",
                    )
                }
                callback.onReceived(offerings)
            },
        )
    }

    val cachedOfferings: Offerings? get() = offeringsManager.cachedOfferings

    // endregion

    // region 购买

    /**
     * 发起购买。链路见设计 §3：
     * **先落盘上下文 → 再 launchBillingFlow → 只等 listener 回调**。
     *
     * 五道前置校验，每道都 fail-loud：
     * 1. 商品没有 `ProductDetails`（宿主手搭的 `StoreProduct`）→ `productNotAvailableForPurchase`；
     * 2. 传了 `oldProductId` 但商品不是订阅 → `purchaseNotAllowed`；
     * 3. 升降级但 Play 明确不支持 `SUBSCRIPTIONS_UPDATE` → `purchaseNotAllowed`（考古 §3.13）；
     * 4. 同一个商品已有一笔在进行中 → `operationAlreadyInProgress`；
     * 5. 升降级但找不到旧购买 → `purchaseInvalid`。
     */
    @Suppress("ReturnCount", "LongMethod")
    fun purchase(params: PurchaseParams, callback: PurchaseCallback) {
        val purchasingData = params.purchasingData
        val oldProductId = params.oldProductId?.let { PendingPurchaseKey.normalize(it) }

        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_STARTED,
            mapOf(
                "product_id" to purchasingData?.productId,
                "package_id" to params.presentedPackageIdentifier,
                "offering_id" to params.presentedOfferingIdentifier,
                "is_upgrade" to (oldProductId != null),
                "replacement_mode" to params.replacementMode?.wireName,
            ),
        )

        if (purchasingData == null) {
            dispatchPurchaseError(
                callback,
                PurchasesError(
                    PurchasesErrorCode.ProductNotAvailableForPurchaseError,
                    "该商品没有 Play 商品详情，无法购买（offerings 里它的 product 为 null？）",
                ),
            )
            return
        }
        if (appConfig.purchasesAreCompletedBy != PurchasesAreCompletedBy.REVENUE_DOG) {
            Logger.warn {
                "purchasesCompletedBy = my_app：SDK 会照常上报，但**不会** ack / consume，宿主必须自己完成交易"
            }
        }
        if (oldProductId != null && purchasingData.productType != ProductType.SUBS) {
            dispatchPurchaseError(
                callback,
                PurchasesError(PurchasesErrorCode.PurchaseNotAllowedError, "只有订阅支持升降级"),
            )
            return
        }
        if (oldProductId != null &&
            billing.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_UPDATE) == false
        ) {
            dispatchPurchaseError(
                callback,
                PurchasesError(PurchasesErrorCode.PurchaseNotAllowedError, "当前设备的 Play 不支持订阅升降级"),
            )
            return
        }

        // 坑 16：**DEFERRED 的回调要挂在旧商品上** —— Play 在这个模式下回的交易是旧商品的。
        val key = if (params.replacementMode == ReplacementMode.DEFERRED && oldProductId != null) {
            oldProductId
        } else {
            PendingPurchaseKey.normalize(purchasingData.productId)
        }

        val started = pendingPurchases.start(
            key = key,
            productType = purchasingData.productType,
            subscriptionOptionId = purchasingData.subscriptionOptionId,
            presentedPackageIdentifier = params.presentedPackageIdentifier,
            receiptInfo = params.toPurchaseContextReceiptInfo(purchasingData),
            callback = callback,
        )
        if (!started) {
            dispatchPurchaseError(callback, PurchasesError(PurchasesErrorCode.OperationAlreadyInProgressError))
            return
        }

        // 与 iOS 同一个真相源：服务端签发的 `account_token`（契约 §2.2 ⟦决策 21⟧）。
        // 拿不到就不设（best-effort），归属权威始终在后端的 purchaseToken ↔ app_user_id 链上。
        val obfuscatedAccountId = AccountToken.toObfuscatedAccountId(cachedCustomerInfo?.accountToken)

        if (oldProductId == null) {
            billing.makePurchaseAsync(
                activity = params.activity,
                obfuscatedAccountId = obfuscatedAccountId,
                purchasingData = purchasingData,
                replaceProductInfo = null,
                isPersonalizedPrice = params.isPersonalizedPrice,
            )
            return
        }
        // 旧购买的 token 由 SDK **现查**，不由宿主传（对照 RC `replaceOldPurchaseWithNewProduct`）。
        billing.findPurchaseForProductId(
            productId = oldProductId,
            onCompletion = { oldPurchase ->
                billing.makePurchaseAsync(
                    activity = params.activity,
                    obfuscatedAccountId = obfuscatedAccountId,
                    purchasingData = purchasingData,
                    replaceProductInfo = ReplaceProductInfo(
                        oldProductId = oldProductId,
                        oldPurchaseToken = oldPurchase.purchaseToken,
                        replacementMode = params.replacementMode,
                    ),
                    isPersonalizedPrice = params.isPersonalizedPrice,
                )
            },
            onError = { error ->
                pendingPurchases.finish(key)
                dispatchPurchaseError(callback, error)
            },
        )
    }

    /**
     * 购买上下文里的归因 / 价格快照（**发起那一刻**的值）。
     *
     * 它不是最终上行的 body —— 上行的 `ReceiptInfo` 在 `PostTransactionsHelper` 里按
     * 「交易 + 查回来的商品详情」重建。这一份的作用是：进程被杀之后，
     * `BillingWrapper` 仍然能从它补出 offering 与升降级模式。
     */
    private fun PurchaseParams.toPurchaseContextReceiptInfo(purchasingData: PurchasingData): ReceiptInfo {
        val option = purchasingData.subscriptionOption
        return ReceiptInfo(
            productIds = listOf(purchasingData.productId),
            platformProductIds = listOf(
                PlatformProductId(purchasingData.productId, option?.basePlanId, option?.offerId),
            ),
            presentedOfferingIdentifier = presentedOfferingIdentifier,
            priceAmountMicros = purchasingData.storeProduct?.price?.amountMicros,
            currency = purchasingData.storeProduct?.price?.currencyCode,
            formattedPrice = purchasingData.storeProduct?.price?.formatted,
            durationIso = purchasingData.storeProduct?.period?.iso8601?.takeUnless { it.isEmpty() },
            pricingPhases = option?.pricingPhases,
            replacementMode = replacementMode,
            sdkOriginated = true,
        )
    }

    /**
     * `onPurchasesUpdated` 的处置。
     *
     * `sdkOriginated` 的判定与 RC 同源（`Orchestrator:1623-1627`）：
     * 「这批交易的 productId 在待完成表里有没有」—— 这是 Android 版的
     * 「区分 SDK 发起 vs 应用外购买」。
     */
    private fun handlePurchasesUpdated(transactions: List<StoreTransaction>) {
        if (transactions.isEmpty()) return
        val sdkOriginated = transactions.all { transaction ->
            transaction.productIds.any { pendingPurchases.contextFor(it) != null }
        }
        transactions.forEach { transaction ->
            val key = transaction.productIds.firstOrNull()?.let { PendingPurchaseKey.normalize(it) }
            if (transaction.purchaseState != PurchaseState.PURCHASED) {
                handleNonPurchasedUpdate(transaction, key)
                return@forEach
            }
            postTransactionsHelper.postTransactions(
                transactions = listOf(transaction),
                isRestore = false,
                appUserID = appUserID,
                initiationSource = InitiationSource.PURCHASE,
                sdkOriginated = sdkOriginated,
                onTransactionSuccess = { posted, customerInfo ->
                    val callback = key?.let { pendingPurchases.takeCallback(it) }
                    key?.let { pendingPurchases.finish(it) }
                    diagnostics.track(
                        DiagnosticsTracker.EVENT_PURCHASE_RESULT,
                        mapOf(
                            "product_id" to posted.productIds.firstOrNull(),
                            "outcome" to DiagnosticsTracker.OUTCOME_COMPLETED,
                        ),
                    )
                    callback?.let { cb ->
                        mainDispatcher.dispatch {
                            cb.onCompleted(PurchaseResult(customerInfo, posted, isPending = false))
                        }
                    }
                },
                onTransactionError = { posted, error ->
                    val callback = key?.let { pendingPurchases.takeCallback(it) }
                    // 上报上下文（token 维度）按 901 / 902 决定留不留；
                    // 这里清掉的是 productId 维度的「发起中」标记，否则宿主重试购买会被误判成
                    // operationAlreadyInProgress。补报靠的是 token 维度那份。
                    key?.let { pendingPurchases.finish(it) }
                    diagnostics.track(
                        DiagnosticsTracker.EVENT_PURCHASE_RESULT,
                        mapOf(
                            "product_id" to posted.productIds.firstOrNull(),
                            "outcome" to DiagnosticsTracker.OUTCOME_FAILED,
                            "error_code" to error.code.name,
                        ),
                    )
                    callback?.let { cb -> mainDispatcher.dispatch { cb.onError(error, userCancelled = false) } }
                },
            )
        }
    }

    /**
     * `PENDING` / `UNSPECIFIED_STATE`：**不上报、不记台账、不 ack、不 consume**（坑 3）。
     *
     * 回调一个 `isPending = true` 的成功结果 —— 宿主此刻既不该发权益、也不该报错。
     * 上下文**留着**（状态转 `pending`），等 Play 把它转成 `PURCHASED` 之后由补报链路接手；
     * 同时放行同商品的下一次购买。
     */
    private fun handleNonPurchasedUpdate(transaction: StoreTransaction, key: String?) {
        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_PENDING,
            mapOf(
                "product_id" to transaction.productIds.firstOrNull(),
                "purchase_state" to transaction.purchaseState.rawValue,
                "source" to "purchases_updated",
            ),
        )
        val callback = key?.let { pendingPurchases.takeCallback(it) }
        key?.let { pendingPurchases.markPending(it) }
        if (callback == null) {
            Logger.debug { "收到一笔没人在等的 ${transaction.purchaseState} 购买，留待后续补报" }
            return
        }
        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_RESULT,
            mapOf(
                "product_id" to transaction.productIds.firstOrNull(),
                "outcome" to DiagnosticsTracker.OUTCOME_PENDING,
            ),
        )
        getCustomerInfo(
            CacheFetchPolicy.default(),
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    mainDispatcher.dispatch {
                        callback.onCompleted(PurchaseResult(customerInfo, transaction, isPending = true))
                    }
                }

                override fun onError(error: PurchasesError) {
                    mainDispatcher.dispatch { callback.onError(error, userCancelled = false) }
                }
            },
        )
    }

    /**
     * 购买整体失败。
     *
     * `ITEM_ALREADY_OWNED`（→ `productAlreadyPurchasedError`）额外**触发一轮补报**：
     * 这个码的含义就是「Play 那边已经有这笔了」，多半是上一次买成功但没上报成功。
     * 照常把错误回给宿主（RC 行为），但同时把那笔找回来。
     */
    private fun handlePurchasesFailedToUpdate(error: PurchasesError, userCancelled: Boolean) {
        val callbacks = pendingPurchases.takeAllCallbacksAndClearLaunched()
        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_RESULT,
            mapOf(
                "outcome" to if (userCancelled) {
                    DiagnosticsTracker.OUTCOME_CANCELLED
                } else {
                    DiagnosticsTracker.OUTCOME_FAILED
                },
                "error_code" to error.code.name,
            ),
        )
        if (error.code == PurchasesErrorCode.ProductAlreadyPurchasedError) {
            Logger.warn { "Play 说这笔已经拥有了，触发一轮补报把它找回来" }
            syncPendingPurchaseQueue()
        }
        callbacks.forEach { callback -> mainDispatcher.dispatch { callback.onError(error, userCancelled) } }
    }

    private fun dispatchPurchaseError(callback: PurchaseCallback, error: PurchasesError) {
        Logger.error { error.toString() }
        diagnostics.track(
            DiagnosticsTracker.EVENT_PURCHASE_RESULT,
            mapOf(
                "outcome" to DiagnosticsTracker.OUTCOME_FAILED,
                "error_code" to error.code.name,
            ),
        )
        mainDispatcher.dispatch { callback.onError(error, userCancelled = false) }
    }

    // endregion

    // region restore / sync

    /**
     * 恢复购买（设计 §3 A6、考古 §9.3 决策 C）。
     *
     * = `queryPurchases` 全部 + 逐笔以 `initiation_source=restore` / `is_restore=true` 上报
     * + `consume` / `ack`。
     *
     * **尽力而为**：PBL 8 起 `queryPurchaseHistoryAsync` 已被删除（坑 21），
     * 端上能看到的只有**活跃订阅 + 未消耗的一次性商品**。权威历史来自后端
     * （Play Developer API + RTDN，`google-play-plan.md` §1），这一点写进了契约。
     *
     * 与 RC 一致：restore 不先查商品详情，交易以退化形状上报（拿不到 `subscriptionOptionId`，
     * 也就无从得知 `base_plan_id`，考古 §2.3 第 3 条）。
     */
    fun restorePurchases(callback: ReceiveCustomerInfoCallback) {
        val startedAtMs = dateProvider.now().time
        val callback = trackingResult(DiagnosticsTracker.EVENT_RESTORE, startedAtMs, callback)
        val appUserID = this.appUserID
        billing.queryPurchases(
            onSuccess = { byHashedToken ->
                val purchases = byHashedToken.values
                    .filter { it.purchaseState == PurchaseState.PURCHASED }
                    .sortedBy { it.purchaseTime }
                if (purchases.isEmpty()) {
                    Logger.debug { "恢复购买：Play 上没有可恢复的交易，直接刷一次 CustomerInfo" }
                    getCustomerInfo(CacheFetchPolicy.default(), callback)
                    return@queryPurchases
                }
                val aggregator = ResultAggregator(purchases.size, callback, mainDispatcher)
                purchases.forEach { purchase ->
                    postReceiptHelper.postTransactionAndConsumeIfNeeded(
                        purchase = purchase,
                        storeProduct = null,
                        subscriptionOptionsForProductIds = null,
                        isRestore = true,
                        appUserID = appUserID,
                        initiationSource = InitiationSource.RESTORE,
                        sdkOriginated = false,
                        onSuccess = { _, customerInfo -> aggregator.succeed(customerInfo) },
                        onError = { _, error -> aggregator.fail(error) },
                    )
                }
            },
            onError = { error -> mainDispatcher.dispatch { callback.onError(error) } },
        )
    }

    /**
     * 同步购买（设计 §3 A6）：**只上报，绝不碰 Billing 的完成动作**。
     *
     * `initiation_source = unsynced_active_purchases` + `is_restore = false`
     * → 按 ADR 0046 ② 视同后台处理，**不触发转移**。
     *
     * 与 RC 的区别很隐蔽但很重要：RC 的 `syncPurchases` 发的是 `RESTORE`，
     * 我方按自己的设计发 `unsynced_active_purchases`（A6 / 决策 C）。
     */
    fun syncPurchases(callback: ReceiveCustomerInfoCallback) {
        val startedAtMs = dateProvider.now().time
        val callback = trackingResult(DiagnosticsTracker.EVENT_SYNC, startedAtMs, callback)
        val appUserID = this.appUserID
        billing.queryPurchases(
            onSuccess = { byHashedToken ->
                val purchases = byHashedToken.values
                    .filter { it.purchaseState == PurchaseState.PURCHASED }
                    .sortedBy { it.purchaseTime }
                if (purchases.isEmpty()) {
                    getCustomerInfo(CacheFetchPolicy.default(), callback)
                    return@queryPurchases
                }
                val aggregator = ResultAggregator(purchases.size, callback, mainDispatcher)
                purchases.forEach { purchase ->
                    postReceiptHelper.postTokenWithoutConsuming(
                        purchase = purchase,
                        appUserID = appUserID,
                        initiationSource = InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
                        isRestore = false,
                        onSuccess = { customerInfo -> aggregator.succeed(customerInfo) },
                        onError = { error -> aggregator.fail(error) },
                    )
                }
            },
            onError = { error -> mainDispatcher.dispatch { callback.onError(error) } },
        )
    }

    /** 前台恢复 / 连接成功时的补报（考古 §3.4 的四个触发点里我方用两个）。 */
    fun syncPendingPurchaseQueue() {
        postPendingTransactionsHelper.syncPendingPurchaseQueue(appUserID = appUserID)
    }

    /**
     * 把 restore / sync 的**结果**也打进同一个事件（M2 只打了「开始」）。
     *
     * 包一层回调而不是在各分支散打：这两个方法各有三条返回路径（空集 / 成功聚合 / 失败聚合），
     * 散打必漏一条 —— 而「公开 callback 的 error 分支都要有打点」正是 M3 要收口的东西。
     */
    private fun trackingResult(
        event: String,
        startedAtMs: Long,
        callback: ReceiveCustomerInfoCallback,
    ): ReceiveCustomerInfoCallback {
        diagnostics.track(event, mapOf("outcome" to "started"))
        return object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                diagnostics.track(
                    event,
                    mapOf(
                        "outcome" to DiagnosticsTracker.OUTCOME_SUCCESS,
                        "duration_ms" to (dateProvider.now().time - startedAtMs),
                    ),
                )
                callback.onReceived(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                diagnostics.track(
                    event,
                    mapOf(
                        "outcome" to DiagnosticsTracker.OUTCOME_FAILED,
                        "duration_ms" to (dateProvider.now().time - startedAtMs),
                        "error_code" to error.code.name,
                        "status" to error.httpStatusCode,
                        "request_id" to error.requestId,
                    ),
                )
                callback.onError(error)
            }
        }
    }

    // endregion

    // region 属性（设计 §1「属性」/ §5；契约 §2.4）

    /**
     * 写入一批属性。**fire-and-forget**：不抛错、不回调，被拒绝的键只打日志 + 记诊断。
     * @return 被拒绝的键（键名非法 / value > 500 / 触及 50 个自定义属性上限）。
     */
    fun setAttributes(attributes: Map<String, String?>): List<String> =
        attributesManager.setAttributes(attributes, appUserID)

    fun setAttribute(key: String, value: String?): List<String> =
        attributesManager.setAttribute(key, value, appUserID)

    fun collectDeviceIdentifiers() {
        attributesManager.collectDeviceIdentifiers(appConfig.applicationContext, appUserID)
    }

    /** 立刻把待同步属性发出去（宿主显式调用 / 生命周期钩子 / logIn 前后）。 */
    fun syncAttributes() {
        attributesManager.synchronizeIfNeeded(
            currentAppUserID = appUserID,
            appInBackground = appConfig.isAppBackgrounded,
        )
    }

    /** 测试与排障用的读视图。 */
    @VisibleForTesting
    internal fun unsyncedAttributes() = attributesManager.unsyncedAttributes(appUserID)

    val diagnosticsEnabled: Boolean get() = appConfig.diagnosticsEnabled

    // endregion

    /** 前后台状态。进程生命周期观察者会自动维护；测试与宿主也可显式设。 */
    fun setAppBackgrounded(backgrounded: Boolean) {
        appConfig.isAppBackgrounded = backgrounded
        billing.appInBackground = backgrounded
    }

    fun close() {
        diagnosticsRecorder?.close()
        if (observeProcessLifecycle) {
            mainDispatcher.dispatch {
                runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
            }
        }
        billing.close()
        backend.close()
        updateHandler.updatedCustomerInfoListener = null
        updateHandler.internalObserver = null
    }

    internal companion object {

        /** `offerings_fetch.not_found_product_ids` 的上限（契约 §6-4：≤ 50 个字符串）。 */
        const val NOT_FOUND_IDS_LIMIT: Int = 50

        /**
         * 生产装配。结构对照 RC `PurchasesFactory`。
         *
         * 线程（设计 §6）：
         * - 一个后台单线程 executor 跑全部业务 HTTP；
         * - 一条专用 `HandlerThread("revdog-billing")` 跑 BillingClient；
         * - 主线程 Handler 做回调分发，**可空兜底**（坑 38）。
         */
        @Suppress("LongMethod")
        @VisibleForTesting
        fun create(configuration: PurchasesConfiguration): PurchasesOrchestrator {
            val context = configuration.context
            val mainHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
            val mainDispatcher = MainDispatcher(mainHandler)

            val appConfig = AppConfig(
                context = context,
                apiKey = configuration.apiKey,
                baseURL = configuration.baseURL,
                purchasesAreCompletedBy = configuration.purchasesCompletedBy,
                isDebugBuild = context.isDebugBuild(),
                diagnosticsEnabled = configuration.diagnosticsEnabled,
            )

            val httpClient = configuration.httpClientOverride
                ?: HTTPClient(appConfig, ETagManager(context))
            val dispatcher = configuration.dispatcherOverride
                ?: Dispatcher(Executors.newSingleThreadScheduledExecutor(), mainHandler)
            val backend = Backend(httpClient = httpClient, dispatcher = dispatcher)
            val deviceCache = DeviceCache(context, configuration.apiKey)
            val pendingPurchases = PendingPurchaseStore(
                context.getSharedPreferences(
                    "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                    android.content.Context.MODE_PRIVATE,
                ),
                configuration.apiKey,
            )
            val identityManager = IdentityManager(deviceCache, backend)
            val updateHandler = CustomerInfoUpdateHandler(deviceCache, identityManager, mainDispatcher)
            val customerInfoManager = CustomerInfoManager(backend, deviceCache, updateHandler, mainDispatcher)

            // 诊断：专用线程（对照 RC `revenuecat-events-thread`）+ 文件队列 + 攒批上传。
            // **即使 diagnosticsEnabled = false 也要建**：`start()` 那一下要把上一版开着的时候
            // 写在盘上的队列清掉（关掉诊断之后不该还有事件留在设备上）。
            val diagnosticsThread = HandlerThread(DiagnosticsRecorder.THREAD_NAME).apply { start() }
            val diagnosticsSettings = DiagnosticsSettings(
                context.getSharedPreferences(
                    "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                    android.content.Context.MODE_PRIVATE,
                ),
                configuration.apiKey,
            )
            val diagnosticsQueue = DiagnosticsQueue(DiagnosticsQueue.directoryIn(context.filesDir))
            val diagnosticsDispatcher = Dispatcher(
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, DiagnosticsRecorder.THREAD_NAME).apply { isDaemon = true }
                },
                mainHandler,
            )
            val diagnosticsRecorder = DiagnosticsRecorder(
                queue = diagnosticsQueue,
                uploader = DiagnosticsUploader(
                    backend = backend,
                    queue = diagnosticsQueue,
                    settings = diagnosticsSettings,
                    appConfig = appConfig,
                    sessionID = UUID.randomUUID().toString().lowercase(),
                ),
                settings = diagnosticsSettings,
                dispatcher = diagnosticsDispatcher,
                scheduler = HandlerDiagnosticsScheduler(Handler(diagnosticsThread.looper)),
                appUserIDProvider = { deviceCache.getCachedAppUserID() },
                enabled = configuration.diagnosticsEnabled,
                ownedThread = diagnosticsThread,
            )
            val diagnostics: DiagnosticsTracker = diagnosticsRecorder

            val attributesManager = SubscriberAttributesManager(
                cache = SubscriberAttributesCache(
                    context.getSharedPreferences(
                        "${context.packageName}${DeviceCache.PREFERENCES_FILE_SUFFIX}",
                        android.content.Context.MODE_PRIVATE,
                    ),
                    configuration.apiKey,
                ),
                poster = SubscriberAttributesPoster(backend),
                diagnostics = diagnostics,
                dispatcher = dispatcher,
            )

            val billing = BillingWrapper(
                clientFactory = BillingWrapper.ClientFactory(
                    context,
                    configuration.pendingTransactionsForPrepaidPlansEnabled,
                ),
                mainHandler = mainHandler,
                deviceCache = deviceCache,
                diagnostics = diagnostics,
            )
            val offeringsManager = OfferingsManager(deviceCache, backend, billing, mainDispatcher)
            val postReceiptHelper = PostReceiptHelper(
                appConfig = appConfig,
                backend = backend,
                billing = billing,
                customerInfoUpdateHandler = updateHandler,
                deviceCache = deviceCache,
                pendingPurchases = pendingPurchases,
                diagnostics = diagnostics,
                attributesManager = attributesManager,
            )
            val postTransactionsHelper = PostTransactionsHelper(billing, postReceiptHelper)

            return PurchasesOrchestrator(
                appConfig = appConfig,
                identityManager = identityManager,
                backend = backend,
                customerInfoManager = customerInfoManager,
                updateHandler = updateHandler,
                offeringsManager = offeringsManager,
                billing = billing,
                mainDispatcher = mainDispatcher,
                diagnostics = diagnostics,
                pendingPurchases = pendingPurchases,
                postReceiptHelper = postReceiptHelper,
                postTransactionsHelper = postTransactionsHelper,
                postPendingTransactionsHelper = PostPendingTransactionsHelper(
                    deviceCache = deviceCache,
                    billing = billing,
                    dispatcher = dispatcher,
                    postTransactionsHelper = postTransactionsHelper,
                    postReceiptHelper = postReceiptHelper,
                ),
                attributesManager = attributesManager,
                configuredAppUserID = configuration.appUserID,
                diagnosticsRecorder = diagnosticsRecorder,
            )
        }

        private fun android.content.Context.isDebugBuild(): Boolean =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

/**
 * 「攒齐全部结果后，任一失败就整体报错，全成功才用最后一个的 CustomerInfo」
 * —— restore / sync 共用（结构对照 RC `callCompletionFromResults`）。
 *
 * 回调**只会被调用一次**：多笔交易里有两笔失败也只报第一个错误。
 */
private class ResultAggregator(
    private val expected: Int,
    private val callback: ReceiveCustomerInfoCallback,
    private val mainDispatcher: MainDispatcher,
) {
    private val lock = Any()
    private var completed = 0
    private var firstError: PurchasesError? = null
    private var lastCustomerInfo: CustomerInfo? = null
    private var delivered = false

    fun succeed(customerInfo: CustomerInfo) {
        finishOne { lastCustomerInfo = customerInfo }
    }

    fun fail(error: PurchasesError) {
        finishOne { if (firstError == null) firstError = error }
    }

    private fun finishOne(record: () -> Unit) {
        val outcome = synchronized(lock) {
            record()
            completed++
            if (completed < expected || delivered) return
            delivered = true
            firstError to lastCustomerInfo
        }
        // 锁外回调（三条防死锁铁律之一）。
        val (error, customerInfo) = outcome
        mainDispatcher.dispatch {
            when {
                error != null -> callback.onError(error)
                customerInfo != null -> callback.onReceived(customerInfo)
                else -> callback.onError(
                    PurchasesError(PurchasesErrorCode.UnknownError, "上报完成但没有拿到 CustomerInfo"),
                )
            }
        }
    }
}
