package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.MainDispatcher
import org.revdog.purchases.google.BillingWrapper
import org.revdog.purchases.google.usecase.QueryProductDetailsResponse
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.offerings.Offerings
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.StoreProductBuilders
import org.robolectric.RobolectricTestRunner

/**
 * offerings 取数 + **分 type 两次查 Play** + 填充 + 缓存。
 */
@RunWith(RobolectricTestRunner::class)
class OfferingsManagerTest {

    private lateinit var context: Context
    private lateinit var httpClient: FakeHTTPClient
    private lateinit var deviceCache: DeviceCache
    private lateinit var billing: BillingWrapper
    private lateinit var manager: org.revdog.purchases.offerings.OfferingsManager

    private val subs = listOf(
        StoreProductBuilders.subscription("sub_premium", "monthly-base", billingPeriod = "P1M"),
        StoreProductBuilders.subscription("sub_premium", "annual-base", billingPeriod = "P1Y"),
    )
    private val inApps = listOf(StoreProductBuilders.inApp("coins_100"))

    private val queriedTypes = mutableListOf<ProductType>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        httpClient = FakeHTTPClient(FakeHTTPClient.appConfig(context), ETagManager(context))
        deviceCache = DeviceCache(
            context.getSharedPreferences("offerings-${System.nanoTime()}", Context.MODE_PRIVATE),
            "pk_test",
        )
        billing = mockk(relaxed = true)
        stubBilling(subsResult = subs, inAppResult = inApps)
        manager = org.revdog.purchases.offerings.OfferingsManager(
            deviceCache = deviceCache,
            backend = Backend(httpClient, DirectDispatcher()),
            billing = billing,
            mainDispatcher = MainDispatcher(null),
        )
    }

    private fun stubBilling(
        subsResult: List<StoreProduct>,
        inAppResult: List<StoreProduct>,
        notFound: List<String> = emptyList(),
    ) {
        every {
            billing.queryProductDetailsAsync(any(), any(), any(), any())
        } answers {
            val type = firstArg<ProductType>()
            queriedTypes += type
            val onReceive = thirdArg<(QueryProductDetailsResponse) -> Unit>()
            val products = if (type == ProductType.SUBS) subsResult else inAppResult
            onReceive(QueryProductDetailsResponse(products, notFound))
        }
    }

    private fun fetch(fetchCurrent: Boolean = false): Offerings? {
        var result: Offerings? = null
        manager.getOfferings(
            appUserID = "user-42",
            appInBackground = false,
            fetchCurrent = fetchCurrent,
            onError = { throw AssertionError("不该失败：$it") },
            onSuccess = { result = it },
        )
        return result
    }

    @Test
    fun `按 type 分两次查 —— 先 SUBS 再 INAPP，用的是同一批 productIds`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch()

        assertThat(queriedTypes).containsExactly(ProductType.SUBS, ProductType.INAPP)
    }

    @Test
    fun `商品详情被批量填充进 Package`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        val offerings = requireNotNull(fetch())
        val offering = requireNotNull(offerings.current)

        assertThat(offering.monthly?.product?.id).isEqualTo("sub_premium:monthly-base")
        assertThat(offering.annual?.product?.id).isEqualTo("sub_premium:annual-base")
        assertThat(offering["consumable"]?.product?.productId).isEqualTo("coins_100")
    }

    @Test
    fun `查不到的商品 product 为 null 并进 notFoundProductIds`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        val offerings = requireNotNull(fetch())

        assertThat(offerings.notFoundProductIds).contains("not_on_play")
        assertThat(offerings.current?.get("\$rd_missing")?.product).isNull()
    }

    @Test
    fun `一个 type 查不到但另一个查到了，不算 not found`() {
        // coins_100 在 SUBS 查询里「查不到」，但在 INAPP 里查到了 —— 取交集才是真的没有。
        stubBilling(subsResult = subs, inAppResult = inApps, notFound = listOf("coins_100"))
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)

        val offerings = requireNotNull(fetch())
        assertThat(offerings.notFoundProductIds).doesNotContain("coins_100")
    }

    @Test
    fun `响应被写进磁盘缓存（存的是原始 JSON）`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch()

        val cached = requireNotNull(deviceCache.getCachedOfferingsResponse())
        assertThat(cached.getString("current_offering_id")).isEqualTo("default")
    }

    @Test
    fun `第二次调用命中内存缓存，不再打后端`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch()
        val afterFirst = httpClient.recordedRequests.size

        fetch()

        assertThat(httpClient.recordedRequests.size).isEqualTo(afterFirst)
        assertThat(manager.cachedOfferings).isNotNull
    }

    @Test
    fun `fetchCurrent 强制联网`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch()
        val afterFirst = httpClient.recordedRequests.size

        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch(fetchCurrent = true)

        assertThat(httpClient.recordedRequests.size).isGreaterThan(afterFirst)
    }

    @Test
    fun `后端失败时退回磁盘上的原始响应 —— 空白付费墙比过期付费墙更糟`() {
        httpClient.enqueue(200, Fixtures.OFFERINGS_RESPONSE)
        fetch()
        deviceCache.clearOfferingsMemoryCache()

        httpClient.enqueue(500, """{"message":"boom"}""")
        val offerings = requireNotNull(fetch())

        assertThat(offerings.current?.availablePackages).isNotEmpty
    }

    @Test
    fun `后端失败且没有任何缓存时才报错`() {
        httpClient.enqueue(500, """{"message":"boom"}""")

        var error: PurchasesError? = null
        manager.getOfferings(
            appUserID = "user-42",
            appInBackground = false,
            onError = { error = it },
            onSuccess = { throw AssertionError("不该成功") },
        )

        assertThat(error?.code).isEqualTo(PurchasesErrorCode.UnknownBackendError)
    }

    @Test
    fun `没有任何 productId 时不查 Play`() {
        httpClient.enqueue(200, """{"current_offering_id":null,"offerings":[]}""")
        val offerings = requireNotNull(fetch())

        assertThat(queriedTypes).isEmpty()
        assertThat(offerings.all).isEmpty()
    }
}
