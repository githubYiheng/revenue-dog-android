package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.models.OfferPaymentMode
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.offerings.OfferingParser
import org.revdog.purchases.support.Fixtures
import org.revdog.purchases.support.StoreProductBuilders
import org.robolectric.RobolectricTestRunner

/**
 * 测试用模型工厂（0.2.0，主代理裁定 7）：后端 JSON 走与真实路径同一个解析器；
 * 商店侧模型按字段构造，派生属性由既有规则推导。
 */
@OptIn(InternalRevenueDogAPI::class)
@RunWith(RobolectricTestRunner::class)
class RevenueDogTestModelsTest {

    @Test
    fun `customerInfoFromJson 与直接解析结果相等`() {
        for (fixture in listOf(Fixtures.SUBSCRIBER_RESPONSE, Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS)) {
            val viaFactory = RevenueDogTestModels.customerInfoFromJson(fixture)
            val direct = CustomerInfoFactory.buildCustomerInfo(JSONObject(fixture), null, loadedFromCache = false)

            assertThat(viaFactory).isEqualTo(direct)
            // equals 刻意不比 requestDate / loadedFromCache（去重口径），这里单独锁住。
            assertThat(viaFactory.requestDate).isEqualTo(direct.requestDate)
            assertThat(viaFactory.loadedFromCache).isFalse()
        }
    }

    @Test
    fun `customerInfoFromJson 的 requestDate 取响应体`() {
        val info = RevenueDogTestModels.customerInfoFromJson(Fixtures.SUBSCRIBER_RESPONSE)
        assertThat(info.requestDate.time).isEqualTo(1564162810000L) // 2019-07-26T17:40:10Z
    }

    @Test
    fun `offeringsFromJson 与直接解析结果相等`() {
        val products = listOf(
            StoreProductBuilders.subscription("sub_premium", "monthly-base", billingPeriod = "P1M"),
            StoreProductBuilders.subscription("sub_premium", "annual-base", billingPeriod = "P1Y"),
            StoreProductBuilders.inApp("coins_100"),
        )

        val viaFactory = RevenueDogTestModels.offeringsFromJson(Fixtures.OFFERINGS_RESPONSE, products)
        val direct = OfferingParser.createOfferings(
            JSONObject(Fixtures.OFFERINGS_RESPONSE),
            products.groupBy { it.productId },
            emptyList(),
        )

        assertThat(viaFactory).isEqualTo(direct)
        assertThat(viaFactory.current?.get("\$rc_monthly")?.product?.id).isEqualTo("sub_premium:monthly-base")
        assertThat(viaFactory.notFoundProductIds).containsExactly("not_on_play")
    }

    private fun phase(iso: String, micros: Long, mode: RecurrenceMode, cycles: Int?) =
        RevenueDogTestModels.pricingPhase(
            billingPeriod = iso,
            recurrenceMode = mode,
            billingCycleCount = cycles,
            priceAmountMicros = micros,
            priceCurrencyCode = "USD",
            formattedPrice = if (micros == 0L) "$0.00" else "$" + "%.2f".format(micros / 1_000_000.0),
        )

    @Test
    fun `storeProduct 按字段构造，id 与 defaultOption 与 freePhase 由既有规则推导`() {
        val fullPrice = phase("P1M", 4_990_000L, RecurrenceMode.INFINITE_RECURRING, null)
        val base = RevenueDogTestModels.subscriptionOption(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            offerId = null,
            pricingPhases = listOf(fullPrice),
            tags = emptyList(),
            offerToken = "token-base",
        )
        val shortTrial = RevenueDogTestModels.subscriptionOption(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            offerId = "trial-3d",
            pricingPhases = listOf(phase("P3D", 0L, RecurrenceMode.FINITE_RECURRING, 1), fullPrice),
            tags = emptyList(),
            offerToken = "token-trial-3d",
        )
        val longTrial = RevenueDogTestModels.subscriptionOption(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            offerId = "trial-1w",
            pricingPhases = listOf(phase("P1W", 0L, RecurrenceMode.FINITE_RECURRING, 1), fullPrice),
            tags = emptyList(),
            offerToken = "token-trial-1w",
        )
        val intro = RevenueDogTestModels.subscriptionOption(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            offerId = "intro",
            pricingPhases = listOf(phase("P1M", 990_000L, RecurrenceMode.FINITE_RECURRING, 2), fullPrice),
            tags = emptyList(),
            offerToken = "token-intro",
        )

        val product = RevenueDogTestModels.storeProduct(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            type = ProductType.SUBS,
            priceAmountMicros = 4_990_000L,
            priceCurrencyCode = "USD",
            formattedPrice = "$4.99",
            name = "Premium",
            title = "Premium (App)",
            description = "desc",
            period = "P1M",
            subscriptionOptions = listOf(base, shortTrial, longTrial, intro),
        )

        assertThat(product.id).isEqualTo("sub_premium:monthly-base")
        assertThat(product.period).isEqualTo(Period.create("P1M"))
        assertThat(product.price.amountMicros).isEqualTo(4_990_000L)
        assertThat(product.price.currencyCode).isEqualTo("USD")
        // 默认选项：最长免费试用优先（RC 同款规则，`SubscriptionOptions.defaultOffer`）。
        assertThat(product.defaultOption).isEqualTo(longTrial)
        assertThat(product.defaultOption?.id).isEqualTo("monthly-base:trial-1w")
        val freePhase = requireNotNull(product.defaultOption?.freePhase)
        assertThat(freePhase.billingPeriod.iso8601).isEqualTo("P1W")
        assertThat(freePhase.offerPaymentMode).isEqualTo(OfferPaymentMode.FREE_TRIAL)
        assertThat(product.subscriptionOptions?.basePlan).isEqualTo(base)
        assertThat(intro.introPhase?.price?.amountMicros).isEqualTo(990_000L)
    }

    @Test
    fun `只有 base plan 时 defaultOption 就是它，且与测试构造器造出的商品相等`() {
        val base = RevenueDogTestModels.subscriptionOption(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            offerId = null,
            pricingPhases = listOf(phase("P1M", 4_990_000L, RecurrenceMode.INFINITE_RECURRING, null)),
            tags = emptyList(),
            offerToken = "token-sub_premium-monthly-base",
        )
        val product = RevenueDogTestModels.storeProduct(
            productId = "sub_premium",
            basePlanId = "monthly-base",
            type = ProductType.SUBS,
            priceAmountMicros = 4_990_000L,
            priceCurrencyCode = "USD",
            formattedPrice = "$4.99",
            name = "sub_premium",
            title = "sub_premium (monthly-base)",
            description = "desc",
            period = "P1M",
            subscriptionOptions = listOf(base),
        )

        assertThat(product.defaultOption).isEqualTo(base)
        assertThat(product.defaultOption?.freePhase).isNull()
        assertThat(product).isEqualTo(StoreProductBuilders.subscription("sub_premium", "monthly-base"))
    }

    @Test
    fun `一次性商品：无 basePlan、无 period、无 defaultOption`() {
        val product = RevenueDogTestModels.storeProduct(
            productId = "coins_100",
            basePlanId = null,
            type = ProductType.INAPP,
            priceAmountMicros = 990_000L,
            priceCurrencyCode = "USD",
            formattedPrice = "$0.99",
            name = "coins_100",
            title = "coins_100",
            description = "desc",
            period = null,
            subscriptionOptions = null,
        )

        assertThat(product.id).isEqualTo("coins_100")
        assertThat(product.period).isNull()
        assertThat(product.defaultOption).isNull()
        assertThat(product).isEqualTo(StoreProductBuilders.inApp("coins_100"))
    }

    @Test
    fun `storeTransaction 按字段构造`() {
        val transaction = RevenueDogTestModels.storeTransaction(
            orderId = "GPA.1234-5678-9012-34567",
            productIds = listOf("sub_premium"),
            type = ProductType.SUBS,
            purchaseTime = 1_789_000_000_000L,
            purchaseToken = "token-abc",
            purchaseState = PurchaseState.PURCHASED,
            isAutoRenewing = true,
            isAcknowledged = false,
            presentedOfferingIdentifier = "default",
            subscriptionOptionId = "monthly-base",
            replacementMode = null,
        )

        assertThat(transaction.orderId).isEqualTo("GPA.1234-5678-9012-34567")
        assertThat(transaction.productIds).containsExactly("sub_premium")
        assertThat(transaction.type).isEqualTo(ProductType.SUBS)
        assertThat(transaction.purchaseTime).isEqualTo(1_789_000_000_000L)
        assertThat(transaction.purchaseToken).isEqualTo("token-abc")
        assertThat(transaction.purchaseState).isEqualTo(PurchaseState.PURCHASED)
        assertThat(transaction.isAutoRenewing).isTrue()
        assertThat(transaction.isAcknowledged).isFalse()
        assertThat(transaction.presentedOfferingIdentifier).isEqualTo("default")
        assertThat(transaction.subscriptionOptionId).isEqualTo("monthly-base")
        assertThat(transaction.replacementMode).isNull()
    }
}
