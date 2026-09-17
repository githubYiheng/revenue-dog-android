package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsEvent
import org.revdog.purchases.diagnostics.DiagnosticsLevel
import org.revdog.purchases.diagnostics.DiagnosticsLevels
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * **两端 wire 一致性**（任务书 M3 硬要求）。
 *
 * 做法：把 iOS 测试夹具里真实产出的两条事件 JSON 原样搬过来当对照物
 * （来源：`sdk/ios/Tests/RevenueDogTests/DiagnosticsTests.swift` 的
 * `purchaseProducesCompliantEventSequence` 与 `Purchases.swift:535` 的 `sdk_configured` 打点），
 * 再让 Android 走**生产路径**（编排层打点 → `DiagnosticsLevels.levelFor` → `DiagnosticsEvent`）
 * 产出同名事件，逐项比对。
 *
 * 断言分三段：
 * 1. 事件**顶层**键集必须**完全相同**（`id` / `ts_ms` / `app_user_id` / `seq` / `type` / `level` / `fields`）；
 * 2. 同名字段的**名字与 JSON 类型**必须一致；
 * 3. iOS 有而 Android **故意不发**的字段，必须出现在本测试的白名单里并写明理由 ——
 *    白名单是唯一允许的分叉出口，加一项就得改这个测试。
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsWireParityTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    // region iOS 夹具（原样搬运，不要「顺手整理」）

    /**
     * iOS `sdk_configured`（`Purchases.swift:535` 的六个字段 + 信封）。
     * iOS 的编码器带 `.sortedKeys`，所以这里的键序就是它落盘的键序。
     */
    private val iosSdkConfigured = """
        {
          "app_user_id": "tester",
          "fields": {
            "diagnostics_enabled": true,
            "has_app_user_id": true,
            "identity_gated": false,
            "log_level": "info",
            "purchases_completed_by": "revenue_dog",
            "waits_for_login_before_sync": false
          },
          "id": "11111111-1111-1111-1111-111111111111",
          "level": "info",
          "seq": 1,
          "ts_ms": 1789000000000,
          "type": "sdk_configured"
        }
    """.trimIndent()

    /** iOS `receipt_post` 成功那一条（`DiagnosticsTests.swift` 的 `receipt.fields[...]` 断言）。 */
    private val iosReceiptPost = """
        {
          "app_user_id": "tester",
          "fields": {
            "attempt": 1,
            "duration_ms": 12,
            "request_id": "req-abc-123",
            "status": 200,
            "transaction_id": "tx-diag-1"
          },
          "id": "22222222-2222-2222-2222-222222222222",
          "level": "info",
          "seq": 7,
          "ts_ms": 1789000000000,
          "type": "receipt_post"
        }
    """.trimIndent()

    /**
     * iOS 有、Android **故意不发**的字段（每一项都要有理由）。
     *
     * | 字段 | 事件 | 不发的理由 |
     * |---|---|---|
     * | `waits_for_login_before_sync` | `sdk_configured` | 身份门控开关（ADR 0046/0047）是 iOS 专属模式，Android 侧没有这个配置项 |
     * | `identity_gated` | `sdk_configured` | 同上 |
     * | `attempt` | `receipt_post` | iOS 的 `HTTPClient` 自己重试，尝试序号有意义；Android **HTTP 层不重试**（考古 §2.12），每次上报都是一条独立事件，恒为 1 的字段是噪音 |
     * | `transaction_id` | `receipt_post` | Play 没有 Apple 那种交易 id；`purchaseToken` **绝不进诊断字段**。可用的替代是 `orderId`，但它在补报路径上拿不到（Play 已经看不到那笔）。留待 M4 |
     */
    private val intentionallyMissing = mapOf(
        DiagnosticsTracker.EVENT_SDK_CONFIGURED to setOf("waits_for_login_before_sync", "identity_gated"),
        DiagnosticsTracker.EVENT_RECEIPT_POST to setOf("attempt", "transaction_id"),
    )

    // endregion

    private fun androidEvent(name: String, properties: Map<String, Any?>): JSONObject =
        DiagnosticsEvent.create(
            id = "33333333-3333-3333-3333-333333333333",
            tsMs = 1_789_000_000_000L,
            appUserID = "tester",
            seq = 1,
            type = name,
            level = DiagnosticsLevels.levelFor(name, properties),
            fields = properties,
        ).toJson()

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    private fun jsonTypeOf(value: Any?): String = when (value) {
        is Boolean -> "boolean"
        is Int, is Long -> "integer"
        is Double, is Float -> "number"
        is String -> "string"
        else -> value?.javaClass?.simpleName ?: "null"
    }

    private fun assertParity(name: String, androidJson: JSONObject, iosJson: JSONObject) {
        assertThat(keys(androidJson))
            .describedAs("$name 的事件顶层键集两端必须完全一致")
            .isEqualTo(keys(iosJson))
        assertThat(androidJson.getString("type")).isEqualTo(iosJson.getString("type"))

        val androidFields = androidJson.getJSONObject("fields")
        val iosFields = iosJson.getJSONObject("fields")
        val missing = keys(iosFields) - keys(androidFields)
        assertThat(missing)
            .describedAs("$name：iOS 有而 Android 没有的字段必须登记在 intentionallyMissing 里")
            .isSubsetOf(intentionallyMissing[name].orEmpty())

        (keys(iosFields) intersect keys(androidFields)).forEach { key ->
            assertThat(jsonTypeOf(androidFields.get(key)))
                .describedAs("$name.fields.$key 的 JSON 类型两端必须一致")
                .isEqualTo(jsonTypeOf(iosFields.get(key)))
        }
    }

    @Test
    fun `sdk_configured 与 iOS 逐项对齐`() {
        val properties = harness.diagnostics.named(DiagnosticsTracker.EVENT_SDK_CONFIGURED).single()

        val androidJson = androidEvent(DiagnosticsTracker.EVENT_SDK_CONFIGURED, properties)
        assertParity(DiagnosticsTracker.EVENT_SDK_CONFIGURED, androidJson, JSONObject(iosSdkConfigured))

        val fields = androidJson.getJSONObject("fields")
        assertThat(fields.getString("log_level")).isEqualTo(LogLevel.INFO.name)
        assertThat(fields.getString("purchases_completed_by")).isEqualTo("revenue_dog")
        assertThat(fields.getBoolean("has_app_user_id")).isTrue()
        assertThat(fields.getBoolean("diagnostics_enabled")).isTrue()
        assertThat(androidJson.getString("level")).isEqualTo(DiagnosticsLevel.INFO)
    }

    @Test
    fun `receipt_post 与 iOS 逐项对齐`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        harness.httpClient.enqueue(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val properties = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        val androidJson = androidEvent(DiagnosticsTracker.EVENT_RECEIPT_POST, properties)
        assertParity(DiagnosticsTracker.EVENT_RECEIPT_POST, androidJson, JSONObject(iosReceiptPost))

        val fields = androidJson.getJSONObject("fields")
        // `status` 是契约里的名字（服务端把它提成列做巡检不变式 18/19）。
        assertThat(fields.getInt("status")).isEqualTo(200)
        assertThat(fields.getString("request_id")).isEqualTo("req-test")
        assertThat(fields.has("duration_ms")).isTrue()
        assertThat(androidJson.getString("level")).isEqualTo(DiagnosticsLevel.INFO)
    }

    @Test
    fun `失败的 receipt_post 带 error_class 与 status（巡检不变式 18 19 的两个列）`() {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        harness.httpClient.enqueue(401, """{"code":7401,"message":"bad key"}""")
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val properties = harness.diagnostics.named(DiagnosticsTracker.EVENT_RECEIPT_POST).single()
        val json = androidEvent(DiagnosticsTracker.EVENT_RECEIPT_POST, properties)
        val fields = json.getJSONObject("fields")

        assertThat(fields.getInt("status")).isEqualTo(401)
        assertThat(fields.getString("error_class")).isEqualTo("auth")
        assertThat(fields.getString("error_code")).isEqualTo(PurchasesErrorCode.InvalidCredentialsError.name)
        assertThat(fields.getString("request_id")).isEqualTo("req-test")
        assertThat(json.getString("level")).isEqualTo(DiagnosticsLevel.ERROR)
        // 401 按可重试处理（ADR 0023：把一笔已扣款的交易 finish 掉等于丢单）。
        assertThat(fields.getString("outcome")).isEqualTo(DiagnosticsTracker.OUTCOME_RETRYABLE)
    }

    @Test
    fun `error_class 分类与 iOS 同口径`() {
        val cases = mapOf(
            null to "network",
            401 to "auth",
            403 to "auth",
            400 to "client",
            404 to "client",
            429 to "client",
            500 to "server",
            503 to "server",
        )
        cases.forEach { (status, expected) ->
            assertThat(org.revdog.purchases.diagnostics.DiagnosticsErrorClass.from(status))
                .describedAs("status=$status")
                .isEqualTo(expected)
        }
    }
}
