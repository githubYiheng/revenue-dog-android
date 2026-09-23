package org.revdog.purchases.customerinfo

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import org.revdog.purchases.Logger
import org.revdog.purchases.OwnershipType
import org.revdog.purchases.PeriodType
import org.revdog.purchases.Store
import org.revdog.purchases.common.DateHelper
import org.revdog.purchases.common.EntitlementInfoHelper
import org.revdog.purchases.common.Iso8601Utils
import org.revdog.purchases.common.keysSequence
import org.revdog.purchases.common.objects
import org.revdog.purchases.common.optDate
import org.revdog.purchases.common.optNullableString
import org.revdog.purchases.networking.HTTPResult
import java.util.Date

/**
 * `GET /v1/subscribers/{id}` 响应 → [CustomerInfo]。
 * 结构对照 RC `common/CustomerInfoFactory.kt` + `common/EntitlementInfoFactories.kt`。
 *
 * 解析纪律（坑 42、契约 §2.2）：
 * - `subscriber` / `first_seen` / `original_app_user_id` **缺失就是真·破契约**，允许整体失败。
 * - 其余一切字段缺失、为 null、枚举值不认识 —— 一律降级，**绝不抛**。
 */
internal object CustomerInfoFactory {

    private const val KEY_SUBSCRIBER = "subscriber"
    private const val KEY_REQUEST_DATE = "request_date"
    private const val KEY_ENTITLEMENTS = "entitlements"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_NON_SUBSCRIPTIONS = "non_subscriptions"
    private const val KEY_FIRST_SEEN = "first_seen"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_ORIGINAL_APP_USER_ID = "original_app_user_id"
    private const val KEY_MANAGEMENT_URL = "management_url"
    private const val KEY_ORIGINAL_PURCHASE_DATE = "original_purchase_date"
    private const val KEY_ACCOUNT_TOKEN = "account_token"
    private const val KEY_PRODUCT_IDENTIFIER = "product_identifier"
    private const val KEY_PRODUCT_PLAN_IDENTIFIER = "product_plan_identifier"
    private const val KEY_EXPIRES_DATE = "expires_date"
    private const val KEY_GRACE_PERIOD_EXPIRES_DATE = "grace_period_expires_date"
    private const val KEY_PURCHASE_DATE = "purchase_date"
    private const val KEY_STORE = "store"
    private const val KEY_IS_SANDBOX = "is_sandbox"
    private const val KEY_PERIOD_TYPE = "period_type"
    private const val KEY_OWNERSHIP_TYPE = "ownership_type"
    private const val KEY_UNSUBSCRIBE_DETECTED_AT = "unsubscribe_detected_at"
    private const val KEY_BILLING_ISSUES_DETECTED_AT = "billing_issues_detected_at"
    private const val KEY_REFUNDED_AT = "refunded_at"
    private const val KEY_AUTO_RESUME_DATE = "auto_resume_date"
    private const val KEY_STORE_TRANSACTION_ID = "store_transaction_id"
    private const val KEY_ID = "id"
    private const val KEY_DISPLAY_NAME = "display_name"

    /** Google 商品的复合 key 分隔符：`productId:basePlanId`。 */
    const val SUBS_ID_BASE_PLAN_ID_SEPARATOR: String = ":"

    @Throws(JSONException::class)
    fun buildCustomerInfo(httpResult: HTTPResult): CustomerInfo =
        buildCustomerInfo(httpResult.body, httpResult.requestDate, loadedFromCache = false)

    @Suppress("LongMethod")
    @Throws(JSONException::class)
    fun buildCustomerInfo(
        body: JSONObject,
        overrideRequestDate: Date?,
        loadedFromCache: Boolean,
        now: Date = Date(),
    ): CustomerInfo {
        val subscriber = body.getJSONObject(KEY_SUBSCRIBER)

        // 契约 §1.7：`request_date` 是必填。头里的服务端时间优先（它更接近「这次响应的时刻」）。
        val requestDate = overrideRequestDate
            ?: subscriber.parentRequestDate(body)
            ?: error("response is missing request_date")

        val subscriptionsJson = subscriber.optJSONObject(KEY_SUBSCRIPTIONS) ?: JSONObject()
        val nonSubscriptionsJson = subscriber.optJSONObject(KEY_NON_SUBSCRIPTIONS) ?: JSONObject()
        val entitlementsJson = subscriber.optJSONObject(KEY_ENTITLEMENTS) ?: JSONObject()

        val subscriptions = subscriptionsJson.keysSequence().associateWith { productId ->
            subscriptionsJson.getJSONObject(productId).toSubscriptionInfo(productId, requestDate, now)
        }
        val nonSubscriptions = nonSubscriptionsJson.keysSequence().associateWith { productId ->
            nonSubscriptionsJson.getJSONArray(productId).objects().map { it.toNonSubscription(productId) }
        }

        // 每个一次性商品**只有最后一笔**参与权益关联（RC 同款）。
        val nonSubscriptionsLatest = JSONObject().apply {
            nonSubscriptionsJson.keysSequence().forEach { productId ->
                nonSubscriptionsJson.getJSONArray(productId).objects().lastOrNull()?.let { put(productId, it) }
            }
        }

        val entitlements = entitlementsJson.buildEntitlementInfos(
            subscriptions = subscriptionsJson,
            nonSubscriptionsLatest = nonSubscriptionsLatest,
            requestDate = requestDate,
            now = now,
        )

        return CustomerInfo(
            entitlements = entitlements,
            subscriptions = subscriptions,
            nonSubscriptions = nonSubscriptions,
            allExpirationDatesByProduct = subscriptionsJson.parseDates(KEY_EXPIRES_DATE),
            allPurchaseDatesByProduct =
            subscriptionsJson.parseDates(KEY_PURCHASE_DATE) + nonSubscriptionsLatest.parseDates(KEY_PURCHASE_DATE),
            requestDate = requestDate,
            // 契约 §2.2：`first_seen` 不可缺失。
            firstSeen = subscriber.optDate(KEY_FIRST_SEEN),
            lastSeen = subscriber.optDate(KEY_LAST_SEEN),
            originalAppUserId = subscriber.getString(KEY_ORIGINAL_APP_USER_ID),
            managementURL = subscriber.optNullableString(KEY_MANAGEMENT_URL)?.let { Uri.parse(it) },
            originalPurchaseDate = subscriber.optDate(KEY_ORIGINAL_PURCHASE_DATE),
            accountToken = subscriber.optNullableString(KEY_ACCOUNT_TOKEN),
            rawData = body,
            loadedFromCache = loadedFromCache,
        )
    }

    private fun JSONObject.parentRequestDate(body: JSONObject): Date? =
        body.optDate(KEY_REQUEST_DATE) ?: optDate(KEY_REQUEST_DATE)

    /**
     * 结构对照 RC `CustomerInfoFactory.parseDates`：Google 下用 `product_plan_identifier`
     * 把 key 重写成 `productId:basePlanId`。同一个订阅商品可以有多个 base plan，
     * 不重写 key 它们会互相覆盖。
     */
    private fun JSONObject.parseDates(jsonKey: String): Map<String, Date?> =
        keysSequence().associate { productId ->
            val productJson = getJSONObject(productId)
            val basePlanId = productJson.optNullableString(KEY_PRODUCT_PLAN_IDENTIFIER)
            val key = basePlanId?.let { "$productId$SUBS_ID_BASE_PLAN_ID_SEPARATOR$it" } ?: productId
            key to productJson.optDate(jsonKey)
        }

    /**
     * `isActive` / `willRenew` 与 [buildEntitlementInfo] 走**同一组 helper**（`DateHelper.isDateActive` +
     * `EntitlementInfoHelper.isActive` / `getWillRenew`），`requestDate` / `now` 同样由调用方传入 ——
     * 同一份响应里 entitlement 与它关联的 subscription 必然同判定。
     */
    private fun JSONObject.toSubscriptionInfo(productId: String, requestDate: Date, now: Date): SubscriptionInfo {
        val expiresDate = optDate(KEY_EXPIRES_DATE)
        val store = Store.fromString(optNullableString(KEY_STORE))
        val periodType = PeriodType.fromString(optNullableString(KEY_PERIOD_TYPE))
        val unsubscribeDetectedAt = optDate(KEY_UNSUBSCRIBE_DETECTED_AT)
        val billingIssuesDetectedAt = optDate(KEY_BILLING_ISSUES_DETECTED_AT)
        val gracePeriodExpiresDate = optDate(KEY_GRACE_PERIOD_EXPIRES_DATE)
        return SubscriptionInfo(
            productIdentifier = productId,
            purchaseDate = optDate(KEY_PURCHASE_DATE),
            originalPurchaseDate = optDate(KEY_ORIGINAL_PURCHASE_DATE),
            expiresDate = expiresDate,
            store = store,
            isSandbox = optBoolean(KEY_IS_SANDBOX, false),
            periodType = periodType,
            ownershipType = OwnershipType.fromString(optNullableString(KEY_OWNERSHIP_TYPE)),
            unsubscribeDetectedAt = unsubscribeDetectedAt,
            billingIssuesDetectedAt = billingIssuesDetectedAt,
            gracePeriodExpiresDate = gracePeriodExpiresDate,
            refundedAt = optDate(KEY_REFUNDED_AT),
            autoResumeDate = optDate(KEY_AUTO_RESUME_DATE),
            storeTransactionId = optNullableString(KEY_STORE_TRANSACTION_ID),
            productPlanIdentifier = optNullableString(KEY_PRODUCT_PLAN_IDENTIFIER),
            managementURL = optNullableString(KEY_MANAGEMENT_URL)?.let { Uri.parse(it) },
            isActive = EntitlementInfoHelper.isActive(
                DateHelper.isDateActive(expiresDate, requestDate, now),
                gracePeriodExpiresDate,
                requestDate,
            ),
            willRenew = EntitlementInfoHelper.getWillRenew(
                store,
                expiresDate,
                unsubscribeDetectedAt,
                billingIssuesDetectedAt,
                periodType,
            ),
            displayName = optNullableString(KEY_DISPLAY_NAME),
        )
    }

    private fun JSONObject.toNonSubscription(productId: String): NonSubscriptionTransaction =
        NonSubscriptionTransaction(
            transactionIdentifier = optNullableString(KEY_ID).orEmpty(),
            productIdentifier = productId,
            purchaseDate = optDate(KEY_PURCHASE_DATE),
            originalPurchaseDate = optDate(KEY_ORIGINAL_PURCHASE_DATE),
            store = Store.fromString(optNullableString(KEY_STORE)),
            storeTransactionId = optNullableString(KEY_STORE_TRANSACTION_ID),
            isSandbox = optBoolean(KEY_IS_SANDBOX, false),
        )

    /**
     * 结构对照 RC `buildEntitlementInfos`：权益的 `store` / `periodType` / `isSandbox` 等
     * **一律从关联的 subscription（或最后一笔 non-subscription）里取**，
     * 契约 §2.2 明确禁止后端往 entitlement 里塞这些字段。
     *
     * 关联不上的 entitlement（product_identifier 既不在 subscriptions 也不在 non_subscriptions）
     * 被整条丢弃 —— 这与 RC 一致：没有商品就算不出周期与商店，给宿主一个半残对象更危险。
     */
    private fun JSONObject.buildEntitlementInfos(
        subscriptions: JSONObject,
        nonSubscriptionsLatest: JSONObject,
        requestDate: Date,
        now: Date,
    ): EntitlementInfos {
        val all = mutableMapOf<String, EntitlementInfo>()
        keysSequence().forEach { entitlementId ->
            val entitlement = getJSONObject(entitlementId)
            val productIdentifier = entitlement.optNullableString(KEY_PRODUCT_IDENTIFIER) ?: return@forEach
            val productData = when {
                subscriptions.has(productIdentifier) -> subscriptions.getJSONObject(productIdentifier)
                nonSubscriptionsLatest.has(productIdentifier) ->
                    nonSubscriptionsLatest.getJSONObject(productIdentifier)
                else -> {
                    Logger.warn {
                        "权益 $entitlementId 的商品 $productIdentifier 既不在 subscriptions 也不在 " +
                            "non_subscriptions，跳过"
                    }
                    return@forEach
                }
            }
            all[entitlementId] = entitlement.buildEntitlementInfo(
                identifier = entitlementId,
                productIdentifier = productIdentifier,
                productData = productData,
                requestDate = requestDate,
                now = now,
            )
        }
        return EntitlementInfos(all)
    }

    private fun JSONObject.buildEntitlementInfo(
        identifier: String,
        productIdentifier: String,
        productData: JSONObject,
        requestDate: Date,
        now: Date,
    ): EntitlementInfo {
        val expirationDate = optDate(KEY_EXPIRES_DATE)
        val gracePeriodExpiresDate = optDate(KEY_GRACE_PERIOD_EXPIRES_DATE)
        val unsubscribeDetectedAt = productData.optDate(KEY_UNSUBSCRIBE_DETECTED_AT)
        val billingIssueDetectedAt = productData.optDate(KEY_BILLING_ISSUES_DETECTED_AT)
        val periodType = PeriodType.fromString(productData.optNullableString(KEY_PERIOD_TYPE))
        val store = Store.fromString(productData.optNullableString(KEY_STORE))

        val dateActive = DateHelper.isDateActive(expirationDate, requestDate, now)
        if (!dateActive.isActive && !dateActive.inGracePeriod) {
            Logger.warn {
                "权益 $identifier 已过期且超出 3 天 grace（expires=$expirationDate, requestDate=$requestDate）"
            }
        }
        // 口径见 `EntitlementInfoHelper.isActive`（与 `SubscriptionInfo.isActive` 共用）。
        val isActive = EntitlementInfoHelper.isActive(dateActive, gracePeriodExpiresDate, requestDate)

        return EntitlementInfo(
            identifier = identifier,
            isActive = isActive,
            willRenew = EntitlementInfoHelper.getWillRenew(
                store,
                expirationDate,
                unsubscribeDetectedAt,
                billingIssueDetectedAt,
                periodType,
            ),
            periodType = periodType,
            latestPurchaseDate = optDate(KEY_PURCHASE_DATE),
            originalPurchaseDate = productData.optDate(KEY_ORIGINAL_PURCHASE_DATE),
            expirationDate = expirationDate,
            store = store,
            productIdentifier = productIdentifier,
            productPlanIdentifier = optNullableString(KEY_PRODUCT_PLAN_IDENTIFIER)
                ?: productData.optNullableString(KEY_PRODUCT_PLAN_IDENTIFIER),
            isSandbox = productData.optBoolean(KEY_IS_SANDBOX, false),
            unsubscribeDetectedAt = unsubscribeDetectedAt,
            billingIssueDetectedAt = billingIssueDetectedAt,
            ownershipType = OwnershipType.fromString(productData.optNullableString(KEY_OWNERSHIP_TYPE)),
        )
    }

    /** 缓存回读用：requestDate 来自缓存元数据而不是响应体。 */
    fun formatRequestDate(date: Date): String = Iso8601Utils.format(date)
}
