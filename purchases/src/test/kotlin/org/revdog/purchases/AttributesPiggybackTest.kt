package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.attributes.SubscriberAttributeKeys
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.RequestSnapshot
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * 属性**搭车** `POST /v1/receipts`（契约 §2.1 的 `attributes` 键，设计 §5）。
 *
 * 为什么要搭车：属性同步与购买上报在同一时刻发生是常态（购买页填了邮箱就下单），
 * 搭车省一次请求、而且让「属性」与「这笔购买」在服务端落在同一个事务边界附近。
 */
@RunWith(RobolectricTestRunner::class)
class AttributesPiggybackTest {

    private lateinit var context: Context
    private lateinit var billing: BillingHarness
    private lateinit var harness: OrchestratorHarness

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        billing = BillingHarness()
        harness = OrchestratorHarness(context, billing)
    }

    private val noopCallback = object : PurchaseCallback {
        override fun onCompleted(result: PurchaseResult) = Unit
        override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
    }

    private fun purchase(responseCode: Int, payload: String) {
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        harness.orchestrator.purchase(PurchaseParams.Builder(billing.activity, product).build(), noopCallback)
        harness.httpClient.enqueue(responseCode, payload)
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )
    }

    @Test
    fun `receipts 带上未同步属性，成功后标已同步`() {
        harness.orchestrator.setAttributes(
            mapOf(SubscriberAttributeKeys.EMAIL to "a@b.c", "food" to "pizza"),
        )
        assertThat(harness.orchestrator.unsyncedAttributes()).hasSize(2)

        purchase(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        val body = requireNotNull(harness.receiptRequests().single().body)
        val attributes = body.getJSONObject("attributes")
        assertThat(attributes.keys().asSequence().toList()).containsExactlyInAnyOrder("\$email", "food")
        assertThat(attributes.getJSONObject("food").getString("value")).isEqualTo("pizza")
        assertThat(attributes.getJSONObject("food").getLong("updated_at_ms"))
            .isEqualTo(OrchestratorHarness.FIXED_NOW_MS)
        // 200 之后不再重传。
        assertThat(harness.orchestrator.unsyncedAttributes()).isEmpty()
    }

    @Test
    fun `没有待同步属性时 receipts 不带 attributes 键`() {
        purchase(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.has("attributes")).isFalse()
    }

    @Test
    fun `attributes_error_response 里出错的键也标已同步（不再反复重传）`() {
        harness.orchestrator.setAttributes(mapOf("food" to "pizza", "bad_one" to "x"))

        purchase(
            200,
            Fixtures.receiptResponse(mapOf("sub_premium" to false)).let { base ->
                // 在成功响应里塞进搭车属性的逐键错误（考古 §2.9 的包装形状）。
                org.json.JSONObject(base).apply {
                    put(
                        "attributes_error_response",
                        org.json.JSONObject(
                            """{"attribute_errors":[{"key_name":"bad_one","message":"Value is too long."}]}""",
                        ),
                    )
                }.toString()
            },
        )

        assertThat(harness.orchestrator.unsyncedAttributes()).isEmpty()
    }

    @Test
    fun `上报可重试失败时属性保持未同步，下一次上报再带一遍`() {
        harness.orchestrator.setAttributes(mapOf("food" to "pizza"))

        purchase(503, """{"message":"unavailable"}""")

        assertThat(harness.orchestrator.unsyncedAttributes()).hasSize(1)
        val body = requireNotNull(harness.receiptRequests().single().body)
        assertThat(body.getJSONObject("attributes").has("food")).isTrue()
    }

    @Test
    fun `确定性 4xx 时属性仍然保持未同步（这批属性没被处理过）`() {
        harness.orchestrator.setAttributes(mapOf("food" to "pizza"))

        purchase(422, """{"code":7422,"message":"invalid receipt"}""")

        // 与 `POST /attributes` 的 4xx 不同：这里的 4xx 说的是**收据**不合法，
        // 搭车属性根本没被服务端处理（`applyInlineAttributes` 在成功分支里），必须留着。
        assertThat(harness.orchestrator.unsyncedAttributes()).hasSize(1)
    }

    @Test
    fun `搭车属性进出站快照`() {
        harness.orchestrator.setAttributes(mapOf(SubscriberAttributeKeys.EMAIL to "a@b.c"))

        purchase(200, Fixtures.receiptResponse(mapOf("sub_premium" to false)))

        RequestSnapshot.assertMatches(harness.receiptRequests().single(), "post-receipts-with-attributes")
    }

    @Test
    fun `属性上行是单独请求时走 POST subscribers attributes`() {
        harness.orchestrator.setAttributes(mapOf("food" to "pizza"))
        harness.httpClient.enqueue(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}""")

        harness.orchestrator.syncAttributes()

        val request = harness.httpClient.recordedRequests.single { it.fullURL.path.endsWith("/attributes") }
        assertThat(request.fullURL.path).isEqualTo("/v1/subscribers/user-42/attributes")
        RequestSnapshot.assertMatches(request, "post-attributes")
        assertThat(harness.orchestrator.unsyncedAttributes()).isEmpty()
    }

    @Test
    fun `logIn 把未同步属性搬到新身份并再刷一轮`() {
        harness.orchestrator.setAttributes(mapOf("food" to "pizza"))
        // ① logIn 前的那一轮属性同步（失败，属性留着）
        harness.httpClient.enqueue(503, "")
        // ② identify
        harness.httpClient.enqueue(200, Fixtures.SUBSCRIBER_RESPONSE)
        // ③ logIn 后的那一轮属性同步（这次成功）
        harness.httpClient.enqueue(200, """{"request_date":"2026-09-18T00:00:00Z","subscriber":{}}""")
        // ④ logIn 之后会顺带刷 offerings
        harness.httpClient.enqueue(200, """{"current_offering_id":null,"offerings":[]}""")

        harness.orchestrator.logIn(
            "new-user",
            object : LogInCallback {
                override fun onReceived(customerInfo: org.revdog.purchases.customerinfo.CustomerInfo, created: Boolean) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )

        val attributeRequests = harness.httpClient.recordedRequests.filter { it.fullURL.path.endsWith("/attributes") }
        assertThat(attributeRequests.map { it.fullURL.path })
            .containsExactly("/v1/subscribers/user-42/attributes", "/v1/subscribers/new-user/attributes")
        assertThat(harness.orchestrator.unsyncedAttributes()).isEmpty()
    }
}
