package org.revdog.purchases

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * `addCustomerInfoObserver`（0.2.0，主代理裁定 1）：Flutter 插件每个引擎一条订阅，互不覆盖。
 *
 * 语义：挂 `customerInfoFlow`（replay = 1 + distinct）→ 订阅即回放最近值、之后每次变化一次、
 * 回调在主线程、`close()` 幂等、实例 close 后全部失效。
 */
@OptIn(InternalRevenueDogAPI::class)
@RunWith(RobolectricTestRunner::class)
class CustomerInfoObserverTest {

    private lateinit var context: Context
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        harness = OrchestratorHarness(context, BillingHarness())
        Purchases.resetSharedInstance()
    }

    @After
    fun tearDown() {
        Purchases.resetSharedInstance()
        Purchases.logLevel = LogLevel.INFO
    }

    private val noop = object : ReceiveCustomerInfoCallback {
        override fun onReceived(customerInfo: CustomerInfo) = Unit
        override fun onError(error: PurchasesError) = Unit
    }

    private fun fetch(payload: String = Fixtures.SUBSCRIBER_RESPONSE) {
        harness.httpClient.enqueue(200, payload)
        harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, noop)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `订阅时已有最近值就立刻回放一次`() {
        fetch()
        val received = mutableListOf<CustomerInfo>()

        harness.orchestrator.addCustomerInfoObserver { received += it }

        assertThat(received).hasSize(1)
        assertThat(received.single().originalAppUserId).isEqualTo("XXX-XXXXX-XXXXX-XX")
    }

    @Test
    fun `还没有任何值时订阅不回调，之后每次变化回调一次`() {
        val received = mutableListOf<CustomerInfo>()
        harness.orchestrator.addCustomerInfoObserver { received += it }
        assertThat(received).isEmpty()

        fetch(Fixtures.SUBSCRIBER_RESPONSE)
        fetch(Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)

        assertThat(received.map { it.originalAppUserId }).containsExactly("XXX-XXXXX-XXXXX-XX", "user-1")
    }

    @Test
    fun `两个 observer 各自收到，互不影响`() {
        val first = mutableListOf<CustomerInfo>()
        val second = mutableListOf<CustomerInfo>()
        val firstHandle = harness.orchestrator.addCustomerInfoObserver { first += it }
        harness.orchestrator.addCustomerInfoObserver { second += it }

        fetch()
        assertThat(first).hasSize(1)
        assertThat(second).hasSize(1)

        // 关掉一个，另一个照常收。
        firstHandle.close()
        fetch(Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)
        assertThat(first).hasSize(1)
        assertThat(second).hasSize(2)
    }

    @Test
    fun `close 之后不再回调，且 close 幂等`() {
        val received = mutableListOf<CustomerInfo>()
        val handle = harness.orchestrator.addCustomerInfoObserver { received += it }
        fetch()
        assertThat(received).hasSize(1)

        handle.close()
        handle.close()
        fetch(Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)

        assertThat(received).hasSize(1)
    }

    @Test
    fun `实例 close 之后全部 observer 不再回调`() {
        val received = mutableListOf<CustomerInfo>()
        val handle = harness.orchestrator.addCustomerInfoObserver { received += it }
        fetch()
        assertThat(received).hasSize(1)

        harness.orchestrator.close()
        fetch(Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)
        // close 之后新挂的也不会收到回放。
        val late = mutableListOf<CustomerInfo>()
        harness.orchestrator.addCustomerInfoObserver { late += it }

        assertThat(received).hasSize(1)
        assertThat(late).isEmpty()
        handle.close() // 实例已关，句柄 close 仍然安全
    }

    @Test
    fun `同一份 CustomerInfo 不重复回调`() {
        val received = mutableListOf<CustomerInfo>()
        harness.orchestrator.addCustomerInfoObserver { received += it }

        fetch()
        fetch()
        fetch()

        assertThat(received).hasSize(1)
    }

    @Test
    fun `非主线程上产生的变更也在主线程回调`() {
        val threads = mutableListOf<Thread>()
        harness.orchestrator.addCustomerInfoObserver { threads += Thread.currentThread() }

        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val worker = Thread { harness.orchestrator.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, noop) }
        worker.start()
        worker.join()
        assertThat(threads).isEmpty() // 已经 post 到主线程，还没跑

        idle()
        assertThat(threads).containsExactly(Looper.getMainLooper().thread)
    }

    @Test
    fun `不影响 updatedCustomerInfoListener 的既有行为`() {
        val listener = mutableListOf<CustomerInfo>()
        val observer = mutableListOf<CustomerInfo>()
        harness.orchestrator.updatedCustomerInfoListener = UpdatedCustomerInfoListener { listener += it }
        harness.orchestrator.addCustomerInfoObserver { observer += it }

        fetch()

        assertThat(listener).hasSize(1)
        assertThat(observer).hasSize(1)
        assertThat(harness.orchestrator.updatedCustomerInfoListener).isNotNull
    }

    @Test
    fun `门面入口：重新 configure 替换实例后旧订阅失效`() {
        val httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        val billing = BillingHarness()
        val dispatcher = DirectDispatcher()
        fun configure(appUserID: String) = Purchases.configure(
            PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
                .appUserID(appUserID)
                .baseURL(FakeHTTPClient.TEST_BASE_URL)
                .logLevel(LogLevel.ERROR)
                .httpClientOverride(httpClient)
                .dispatcherOverride(dispatcher)
                .billingOverride(billing.wrapper)
                .build(),
        )

        val first = configure("user-a")
        idle()
        val received = mutableListOf<CustomerInfo>()
        first.addCustomerInfoObserver { received += it }
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        first.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, noop)
        idle()
        assertThat(received).hasSize(1)

        configure("user-b")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)
        first.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, noop)
        idle()

        assertThat(received).hasSize(1)
    }
}
