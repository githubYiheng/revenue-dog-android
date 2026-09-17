package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * 门面的端到端（假后端）：configure / 身份 / CustomerInfo 四态。
 *
 * 用的是**公开路径** —— `Purchases.configure(...)` + `httpClientOverride`
 * 注入假后端（与 iOS `Configuration.with(transport:)` 同款做法）：
 * 同一条路径一直有人跑 = 不会静默腐坏。
 */
@RunWith(RobolectricTestRunner::class)
class PurchasesFacadeTest {

    private lateinit var context: Context
    private lateinit var httpClient: FakeHTTPClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        Purchases.resetSharedInstance()
    }

    @After
    fun tearDown() {
        Purchases.resetSharedInstance()
    }

    private fun configure(appUserID: String? = null): Purchases {
        val configuration = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .appUserID(appUserID)
            .baseURL(FakeHTTPClient.TEST_BASE_URL)
            .logLevel(LogLevel.ERROR)
            .httpClientOverride(httpClient)
            .dispatcherOverride(DirectDispatcher())
            .build()
        return Purchases.configure(configuration)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // region configure

    @Test
    fun `未 configure 时访问 sharedInstance 会抛`() {
        assertThatThrownBy { Purchases.sharedInstance }
            .isInstanceOf(UncheckedPurchasesException::class.java)
        assertThat(Purchases.isConfigured).isFalse()
    }

    @Test
    fun `configure 之后 sharedInstance 可用且是匿名身份`() {
        val purchases = configure()
        idle()

        assertThat(Purchases.isConfigured).isTrue()
        assertThat(Purchases.sharedInstance).isSameAs(purchases)
        assertThat(purchases.isAnonymous).isTrue()
        assertThat(IdentityManager.isUserIDAnonymous(purchases.appUserID)).isTrue()
    }

    @Test
    fun `configure 传 appUserID 时直接就是具名身份`() {
        val purchases = configure("user-42")
        idle()

        assertThat(purchases.appUserID).isEqualTo("user-42")
        assertThat(purchases.isAnonymous).isFalse()
    }

    @Test
    fun `重复 configure 打日志并替换实例`() {
        val first = configure("user-a")
        idle()
        val second = configure("user-b")
        idle()

        assertThat(second).isNotSameAs(first)
        assertThat(Purchases.sharedInstance).isSameAs(second)
        assertThat(Purchases.sharedInstance.appUserID).isEqualTo("user-b")
    }

    @Test
    fun `apiKey 为空时 build 就失败`() {
        assertThatThrownBy {
            PurchasesConfiguration.Builder(context, "   ").build()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `configure 会设置日志级别`() {
        configure()
        assertThat(Purchases.logLevel).isEqualTo(LogLevel.ERROR)
    }

    // endregion

    // region CustomerInfo

    @Test
    fun `getCustomerInfo 默认策略在无缓存时联网`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val received = mutableListOf<CustomerInfo>()
        purchases.getCustomerInfo(callback(received))
        idle()

        assertThat(received).hasSize(1)
        assertThat(received.single().originalAppUserId).isEqualTo("XXX-XXXXX-XXXXX-XX")
        assertThat(purchases.cachedCustomerInfo).isNotNull
    }

    @Test
    fun `CACHE_ONLY 在无缓存时报 customerInfoError 且不发请求`() {
        val purchases = configure("user-42")
        idle()
        val before = httpClient.recordedRequests.size

        var error: PurchasesError? = null
        purchases.getCustomerInfo(
            CacheFetchPolicy.CACHE_ONLY,
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = error(customerInfo)
                override fun onError(error1: PurchasesError) {
                    error = error1
                }
            },
        )
        idle()

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.CustomerInfoError)
        assertThat(httpClient.recordedRequests.size).isEqualTo(before)
    }

    @Test
    fun `后端 5xx 时供给过期缓存而不是报错（v1 最小离线方案）`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        purchases.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, callback(mutableListOf()))
        idle()

        httpClient.enqueue(500, """{"message":"boom"}""")
        val received = mutableListOf<CustomerInfo>()
        var error: PurchasesError? = null
        purchases.getCustomerInfo(
            CacheFetchPolicy.FETCH_CURRENT,
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    received += customerInfo
                }

                override fun onError(error1: PurchasesError) {
                    error = error1
                }
            },
        )
        idle()

        assertThat(error).isNull()
        assertThat(received).hasSize(1)
        assertThat(received.single().loadedFromCache).isTrue()
    }

    @Test
    fun `updatedCustomerInfoListener 挂上就收到缓存里的那份`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        purchases.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, callback(mutableListOf()))
        idle()

        val notified = mutableListOf<CustomerInfo>()
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { notified += it }
        idle()

        assertThat(notified).hasSize(1)
    }

    @Test
    fun `相同的 CustomerInfo 不重复通知 listener`() {
        val purchases = configure("user-42")
        idle()
        val notified = mutableListOf<CustomerInfo>()
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { notified += it }

        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        purchases.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, callback(mutableListOf()))
        idle()
        val afterFirst = notified.size

        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        purchases.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, callback(mutableListOf()))
        idle()

        assertThat(notified.size).isEqualTo(afterFirst)
    }

    // endregion

    // region 身份门控端到端

    @Test
    fun `logIn 成功后门面身份切过去`() {
        val purchases = configure()
        idle()
        val anonymous = purchases.appUserID
        httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)

        var created: Boolean? = null
        purchases.logIn(
            "user-42",
            object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, c: Boolean) {
                    created = c
                }

                override fun onError(error: PurchasesError) = error(error.toString())
            },
        )
        idle()

        assertThat(created).isTrue()
        assertThat(purchases.appUserID).isEqualTo("user-42")
        assertThat(purchases.appUserID).isNotEqualTo(anonymous)
    }

    @Test
    fun `logIn 失败时门面身份不动`() {
        val purchases = configure()
        idle()
        val before = purchases.appUserID
        httpClient.enqueue(500, """{"message":"boom"}""")

        var error: PurchasesError? = null
        purchases.logIn("user-42", logInCallback { error = it })
        idle()

        assertThat(error).isNotNull
        assertThat(purchases.appUserID).isEqualTo(before)
    }

    @Test
    fun `同 id logIn 不打后端`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val before = httpClient.recordedRequests.size

        var created: Boolean? = null
        purchases.logIn(
            "user-42",
            object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, c: Boolean) {
                    created = c
                }

                override fun onError(error: PurchasesError) = error(error.toString())
            },
        )
        idle()

        assertThat(created).isFalse()
        // 打的是 GET /subscribers（取 CustomerInfo），不是 POST identify。
        val newRequests = httpClient.recordedRequests.drop(before)
        assertThat(newRequests.map { it.method }).doesNotContain("POST")
    }

    @Test
    fun `logOut 成功后切到服务端确认过的匿名身份`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)

        val received = mutableListOf<CustomerInfo>()
        purchases.logOut(callback(received))
        idle()

        assertThat(received).hasSize(1)
        assertThat(purchases.isAnonymous).isTrue()
        // 落盘的 ID 必须与刚刚请求里那个候选**逐字相同**。
        val requestedPath = httpClient.recordedRequests.last().fullURL.path
        assertThat(requestedPath).contains(
            java.net.URLEncoder.encode(purchases.appUserID, "UTF-8").replace("+", "%20"),
        )
    }

    @Test
    fun `logOut 服务端失败时身份一个字节都不动`() {
        val purchases = configure("user-42")
        idle()
        httpClient.enqueue(500, """{"message":"boom"}""")

        var error: PurchasesError? = null
        purchases.logOut(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = error("不该成功")
                override fun onError(error1: PurchasesError) {
                    error = error1
                }
            },
        )
        idle()

        assertThat(error).isNotNull
        assertThat(purchases.appUserID).isEqualTo("user-42")
        assertThat(purchases.isAnonymous).isFalse()
    }

    @Test
    fun `匿名身份上 logOut 报 invalidAppUserIdError 且不发请求`() {
        val purchases = configure()
        idle()
        val before = httpClient.recordedRequests.size

        var error: PurchasesError? = null
        purchases.logOut(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = error("不该成功")
                override fun onError(error1: PurchasesError) {
                    error = error1
                }
            },
        )
        idle()

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.InvalidAppUserIdError)
        assertThat(httpClient.recordedRequests.size).isEqualTo(before)
    }

    // endregion

    private fun callback(sink: MutableList<CustomerInfo>) = object : ReceiveCustomerInfoCallback {
        override fun onReceived(customerInfo: CustomerInfo) {
            sink += customerInfo
        }

        override fun onError(error: PurchasesError) = error(error.toString())
    }

    private fun logInCallback(onError: (PurchasesError) -> Unit) = object : LogInCallback {
        override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = error("不该成功")
        override fun onError(error: PurchasesError) = onError.invoke(error)
    }
}
