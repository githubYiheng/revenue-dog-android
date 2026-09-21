package org.revdog.purchases.google

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import org.revdog.purchases.InAppMessageType
import org.revdog.purchases.Logger
import org.revdog.purchases.PendingPurchaseKey
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCallback
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.PurchasingData
import org.revdog.purchases.ReplacementMode
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchase
import org.revdog.purchases.common.sha1
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.usecase.AcknowledgePurchaseUseCase
import org.revdog.purchases.google.usecase.AcknowledgePurchaseUseCaseParams
import org.revdog.purchases.google.usecase.ConsumePurchaseUseCase
import org.revdog.purchases.google.usecase.ConsumePurchaseUseCaseParams
import org.revdog.purchases.google.usecase.QueryProductDetailsResponse
import org.revdog.purchases.google.usecase.QueryProductDetailsUseCase
import org.revdog.purchases.google.usecase.QueryProductDetailsUseCaseParams
import org.revdog.purchases.google.usecase.QueryPurchasesByTypeUseCase
import org.revdog.purchases.google.usecase.QueryPurchasesByTypeUseCaseParams
import org.revdog.purchases.google.usecase.QueryPurchasesUseCase
import org.revdog.purchases.google.usecase.QueryPurchasesUseCaseParams
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreTransaction
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 延迟调度。
 *
 * **偏离 RC**：RC 直接在 `BillingWrapper` 里用 `backgroundHandler.postDelayed`。
 * 抽出这一个方法的接口，理由只有一个：**重连退避序列必须可单测**
 * （1s → ×2 → 15 分钟封顶 + 重复调度守卫，考古 §3.2 事实 2）。
 * 生产实现就是 Handler，语义完全一致。
 */
internal fun interface DelayedRunner {
    fun postDelayed(delayMillis: Long, action: () -> Unit)
}

internal class HandlerDelayedRunner(private val handler: Handler) : DelayedRunner {
    override fun postDelayed(delayMillis: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMillis)
    }
}

/**
 * Play Billing 的全部交互。结构对照 RC `google/BillingWrapper.kt`。
 *
 * **三条线程纪律**（考古 §3.2 事实 3）：
 * 1. `startConnection` 必须在**后台线程** —— `bindService` 在主线程会 ANR（坑 5）。
 * 2. `launchBillingFlow` 必须在**主线程**（M2）。
 * 3. 待办队列的排空与回调一律 `mainHandler.post`。
 *
 * **三条连接纪律**：
 * - `onBillingServiceDisconnected` **不重连**，只打日志（坑 4）。在那里直接 `startConnection()`
 *   会与退避逻辑打架并造成连接风暴。真正的重连触发点是
 *   `onBillingSetupFinished` 的错误分支 + `executeRequestOnUIThread` 里的懒重连。
 * - 重连退避 1s → ×2 → 15 分钟封顶，带 `reconnectionAlreadyScheduled` 去重，
 *   `onBillingSetupFinished(OK)` 时重置。
 * - 连接**确定性失败**（`FEATURE_NOT_SUPPORTED` / `BILLING_UNAVAILABLE`）时
 *   把待办队列排空并给每个待办发错误 —— 不然用户点了购买什么都不会发生。
 */
@Suppress("TooManyFunctions")
internal class BillingWrapper(
    private val clientFactory: ClientFactory,
    private val mainHandler: Handler?,
    /** 台账（`addSuccessfullyPostedToken`）才是 Android 版的 `finish()`（考古 §3.6）。 */
    private val deviceCache: DeviceCache,
    private val diagnostics: DiagnosticsTracker,
    backgroundRunner: DelayedRunner? = null,
) : PurchasesUpdatedListener, BillingClientStateListener {

    /**
     * 专用 `HandlerThread`。名字带我方前缀，方便在 ANR trace 里一眼认出来。
     * 只有在调用方没注入 runner 时才建（测试会注入）。
     */
    @VisibleForTesting
    internal val ownedBackgroundThread: HandlerThread? =
        if (backgroundRunner == null) HandlerThread(BACKGROUND_THREAD_NAME).apply { start() } else null

    private val backgroundRunner: DelayedRunner =
        backgroundRunner ?: HandlerDelayedRunner(Handler(ownedBackgroundThread!!.looper))

    @get:Synchronized
    @set:Synchronized
    @Volatile
    internal var billingClient: BillingClient? = null

    /**
     * 连接状态监听。**必须在 `startConnection()` 之前挂上** —— 连接成功回调可能同步到达
     * （考古 §1.3 纪律 2）。M2 的「连接成功即补报」挂在这里。
     */
    @Volatile
    var stateListener: StateListener? = null

    /**
     * 购买更新监听。`configure` 时就挂（铁律 A1）。
     * **M1 只记日志** —— 购买链路是 M2。但监听必须现在就挂上，不然应用外购买在 M1 期间完全不可见。
     */
    @Volatile
    var purchasesUpdatedListener: BillingPurchasesUpdatedListener? = null

    /** 前后台状态由编排层喂进来，决定 use case 的退避上限。 */
    @Volatile
    var appInBackground: Boolean = true

    /**
     * 待办队列。所有需要连接的操作都先进这里（考古 §3.3 必抄三件套之一）。
     */
    private val serviceRequests = ConcurrentLinkedQueue<Pair<(PurchasesError?) -> Unit, Long?>>()

    /** 距离下次重连的毫秒数。`onBillingSetupFinished(OK)` 时重置。 */
    @VisibleForTesting
    @Volatile
    internal var reconnectMilliseconds: Long = RECONNECT_TIMER_START_MILLISECONDS

    @get:Synchronized
    @set:Synchronized
    private var reconnectionAlreadyScheduled = false

    fun interface StateListener {
        fun onConnected()
    }

    /**
     * 购买更新的出口。两个面都必须有（对照 RC `BillingAbstract.PurchasesUpdatedListener`）：
     * 失败面不接，用户点了购买又取消时上层的回调会永远挂着。
     */
    interface BillingPurchasesUpdatedListener {
        fun onPurchasesUpdated(transactions: List<StoreTransaction>)

        fun onPurchasesFailedToUpdate(error: PurchasesError, userCancelled: Boolean)
    }

    /**
     * 购买上下文的来源（决策 D：唯一一张表在 `PendingPurchaseStore` 里，由编排层注入）。
     *
     * **偏离 RC**：RC 让 `BillingWrapper` 自己持有 `purchaseContext` 这张表，于是它和
     * Orchestrator 的回调表分属两把锁，DEFERRED 的归一化逻辑因此写了三遍（坑 16）。
     * 这里只留一个只读的查询口子，表本身在编排层那一把锁下。
     */
    @Volatile
    var purchaseContextProvider: (String) -> PendingPurchase? = { null }

    /**
     * 结构对照 RC `BillingWrapper.ClientFactory`。
     * `enableOneTimeProducts()` **恒开**（PBL 8 起 `enablePendingPurchases` 必须带参数）。
     */
    class ClientFactory(
        private val context: Context,
        private val pendingTransactionsForPrepaidPlansEnabled: Boolean = false,
    ) {
        fun buildClient(listener: PurchasesUpdatedListener): BillingClient {
            val pendingPurchaseParams = PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .apply { if (pendingTransactionsForPrepaidPlansEnabled) enablePrepaidPlans() }
                .build()
            return BillingClient.newBuilder(context)
                .enablePendingPurchases(pendingPurchaseParams)
                .setListener(listener)
                .build()
        }
    }

    // region 连接

    fun startConnection(delayMilliseconds: Long = 0) {
        backgroundRunner.postDelayed(delayMilliseconds) { performStartConnection() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun performStartConnection() {
        try {
            // 监视器范围**刻意收窄到状态变更**：下面那句 startConnection(...) 很慢，
            // 不能把 synchronized 在本对象上的主线程调用方一起堵住（RC 原注释）。
            val clientToStart = synchronized(this@BillingWrapper) {
                if (billingClient == null) {
                    billingClient = clientFactory.buildClient(this)
                }
                reconnectionAlreadyScheduled = false
                // **偏离 RC**（RC 只判 `!isReady`）：连接进行中不再发第二次。2026-09-20 真机每次冷启都复现 ——
                // `configure` 的首次连接还没回来，「补报待同步购买」经 `executeRequestOnUIThread` 的懒重连
                // 又排了一次；PBL 对 CONNECTING 状态下的 `startConnection` 直接回 DEVELOPER_ERROR
                // （"Client is already in the process of connecting"）后返回，什么都不做。跳过它行为等价，
                // 只少一次无效 IPC 与一条误导性的告警日志；待办队列照旧由那次在途连接的回调排空。
                billingClient?.takeIf { !it.isReady && it.connectionState != BillingClient.ConnectionState.CONNECTING }
            } ?: return

            Logger.debug { "BillingClient 开始连接" }
            clientToStart.startConnection(this)
        } catch (e: IllegalStateException) {
            // 坑 8：三星设备上在未知情况下会抛这个。
            Logger.error(e) { "连接 BillingClient 时抛 IllegalStateException（已知发生在三星设备上）" }
            sendErrorsToAllPendingRequests(PurchasesError(PurchasesErrorCode.StoreProblemError, e.message))
        } catch (e: SecurityException) {
            // 坑 8：issuetracker.google.com/issues/457463701。
            Logger.error(e) { "连接 BillingClient 时抛 SecurityException" }
            sendErrorsToAllPendingRequests(PurchasesError(PurchasesErrorCode.StoreProblemError, e.message))
        } catch (e: Throwable) {
            // 其余一律在主线程重抛，保持「未捕获异常」的可见性 —— 静默吞掉才是最坏的结果。
            Logger.error(e) { "启动计费连接时出现意外错误" }
            (mainHandler ?: Handler(Looper.getMainLooper())).post { throw e }
        }
    }

    fun endConnection() {
        backgroundRunner.postDelayed(0) {
            synchronized(this@BillingWrapper) {
                billingClient?.endConnection()
                billingClient = null
            }
        }
    }

    fun close() {
        endConnection()
        // quitSafely：让 endConnection 排进去的清理跑完再退出 looper。
        ownedBackgroundThread?.quitSafely()
    }

    fun isConnected(): Boolean = billingClient?.isReady ?: false

    @Suppress("CyclomaticComplexMethod")
    override fun onBillingSetupFinished(billingResult: BillingResult) {
        dispatchToMain {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Logger.debug { "BillingClient 连接成功" }
                    stateListener?.onConnected()
                    executePendingRequests()
                    reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                }

                // 确定性失败：重试没有意义，把待办排空让调用方拿到明确错误。
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                -> {
                    val error = billingResult.toSetupError()
                    Logger.error { "BillingClient 连接失败（确定性）：$error" }
                    // **不能调 executePendingRequests** —— 它检查 isReady，什么都不会做（RC 原注释）。
                    sendErrorsToAllPendingRequests(error)
                }

                BillingClient.BillingResponseCode.ERROR,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.USER_CANCELED,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.NETWORK_ERROR,
                -> {
                    Logger.warn { "BillingClient 连接出错：${billingResult.toHumanReadableDescription()}" }
                    retryBillingServiceConnectionWithExponentialBackoff()
                }

                else -> Logger.warn { "BillingClient 连接返回：${billingResult.toHumanReadableDescription()}" }
            }
        }
    }

    /**
     * 坑 4：**这里什么都不做**，只打日志。重连由 `onBillingSetupFinished` 的错误分支
     * 与 `executeRequestOnUIThread` 里的懒重连负责。
     */
    override fun onBillingServiceDisconnected() {
        Logger.warn { "BillingClient 连接已断开（不在此处重连，等下次请求时懒重连）" }
    }

    /**
     * 指数退避重连，15 分钟封顶。
     * 动机是 ANR：https://github.com/android/play-billing-samples/issues/310
     */
    private fun retryBillingServiceConnectionWithExponentialBackoff() {
        if (reconnectionAlreadyScheduled) {
            Logger.warn { "已经排了一次重连，跳过" }
            return
        }
        Logger.warn { "${reconnectMilliseconds}ms 后重连 BillingClient" }
        reconnectionAlreadyScheduled = true
        startConnection(reconnectMilliseconds)
        reconnectMilliseconds = min(reconnectMilliseconds * 2, RECONNECT_TIMER_MAX_TIME_MILLISECONDS)
    }

    // endregion

    // region 待办队列

    /**
     * 结构对照 RC `executeRequestOnUIThread`。
     *
     * 没挂 `purchasesUpdatedListener` 时**立刻回错误而不是挂起**：
     * 这种情况不该发生，但真发生了要让调用方看见，不能让 `purchase()` 永远不回调。
     */
    @Synchronized
    fun executeRequestOnUIThread(delayMilliseconds: Long? = null, request: (PurchasesError?) -> Unit) {
        if (purchasesUpdatedListener == null) {
            request(PurchasesError(PurchasesErrorCode.UnknownError, "BillingWrapper 未挂载监听器"))
            return
        }
        serviceRequests.add(request to delayMilliseconds)
        if (billingClient?.isReady == false) startConnection() else executePendingRequests()
    }

    private fun executePendingRequests() {
        synchronized(this@BillingWrapper) {
            while (billingClient?.isReady == true) {
                val next = serviceRequests.poll() ?: break
                val (request, delayMilliseconds) = next
                if (delayMilliseconds != null) {
                    (mainHandler ?: Handler(Looper.getMainLooper())).postDelayed({ request(null) }, delayMilliseconds)
                } else {
                    dispatchToMain { request(null) }
                }
            }
        }
    }

    private fun sendErrorsToAllPendingRequests(error: PurchasesError) {
        while (true) {
            val next = serviceRequests.poll() ?: break
            dispatchToMain { next.first(error) }
        }
    }

    @VisibleForTesting
    internal fun pendingRequestCount(): Int = serviceRequests.size

    // endregion

    // region 查询商品

    /**
     * **按 type 分两次查**（考古 §3.8）：先用全部 productIds 查 SUBS，再用**同一批**查 INAPP，
     * 结果相加。同一个 id 若两种类型都存在会返回两条 —— 这是 Play 的实际行为，不去重。
     */
    fun queryProductDetailsAsync(
        productType: ProductType,
        productIds: Set<String>,
        onReceive: (QueryProductDetailsResponse) -> Unit,
        onError: PurchasesErrorCallback,
    ) {
        val useCase = QueryProductDetailsUseCase(
            useCaseParams = QueryProductDetailsUseCaseParams(productIds, productType, appInBackground),
            onReceive = onReceive,
            onError = onError,
            withConnectedClient = { billingClient.withConnectedClientOrWarn(it) },
            executeRequestOnUIThread = { delay, callback -> executeRequestOnUIThread(delay, callback) },
        )
        useCase.run()
    }

    private inline fun BillingClient?.withConnectedClientOrWarn(receivingFunction: BillingClient.() -> Unit) {
        this?.takeIf { it.isReady }?.receivingFunction()
            ?: Logger.warn { "BillingClient 未连接，请求被跳过" }
    }

    // endregion

    // region 购买

    /**
     * 发起购买。结构对照 RC `BillingWrapper.makePurchaseAsync`。
     *
     * 两条纪律：
     * - 走 [executeRequestOnUIThread]（需要连接的操作统一入口），**连不上时立刻回错误**而不是挂起；
     * - `launchBillingFlow` 必须在**主线程**（[launchBillingFlow] 上的 `@UiThread`），
     *   而 `startConnection` 必须在后台线程 —— 两条线程纪律同时成立（坑 5）。
     *
     * 购买上下文的落盘在**编排层**、在调用本方法之前完成（铁律 A3）。
     */
    fun makePurchaseAsync(
        activity: Activity,
        obfuscatedAccountId: String?,
        purchasingData: PurchasingData,
        replaceProductInfo: ReplaceProductInfo?,
        isPersonalizedPrice: Boolean?,
    ) {
        if (replaceProductInfo == null) {
            Logger.debug { "发起购买 ${purchasingData.productId}" }
        } else {
            Logger.debug { "发起升降级 ${replaceProductInfo.oldProductId} → ${purchasingData.productId}" }
        }
        executeRequestOnUIThread { connectionError ->
            if (connectionError != null) {
                purchasesUpdatedListener?.onPurchasesFailedToUpdate(connectionError, userCancelled = false)
                return@executeRequestOnUIThread
            }
            // 坑 9 / 坑 12：`BillingFlowParams` 的构造本身会抛（Billing 7 的
            // `NoClassDefFoundError`、部分 Chromebook 的 `ExceptionInInitializerError`）。
            // 这里兜住，换成一个正经的 storeProblem 回给宿主，而不是崩在付款按钮上。
            val params = runCatching {
                buildPurchaseParams(purchasingData, replaceProductInfo, obfuscatedAccountId, isPersonalizedPrice)
            }.getOrElse { throwable ->
                val error = PurchasesError(
                    PurchasesErrorCode.StoreProblemError,
                    "构造 BillingFlowParams 失败（已知发生在部分设备上）：${throwable.message}",
                )
                Logger.error(throwable) { error.toString() }
                purchasesUpdatedListener?.onPurchasesFailedToUpdate(error, userCancelled = false)
                return@executeRequestOnUIThread
            }
            launchBillingFlow(activity, params)
        }
    }

    /**
     * **偏离 RC**：RC 在 `launchBillingFlow` 返回非 OK 时**只打一行日志**
     * （`BillingWrapper.kt:366-383`），于是宿主的购买回调永远不会被调用 —— 付款按钮点下去
     * 什么都不发生。支付逻辑必须 fail-loud，所以这里把它转成一次明确的失败回调。
     */
    @UiThread
    private fun launchBillingFlow(activity: Activity, params: BillingFlowParams) {
        if (activity.intent == null) {
            // 坑 20：purchases-android#381。RC 也只是 warn，不阻断。
            Logger.warn { "传给 launchBillingFlow 的 Activity 没有 intent，Play 可能会崩" }
        }
        billingClient.withConnectedClientOrWarn {
            val result = launchBillingFlow(activity, params)
            if (result.isSuccessful()) return@withConnectedClientOrWarn
            val error = result.responseCode.billingResponseToPurchasesError(
                "启动 Play 付款流程失败 - ${result.toHumanReadableDescription()}",
            )
            Logger.error { error.toString() }
            purchasesUpdatedListener?.onPurchasesFailedToUpdate(
                error,
                userCancelled = result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED,
            )
        }
    }

    /**
     * 拼 `BillingFlowParams`。
     *
     * **PBL 9 的升降级写法与 RC（8.3.0）不同**，以官方参考页为准：
     * `SubscriptionUpdateParams.setSubscriptionReplacementMode` 自 PBL 8.1 起废弃，
     * 替换模式改为**商品级**的 `ProductDetailsParams.setSubscriptionProductReplacementParams`
     * （`setOldProductId` + `setReplacementMode`），而旧购买仍由
     * `SubscriptionUpdateParams.setOldPurchaseToken` 指定 —— 官方 9 的示例就是这两段配合
     * （release notes 9.0.0「Subscription Replacement Mode Changes」）。
     *
     * 两个不设：
     * - 坑 10：**升降级时不设 `obfuscatedAccountId`**（issuetracker 155005449 未修）；
     * - `setObfuscatedProfileId` 一律不设 —— 我方没有「一个账号多档 profile」的概念。
     */
    private fun buildPurchaseParams(
        purchasingData: PurchasingData,
        replaceProductInfo: ReplaceProductInfo?,
        obfuscatedAccountId: String?,
        isPersonalizedPrice: Boolean?,
    ): BillingFlowParams {
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(purchasingData.productDetails)
            .apply {
                purchasingData.offerToken?.let { setOfferToken(it) }
                replaceProductInfo?.let { info ->
                    setSubscriptionProductReplacementParams(
                        SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(info.oldProductId)
                            .apply { info.replacementMode?.let { setReplacementMode(it.playBillingMode) } }
                            .build(),
                    )
                }
            }
            .build()

        return BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .apply {
                isPersonalizedPrice?.let { setIsOfferPersonalized(it) }
                obfuscatedAccountIdToSend(obfuscatedAccountId, isProductChange = replaceProductInfo != null)
                    ?.let { setObfuscatedAccountId(it) }
                replaceProductInfo?.let { info ->
                    setSubscriptionUpdateParams(
                        BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(info.oldPurchaseToken)
                            .build(),
                    )
                }
            }
            .build()
    }

    /**
     * 升降级前的能力检查（考古 §3.13）。
     *
     * 返回 `null` = **还判定不了**（客户端没连上）。这时**放行**而不是拦住购买：
     * 一个连接抖动不该让用户点不了升级按钮，真不支持时 Play 会在 `launchBillingFlow`
     * 返回 `FEATURE_NOT_SUPPORTED`，那条路已经是 fail-loud 的。
     */
    fun isFeatureSupported(feature: String): Boolean? =
        billingClient?.takeIf { it.isReady }?.isFeatureSupported(feature)?.isSuccessful()

    // endregion

    // region Play in-app messages

    /**
     * 展示 Play in-app message（订阅扣款失败时 Google 官方的挽回 snackbar）。
     * 结构对照 RC `google/BillingWrapper.showInAppMessagesIfNeeded:779-846`，逐条照搬：
     *
     * - 类别列表为空 → **直接返回**（宿主传错了，展示不出任何东西）；
     * - 经 [executeRequestOnUIThread] 排队：没连上就先连，连上了再在**主线程**跑
     *   （`showInAppMessages` 要挂 snackbar 到 Activity 的 window 上，必须主线程）；
     * - Activity 用 [WeakReference] 持有：排队期间用户可能已经退出那个页面，
     *   强引用会把整个 Activity 拖到连接成功为止；
     * - 拿回来的 Activity 已经 finishing / destroyed / 还没 attach 到 window → 跳过并记 debug，
     *   **不报错**（这是正常的竞态，不是故障）；
     * - `showInAppMessages` 本身会抛 `RuntimeException`（RC 实测），兜住只记日志。
     *
     * 结果两态：`NO_ACTION_NEEDED` 只记 debug；`SUBSCRIPTION_STATUS_UPDATED` =
     * 用户在 snackbar 里把订阅救回来了，回调 [subscriptionStatusChange] 让上层补一次同步。
     */
    fun showInAppMessagesIfNeeded(
        activity: Activity,
        inAppMessageTypes: List<InAppMessageType>,
        subscriptionStatusChange: () -> Unit,
    ) {
        if (inAppMessageTypes.isEmpty()) {
            Logger.error { "showInAppMessagesIfNeeded 没有指定任何类别，什么都不会展示（请传 InAppMessageType.ALL）" }
            return
        }

        val params = InAppMessageParams.newBuilder()
            .apply { inAppMessageCategoryIds(inAppMessageTypes).forEach { addInAppMessageCategoryToShow(it) } }
            .build()
        val weakActivity = WeakReference(activity)

        executeRequestOnUIThread { connectionError ->
            if (connectionError != null) {
                Logger.error { "连接 BillingClient 失败，Play in-app message 未展示：$connectionError" }
                return@executeRequestOnUIThread
            }
            billingClient.withConnectedClientOrWarn {
                val current = weakActivity.get()
                if (current == null || current.isFinishing || current.isDestroyed) {
                    Logger.debug { "Activity 已销毁或正在结束，跳过 Play in-app message" }
                    return@withConnectedClientOrWarn
                }
                if (current.window?.peekDecorView()?.windowToken == null) {
                    Logger.debug { "Activity 还没 attach 到 window，跳过 Play in-app message" }
                    return@withConnectedClientOrWarn
                }
                runCatching {
                    showInAppMessages(current, params) { result ->
                        handleInAppMessageResult(result, subscriptionStatusChange)
                    }
                }.onFailure { Logger.error(it) { "展示 Play in-app message 失败：${it.message}" } }
            }
        }
    }

    /** 拆出来的结果处置：`showInAppMessagesIfNeeded` 已经四层嵌套，再塞一个 when 就读不动了。 */
    private fun handleInAppMessageResult(result: InAppMessageResult, subscriptionStatusChange: () -> Unit) {
        when (result.responseCode) {
            InAppMessageResult.InAppMessageResponseCode.NO_ACTION_NEEDED ->
                Logger.debug { "没有可展示的 Play in-app message" }

            InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED -> {
                Logger.debug { "用户在 Play in-app message 里更新了订阅状态，触发一次同步" }
                subscriptionStatusChange()
            }

            else -> Logger.error { "Play in-app message 返回了意料之外的响应码：${result.responseCode}" }
        }
    }

    // endregion

    // region 查询已有购买

    /**
     * 全量 `queryPurchases`（SUBS + INAPP 各一次，考古 §3.4）。
     * 返回 `sha1(purchaseToken)` → 交易，与台账的 key 形状一致。
     */
    fun queryPurchases(
        onSuccess: (Map<String, StoreTransaction>) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        QueryPurchasesUseCase(
            useCaseParams = QueryPurchasesUseCaseParams(appInBackground),
            onSuccess = onSuccess,
            onErrorCallback = onError,
            withConnectedClient = { billingClient.withConnectedClientOrWarn(it) },
            executeRequestOnUIThread = { delay, callback -> executeRequestOnUIThread(delay, callback) },
        ).run()
    }

    /**
     * 找出要被替换的旧订阅。
     *
     * **偏离 RC（被迫）**：RC 用 `findPurchaseInPurchaseHistory`（`queryPurchaseHistoryAsync`），
     * 而那个 API 自 PBL 8 起已被**删除**（坑 21；9.1.0 的 `BillingClient` 上确认没有这个方法）。
     * 只能查当前活跃订阅 —— 对升降级而言够用：能被替换的订阅必然是活跃的。
     */
    fun findPurchaseForProductId(
        productId: String,
        onCompletion: (StoreTransaction) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        val normalized = PendingPurchaseKey.normalize(productId)
        queryPurchasesByType(
            googleProductType = BillingClient.ProductType.SUBS,
            onSuccess = { byHashedToken ->
                val found = byHashedToken.values.firstOrNull { transaction ->
                    transaction.productIds.any { PendingPurchaseKey.normalize(it) == normalized }
                }
                if (found != null) {
                    onCompletion(found)
                } else {
                    onError(
                        PurchasesError(
                            PurchasesErrorCode.PurchaseInvalidError,
                            "找不到要替换的旧订阅 $normalized：Play 上没有该商品的活跃购买",
                        ).also { Logger.error { it.toString() } },
                    )
                }
            },
            onError = onError,
        )
    }

    private fun queryPurchasesByType(
        googleProductType: String,
        onSuccess: (Map<String, StoreTransaction>) -> Unit,
        onError: (PurchasesError) -> Unit,
    ) {
        QueryPurchasesByTypeUseCase(
            useCaseParams = QueryPurchasesByTypeUseCaseParams(googleProductType, appInBackground),
            onSuccess = onSuccess,
            onError = onError,
            withConnectedClient = { billingClient.withConnectedClientOrWarn(it) },
            executeRequestOnUIThread = { delay, callback -> executeRequestOnUIThread(delay, callback) },
        ).run()
    }

    // endregion

    // region consumeAndSave 七分支

    /**
     * **整个 Android SDK 最该逐行抄的 50 行**（考古 §9.1 必抄第 1 项）。
     * 结构对照 RC `BillingWrapper.consumeAndSave:428-477`。
     *
     * | # | 条件 | 动作 |
     * |---|---|---|
     * | ① | `PENDING` / `UNSPECIFIED_STATE` / 类型 `UNKNOWN` | **完全跳过**：不 ack、不 consume、**不记台账**（坑 3） |
     * | ② | INAPP + 我方完成 + 后端说 consume | `consumeAsync` → 成功后记台账 |
     * | ③ | INAPP + 我方完成 + 后端说不 consume + 尚未 ack | `acknowledgePurchase` → 成功后记台账 |
     * | ④ | INAPP + 快照已 ack | 只记台账（**服务端已代为 ack**，设计 §8） |
     * | ⑤ | SUBS + 我方完成 + 尚未 ack | `acknowledgePurchase` |
     * | ⑥ | SUBS + 快照已 ack | 只记台账 |
     * | ⑦ | 上报被**确定性 4xx** 拒绝（[deterministicallyRejected]） | 按 ③/⑤ 走：**ack 但不 consume** |
     *
     * 台账（`addSuccessfullyPostedToken`）才是 Android 版的 `finish()`。
     *
     * **偏离 RC（决策 B，设计 §3 A2）**：`shouldConsume` 为 `null`（后端没下发
     * `purchased_products[pid].should_consume`）时 RC 缺省 `false` → 静默 acknowledge →
     * **消耗品永远无法复购**（坑 1）。我方 fail-loud：**不 ack、不 consume、不记台账**，
     * 记 error 级诊断，保留上下文等下次前台重试。
     *
     * @param shouldConsume 后端下发值；`null` = 契约违规。
     * @param deterministicallyRejected 上报被确定性 4xx 拒绝（第 ⑦ 条）。
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun consumeAndSave(
        purchasesAreCompletedBy: PurchasesAreCompletedBy,
        purchase: StoreTransaction,
        shouldConsume: Boolean?,
        deterministicallyRejected: Boolean = false,
    ) {
        // ① 钱没扣（或不知道扣没扣）就什么都不做。记了台账，用户付款完成后这笔就永远不会被补报。
        if (purchase.type == ProductType.UNKNOWN || purchase.purchaseState != PurchaseState.PURCHASED) {
            Logger.warn {
                "跳过完成交易：type=${purchase.type} state=${purchase.purchaseState}（不 ack、不 consume、不记台账）"
            }
            trackConsumeDecision(DECISION_SKIPPED_NOT_PURCHASED, purchase, shouldConsume)
            return
        }

        val addToken = { token: String -> deviceCache.addSuccessfullyPostedToken(token, purchase.isAutoRenewing) }

        // observer 模式：SDK 绝不碰 Billing 的完成动作，但仍记台账避免重复上报（④⑥ 的另一半）。
        if (purchasesAreCompletedBy != PurchasesAreCompletedBy.REVENUE_DOG) {
            trackConsumeDecision(DECISION_OBSERVER_LEDGER_ONLY, purchase, shouldConsume)
            addToken(purchase.purchaseToken)
            return
        }

        // 契约违规（决策 B）。注意 ⑦ 条不走这里：确定性 4xx 的响应体本来就没有 purchased_products。
        if (shouldConsume == null && !deterministicallyRejected) {
            Logger.error {
                "后端响应缺 purchased_products[].should_consume（契约违规）：不 ack、不 consume、" +
                    "保留上下文等下次前台重试"
            }
            trackConsumeDecision(DECISION_MISSING_SHOULD_CONSUME, purchase, shouldConsume)
            return
        }

        val alreadyAcknowledged = purchase.isAcknowledged
        val effectiveShouldConsume = shouldConsume == true && !deterministicallyRejected

        when {
            purchase.type == ProductType.INAPP && effectiveShouldConsume -> {
                trackConsumeDecision(DECISION_CONSUMED, purchase, shouldConsume)
                consumePurchase(purchase.purchaseToken, onConsumed = addToken)
            }

            !alreadyAcknowledged -> {
                trackConsumeDecision(
                    if (deterministicallyRejected) DECISION_ACK_AFTER_REJECTION else DECISION_ACKNOWLEDGED,
                    purchase,
                    shouldConsume,
                )
                acknowledge(purchase.purchaseToken, onAcknowledged = addToken)
            }

            else -> {
                // 设计 §8：服务端是 ack 权威，它在返回 200 之前就可能已经 ack 过这笔。
                trackConsumeDecision(DECISION_ACKNOWLEDGED_BY_SERVER, purchase, shouldConsume)
                addToken(purchase.purchaseToken)
            }
        }
    }

    @VisibleForTesting
    internal fun consumePurchase(token: String, onConsumed: (String) -> Unit) {
        Logger.debug { "消耗购买（token 哈希 ${token.sha1().take(TOKEN_LOG_PREFIX_LENGTH)}）" }
        ConsumePurchaseUseCase(
            useCaseParams = ConsumePurchaseUseCaseParams(token, appInBackground),
            onReceive = onConsumed,
            onError = PurchasesErrorCallback { error -> Logger.error { "消耗失败，下次前台重试：$error" } },
            withConnectedClient = { billingClient.withConnectedClientOrWarn(it) },
            executeRequestOnUIThread = { delay, callback -> executeRequestOnUIThread(delay, callback) },
        ).run()
    }

    @VisibleForTesting
    /**
     * @param onFailed ack 失败时回调（默认只记日志）。A8 自保要靠它释放「按 token 单飞」的占位，
     * 否则一次失败会把这个 token 的自保卡到进程重启。
     */
    internal fun acknowledge(
        token: String,
        onFailed: (PurchasesError) -> Unit = {},
        onAcknowledged: (String) -> Unit,
    ) {
        Logger.debug { "确认购买（token 哈希 ${token.sha1().take(TOKEN_LOG_PREFIX_LENGTH)}）" }
        AcknowledgePurchaseUseCase(
            useCaseParams = AcknowledgePurchaseUseCaseParams(token, appInBackground),
            onReceive = onAcknowledged,
            onError = PurchasesErrorCallback { error ->
                Logger.error { "确认失败，下次前台重试：$error" }
                onFailed(error)
            },
            withConnectedClient = { billingClient.withConnectedClientOrWarn(it) },
            executeRequestOnUIThread = { delay, callback -> executeRequestOnUIThread(delay, callback) },
        ).run()
    }

    private fun trackConsumeDecision(decision: String, purchase: StoreTransaction, shouldConsume: Boolean?) {
        diagnostics.track(
            DiagnosticsTracker.EVENT_CONSUME_DECISION,
            mapOf(
                "decision" to decision,
                "product_type" to purchase.type.rawValue,
                "purchase_state" to purchase.purchaseState.rawValue,
                "should_consume" to shouldConsume,
                "already_acknowledged" to purchase.isAcknowledged,
            ),
        )
    }

    // endregion

    /**
     * 坑 17：`onPurchasesUpdated` 可能收到 `OK` + `null` purchases（PBL ≤ 4 的 DEFERRED 升降级）。
     * PBL 5+ 不该再发生，但 RC 保留了防御，我方照做（当 `ERROR` 处理）。
     *
     * 三条边界（都要不崩、不重复回调）：
     * - `OK` + **非空**列表 → 补齐类型与归因后交给上层；
     * - `OK` + **空**列表 → **既不回调成功也不回调失败**，只记一行日志；
     * - 其余（`USER_CANCELED` / `ITEM_ALREADY_OWNED` / …）→ 一次失败回调，带 `userCancelled` 标志。
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val isSuccessful = billingResult.isSuccessful()
        diagnostics.track(
            DiagnosticsTracker.EVENT_BILLING_PURCHASE_UPDATE,
            mapOf(
                "response_code" to billingResult.responseCode.getBillingResponseCodeName(),
                "count" to (purchases?.size ?: 0),
            ),
        )

        if (isSuccessful && !purchases.isNullOrEmpty()) {
            toStoreTransactions(purchases.toList()) { transactions ->
                purchasesUpdatedListener?.onPurchasesUpdated(transactions)
            }
            return
        }
        if (isSuccessful && purchases != null) {
            // 空列表：Play 偶发地这么回。不是错误，也不该让上层再收到一次回调。
            Logger.debug { "onPurchasesUpdated 返回 OK 但列表为空，忽略" }
            return
        }

        val responseCode = if (purchases == null && isSuccessful) {
            Logger.error { "onPurchasesUpdated 返回 OK 但 purchases 为 null，按 ERROR 处理（坑 17）" }
            BillingClient.BillingResponseCode.ERROR
        } else {
            billingResult.responseCode
        }
        val error = responseCode.billingResponseToPurchasesError(
            "购买更新失败 - ${billingResult.toHumanReadableDescription()}",
        )
        Logger.warn { error.toString() }
        purchasesUpdatedListener?.onPurchasesFailedToUpdate(
            error,
            userCancelled = responseCode == BillingClient.BillingResponseCode.USER_CANCELED,
        )
    }

    /**
     * `Purchase` → [StoreTransaction]，缺的信息从购买上下文补。
     *
     * **偏离 RC（修掉它的隐患）**：RC 的 `getStoreTransaction` 在
     * `synchronized(this@BillingWrapper)` 内部发起 `queryPurchasesAsync`（考古 §3.14 明确记为
     * 「RC 自己的残留隐患」—— 持锁 I/O）。这里全程无锁：计数用 `AtomicInteger`，
     * 结果按下标写进 `ConcurrentHashMap`，凑齐了才回调一次。
     */
    private fun toStoreTransactions(
        purchases: List<Purchase>,
        onCompleted: (List<StoreTransaction>) -> Unit,
    ) {
        val results = ConcurrentHashMap<Int, StoreTransaction>()
        val remaining = AtomicInteger(purchases.size)
        val finishIfDone = {
            if (remaining.decrementAndGet() == 0) {
                onCompleted(purchases.indices.mapNotNull { results[it] })
            }
        }

        purchases.forEachIndexed { index, purchase ->
            val context = purchase.products.firstOrNull()?.let { purchaseContextProvider(it) }
            if (context != null) {
                results[index] = purchase.toStoreTransaction(
                    type = context.productType,
                    subscriptionOptionId = context.subscriptionOptionId,
                    presentedOfferingIdentifier = context.receiptInfo.presentedOfferingIdentifier,
                    replacementMode = context.receiptInfo.replacementMode,
                )
                finishIfDone()
            } else {
                // 坑 18：应用外购买（兑换码 / Play 端购买 / 其它设备）拿不到 productType，
                // 只能靠两次 queryPurchases 反查。
                getPurchaseType(purchase.purchaseToken) { type ->
                    results[index] = purchase.toStoreTransaction(type)
                    finishIfDone()
                }
            }
        }
    }

    /** 先查 SUBS 再查 INAPP，都没有就 `UNKNOWN`（考古 §3.14）。`UNKNOWN` 在七分支里被整笔跳过。 */
    private fun getPurchaseType(purchaseToken: String, onReceived: (ProductType) -> Unit) {
        queryPurchasesByType(
            googleProductType = BillingClient.ProductType.SUBS,
            onSuccess = { subscriptions ->
                if (subscriptions.values.any { it.purchaseToken == purchaseToken }) {
                    onReceived(ProductType.SUBS)
                    return@queryPurchasesByType
                }
                queryPurchasesByType(
                    googleProductType = BillingClient.ProductType.INAPP,
                    onSuccess = { inApps ->
                        val type = if (inApps.values.any { it.purchaseToken == purchaseToken }) {
                            ProductType.INAPP
                        } else {
                            ProductType.UNKNOWN
                        }
                        onReceived(type)
                    },
                    onError = { onReceived(ProductType.UNKNOWN) },
                )
            },
            onError = { onReceived(ProductType.UNKNOWN) },
        )
    }

    private fun dispatchToMain(action: () -> Unit) {
        (mainHandler ?: Handler(Looper.getMainLooper())).post(action)
    }

    internal companion object {
        const val BACKGROUND_THREAD_NAME: String = "revdog-billing"
        const val RECONNECT_TIMER_START_MILLISECONDS: Long = 1L * 1000L
        const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS: Long = 1000L * 60L * 15L // 15 分钟

        /** 日志里只出现 token 哈希的前缀 —— **purchaseToken 原文绝不进日志**。 */
        const val TOKEN_LOG_PREFIX_LENGTH: Int = 8

        // `consume_decision` 诊断事件的 decision 取值（M3 换成真实上传管线，取值不变）。
        const val DECISION_CONSUMED: String = "consumed"
        const val DECISION_ACKNOWLEDGED: String = "acknowledged"
        const val DECISION_ACKNOWLEDGED_BY_SERVER: String = "acknowledged_by_server"
        const val DECISION_ACK_AFTER_REJECTION: String = "acknowledged_after_deterministic_rejection"
        const val DECISION_OBSERVER_LEDGER_ONLY: String = "observer_mode_ledger_only"
        const val DECISION_MISSING_SHOULD_CONSUME: String = "missing_should_consume"
        const val DECISION_SKIPPED_NOT_PURCHASED: String = "skipped_not_purchased"

        /**
         * A8 超时自保 ack（设计 §3 A8）：首次上报起 24h 仍未成功 → 先 ack 再继续补报。
         * 判定与执行在 `PostReceiptHelper`，取值放这里是为了与其它 decision 同源。
         */
        const val DECISION_ACK_SELF_PROTECT: String = "ack_self_protect"

        /** A8 的另一半：查回来发现服务端已经 ack 过了 → 只标记，不再 ack。 */
        const val DECISION_ACK_SELF_PROTECT_ALREADY_ACKED: String = "ack_self_protect_already_acknowledged"
    }
}

/**
 * **坑 10 的唯一判定点**：升降级时一律**不设** `obfuscatedAccountId`。
 *
 * 依据 https://issuetracker.google.com/issues/155005449（至今未修）：在带
 * `SubscriptionUpdateParams` 的流程里设了它，Play 会出问题。RC 也这么做，而且把「要设」
 * 藏在 `DangerousSettings.applyObfuscatedAccountIdToSubscriptionChanges` 后面；
 * 我方连那个开关都不提供 —— 归属权威在后端的 `purchaseToken ↔ app_user_id` 链上，
 * 少一个辅助字段换掉一个已知的 Play 缺陷是划算的。
 *
 * 抽成独立函数只为一件事：这条规则必须能被单测直接断言
 * （`BillingFlowParams` 没有 `getObfuscatedAccountId()`，拼好之后读不回来）。
 */
internal fun obfuscatedAccountIdToSend(obfuscatedAccountId: String?, isProductChange: Boolean): String? =
    if (isProductChange) null else obfuscatedAccountId

/**
 * [InAppMessageType] → Billing 的 category id。
 *
 * 抽成独立函数的理由与 [obfuscatedAccountIdToSend] 同：`InAppMessageParams` 拼好之后**读不回来**
 * （没有 getter，内部那张表是包私有的），类别映射只能这样被单测直接断言。
 */
internal fun inAppMessageCategoryIds(types: List<InAppMessageType>): List<Int> = types.map { it.categoryId }

/**
 * 升降级要替换掉的那笔旧购买。结构对照 RC `ReplaceProductInfo`。
 *
 * [oldProductId] 是**已归一化**的裸 productId（剥掉 `:basePlanId`）——
 * PBL 9 的 `SubscriptionProductReplacementParams.setOldProductId` 要的就是它；
 * [oldPurchaseToken] 由 `findPurchaseForProductId` 现查，不由宿主传（对照 RC）。
 */
internal class ReplaceProductInfo(
    val oldProductId: String,
    val oldPurchaseToken: String,
    val replacementMode: ReplacementMode?,
)
