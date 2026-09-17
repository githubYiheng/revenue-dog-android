package org.revdog.purchases.google

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCallback
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.google.usecase.QueryProductDetailsResponse
import org.revdog.purchases.google.usecase.QueryProductDetailsUseCase
import org.revdog.purchases.google.usecase.QueryProductDetailsUseCaseParams
import java.util.concurrent.ConcurrentLinkedQueue
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

    fun interface BillingPurchasesUpdatedListener {
        fun onPurchasesUpdated(purchases: List<Purchase>)
    }

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
                billingClient?.takeIf { !it.isReady }
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

    /**
     * 坑 17：`onPurchasesUpdated` 可能收到 `OK` + `null` purchases（PBL ≤ 4 的 DEFERRED 升降级）。
     * PBL 5+ 不该再发生，但 RC 保留了防御，我方照做。
     *
     * **M1 只记日志**：购买链路（上报 / consumeAndSave 七分支）是 M2。
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.isSuccessful() && !purchases.isNullOrEmpty()) {
            Logger.debug { "收到 ${purchases.size} 笔购买更新（M1 不处理，M2 接管上报）" }
            purchasesUpdatedListener?.onPurchasesUpdated(purchases)
            return
        }
        if (purchases == null && billingResult.isSuccessful()) {
            Logger.error { "onPurchasesUpdated 返回 OK 但 purchases 为 null" }
            return
        }
        Logger.warn { "购买更新失败：${billingResult.toHumanReadableDescription()}" }
    }

    private fun dispatchToMain(action: () -> Unit) {
        (mainHandler ?: Handler(Looper.getMainLooper())).post(action)
    }

    internal companion object {
        const val BACKGROUND_THREAD_NAME: String = "revdog-billing"
        const val RECONNECT_TIMER_START_MILLISECONDS: Long = 1L * 1000L
        const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS: Long = 1000L * 60L * 15L // 15 分钟
    }
}
