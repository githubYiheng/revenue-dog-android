package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.attributes.SubscriberAttribute
import org.revdog.purchases.attributes.SubscriberAttributeKeys
import org.revdog.purchases.attributes.SubscriberAttributeLimits
import org.revdog.purchases.attributes.SubscriberAttributesCache
import org.revdog.purchases.attributes.SubscriberAttributesManager
import org.revdog.purchases.attributes.SubscriberAttributesPoster
import org.revdog.purchases.attributes.parseAttributeErrors
import org.revdog.purchases.attributes.toWireJson
import org.revdog.purchases.common.AppConfig
import org.revdog.purchases.common.DateProvider
import org.revdog.purchases.networking.Backend
import org.revdog.purchases.networking.ETagManager
import org.revdog.purchases.support.DirectDispatcher
import org.revdog.purchases.support.FakeHTTPClient
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * 订阅者属性：LWW、删除语义、分桶隔离、约束前置校验、`is_synced` 标记、
 * `attributes_error_response` 解析。
 *
 * 口径来源：契约 §2.4（键值约束与保留键全集）+ RC `SubscriberAttributesManager` /
 * `SubscriberAttributesCache` 的行为 + iOS `M3AttributesTests` 的同款用例。
 */
@RunWith(RobolectricTestRunner::class)
class SubscriberAttributesTest {

    private lateinit var context: Context
    private lateinit var cache: SubscriberAttributesCache
    private lateinit var manager: SubscriberAttributesManager
    private lateinit var httpClient: FakeHTTPClient
    private var nowMs: Long = FIXED_NOW_MS

    private val userA = "user-a"
    private val userB = "user-b"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val preferences = context.getSharedPreferences("attrs-${System.nanoTime()}", Context.MODE_PRIVATE)
        cache = SubscriberAttributesCache(preferences, FakeHTTPClient.TEST_API_KEY)
        val appConfig = AppConfig(
            context = context,
            apiKey = FakeHTTPClient.TEST_API_KEY,
            baseURL = FakeHTTPClient.TEST_BASE_URL,
            purchasesAreCompletedBy = PurchasesAreCompletedBy.REVENUE_DOG,
            isDebugBuild = false,
            diagnosticsEnabled = true,
        ).apply { isAppBackgrounded = false }
        httpClient = FakeHTTPClient(appConfig, ETagManager(context))
        manager = SubscriberAttributesManager(
            cache = cache,
            poster = SubscriberAttributesPoster(Backend(httpClient, DirectDispatcher())),
            diagnostics = org.revdog.purchases.diagnostics.NoOpDiagnosticsTracker,
            dispatcher = DirectDispatcher(),
            dateProvider = DateProvider { Date(nowMs) },
        )
    }

    private fun attributesRequests() =
        httpClient.recordedRequests.filter { it.fullURL.path.endsWith("/attributes") }

    // region 写入与 LWW

    @Test
    fun `写入后是未同步状态且带 updated_at_ms`() {
        manager.setAttributes(mapOf("favorite_food" to "pizza"), userA)

        val attribute = cache.attribute(userA, "favorite_food")!!
        assertThat(attribute.value).isEqualTo("pizza")
        assertThat(attribute.isSynced).isFalse()
        assertThat(attribute.updatedAtMs).isEqualTo(FIXED_NOW_MS)
        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `LWW：新值的时间戳一定比库中值新，即使时钟被拨回过去`() {
        manager.setAttributes(mapOf("k" to "v1"), userA)
        val first = cache.attribute(userA, "k")!!.updatedAtMs

        // 时钟倒退一小时：本地 setter 表达的是「此刻的最新意图」，必须赢。
        nowMs -= 3_600_000
        manager.setAttributes(mapOf("k" to "v2"), userA)

        val second = cache.attribute(userA, "k")!!
        assertThat(second.value).isEqualTo("v2")
        assertThat(second.updatedAtMs).isEqualTo(first + 1)
    }

    @Test
    fun `同值且已同步时不重置未同步标记`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        cache.markSynced(userA, manager.unsyncedAttributes(userA))
        assertThat(manager.unsyncedAttributes(userA)).isEmpty()

        manager.setAttributes(mapOf("k" to "v"), userA)

        assertThat(manager.unsyncedAttributes(userA)).isEmpty()
    }

    @Test
    fun `同值但未同步时保持未同步`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        manager.setAttributes(mapOf("k" to "v"), userA)

        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    // endregion

    // region 删除语义

    @Test
    fun `null 与空串都是墓碑，上行编码成空串`() {
        manager.setAttributes(mapOf("a" to null, "b" to ""), userA)

        val all = manager.storedAttributes(userA)
        assertThat(all.map { it.key }).containsExactly("a", "b")
        assertThat(all).allMatch { it.isTombstone }
        val wire = all.toWireJson()
        assertThat(wire.getJSONObject("a").getString("value")).isEmpty()
        assertThat(wire.getJSONObject("b").getString("value")).isEmpty()
        assertThat(wire.getJSONObject("a").getLong("updated_at_ms")).isEqualTo(FIXED_NOW_MS)
    }

    @Test
    fun `已同步的墓碑直接删条目（服务端已存 NULL）`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        cache.markSynced(userA, manager.unsyncedAttributes(userA))
        manager.setAttributes(mapOf("k" to null), userA)

        cache.markSynced(userA, manager.unsyncedAttributes(userA))

        assertThat(cache.attribute(userA, "k")).isNull()
        assertThat(manager.storedAttributes(userA)).isEmpty()
    }

    // endregion

    // region 约束前置校验

    @Test
    fun `非法键被拒：不以字母开头 超长 含非法字符 空 以及 $ 前缀`() {
        val rejected = manager.setAttributes(
            mapOf(
                "1abc" to "v",
                "a".repeat(41) to "v",
                "has space" to "v",
                "has.dot" to "v",
                "" to "v",
                "\$notAReservedKeyWeKnow" to "v",
                "legit_key-1" to "v",
            ),
            userA,
        )

        // `$` 前缀一律放行（保留键；服务端认所有 `$` 开头的键）。
        assertThat(rejected).containsExactly("", "1abc", "a".repeat(41), "has space", "has.dot")
        assertThat(manager.storedAttributes(userA).map { it.key })
            .containsExactly("\$notAReservedKeyWeKnow", "legit_key-1")
    }

    @Test
    fun `value 超 500 字符被拒`() {
        val rejected = manager.setAttributes(
            mapOf("k" to "v".repeat(SubscriberAttributeLimits.MAX_VALUE_LENGTH + 1)),
            userA,
        )

        assertThat(rejected).containsExactly("k")
        assertThat(manager.storedAttributes(userA)).isEmpty()
    }

    @Test
    fun `50 个自定义属性上限：保留键不计入，墓碑不计入`() {
        val fifty = (1..SubscriberAttributeLimits.MAX_CUSTOM_ATTRIBUTES).associate { "k$it" to "v" }
        assertThat(manager.setAttributes(fifty, userA)).isEmpty()

        // 第 51 个自定义键被拒。
        assertThat(manager.setAttributes(mapOf("overflow" to "v"), userA)).containsExactly("overflow")
        // 保留键不受这个上限约束。
        assertThat(manager.setAttributes(mapOf(SubscriberAttributeKeys.EMAIL to "a@b.c"), userA)).isEmpty()
        // 覆盖已有的键也不占新位。
        assertThat(manager.setAttributes(mapOf("k1" to "v2"), userA)).isEmpty()
        // 删一个之后又有位了。
        manager.setAttributes(mapOf("k1" to null), userA)
        assertThat(manager.setAttributes(mapOf("overflow" to "v"), userA)).isEmpty()
    }

    @Test
    fun `身份未就绪时整批丢弃并如实回报`() {
        val rejected = manager.setAttributes(mapOf("b" to "v", "a" to "v"), appUserID = "")

        assertThat(rejected).containsExactly("a", "b")
    }

    // endregion

    // region 分桶隔离

    @Test
    fun `分桶隔离：两个身份的同名键互不影响`() {
        manager.setAttributes(mapOf("k" to "va"), userA)
        manager.setAttributes(mapOf("k" to "vb"), userB)

        assertThat(cache.attribute(userA, "k")!!.value).isEqualTo("va")
        assertThat(cache.attribute(userB, "k")!!.value).isEqualTo("vb")
        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `logOut 之后新匿名身份不串味，且旧身份的待发属性还在`() {
        manager.setAttributes(mapOf("k" to "va"), userA)
        val anonymous = "\$RDAnonymousID:" + "0".repeat(32)

        assertThat(manager.storedAttributes(anonymous)).isEmpty()
        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
        // 旧身份的桶要留着 —— 它们还没进库，logOut 不该把它们扔掉（下一轮同步会把全部身份都发）。
        assertThat(cache.unsyncedForAllUsers().keys).containsExactly(userA)
    }

    @Test
    fun `logIn 把未同步的搬到新身份，已同步的留在旧身份不搬`() {
        manager.setAttributes(mapOf("synced" to "v", "unsynced" to "v"), userA)
        cache.markSynced(userA, listOf(cache.attribute(userA, "synced")!!))

        manager.copyUnsyncedAttributes(from = userA, to = userB)

        assertThat(manager.storedAttributes(userB).map { it.key }).containsExactly("unsynced")
        assertThat(manager.unsyncedAttributes(userB)).hasSize(1)
        // 旧桶整体清掉（已同步项归旧 customer，服务端那边已经落库了）。
        assertThat(manager.storedAttributes(userA)).isEmpty()
    }

    @Test
    fun `logIn 搬迁时不覆盖新身份下更新的同键值`() {
        manager.setAttributes(mapOf("k" to "old"), userA)
        nowMs += 1_000
        manager.setAttributes(mapOf("k" to "new"), userB)

        manager.copyUnsyncedAttributes(from = userA, to = userB)

        assertThat(cache.attribute(userB, "k")!!.value).isEqualTo("new")
    }

    @Test
    fun `空白身份不参与全量同步（老版本 SDK 留下的脏桶）`() {
        cache.setAttributes("", mapOf("k" to SubscriberAttribute("k", "v", FIXED_NOW_MS)))

        assertThat(cache.unsyncedForAllUsers()).isEmpty()
    }

    // endregion

    // region markSynced 语义

    @Test
    fun `上行途中被新 setter 覆盖过的属性保持未同步`() {
        manager.setAttributes(mapOf("k" to "v1"), userA)
        val sent = manager.unsyncedAttributes(userA)
        // 上行在飞期间宿主又设了一次。
        manager.setAttributes(mapOf("k" to "v2"), userA)

        cache.markSynced(userA, sent)

        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
        assertThat(cache.attribute(userA, "k")!!.value).isEqualTo("v2")
    }

    // endregion

    // region 同步

    @Test
    fun `同步成功后标已同步且出站形状符合契约`() {
        manager.setAttributes(mapOf(SubscriberAttributeKeys.EMAIL to "a@b.c", "food" to "pizza"), userA)
        httpClient.enqueue(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}""")

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        val body = requireNotNull(attributesRequests().single().body)
        val attributes = body.getJSONObject("attributes")
        assertThat(attributes.keys().asSequence().toList()).containsExactlyInAnyOrder("\$email", "food")
        assertThat(attributes.getJSONObject("\$email").getString("value")).isEqualTo("a@b.c")
        assertThat(attributes.getJSONObject("food").getLong("updated_at_ms")).isEqualTo(FIXED_NOW_MS)
        assertThat(attributesRequests().single().fullURL.path).isEqualTo("/v1/subscribers/user-a/attributes")
        assertThat(manager.unsyncedAttributes(userA)).isEmpty()
    }

    @Test
    fun `5xx 保持未同步等下次时机`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        httpClient.enqueue(503, """{"message":"unavailable"}""")

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `404 保持未同步（customer 还没建出来，重试会成功）`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        httpClient.enqueue(404, """{"code":7404}""")

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `网络失败保持未同步`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        httpClient.enqueueIOException()

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(manager.unsyncedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `400 确定性拒绝标已同步不再重传`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        httpClient.enqueue(
            400,
            """{"code":7263,"message":"Some customer attribute keys were unable to be saved.",
               "attribute_errors":[{"key_name":"k","message":"Value is too long."}]}""",
        )

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(manager.unsyncedAttributes(userA)).isEmpty()
    }

    @Test
    fun `401 也标已同步（属性重传一万次也不会进库；与 receipts 的 401 语义相反）`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        httpClient.enqueue(401, """{"code":7401}""")

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(manager.unsyncedAttributes(userA)).isEmpty()
    }

    @Test
    fun `没有待同步属性时不发请求`() {
        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(attributesRequests()).isEmpty()
    }

    @Test
    fun `全量同步覆盖其它身份的桶，且同步完就把它删掉`() {
        manager.setAttributes(mapOf("k" to "va"), userA)
        manager.setAttributes(mapOf("k" to "vb"), userB)
        repeat(2) { httpClient.enqueue(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}""") }

        manager.synchronizeIfNeeded(currentAppUserID = userA, appInBackground = false)

        assertThat(attributesRequests().map { it.fullURL.path })
            .containsExactlyInAnyOrder("/v1/subscribers/user-a/attributes", "/v1/subscribers/user-b/attributes")
        // 非当前身份且已全部同步 → 整桶删掉。
        assertThat(manager.storedAttributes(userB)).isEmpty()
        // 当前身份的桶留着（已同步项还在，用来判断「值没变就不重发」）。
        assertThat(manager.storedAttributes(userA)).hasSize(1)
    }

    @Test
    fun `单飞：同步在飞期间的第二次调用不重复打请求`() {
        manager.setAttributes(mapOf("k" to "v"), userA)
        var nestedRequests = 0
        httpClient.beforeResponse = {
            manager.synchronizeIfNeeded(userA, appInBackground = false)
            nestedRequests = attributesRequests().size
        }
        httpClient.enqueue(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}""")

        manager.synchronizeIfNeeded(userA, appInBackground = false)

        assertThat(nestedRequests).isEqualTo(1)
        assertThat(attributesRequests()).hasSize(1)
    }

    // endregion

    // region attributes_error_response 解析

    @Test
    fun `平铺形状（POST attributes 的 400）带顶层 code`() {
        val errors = JSONObject(
            """{"code":7263,"attribute_errors":[{"key_name":"${'$'}email","message":"bad email"}]}""",
        ).parseAttributeErrors()

        assertThat(errors).hasSize(1)
        assertThat(errors.single().keyName).isEqualTo("\$email")
        assertThat(errors.single().backendCode).isEqualTo(7263)
    }

    @Test
    fun `包装形状（POST receipts 的搭车通道）不带 code`() {
        val errors = JSONObject(
            """{"attributes_error_response":{"attribute_errors":[{"key_name":"bad","message":"Key name format is not valid."}]}}""",
        ).parseAttributeErrors()

        assertThat(errors).hasSize(1)
        assertThat(errors.single().keyName).isEqualTo("bad")
        assertThat(errors.single().backendCode).isNull()
    }

    @Test
    fun `没有属性错误时返回空表，且缺字段的条目被跳过`() {
        assertThat(JSONObject("{}").parseAttributeErrors()).isEmpty()
        assertThat(
            JSONObject("""{"attribute_errors":[{"key_name":"only_key"},{"message":"only_message"}]}""")
                .parseAttributeErrors(),
        ).isEmpty()
    }

    // endregion

    @Test
    fun `保留键全集与契约 2_4 一致（35 个原文键 + 6 个归因键）`() {
        assertThat(SubscriberAttributeKeys.ALL).hasSize(41)
        assertThat(SubscriberAttributeKeys.ALL).doesNotHaveDuplicates()
        assertThat(SubscriberAttributeKeys.ALL).allMatch { SubscriberAttributeKeys.isReserved(it) }
        // 抽查任务书点名的四个。
        assertThat(SubscriberAttributeKeys.EMAIL).isEqualTo("\$email")
        assertThat(SubscriberAttributeKeys.PHONE_NUMBER).isEqualTo("\$phoneNumber")
        assertThat(SubscriberAttributeKeys.DISPLAY_NAME).isEqualTo("\$displayName")
        assertThat(SubscriberAttributeKeys.GPS_AD_ID).isEqualTo("\$gpsAdId")
        assertThat(SubscriberAttributeKeys.ANDROID_ID).isEqualTo("\$androidId")
    }

    @Test
    fun `落盘往返不丢字段`() {
        val prefsName = "attrs-roundtrip-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val first = SubscriberAttributesCache(preferences, FakeHTTPClient.TEST_API_KEY)
        first.setAttributes(
            userA,
            mapOf(
                "k" to SubscriberAttribute("k", "v", FIXED_NOW_MS, isSynced = true),
                "gone" to SubscriberAttribute("gone", null, FIXED_NOW_MS + 1),
            ),
        )

        // 新实例读同一份 prefs = 「进程重启但磁盘还在」。
        val reloaded = SubscriberAttributesCache(preferences, FakeHTTPClient.TEST_API_KEY)
        val stored = reloaded.allStored(userA)

        assertThat(stored.keys).containsExactlyInAnyOrder("k", "gone")
        assertThat(stored.getValue("k").value).isEqualTo("v")
        assertThat(stored.getValue("k").isSynced).isTrue()
        assertThat(stored.getValue("k").updatedAtMs).isEqualTo(FIXED_NOW_MS)
        assertThat(stored.getValue("gone").isTombstone).isTrue()
        assertThat(stored.getValue("gone").isSynced).isFalse()
        assertThat(reloaded.unsynced(userA).keys).containsExactly("gone")
    }

    @Test
    fun `缓存解析失败按空处理，不崩`() {
        val prefsName = "attrs-corrupt-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("org.revdog.purchases.${FakeHTTPClient.TEST_API_KEY}.subscriberAttributes", "这不是 json")
            .commit()

        val corrupt = SubscriberAttributesCache(preferences, FakeHTTPClient.TEST_API_KEY)

        assertThat(corrupt.allStored(userA)).isEmpty()
        assertThat(corrupt.unsyncedForAllUsers()).isEmpty()
    }

    private companion object {
        const val FIXED_NOW_MS = 1_789_000_000_000L
    }
}

