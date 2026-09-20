package org.revdog.purchases

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.caching.PendingPurchase
import org.revdog.purchases.diagnostics.NoOpDiagnosticsTracker
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE
import org.revdog.purchases.google.inAppMessageCategoryIds
import org.revdog.purchases.google.PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT
import org.revdog.purchases.google.toSetupError
import org.revdog.purchases.posting.PlatformProductId
import org.revdog.purchases.posting.ReceiptInfo
import org.revdog.purchases.support.FakeBillingClientFixture
import org.revdog.purchases.support.FakeBillingClientFixture.Companion.billingResult
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.RecordingDelayedRunner
import org.revdog.purchases.support.RecordingPurchasesUpdatedListener
import org.revdog.purchases.support.purchaseFixture
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BillingWrapperTest {

    private lateinit var fixture: FakeBillingClientFixture
    private lateinit var runner: RecordingDelayedRunner
    private lateinit var wrapper: BillingWrapper
    private lateinit var listener: RecordingPurchasesUpdatedListener

    @Before
    fun setUp() {
        fixture = FakeBillingClientFixture()
        runner = RecordingDelayedRunner()
        listener = RecordingPurchasesUpdatedListener()
        wrapper = BillingWrapper(
            clientFactory = fixture.clientFactory,
            mainHandler = Handler(Looper.getMainLooper()),
            deviceCache = DeviceCache(ApplicationProvider.getApplicationContext<Context>(), FakeHTTPClient.TEST_API_KEY),
            diagnostics = NoOpDiagnosticsTracker,
            backgroundRunner = runner,
        )
        // 待办队列的守卫要求先挂 listener（否则直接回错误），先满足前提。
        wrapper.purchasesUpdatedListener = listener
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

    @Test
    fun `连接进行中时懒重连不再发第二次 startConnection —— 真机冷启 DEVELOPER_ERROR`() {
        // configure 的首次连接已发出、回调还没到：PBL 此时处于 CONNECTING。
        buildClient(ready = false)
        every { fixture.billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTING

        // 「补报待同步购买」这类请求进队并触发懒重连。
        var executed = 0
        wrapper.executeRequestOnUIThread { executed++ }
        runner.runPending()

        // 只有 buildClient 里的那一次；第二次会被 PBL 以 DEVELOPER_ERROR 打回。
        verify(exactly = 1) { fixture.billingClient.startConnection(any()) }
        assertThat(wrapper.pendingRequestCount()).isEqualTo(1)

        // 在途的那次连接回来之后，待办照常排空。
        every { fixture.billingClient.connectionState } returns BillingClient.ConnectionState.CONNECTED
        fixture.setReady(true)
        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idleMain()
        assertThat(executed).isEqualTo(1)
        assertThat(wrapper.pendingRequestCount()).isEqualTo(0)
    }

    @Test
    fun `连接已断开（DISCONNECTED）时懒重连照常发起`() {
        buildClient(ready = false)
        every { fixture.billingClient.connectionState } returns BillingClient.ConnectionState.DISCONNECTED

        wrapper.executeRequestOnUIThread { }
        runner.runPending()

        verify(exactly = 2) { fixture.billingClient.startConnection(any()) }
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
            deviceCache = DeviceCache(ApplicationProvider.getApplicationContext<Context>(), FakeHTTPClient.TEST_API_KEY),
            diagnostics = NoOpDiagnosticsTracker,
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
    fun `OK 加 null purchases 不会崩也不会通知成功（坑 17：按 ERROR 处理）`() {
        wrapper.onPurchasesUpdated(FakeBillingClientFixture.ok(), null)

        assertThat(listener.updates).isEmpty()
        assertThat(listener.failures).hasSize(1)
        assertThat(listener.failures.single().first.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        assertThat(listener.failures.single().second).isFalse()
    }

    @Test
    fun `USER_CANCELED 回一次失败并带 userCancelled 标志`() {
        wrapper.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.USER_CANCELED), mutableListOf())

        assertThat(listener.updates).isEmpty()
        assertThat(listener.failures).hasSize(1)
        assertThat(listener.failures.single().first.code).isEqualTo(PurchasesErrorCode.PurchaseCancelledError)
        assertThat(listener.failures.single().second).isTrue()
    }

    @Test
    fun `OK 加空列表既不回调成功也不回调失败`() {
        wrapper.onPurchasesUpdated(FakeBillingClientFixture.ok(), mutableListOf())

        assertThat(listener.updates).isEmpty()
        assertThat(listener.failures).isEmpty()
    }

    // endregion

    // region onPurchasesUpdated 的补齐（决策 D + 坑 18）

    @Test
    fun `有购买上下文时交易被补齐类型、offering 与升降级模式`() {
        connect()
        val pending = pendingPurchase(
            productId = "sub_premium",
            offeringIdentifier = "default",
            replacementMode = ReplacementMode.CHARGE_PRORATED_PRICE,
        )
        wrapper.purchaseContextProvider = { productId -> if (productId == "sub_premium") pending else null }

        wrapper.onPurchasesUpdated(
            FakeBillingClientFixture.ok(),
            mutableListOf(purchaseFixture(productIds = listOf("sub_premium"))),
        )
        idleMain()

        val transaction = listener.allTransactions.single()
        assertThat(transaction.type).isEqualTo(ProductType.SUBS)
        assertThat(transaction.subscriptionOptionId).isEqualTo("monthly-base")
        assertThat(transaction.presentedOfferingIdentifier).isEqualTo("default")
        assertThat(transaction.replacementMode).isEqualTo(ReplacementMode.CHARGE_PRORATED_PRICE)
        // 一次都没反查 —— 上下文在就不该多打两次 queryPurchases。
        assertThat(fixture.queryPurchasesCallCount).isZero()
    }

    @Test
    fun `坑 18 —— 应用外购买没有上下文，靠两次 queryPurchases 反查类型`() {
        connect()
        fixture.stubQueryPurchases()
        val purchase = purchaseFixture(productIds = listOf("coins_100"), purchaseToken = "token-outside")
        // 第一项 = SUBS 的答复（空），第二项 = INAPP 的答复（命中）。
        fixture.queryPurchasesResponses.addLast(emptyList())
        fixture.queryPurchasesResponses.addLast(listOf(purchase))

        wrapper.onPurchasesUpdated(FakeBillingClientFixture.ok(), mutableListOf(purchase))
        idleMain()

        assertThat(listener.allTransactions.single().type).isEqualTo(ProductType.INAPP)
        assertThat(listener.allTransactions.single().presentedOfferingIdentifier).isNull()
        assertThat(fixture.queryPurchasesCallCount).isEqualTo(2)
    }

    @Test
    fun `反查两次都没命中 —— 类型落 UNKNOWN（七分支会整笔跳过）`() {
        connect()
        fixture.stubQueryPurchases()
        val purchase = purchaseFixture(productIds = listOf("ghost"), purchaseToken = "token-ghost")

        wrapper.onPurchasesUpdated(FakeBillingClientFixture.ok(), mutableListOf(purchase))
        idleMain()

        assertThat(listener.allTransactions.single().type).isEqualTo(ProductType.UNKNOWN)
    }

    @Test
    fun `一批多笔购买要凑齐才回调一次`() {
        connect()
        val subs = pendingPurchase("sub_premium")
        val coins = pendingPurchase("coins_100", productType = ProductType.INAPP, subscriptionOptionId = null)
        wrapper.purchaseContextProvider = { productId ->
            when (productId) {
                "sub_premium" -> subs
                "coins_100" -> coins
                else -> null
            }
        }

        wrapper.onPurchasesUpdated(
            FakeBillingClientFixture.ok(),
            mutableListOf(
                purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "t1"),
                purchaseFixture(productIds = listOf("coins_100"), purchaseToken = "t2"),
            ),
        )
        idleMain()

        assertThat(listener.updates).hasSize(1)
        assertThat(listener.allTransactions.map { it.purchaseToken }).containsExactly("t1", "t2")
    }

    @Test
    fun `isFeatureSupported 在未连接时返回 null（判定不了，不拦购买）`() {
        assertThat(wrapper.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_UPDATE)).isNull()
    }

    // endregion

    // region Play in-app messages

    @Test
    fun `类别映射：BILLING_ISSUES 对应 Billing 的 TRANSACTIONAL`() {
        // `InAppMessageParams` 拼好之后读不回来，映射只能在这一层断言（同 obfuscatedAccountIdToSend）。
        assertThat(inAppMessageCategoryIds(InAppMessageType.ALL))
            .containsExactly(InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL)
        assertThat(inAppMessageCategoryIds(emptyList())).isEmpty()
    }

    @Test
    fun `已连接时调用 billingClient 的 showInAppMessages`() {
        connect()
        fixture.stubShowInAppMessages()
        val activity = liveActivity()

        wrapper.showInAppMessagesIfNeeded(activity, InAppMessageType.ALL) { }
        idleMain()

        assertThat(fixture.inAppMessageActivities).containsExactly(activity)
    }

    @Test
    fun `SUBSCRIPTION_STATUS_UPDATED 触发上层回调恰好一次`() {
        connect()
        fixture.stubShowInAppMessages()
        fixture.inAppMessageResponseCode = InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED
        var updates = 0

        wrapper.showInAppMessagesIfNeeded(liveActivity(), InAppMessageType.ALL) { updates++ }
        idleMain()

        assertThat(updates).isEqualTo(1)
    }

    @Test
    fun `NO_ACTION_NEEDED 不触发上层回调`() {
        connect()
        fixture.stubShowInAppMessages()
        fixture.inAppMessageResponseCode = InAppMessageResult.InAppMessageResponseCode.NO_ACTION_NEEDED
        var updates = 0

        wrapper.showInAppMessagesIfNeeded(liveActivity(), InAppMessageType.ALL) { updates++ }
        idleMain()

        assertThat(updates).isZero()
    }

    @Test
    fun `Activity 已销毁时不展示 —— 排队期间用户可能已经退出那个页面`() {
        connect()
        fixture.stubShowInAppMessages()
        val activity = liveActivity()
        every { activity.isDestroyed } returns true

        wrapper.showInAppMessagesIfNeeded(activity, InAppMessageType.ALL) { }
        idleMain()

        assertThat(fixture.inAppMessageActivities).isEmpty()
    }

    @Test
    fun `Activity 正在结束时不展示`() {
        connect()
        fixture.stubShowInAppMessages()
        val activity = liveActivity()
        every { activity.isFinishing } returns true

        wrapper.showInAppMessagesIfNeeded(activity, InAppMessageType.ALL) { }
        idleMain()

        assertThat(fixture.inAppMessageActivities).isEmpty()
    }

    @Test
    fun `Activity 还没 attach 到 window 时不展示`() {
        connect()
        fixture.stubShowInAppMessages()
        val activity = liveActivity()
        every { activity.window.peekDecorView().windowToken } returns null

        wrapper.showInAppMessagesIfNeeded(activity, InAppMessageType.ALL) { }
        idleMain()

        assertThat(fixture.inAppMessageActivities).isEmpty()
    }

    @Test
    fun `类别列表为空时直接返回，连待办都不排`() {
        connect()
        fixture.stubShowInAppMessages()

        wrapper.showInAppMessagesIfNeeded(liveActivity(), emptyList()) { }
        idleMain()

        assertThat(fixture.inAppMessageActivities).isEmpty()
        assertThat(wrapper.pendingRequestCount()).isZero()
    }

    @Test
    fun `未连接时先进队，连上之后才展示`() {
        buildClient(ready = false)
        fixture.stubShowInAppMessages()

        wrapper.showInAppMessagesIfNeeded(liveActivity(), InAppMessageType.ALL) { }
        idleMain()
        assertThat(fixture.inAppMessageActivities).isEmpty()
        assertThat(wrapper.pendingRequestCount()).isEqualTo(1)

        fixture.setReady(true)
        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idleMain()

        assertThat(fixture.inAppMessageActivities).hasSize(1)
    }

    /** 一个「活着并且已经 attach 到 window」的 Activity：relaxed mock 的默认值正好满足三道守卫。 */
    private fun liveActivity(): Activity = mockk(relaxed = true)

    // endregion

    private fun connect() {
        buildClient(ready = true)
        wrapper.onBillingSetupFinished(FakeBillingClientFixture.ok())
        idleMain()
    }

    private fun pendingPurchase(
        productId: String,
        productType: ProductType = ProductType.SUBS,
        subscriptionOptionId: String? = "monthly-base",
        offeringIdentifier: String? = null,
        replacementMode: ReplacementMode? = null,
    ) = PendingPurchase(
        key = productId,
        productType = productType,
        subscriptionOptionId = subscriptionOptionId,
        presentedPackageIdentifier = null,
        receiptInfo = ReceiptInfo(
            productIds = listOf(productId),
            platformProductIds = listOf(PlatformProductId(productId)),
            presentedOfferingIdentifier = offeringIdentifier,
            replacementMode = replacementMode,
        ),
        startedAtMs = 1L,
        state = PendingPurchase.STATE_LAUNCHED,
    )

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
