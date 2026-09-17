package org.revdog.purchases

import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.common.DateHelper
import org.revdog.purchases.common.EntitlementInfoHelper
import org.revdog.purchases.common.Iso8601Utils
import org.revdog.purchases.customerinfo.CustomerInfoFactory
import org.revdog.purchases.support.Fixtures
import org.robolectric.RobolectricTestRunner
import java.util.Date
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
class CustomerInfoFactoryTest {

    private fun parse(json: String, now: Date = Date(REQUEST_DATE_MS)) =
        CustomerInfoFactory.buildCustomerInfo(JSONObject(json), null, loadedFromCache = false, now = now)

    // region 契约 §2.2 完整示例

    @Test
    fun `解析契约 §2_2 的完整响应示例`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE)

        assertThat(info.originalAppUserId).isEqualTo("XXX-XXXXX-XXXXX-XX")
        assertThat(info.firstSeen).isEqualTo(Iso8601Utils.parseOrNull("2019-02-21T00:08:41Z"))
        assertThat(info.lastSeen).isEqualTo(Iso8601Utils.parseOrNull("2019-07-26T17:40:10Z"))
        assertThat(info.requestDate).isEqualTo(Iso8601Utils.parseOrNull("2019-07-26T17:40:10Z"))
        assertThat(info.managementURL.toString())
            .isEqualTo("https://play.google.com/store/account/subscriptions")
        assertThat(info.accountToken).isEqualTo("0123456789abcdef0123456789abcdef")
        assertThat(info.originalPurchaseDate).isEqualTo(Iso8601Utils.parseOrNull("2019-01-30T23:54:10Z"))
        assertThat(info.subscriptions.keys).containsExactlyInAnyOrder("annual", "rc_promo_pro_cat_monthly")
        assertThat(info.nonSubscriptions.keys).containsExactly("onetime")
        assertThat(info.allPurchasedProductIdentifiers)
            .containsExactlyInAnyOrder("annual", "rc_promo_pro_cat_monthly", "onetime")
    }

    @Test
    fun `entitlement 的 store 与 periodType 从关联的 subscription 算出来`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE)
        val premium = requireNotNull(info.entitlements["premium"])

        // 契约 §2.2：entitlement 下行只有 4 个字段，其余一律关联算出。
        assertThat(premium.store).isEqualTo(Store.PLAY_STORE)
        assertThat(premium.periodType).isEqualTo(PeriodType.NORMAL)
        assertThat(premium.ownershipType).isEqualTo(OwnershipType.PURCHASED)
        assertThat(premium.isSandbox).isTrue()
        assertThat(premium.productIdentifier).isEqualTo("annual")
        assertThat(premium.productPlanIdentifier).isEqualTo("annual-base")
        assertThat(premium.unsubscribeDetectedAt).isNotNull
    }

    @Test
    fun `一次性购买授予的权益是终身的`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE)
        val proCat = requireNotNull(info.entitlements["pro_cat"])

        assertThat(proCat.expirationDate).isNull()
        assertThat(proCat.isLifetime).isTrue()
        assertThat(proCat.isActive).isTrue()
        // 终身 = 不会续订（willRenew 五项否定的第二项）。
        assertThat(proCat.willRenew).isFalse()
        assertThat(proCat.store).isEqualTo(Store.PLAY_STORE)
    }

    @Test
    fun `已退订的订阅 willRenew 为 false 但仍然有效到期末`() {
        // 在到期前一天求值：unsubscribe_detected_at 有值 → willRenew false，但 isActive 仍 true。
        val beforeExpiry = Date(Iso8601Utils.parseOrNull("2019-08-13T00:00:00Z")!!.time)
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE, now = beforeExpiry)
        val premium = requireNotNull(info.entitlements["premium"])

        assertThat(premium.willRenew).isFalse()
        assertThat(premium.isActive).isTrue()
    }

    // endregion

    // region Google 独有：product_plan_identifier 重写 key（考古 §2.10）

    @Test
    fun `allExpirationDatesByProduct 的 key 在 Google 下是 productId 冒号 basePlanId`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE)

        assertThat(info.allExpirationDatesByProduct.keys).contains("annual:annual-base")
        // 没有 base plan 的（促销授予）保持裸 productId。
        assertThat(info.allExpirationDatesByProduct.keys).contains("rc_promo_pro_cat_monthly")
        assertThat(info.allExpirationDatesByProduct.keys).doesNotContain("annual")
    }

    @Test
    fun `allPurchaseDatesByProduct 同时含订阅与一次性`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE)
        assertThat(info.allPurchaseDatesByProduct.keys).contains("annual:annual-base", "onetime")
    }

    @Test
    fun `auto_resume_date 被解析进 SubscriptionInfo`() {
        val json = JSONObject(Fixtures.SUBSCRIBER_RESPONSE).apply {
            getJSONObject("subscriber")
                .getJSONObject("subscriptions")
                .getJSONObject("annual")
                .put("auto_resume_date", "2019-09-01T00:00:00Z")
        }
        val info = CustomerInfoFactory.buildCustomerInfo(json, null, loadedFromCache = false)
        assertThat(info.subscriptions["annual"]?.autoResumeDate)
            .isEqualTo(Iso8601Utils.parseOrNull("2019-09-01T00:00:00Z"))
    }

    // endregion

    // region 容错（坑 42）

    @Test
    fun `未知枚举值与未知字段一律容忍`() {
        val info = parse(Fixtures.SUBSCRIBER_RESPONSE_WITH_UNKNOWN_ENUMS, now = Date(NOW_2026_MS))
        val subscription = requireNotNull(info.subscriptions["sub_a"])

        assertThat(subscription.store).isEqualTo(Store.UNKNOWN)
        // 未知 periodType 落 NORMAL（对照 RC `optPeriodType` 的 else 分支）。
        assertThat(subscription.periodType).isEqualTo(PeriodType.NORMAL)
        assertThat(subscription.ownershipType).isEqualTo(OwnershipType.UNKNOWN)
        assertThat(info.entitlements["pro"]?.isActive).isTrue()
    }

    @Test
    fun `缺 subscriptions 与 non_subscriptions 时按空处理`() {
        val info = parse(
            """{"request_date":"2026-09-18T00:00:00Z","subscriber":{
                 "original_app_user_id":"u","first_seen":"2026-01-01T00:00:00Z"}}""",
            now = Date(NOW_2026_MS),
        )
        assertThat(info.subscriptions).isEmpty()
        assertThat(info.nonSubscriptions).isEmpty()
        assertThat(info.entitlements.all).isEmpty()
    }

    @Test
    fun `关联不到商品的 entitlement 被丢弃而不是给出半残对象`() {
        val info = parse(
            """{"request_date":"2026-09-18T00:00:00Z","subscriber":{
                 "original_app_user_id":"u","first_seen":"2026-01-01T00:00:00Z",
                 "entitlements":{"ghost":{"expires_date":null,"grace_period_expires_date":null,
                   "product_identifier":"nowhere","purchase_date":"2026-01-01T00:00:00Z"}},
                 "subscriptions":{},"non_subscriptions":{}}}""",
            now = Date(NOW_2026_MS),
        )
        assertThat(info.entitlements.all).isEmpty()
    }

    // endregion

    // region 3 天 grace 与 willRenew（与 iOS 逐行同口径）

    @Test
    fun `grace 内用服务端时间判定到期`() {
        val requestDate = Date(REQUEST_DATE_MS)
        val expires = Date(REQUEST_DATE_MS + 1.hours.inWholeMilliseconds)
        // 本地钟被拨到 10 天后：grace 内（响应只有 1 天前）→ 仍然用服务端时间 → 有效。
        val now = Date(REQUEST_DATE_MS + 1.days.inWholeMilliseconds)

        val active = DateHelper.isDateActive(expires, requestDate, now)
        assertThat(active.inGracePeriod).isTrue()
        assertThat(active.isActive).isTrue()
    }

    @Test
    fun `超过 3 天 grace 后回落本地时钟`() {
        val requestDate = Date(REQUEST_DATE_MS)
        val expires = Date(REQUEST_DATE_MS + 1.hours.inWholeMilliseconds)
        val now = Date(REQUEST_DATE_MS + 4.days.inWholeMilliseconds)

        val active = DateHelper.isDateActive(expires, requestDate, now)
        assertThat(active.inGracePeriod).isFalse()
        // 回落本地钟：4 天后早就过期了。
        assertThat(active.isActive).isFalse()
    }

    @Test
    fun `本地钟被拨到过去时仍然信服务端时间`() {
        val requestDate = Date(REQUEST_DATE_MS)
        val expires = Date(REQUEST_DATE_MS - 1.hours.inWholeMilliseconds) // 已过期
        val now = Date(REQUEST_DATE_MS - 30.days.inWholeMilliseconds) // 用户把表拨回一个月前

        val active = DateHelper.isDateActive(expires, requestDate, now)
        // `now - requestDate` 为负 → 仍在 grace 内 → 用服务端时间 → **不能**因为拨表就白给权益。
        assertThat(active.inGracePeriod).isTrue()
        assertThat(active.isActive).isFalse()
    }

    @Test
    fun `没有到期时间就是终身有效`() {
        val active = DateHelper.isDateActive(null, Date(REQUEST_DATE_MS), Date(NOW_2026_MS))
        assertThat(active.isActive).isTrue()
    }

    @Test
    fun `willRenew 五项否定`() {
        val future = Date(NOW_2026_MS + 30.days.inWholeMilliseconds)

        // 正常续订中。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PLAY_STORE, future, null, null, PeriodType.NORMAL),
        ).isTrue()
        // ① 促销授予。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PROMOTIONAL, future, null, null, PeriodType.NORMAL),
        ).isFalse()
        // ② 终身。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PLAY_STORE, null, null, null, PeriodType.NORMAL),
        ).isFalse()
        // ③ 已退订。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PLAY_STORE, future, Date(NOW_2026_MS), null, PeriodType.NORMAL),
        ).isFalse()
        // ④ 计费问题。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PLAY_STORE, future, null, Date(NOW_2026_MS), PeriodType.NORMAL),
        ).isFalse()
        // ⑤ 预付费套餐（Android 独有）。
        assertThat(
            EntitlementInfoHelper.getWillRenew(Store.PLAY_STORE, future, null, null, PeriodType.PREPAID),
        ).isFalse()
    }

    // endregion

    // region ISO 8601

    @Test
    fun `秒精度与毫秒精度都能解析`() {
        assertThat(Iso8601Utils.parseOrNull("2019-07-26T17:40:10Z")).isNotNull
        assertThat(Iso8601Utils.parseOrNull("2019-07-26T17:40:10.884Z")).isNotNull
        assertThat(Iso8601Utils.parseOrNull("2019-07-26T17:40:10+00:00")).isNotNull
    }

    @Test
    fun `解析不了的日期返回 null 而不是抛`() {
        assertThat(Iso8601Utils.parseOrNull("not-a-date")).isNull()
        assertThat(Iso8601Utils.parseOrNull("")).isNull()
    }

    // endregion

    private companion object {
        /** 2019-07-26T17:40:10Z。 */
        const val REQUEST_DATE_MS = 1564162810000L
        /** 2026-09-18T00:00:00Z。 */
        const val NOW_2026_MS = 1789689600000L
    }
}
