package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * 身份切换时的两个触发点，逐条对照 RC：
 *
 * 1. **未同步属性只在「旧身份是匿名」时搬**（RC `IdentityManager.kt:176, 263-267`；
 *    iOS `migrateIfOldIsAnonymous`）。具名 A → 具名 B 是换了个人，把 A 还没发出去的
 *    `$email` 搬到 B 名下就是两个真实用户串号；
 * 2. **logOut 成功后按新身份重拉 offerings**（RC `PurchasesOrchestrator.kt:949-953 → 1573-1589`
 *    的 `updateAllCaches`）。不拉的话付费墙上还挂着上一个用户命中的那组 offering。
 */
@RunWith(RobolectricTestRunner::class)
class IdentityTriggerPointsTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        billing.subscriptionProducts = listOf(
            StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails(),
        )
    }

    private fun harnessFor(appUserID: String) = OrchestratorHarness(context, billing, appUserID = appUserID)

    private class LogInRecorder : LogInCallback {
        val received: MutableList<CustomerInfo> = mutableListOf()
        val errors: MutableList<PurchasesError> = mutableListOf()
        override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
            received += customerInfo
        }

        override fun onError(error: PurchasesError) {
            errors += error
        }
    }

    /**
     * logIn 前后各有一轮属性同步：两轮都让它失败，待同步项才留得住，
     * 「搬没搬」才观察得到（属性发成功就被标已同步、从待发队列里消失了）。
     */
    private fun failEveryAttributeSyncExceptIdentify(harness: OrchestratorHarness) {
        harness.httpClient.enqueue(500, """{"message":"attributes down"}""")
        harness.httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)
        harness.httpClient.defaultResponse =
            Result.success(FakeHTTPClient.response(500, """{"message":"attributes down"}"""))
    }

    // region 1. 未同步属性的迁移门

    @Test
    fun `匿名 logIn 具名：未同步属性跟着搬过去`() {
        val anonymous = IdentityManager.generateAnonymousAppUserID()
        val harness = harnessFor(anonymous)
        harness.orchestrator.setAttribute("tier", "gold")
        failEveryAttributeSyncExceptIdentify(harness)

        val recorder = LogInRecorder()
        harness.orchestrator.logIn("user-42", recorder)

        assertThat(recorder.errors).isEmpty()
        assertThat(harness.attributesCache.unsynced("user-42").keys).containsExactly("tier")
        assertThat(harness.attributesCache.unsynced(anonymous)).isEmpty()
    }

    @Test
    fun `具名 logIn 具名：不搬，旧身份的未同步项留在旧身份键下`() {
        val harness = harnessFor("user-a")
        harness.orchestrator.setAttribute("tier", "gold")
        failEveryAttributeSyncExceptIdentify(harness)

        val recorder = LogInRecorder()
        harness.orchestrator.logIn("user-b", recorder)

        assertThat(recorder.errors).isEmpty()
        // 两个真实用户之间绝不串属性。
        assertThat(harness.attributesCache.unsynced("user-b")).isEmpty()
        assertThat(harness.attributesCache.unsynced("user-a").keys).containsExactly("tier")
    }

    // endregion

    // region 2. logOut 之后重拉 offerings

    private fun offeringsRequests(harness: OrchestratorHarness) =
        harness.httpClient.recordedRequests.filter { it.fullURL.path.endsWith("/offerings") }

    @Test
    fun `logOut 成功后按新匿名身份重拉一次 offerings`() {
        val harness = harnessFor("user-42")
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)

        val received = mutableListOf<CustomerInfo>()
        harness.orchestrator.logOut(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    received += customerInfo
                }

                override fun onError(error: PurchasesError) = throw AssertionError("不该失败：$error")
            },
        )

        assertThat(received).hasSize(1)
        val offerings = offeringsRequests(harness)
        assertThat(offerings).hasSize(1)
        // 拉的是**新**身份的 offerings。
        assertThat(offerings.single().fullURL.path)
            .contains(java.net.URLEncoder.encode(harness.orchestrator.appUserID, "UTF-8").replace("+", "%20"))
    }

    @Test
    fun `offerings 重拉失败不影响 logOut 成功`() {
        val harness = harnessFor("user-42")
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        harness.httpClient.enqueue(500, """{"message":"offerings down"}""")

        val received = mutableListOf<CustomerInfo>()
        val errors = mutableListOf<PurchasesError>()
        harness.orchestrator.logOut(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    received += customerInfo
                }

                override fun onError(error: PurchasesError) {
                    errors += error
                }
            },
        )

        assertThat(errors).isEmpty()
        assertThat(received).hasSize(1)
        assertThat(offeringsRequests(harness)).hasSize(1)
        assertThat(harness.orchestrator.isAnonymous).isTrue()
    }

    // endregion
}
