package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.identity.IdentityManager
import org.revdog.purchases.identity.PurchasesErrorHolder
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityManagerTest {

    private lateinit var context: Context
    private lateinit var deviceCache: DeviceCache
    private lateinit var httpClient: FakeHTTPClient
    private lateinit var backend: Backend
    private lateinit var identityManager: IdentityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deviceCache = DeviceCache(context, FakeHTTPClient.TEST_API_KEY)
        httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        // 单线程**直接执行**的 dispatcher：单测里不要异步，断言才写得准。
        backend = Backend(httpClient, DirectDispatcher())
        identityManager = IdentityManager(deviceCache, backend)
    }

    // region 匿名 ID 格式（ADR 0019）

    @Test
    fun `匿名 ID 是 RDAnonymousID 加 32 位小写 hex`() {
        val id = IdentityManager.generateAnonymousAppUserID()
        assertThat(id).startsWith("\$RDAnonymousID:")
        val hex = id.removePrefix("\$RDAnonymousID:")
        assertThat(hex).hasSize(32)
        assertThat(hex).matches("[a-f0-9]{32}")
    }

    @Test
    fun `RD 与 RC 两种前缀都算匿名`() {
        assertThat(IdentityManager.isUserIDAnonymous("\$RDAnonymousID:0123456789abcdef0123456789abcdef")).isTrue()
        assertThat(IdentityManager.isUserIDAnonymous("\$RCAnonymousID:0123456789abcdef0123456789abcdef")).isTrue()
    }

    @Test
    fun `具名 id 与畸形 id 都不算匿名`() {
        assertThat(IdentityManager.isUserIDAnonymous("user-42")).isFalse()
        // 大写 hex / 长度不对 / 前缀不对：一律不认，宁可当具名也不能把具名当匿名。
        assertThat(IdentityManager.isUserIDAnonymous("\$RDAnonymousID:ABCDEF")).isFalse()
        assertThat(IdentityManager.isUserIDAnonymous("\$RDAnonymousID:0123456789ABCDEF0123456789ABCDEF")).isFalse()
        assertThat(IdentityManager.isUserIDAnonymous("RDAnonymousID:0123456789abcdef0123456789abcdef")).isFalse()
    }

    @Test
    fun `configure 不传 id 时生成匿名身份并落盘`() {
        identityManager.configure(null)
        val first = identityManager.currentAppUserID
        assertThat(IdentityManager.isUserIDAnonymous(first)).isTrue()

        // 第二次 configure 必须沿用已持久化的那个，不能每次启动换一个匿名身份。
        identityManager.configure(null)
        assertThat(identityManager.currentAppUserID).isEqualTo(first)
    }

    @Test
    fun `configure 传空白串按匿名处理`() {
        identityManager.configure("   ")
        assertThat(identityManager.currentUserIsAnonymous()).isTrue()
    }

    @Test
    fun `configure 传具名 id 时以传入值为准`() {
        identityManager.configure(null)
        identityManager.configure("user-42")
        assertThat(identityManager.currentAppUserID).isEqualTo("user-42")
        assertThat(identityManager.currentUserIsAnonymous()).isFalse()
    }

    // endregion

    // region logIn 门控（ADR 0046–0048）

    @Test
    fun `logIn 服务端成功后才切本地身份`() {
        identityManager.configure(null)
        val anonymous = identityManager.currentAppUserID
        httpClient.enqueue(201, Fixtures.SUBSCRIBER_RESPONSE)

        var created: Boolean? = null
        identityManager.logIn("user-42", onSuccess = { _, c -> created = c }, onError = { fail(it) })

        assertThat(created).isTrue()
        assertThat(identityManager.currentAppUserID).isEqualTo("user-42")
        assertThat(identityManager.currentAppUserID).isNotEqualTo(anonymous)
        // 新身份的 CustomerInfo 已经写进缓存，宿主紧接着读缓存不会拿到空。
        assertThat(deviceCache.getCachedCustomerInfo("user-42")).isNotNull
    }

    @Test
    fun `logIn 服务端失败时零副作用`() {
        identityManager.configure(null)
        val before = identityManager.currentAppUserID
        httpClient.enqueue(500, """{"message":"boom"}""")

        var error: PurchasesError? = null
        identityManager.logIn("user-42", onSuccess = { _, _ -> fail("不该成功") }, onError = { error = it })

        assertThat(error).isNotNull
        assertThat(error!!.code).isEqualTo(PurchasesErrorCode.UnknownBackendError)
        // 身份一个字节都没动。
        assertThat(identityManager.currentAppUserID).isEqualTo(before)
        assertThat(identityManager.currentUserIsAnonymous()).isTrue()
        assertThat(deviceCache.getCachedCustomerInfo("user-42")).isNull()
    }

    @Test
    fun `logIn 网络异常时零副作用`() {
        identityManager.configure(null)
        val before = identityManager.currentAppUserID
        httpClient.enqueueIOException()

        var error: PurchasesError? = null
        identityManager.logIn("user-42", onSuccess = { _, _ -> fail("不该成功") }, onError = { error = it })

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.NetworkError)
        assertThat(identityManager.currentAppUserID).isEqualTo(before)
    }

    @Test
    fun `logIn 空 id 直接报 invalidAppUserIdError 且不发请求`() {
        identityManager.configure(null)
        var error: PurchasesError? = null
        identityManager.logIn("  ", onSuccess = { _, _ -> fail("不该成功") }, onError = { error = it })

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.InvalidAppUserIdError)
        assertThat(httpClient.recordedRequests).isEmpty()
    }

    @Test
    fun `logIn 200 时 created 为 false`() {
        identityManager.configure(null)
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        var created: Boolean? = null
        identityManager.logIn("user-42", onSuccess = { _, c -> created = c }, onError = { fail(it) })
        assertThat(created).isFalse()
    }

    @Test
    fun `logIn 成功会清掉旧身份的缓存`() {
        identityManager.configure("old-user")
        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        identityManager.logIn("old-user-info-seed", onSuccess = { _, _ -> }, onError = { fail(it) })

        // 给旧身份塞一份缓存，再登入新身份。
        identityManager.configure("old-user")
        val info = requireNotNull(
            org.revdog.purchases.customerinfo.CustomerInfoFactory.buildCustomerInfo(
                JSONObject(Fixtures.SUBSCRIBER_RESPONSE),
                null,
                loadedFromCache = false,
            ),
        )
        deviceCache.cacheCustomerInfo("old-user", info)
        assertThat(deviceCache.getCachedCustomerInfo("old-user")).isNotNull

        httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        identityManager.logIn("new-user", onSuccess = { _, _ -> }, onError = { fail(it) })
        assertThat(deviceCache.getCachedCustomerInfo("old-user")).isNull()
    }

    // endregion

    // region logOut 两段式（偏离 RC 的纯本地实现）

    @Test
    fun `匿名身份上 logOut 第一段就失败且零副作用`() {
        identityManager.configure(null)
        val before = identityManager.currentAppUserID

        val result = identityManager.candidateAnonymousAppUserID()

        assertThat(result.isFailure).isTrue()
        val error = (result.exceptionOrNull() as PurchasesErrorHolder).error
        assertThat(error.code).isEqualTo(PurchasesErrorCode.InvalidAppUserIdError)
        assertThat(identityManager.currentAppUserID).isEqualTo(before)
        assertThat(httpClient.recordedRequests).isEmpty()
    }

    @Test
    fun `logOut 候选只是候选 —— 未提交前身份不变`() {
        identityManager.configure("user-42")
        val candidate = identityManager.candidateAnonymousAppUserID().getOrThrow()

        assertThat(IdentityManager.isUserIDAnonymous(candidate)).isTrue()
        // **候选没有落盘**：服务端确认失败时设备不会被留在一个后端没见过的匿名 ID 上。
        assertThat(identityManager.currentAppUserID).isEqualTo("user-42")
    }

    @Test
    fun `commitLogOut 落盘的就是服务端确认过的那个候选`() {
        identityManager.configure("user-42")
        val candidate = identityManager.candidateAnonymousAppUserID().getOrThrow()

        identityManager.commitLogOut(candidate)

        assertThat(identityManager.currentAppUserID).isEqualTo(candidate)
        assertThat(identityManager.currentUserIsAnonymous()).isTrue()
    }

    @Test
    fun `logOut 提交后旧身份的缓存被清掉`() {
        identityManager.configure("user-42")
        val info = org.revdog.purchases.customerinfo.CustomerInfoFactory.buildCustomerInfo(
            JSONObject(Fixtures.SUBSCRIBER_RESPONSE),
            null,
            loadedFromCache = false,
        )
        deviceCache.cacheCustomerInfo("user-42", info)

        val candidate = identityManager.candidateAnonymousAppUserID().getOrThrow()
        identityManager.commitLogOut(candidate)

        assertThat(deviceCache.getCachedCustomerInfo("user-42")).isNull()
    }

    // endregion

    private fun fail(error: PurchasesError): Nothing = throw AssertionError("不该失败：$error")

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
