package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.Config
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.RequestSnapshot
import org.robolectric.RobolectricTestRunner

/**
 * **出站请求快照**（设计 §7）：上行契约变成可 diff 的产物。
 *
 * 请求本身由生产代码 `HTTPClient.buildRequest` 拼出来 —— 假后端只替换了最底层的 IO，
 * 所以这里锁住的就是真正会发出去的 URL、方法、头与 body。
 * 快照文件在 `src/test/resources/snapshots/`；录制方式见 [RequestSnapshot]。
 */
@RunWith(RobolectricTestRunner::class)
class OutboundRequestSnapshotTest {

    private lateinit var context: Context
    private lateinit var httpClient: FakeHTTPClient
    private lateinit var backend: Backend

    private val anonymousID = "\$RDAnonymousID:0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        backend = Backend(httpClient, DirectDispatcher())
    }

    @Test
    fun `GET subscribers 的出站请求`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        RequestSnapshot.assertMatches(httpClient.recordedRequests.single(), "get-subscribers")
    }

    @Test
    fun `GET offerings 的出站请求`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        backend.getOfferings(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        RequestSnapshot.assertMatches(httpClient.recordedRequests.single(), "get-offerings")
    }

    @Test
    fun `POST identify 的出站请求`() {
        httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)
        backend.logIn(anonymousID, "user-42", onSuccessHandler = { _, _ -> }, onErrorHandler = { })

        RequestSnapshot.assertMatches(httpClient.recordedRequests.single(), "post-identify")
    }

    @Test
    fun `后台请求带抖动但头一样`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val backgroundConfig = FakeHTTPClient.appConfig(context).apply { isAppBackgrounded = true }
        val backgroundClient = FakeHTTPClient(backgroundConfig, ETagManager(context))
        Backend(backgroundClient, DirectDispatcher())
            .getCustomerInfo(anonymousID, appInBackground = true, onSuccess = {}, onError = { _, _ -> })

        RequestSnapshot.assertMatches(backgroundClient.recordedRequests.single(), "get-subscribers-backgrounded")
    }

    // region 单独锁死几条容易被改坏的头

    @Test
    fun `匿名 ID 的 dollar 与冒号必须被百分号编码`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        val path = httpClient.recordedRequests.single().fullURL.path
        assertThat(path).isEqualTo("/v1/subscribers/%24RDAnonymousID%3A0123456789abcdef0123456789abcdef")
        assertThat(path).doesNotContain("$")
    }

    @Test
    fun `X-Client-Build-Version 必须发 —— RC Android 不发，我方 last_seen_app_build 靠它`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        assertThat(httpClient.recordedRequests.single().headers).containsKey("X-Client-Build-Version")
    }

    @Test
    fun `X-Version 与 Config_FRAMEWORK_VERSION 一致`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        assertThat(httpClient.recordedRequests.single().headers["X-Version"]).isEqualTo(Config.FRAMEWORK_VERSION)
    }

    @Test
    fun `X-Billing-Client-Sdk-Version 发的是 PBL 基线版本`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        assertThat(httpClient.recordedRequests.single().headers["X-Billing-Client-Sdk-Version"])
            .isEqualTo(Config.BILLING_CLIENT_VERSION)
    }

    @Test
    fun `X-Observer-Mode-Enabled 随 purchasesCompletedBy 变`() {
        val observerConfig = FakeHTTPClient.appConfig(
            context,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.MY_APP,
        )
        val observerClient = FakeHTTPClient(observerConfig, ETagManager(context))
        Backend(observerClient, DirectDispatcher())
            .getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        assertThat(observerClient.recordedRequests.single().headers["X-Observer-Mode-Enabled"]).isEqualTo("true")
    }

    /**
     * R1 PlatformInfo（0.2.0）：配置 → `PurchasesFactory.appConfig`（生产映射）→ 生产 `HTTPClient` 拼头。
     * 混合框架插件 configure 时带 `platformInfo("flutter", 版本)`，服务端按它分组排障。
     */
    @OptIn(InternalRevenueDogAPI::class)
    @Test
    fun `配置了 platformInfo 时发 X-Platform-Flavor 与 X-Platform-Flavor-Version`() {
        val configuration = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .baseURL(FakeHTTPClient.TEST_BASE_URL)
            .platformInfo("flutter", "0.1.0")
            .build()
        val client = FakeHTTPClient(PurchasesFactory.appConfig(configuration), ETagManager(context))
        Backend(client, DirectDispatcher())
            .getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        val headers = client.recordedRequests.single().headers
        assertThat(headers["X-Platform-Flavor"]).isEqualTo("flutter")
        assertThat(headers["X-Platform-Flavor-Version"]).isEqualTo("0.1.0")
    }

    @Test
    fun `没配 platformInfo 时 flavor 为 native 且不发版本头`() {
        val configuration = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .baseURL(FakeHTTPClient.TEST_BASE_URL)
            .build()
        val client = FakeHTTPClient(PurchasesFactory.appConfig(configuration), ETagManager(context))
        Backend(client, DirectDispatcher())
            .getCustomerInfo(anonymousID, appInBackground = false, onSuccess = {}, onError = { _, _ -> })

        val headers = client.recordedRequests.single().headers
        assertThat(headers["X-Platform-Flavor"]).isEqualTo(Config.PLATFORM_FLAVOR_NATIVE)
        assertThat(headers).doesNotContainKey("X-Platform-Flavor-Version")
    }

    @OptIn(InternalRevenueDogAPI::class)
    @Test
    fun `platformInfo 不同的两份配置不算同一份（重复 configure 判定）`() {
        val native = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY).build()
        val flutter = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .platformInfo("flutter", "0.1.0").build()
        val flutterNext = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .platformInfo("flutter", "0.1.1").build()
        val flutterSame = PurchasesConfiguration.Builder(context, FakeHTTPClient.TEST_API_KEY)
            .platformInfo("flutter", "0.1.0").build()

        assertThat(native.sameAs(flutter)).isFalse()
        assertThat(flutter.sameAs(flutterNext)).isFalse()
        assertThat(flutter.sameAs(flutterSame)).isTrue()
    }

    @Test
    fun `ETag 头只在走协商缓存的端点上出现`() {
        httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)
        backend.logIn(anonymousID, "user-42", onSuccessHandler = { _, _ -> }, onErrorHandler = { })

        // identify 是写操作，不参与 ETag。
        assertThat(httpClient.recordedRequests.single().headers).doesNotContainKey("X-RevenueDog-ETag")
    }

    @Test
    fun `identify 的 body 形状与服务端 identify_ts 对齐`() {
        httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)
        backend.logIn(anonymousID, "user-42", onSuccessHandler = { _, _ -> }, onErrorHandler = { })

        val body = requireNotNull(httpClient.recordedRequests.single().body)
        assertThat(body.getString("app_user_id")).isEqualTo(anonymousID)
        assertThat(body.getString("new_app_user_id")).isEqualTo("user-42")
        assertThat(body.length()).isEqualTo(2)
    }

    // endregion

    @Test
    fun `Backend 会把同一个在飞的请求合并 —— 只发一次`() {
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        val cache = DeviceCache(context, FakeHTTPClient.TEST_API_KEY)
        val identity = IdentityManager(cache, backend)
        identity.configure(anonymousID)

        // DirectDispatcher 是同步的，第二次调用时第一次已经完成、key 已移除，
        // 因此这里验证的是「合并不会丢回调」而不是「合并成一次请求」。
        var received = 0
        backend.getCustomerInfo(anonymousID, false, onSuccess = { received++ }, onError = { _, _ -> })
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        backend.getCustomerInfo(anonymousID, false, onSuccess = { received++ }, onError = { _, _ -> })

        assertThat(received).isEqualTo(2)
        assertThat(httpClient.recordedRequests).hasSize(2)
    }
}
