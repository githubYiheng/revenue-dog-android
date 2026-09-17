package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.offerings.OfferingParser
import org.revdog.purchases.offerings.PackageType
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.StoreProductBuilders
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfferingParserTest {

    private val response = JSONObject(Fixtures.OFFERINGS_RESPONSE)

    private fun productsById(vararg products: StoreProduct): Map<String, List<StoreProduct>> =
        products.toList().groupBy { it.productId }

    private val playProducts = productsById(
        // 同一个订阅 productId 下的两个 base plan —— 这正是 Google 的商品模型
        // （一个 ProductDetails 炸成 N 个 StoreProduct）。
        StoreProductBuilders.subscription("sub_premium", "monthly-base", billingPeriod = "P1M"),
        StoreProductBuilders.subscription("sub_premium", "annual-base", billingPeriod = "P1Y"),
        StoreProductBuilders.inApp("coins_100"),
    )

    @Test
    fun `抽出要向 Play 查询的 productId 集合`() {
        assertThat(OfferingParser.productIdsToQuery(response))
            .containsExactlyInAnyOrder("sub_premium", "coins_100", "not_on_play")
    }

    @Test
    fun `按 productId 冒号 basePlanId 精确匹配订阅`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, emptyList())
        val offering = requireNotNull(offerings["default"])

        val monthly = requireNotNull(offering["\$rc_monthly"])
        assertThat(monthly.platformProductIdentifier).isEqualTo("sub_premium")
        assertThat(monthly.platformProductPlanIdentifier).isEqualTo("monthly-base")
        assertThat(monthly.product?.id).isEqualTo("sub_premium:monthly-base")

        val annual = requireNotNull(offering["\$rc_annual"])
        // 同一个 productId 的两个 base plan 必须各自匹配到自己那条，不能互相顶。
        assertThat(annual.product?.id).isEqualTo("sub_premium:annual-base")
        assertThat(annual.product).isNotEqualTo(monthly.product)
    }

    @Test
    fun `没有 plan identifier 时只认唯一的一次性商品`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, emptyList())
        val consumable = requireNotNull(offerings["default"]?.get("consumable"))

        assertThat(consumable.platformProductPlanIdentifier).isNull()
        assertThat(consumable.product?.productId).isEqualTo("coins_100")
        assertThat(consumable.product?.type).isEqualTo(ProductType.INAPP)
    }

    @Test
    fun `没有 plan identifier 且候选是订阅时宁可不匹配也不猜`() {
        val onlySubs = productsById(StoreProductBuilders.subscription("coins_100", "some-base"))
        val offerings = OfferingParser.createOfferings(response, onlySubs, emptyList())
        val consumable = requireNotNull(offerings["default"]?.get("consumable"))

        assertThat(consumable.product).isNull()
        assertThat(offerings.notFoundProductIds).contains("coins_100")
    }

    @Test
    fun `查不到的商品保留 package、product 为 null、进 notFoundProductIds（偏离 RC）`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, emptyList())
        val offering = requireNotNull(offerings["default"])

        // RC 会把这个 package 整个丢掉，宿主看不见「后端配了但 Play 上没有」。
        val missing = requireNotNull(offering["\$rd_missing"])
        assertThat(missing.product).isNull()
        assertThat(offerings.notFoundProductIds).contains("not_on_play")
        assertThat(offering.availablePackages).hasSize(4)
    }

    @Test
    fun `查询阶段就确定查不到的 id 会被合并进 notFoundProductIds`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, listOf("from_query"))
        assertThat(offerings.notFoundProductIds).contains("from_query", "not_on_play")
    }

    @Test
    fun `notFoundProductIds 去重`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, listOf("not_on_play"))
        assertThat(offerings.notFoundProductIds.count { it == "not_on_play" }).isEqualTo(1)
    }

    @Test
    fun `package identifier 映射到 PackageType`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, emptyList())
        val offering = requireNotNull(offerings["default"])

        assertThat(offering.monthly?.packageType).isEqualTo(PackageType.MONTHLY)
        assertThat(offering.annual?.packageType).isEqualTo(PackageType.ANNUAL)
        // 自定义标识一律落 CUSTOM，绝不因为不认识就报错。
        assertThat(offering["consumable"]?.packageType).isEqualTo(PackageType.CUSTOM)
    }

    @Test
    fun `current offering 解析正确`() {
        val offerings = OfferingParser.createOfferings(response, playProducts, emptyList())
        assertThat(offerings.currentOfferingIdentifier).isEqualTo("default")
        assertThat(offerings.current?.identifier).isEqualTo("default")
        assertThat(offerings.current?.serverDescription).isEqualTo("The default offering")
    }

    @Test
    fun `空响应不会抛`() {
        val empty = OfferingParser.createOfferings(JSONObject("{}"), emptyMap(), emptyList())
        assertThat(empty.all).isEmpty()
        assertThat(empty.current).isNull()
        assertThat(empty.notFoundProductIds).isEmpty()
    }

    @Test
    fun `缺 identifier 的 package 被跳过而不是让整次解析失败`() {
        val malformed = JSONObject(
            """
            {"current_offering_id":"default","offerings":[
              {"identifier":"default","description":"d","packages":[
                {"platform_product_identifier":"sub_premium"},
                {"identifier":"ok","platform_product_identifier":"coins_100"}
              ]}
            ]}
            """.trimIndent(),
        )
        val offerings = OfferingParser.createOfferings(malformed, playProducts, emptyList())
        assertThat(offerings["default"]?.availablePackages?.map { it.identifier }).containsExactly("ok")
    }
}
