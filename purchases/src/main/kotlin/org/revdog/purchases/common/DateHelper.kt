package org.revdog.purchases.common

import org.revdog.purchases.Logger
import org.revdog.purchases.PeriodType
import org.revdog.purchases.Store
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class DateActive(val isActive: Boolean, val inGracePeriod: Boolean)

/**
 * `requestDate` 3 天 grace。结构对照 RC `utils/DateHelper.kt`，与 iOS
 * `EntitlementGracePolicy` **逐行同口径**（两端必须同判定，否则同一个用户在两端权益不一致）。
 *
 * - 服务端时间之后 3 天内：一律用服务端时间，不信本地钟（抗「往回拨表」薅羊毛）。
 * - 超过 3 天：回落本地钟（防「永久离线白嫖」）。
 */
internal object DateHelper {

    val ENTITLEMENT_GRACE_PERIOD: Duration = 3.days

    fun isDateActive(
        expirationDate: Date?,
        requestDate: Date,
        now: Date = Date(),
        gracePeriod: Duration = ENTITLEMENT_GRACE_PERIOD,
    ): DateActive {
        // 终身权益：没有到期时间 = 永远有效。
        if (expirationDate == null) return DateActive(isActive = true, inGracePeriod = true)

        val inGracePeriod = (now.time - requestDate.time) <= gracePeriod.inWholeMilliseconds
        val referenceDate = if (inGracePeriod) requestDate else now
        return DateActive(isActive = expirationDate.after(referenceDate), inGracePeriod = inGracePeriod)
    }
}

/**
 * `willRenew` 的口径。结构对照 RC `utils/EntitlementInfoHelper.kt`：**五项否定**。
 * 第五项 `isPrepaid` 是 Android 独有 —— 预付费套餐（prepaid plan）不会自动续订。
 */
internal object EntitlementInfoHelper {

    /**
     * `isActive` 的口径（`EntitlementInfo` 与 `SubscriptionInfo` 共用这一处，0.2.0 抽出）。
     *
     * 宽限期（后端下发的 grace_period_expires_date）本身就是「还有效」的信号，
     * 与本地时钟 grace 是两件事：前者是计费宽限，后者是抗改表。与 iOS `isActive` 同口径。
     */
    fun isActive(dateActive: DateActive, gracePeriodExpiresDate: Date?, requestDate: Date): Boolean =
        dateActive.isActive || (gracePeriodExpiresDate != null && gracePeriodExpiresDate.after(requestDate))

    fun getWillRenew(
        store: Store,
        expirationDate: Date?,
        unsubscribeDetectedAt: Date?,
        billingIssueDetectedAt: Date?,
        periodType: PeriodType?,
    ): Boolean {
        val isPromo = store == Store.PROMOTIONAL
        val isLifetime = expirationDate == null
        val hasUnsubscribed = unsubscribeDetectedAt != null
        val hasBillingIssues = billingIssueDetectedAt != null
        val isPrepaid = periodType == PeriodType.PREPAID
        return !(isPromo || isLifetime || hasUnsubscribed || hasBillingIssues || isPrepaid)
    }
}

/**
 * 缓存 TTL。结构对照 RC `common/caching/DateExtensions.kt`，与 iOS `CacheTTL` **完全一致**
 * （前台 5 分钟 / 后台 25 小时）。
 */
internal object CacheDurations {

    val FOREGROUND: Duration = 5.minutes
    val BACKGROUND: Duration = 25.hours

    fun cacheDuration(appInBackground: Boolean): Duration = if (appInBackground) BACKGROUND else FOREGROUND

    @Suppress("ReturnCount")
    fun isStale(
        lastUpdated: Date?,
        appInBackground: Boolean,
        now: Date = Date(),
    ): Boolean {
        if (lastUpdated == null) return true
        val age = (now.time - lastUpdated.time).milliseconds
        // 本地钟被拨到过去 → age < 0 → 视为过期，宁可多刷一次（与 iOS 同款处理）。
        if (age.isNegative()) {
            Logger.debug { "缓存时间戳在未来（本地钟被改过？），按过期处理" }
            return true
        }
        return age >= cacheDuration(appInBackground)
    }
}
