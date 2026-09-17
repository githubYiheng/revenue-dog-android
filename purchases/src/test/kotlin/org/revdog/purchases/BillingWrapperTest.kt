package org.revdog.purchases

import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE
import org.revdog.purchases.google.PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT
import org.revdog.purchases.google.toSetupError
import org.revdog.purchases.support.FakeBillingClientFixture
import org.revdog.purchases.support.FakeBillingClientFixture.Companion.billingResult
import org.revdog.purchases.support.RecordingDelayedRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BillingWrapperTest {

    private lateinit var fixture: FakeBillingClientFixture
    private lateinit var runner: RecordingDelayedRunner
    private lateinit var wrapper: BillingWrapper

    @Before
    fun setUp() {
        fixture = FakeBillingClientFixture()
        runner = RecordingDelayedRunner()
        wrapper = BillingWrapper(
            clientFactory = fixture.clientFactory,
            mainHandler = Handler(Looper.getMainLooper()),
            backgroundRunner = runner,
        )
        // 待办队列的守卫要求先挂 listener（否则直接回错误），先满足前提。
        wrapper.purchasesUpdatedListener = BillingWrapper.BillingPurchasesUpdatedListener { }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // region 连接与重连退避

    @Test
    fun `startConnection 排到后台 runner 上而不是主线程 —— 防 bindService ANR`() {
        wrapper.startConnection()
        assertThat(runner.scheduledDelays).containsExactly(0L)
    }

    @Test
    fun `onBillingSetupFinished OK 时重置退避并通知 stateListener`() {
        var connected = false
        wrapper.stateListener = BillingWrapper.StateListener { connected = true }
        wrapper.reconnectMilliseconds = 8_000L

        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idleMain()

        assertThat(connected).isTrue()
        assertThat(wrapper.reconnectMilliseconds).isEqualTo(BillingWrapper.RECONNECT_TIMER_START_MILLISECONDS)
    }

    @Test
    fun `可恢复错误触发退避重连：1s 起、每次翻倍`() {
        wrapper.startConnection()
        runner.clear()

        // 第一次失败 → 1s
        failSetup()
        assertThat(runner.scheduledDelays).containsExactly(1_000L)

        // 排期跑掉（重连开始），再失败一次 → 2s
        runner.runPending()
        runner.clear()
        failSetup()
        assertThat(runner.scheduledDelays).containsExactly(2_000L)

        runner.runPending()
        runner.clear()
        failSetup()
        assertThat(runner.scheduledDelays).containsExactly(4_000L)
    }

    @Test
    fun `退避封顶在 15 分钟`() {
        wrapper.reconnectMilliseconds = BillingWrapper.RECONNECT_TIMER_MAX_TIME_MILLISECONDS
        runner.clear()

        failSetup()

        assertThat(runner.scheduledDelays).containsExactly(BillingWrapper.RECONNECT_TIMER_MAX_TIME_MILLISECONDS)
        assertThat(wrapper.reconnectMilliseconds).isEqualTo(BillingWrapper.RECONNECT_TIMER_MAX_TIME_MILLISECONDS)
    }

    @Test
    fun `重复的失败回调只排一次重连 —— reconnectionAlreadyScheduled 守卫`() {
        runner.clear()

        failSetup()
        failSetup()
        failSetup()

        // 三次失败，只排了一次重连：不这么做会造成连接风暴。
        assertThat(runner.scheduledDelays).hasSize(1)
    }

    @Test
    fun `onBillingServiceDisconnected 不重连`() {
        runner.clear()
        wrapper.onBillingServiceDisconnected()
        idleMain()
        // 坑 4：在这里重连会与退避逻辑打架。真正的重连靠 setupFinished 的错误分支 + 懒重连。
        assertThat(runner.scheduledDelays).isEmpty()
    }

    // endregion

    // region 待办队列

    @Test
    fun `未连接时请求进队并触发连接`() {
        buildClient(ready = false)
        runner.clear()

        var callbackError: PurchasesError? = null
        var called = false
        wrapper.executeRequestOnUIThread { error ->
            called = true
            callbackError = error
        }
        idleMain()

        assertThat(called).isFalse()
        assertThat(callbackError).isNull()
        assertThat(wrapper.pendingRequestCount()).isEqualTo(1)
        assertThat(runner.scheduledDelays).isNotEmpty()
    }

    @Test
    fun `连接成功后待办被排空并在主线程执行`() {
        buildClient(ready = false)
        var executed = 0
        wrapper.executeRequestOnUIThread { executed++ }
        wrapper.executeRequestOnUIThread { executed++ }
        assertThat(wrapper.pendingRequestCount()).isEqualTo(2)

        fixture.setReady(true)
        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idleMain()

        assertThat(executed).isEqualTo(2)
        assertThat(wrapper.pendingRequestCount()).isZero()
    }

    @Test
    fun `连接确定性失败时排空队列并给每个待办发错误`() {
        buildClient(ready = false)
        val errors = mutableListOf<PurchasesError?>()
        wrapper.executeRequestOnUIThread { errors += it }
        wrapper.executeRequestOnUIThread { errors += it }

        wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE))
        idleMain()

        // 不排空的话，用户点了购买什么都不会发生（考古 §3.3 必抄）。
        assertThat(errors).hasSize(2)
        assertThat(errors.map { it?.code }).containsOnly(PurchasesErrorCode.PurchaseNotAllowedError)
        assertThat(wrapper.pendingRequestCount()).isZero()
    }

    @Test
    fun `没挂 listener 时立刻回错误而不是永远挂起`() {
        val bare = BillingWrapper(
            clientFactory = fixture.clientFactory,
            mainHandler = Handler(Looper.getMainLooper()),
            backgroundRunner = runner,
        )
        var error: PurchasesError? = null
        bare.executeRequestOnUIThread { error = it }

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.UnknownError)
    }

    // endregion

    // region 错误映射

    @Test
    fun `API version less than 3 被特判成 storeProblem 并给出可操作的文案`() {
        val error = billingResult(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE,
        ).toSetupError()

        assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        // 文案必须引导「检查 Play 账号 / 清 Play 缓存」，不是「升级 Play」（坑 7）。
        assertThat(error.message).contains("Google 账号")
        assertThat(error.message).contains("缓存")
    }

    @Test
    fun `PBL 9 的 Play Store is blocked 也落 storeProblem`() {
        val error = billingResult(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            "$PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT by policy",
        ).toSetupError()

        assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        assertThat(error.message).contains("屏蔽")
    }

    @Test
    fun `普通 BILLING_UNAVAILABLE 落 purchaseNotAllowed`() {
        val error = billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, "whatever").toSetupError()
        assertThat(error.code).isEqualTo(PurchasesErrorCode.PurchaseNotAllowedError)
    }

    @Test
    fun `Billing 响应码到错误码的映射表`() {
        val cases = mapOf(
            BillingClient.BillingResponseCode.USER_CANCELED to PurchasesErrorCode.PurchaseCancelledError,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE to
                PurchasesErrorCode.ProductNotAvailableForPurchaseError,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR to PurchasesErrorCode.PurchaseInvalidError,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED to PurchasesErrorCode.ProductAlreadyPurchasedError,
            BillingClient.BillingResponseCode.NETWORK_ERROR to PurchasesErrorCode.NetworkError,
            BillingClient.BillingResponseCode.ERROR to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED to PurchasesErrorCode.PurchaseNotAllowedError,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED to PurchasesErrorCode.PurchaseNotAllowedError,
        )
        cases.forEach { (responseCode, expected) ->
            assertThat(billingResult(responseCode).toSetupError().code)
                .describedAs("responseCode=$responseCode")
                .isEqualTo(expected)
        }
    }

    // endregion

    // region onPurchasesUpdated 边界

    @Test
    fun `OK 加 null purchases 不会崩也不会通知 listener（坑 17）`() {
        var notified = false
        wrapper.purchasesUpdatedListener = BillingWrapper.BillingPurchasesUpdatedListener { notified = true }

        wrapper.onPurchasesUpdated(FakeBillingClientFixture.ok(), null)

        assertThat(notified).isFalse()
    }

    @Test
    fun `失败的购买更新不通知 listener`() {
        var notified = false
        wrapper.purchasesUpdatedListener = BillingWrapper.BillingPurchasesUpdatedListener { notified = true }

        wrapper.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.USER_CANCELED), mutableListOf())

        assertThat(notified).isFalse()
    }

    // endregion

    /**
     * 真实链路里 `billingClient` 是在 `performStartConnection()` 里建出来的
     * （orchestrator 的 init 会调 `startConnection()`）。测队列行为前必须先走这一步，
     * 否则 `billingClient` 为 null，`isReady` 的两个分支都不成立。
     */
    private fun buildClient(ready: Boolean) {
        fixture.setReady(false)
        wrapper.startConnection()
        runner.runPending()
        fixture.setReady(ready)
    }

    private fun failSetup() {
        wrapper.onBillingSetupFinished(billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
        idleMain()
    }
}
