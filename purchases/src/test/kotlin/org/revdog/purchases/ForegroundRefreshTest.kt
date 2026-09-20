package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.common.CacheDurations
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

/**
 * 回前台刷新 CustomerInfo（对照 RC `onAppForegrounded` / `shouldRefreshCustomerInfo`）。
 *
 * 2026-09-20 真机发现：冷启后 listener 只收到盘上 2 小时前的快照（已过期的订阅仍显示 active），
 * 全程没有一次 `GET /v1/subscribers`。只靠 listener / Flow 判权益的宿主会一直拿旧数据。
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundRefreshTest {

    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        harness = OrchestratorHarness(context, BillingHarness())
    }

    private fun subscriberRequests() =
        harness.httpClient.recordedRequests.filter { it.fullURL.path.startsWith("/v1/subscribers/") }

    @Test
    fun `进程内首次回前台无条件拉一次，并把结果推给 listener`() {
        val pushed = mutableListOf<CustomerInfo>()
        harness.orchestrator.updatedCustomerInfoListener = UpdatedCustomerInfoListener { pushed += it }
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        harness.orchestrator.onAppForegrounded()

        assertThat(subscriberRequests()).hasSize(1)
        assertThat(pushed).hasSize(1)
        assertThat(harness.appConfig.isAppBackgrounded).isFalse()
    }

    @Test
    fun `缓存还新鲜时再次回前台不发请求`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.onAppForegrounded()

        harness.orchestrator.onAppBackgrounded()
        harness.nowMs += CacheDurations.FOREGROUND.inWholeMilliseconds - 1_000
        harness.orchestrator.onAppForegrounded()

        assertThat(subscriberRequests()).hasSize(1)
    }

    @Test
    fun `缓存过了前台 5 分钟再回前台会重新拉`() {
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.onAppForegrounded()

        harness.orchestrator.onAppBackgrounded()
        harness.nowMs += CacheDurations.FOREGROUND.inWholeMilliseconds + 1_000
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.onAppForegrounded()

        assertThat(subscriberRequests()).hasSize(2)
    }

    @Test
    fun `刷新失败不打扰宿主：不抛、不推 listener，下次回前台（缓存仍为空）继续拉`() {
        val pushed = mutableListOf<CustomerInfo>()
        harness.orchestrator.updatedCustomerInfoListener = UpdatedCustomerInfoListener { pushed += it }
        harness.httpClient.enqueueThrowable(UnknownHostException("api.revdog.test"))

        harness.orchestrator.onAppForegrounded()

        assertThat(subscriberRequests()).hasSize(1)
        assertThat(pushed).isEmpty()

        harness.orchestrator.onAppBackgrounded()
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.orchestrator.onAppForegrounded()

        assertThat(subscriberRequests()).hasSize(2)
        assertThat(pushed).hasSize(1)
    }
}
