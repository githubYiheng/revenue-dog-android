package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.caching.DeviceCache
import org.revdog.purchases.common.CacheDurations
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.common.sha1
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.support.Fixtures
import org.robolectric.RobolectricTestRunner
import java.util.Date
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class DeviceCacheTest {

    private lateinit var context: Context
    private lateinit var deviceCache: DeviceCache
    private var now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deviceCache = DeviceCache(
            preferences = context.getSharedPreferences("test-${System.nanoTime()}", Context.MODE_PRIVATE),
            apiKey = "pk_test",
            dateProvider = DateProvider { Date(now) },
        )
    }

    private fun sampleCustomerInfo(): CustomerInfo =
        CustomerInfoFactory.buildCustomerInfo(JSONObject(Fixtures.SUBSCRIBER_RESPONSE), null, loadedFromCache = false)

    // region CustomerInfo 缓存

    @Test
    fun `写入后能原样读回`() {
        deviceCache.cacheCustomerInfo("user-42", sampleCustomerInfo())
        val cached = requireNotNull(deviceCache.getCachedCustomerInfo("user-42"))

        assertThat(cached.originalAppUserId).isEqualTo("XXX-XXXXX-XXXXX-XX")
        assertThat(cached.loadedFromCache).isTrue()
        // requestDate 从缓存元数据来，不是从 body 里重新解析（缓存的是响应体，时间是写入时的）。
        assertThat(cached.requestDate).isNotNull
    }

    @Test
    fun `schema_version 不符时当未命中`() {
        deviceCache.cacheCustomerInfo("user-42", sampleCustomerInfo())
        val key = deviceCache.customerInfoCacheKey("user-42")
        val prefs = context.getSharedPreferences("tamper", Context.MODE_PRIVATE)

        // 直接把落盘的那份 schema_version 改成一个未来版本。
        val cache = DeviceCache(prefs, "pk_test", DateProvider { Date(now) })
        val info = sampleCustomerInfo()
        cache.cacheCustomerInfo("user-42", info)
        val stored = JSONObject(requireNotNull(prefs.getString(cache.customerInfoCacheKey("user-42"), null)))
        stored.put(DeviceCache.CUSTOMER_INFO_SCHEMA_VERSION_KEY, 999)
        prefs.edit().putString(cache.customerInfoCacheKey("user-42"), stored.toString()).apply()

        assertThat(cache.getCachedCustomerInfo("user-42")).isNull()
        assertThat(key).isNotEmpty()
    }

    @Test
    fun `坏 JSON 当未命中而不是抛`() {
        val prefs = context.getSharedPreferences("bad-json", Context.MODE_PRIVATE)
        val cache = DeviceCache(prefs, "pk_test", DateProvider { Date(now) })
        prefs.edit().putString(cache.customerInfoCacheKey("user-42"), "{not json").apply()
        assertThat(cache.getCachedCustomerInfo("user-42")).isNull()
    }

    @Test
    fun `clearCachesForAppUserID 只清那个身份`() {
        deviceCache.cacheCustomerInfo("user-a", sampleCustomerInfo())
        deviceCache.cacheCustomerInfo("user-b", sampleCustomerInfo())

        deviceCache.clearCachesForAppUserID("user-a")

        assertThat(deviceCache.getCachedCustomerInfo("user-a")).isNull()
        assertThat(deviceCache.getCachedCustomerInfo("user-b")).isNotNull
    }

    @Test
    fun `invalidate 抹掉时间戳但留着内容`() {
        deviceCache.cacheCustomerInfo("user-42", sampleCustomerInfo())
        assertThat(deviceCache.getCustomerInfoCachesLastUpdated("user-42")).isNotNull

        deviceCache.clearCustomerInfoCacheTimestamp("user-42")

        assertThat(deviceCache.getCustomerInfoCachesLastUpdated("user-42")).isNull()
        // 内容还在：离线时仍能供出上一份（与 iOS「失效代」同效果）。
        assertThat(deviceCache.getCachedCustomerInfo("user-42")).isNotNull
    }

    // endregion

    // region TTL（前台 5 min / 后台 25 h）

    @Test
    fun `前台 TTL 是 5 分钟`() {
        val updated = Date(now)
        assertThat(
            CacheDurations.isStale(updated, appInBackground = false, now = Date(now + 4.minutes.inWholeMilliseconds)),
        ).isFalse()
        assertThat(
            CacheDurations.isStale(updated, appInBackground = false, now = Date(now + 6.minutes.inWholeMilliseconds)),
        ).isTrue()
    }

    @Test
    fun `后台 TTL 是 25 小时`() {
        val updated = Date(now)
        assertThat(
            CacheDurations.isStale(updated, appInBackground = true, now = Date(now + 24.hours.inWholeMilliseconds)),
        ).isFalse()
        assertThat(
            CacheDurations.isStale(updated, appInBackground = true, now = Date(now + 26.hours.inWholeMilliseconds)),
        ).isTrue()
    }

    @Test
    fun `没有时间戳就是过期`() {
        assertThat(CacheDurations.isStale(null, appInBackground = false, now = Date(now))).isTrue()
    }

    @Test
    fun `时间戳在未来（本地钟被改过）也算过期`() {
        assertThat(
            CacheDurations.isStale(Date(now + 1000), appInBackground = false, now = Date(now)),
        ).isTrue()
    }

    // endregion

    // region offerings 缓存

    @Test
    fun `offerings 磁盘缓存存的是原始响应`() {
        val response = JSONObject(Fixtures.OFFERINGS_RESPONSE)
        deviceCache.cacheOfferingsResponse(response)

        val cached = requireNotNull(deviceCache.getCachedOfferingsResponse())
        assertThat(cached.getString("current_offering_id")).isEqualTo("default")
        assertThat(deviceCache.getOfferingsCachesLastUpdated()).isNotNull
    }

    // endregion

    // region token 台账（M2 用，M1 建好存储）

    @Test
    fun `记过的 token 出现在台账里（key 是 sha1）`() {
        deviceCache.addSuccessfullyPostedToken("token-abc", isAutoRenewing = true)
        assertThat(deviceCache.getPreviouslySentHashedTokens()).containsExactly("token-abc".sha1())
    }

    @Test
    fun `台账里没有的活跃购买就是要补报的`() {
        deviceCache.addSuccessfullyPostedToken("token-a")
        val current = setOf("token-a".sha1(), "token-b".sha1())

        assertThat(deviceCache.hashedTokensNotInCache(current)).containsExactly("token-b".sha1())
    }

    @Test
    fun `isAutoRenewing 变化能被侦测到 —— 这是 Play 外取消的唯一线索`() {
        deviceCache.addSuccessfullyPostedToken("token-a", isAutoRenewing = true)

        val changed = deviceCache.hashedTokensWithAutoRenewingChange(
            mapOf("token-a".sha1() to false),
        )
        assertThat(changed).containsExactly("token-a".sha1())

        // 状态一致时不算变化。
        assertThat(
            deviceCache.hashedTokensWithAutoRenewingChange(mapOf("token-a".sha1() to true)),
        ).isEmpty()
    }

    @Test
    fun `未知的 isAutoRenewing（null）不算变化`() {
        deviceCache.addSuccessfullyPostedToken("token-a", isAutoRenewing = null)
        assertThat(
            deviceCache.hashedTokensWithAutoRenewingChange(mapOf("token-a".sha1() to false)),
        ).isEmpty()
    }

    @Test
    fun `saveAutoRenewingStatus 只更新已在台账里的 token`() {
        deviceCache.addSuccessfullyPostedToken("token-a", isAutoRenewing = true)
        deviceCache.saveAutoRenewingStatus(
            mapOf("token-a".sha1() to false, "token-unknown".sha1() to true),
        )

        assertThat(deviceCache.hashedTokensWithAutoRenewingChange(mapOf("token-a".sha1() to false))).isEmpty()
        assertThat(deviceCache.getPreviouslySentHashedTokens()).containsExactly("token-a".sha1())
    }

    @Test
    fun `cleanPreviouslySentTokens 清掉已不活跃的条目`() {
        deviceCache.addSuccessfullyPostedToken("token-a")
        deviceCache.addSuccessfullyPostedToken("token-b")

        deviceCache.cleanPreviouslySentTokens(setOf("token-a".sha1()))

        assertThat(deviceCache.getPreviouslySentHashedTokens()).containsExactly("token-a".sha1())
    }

    @Test
    fun `台账跨实例持久化`() {
        val prefs = context.getSharedPreferences("persist", Context.MODE_PRIVATE)
        DeviceCache(prefs, "pk_test").addSuccessfullyPostedToken("token-a", isAutoRenewing = true)

        val reopened = DeviceCache(prefs, "pk_test")
        assertThat(reopened.getPreviouslySentHashedTokens()).containsExactly("token-a".sha1())
        assertThat(
            reopened.hashedTokensWithAutoRenewingChange(mapOf("token-a".sha1() to false)),
        ).containsExactly("token-a".sha1())
    }

    // endregion
}
