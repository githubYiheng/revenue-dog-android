package org.revdog.purchases.customerinfo

import android.net.Uri
import dev.drewhamilton.poko.Poko
import org.json.JSONObject
import org.revdog.purchases.OwnershipType
import org.revdog.purchases.PeriodType
import org.revdog.purchases.Store
import java.util.Date

/**
 * 单个权益的状态。结构与字段对照 RC `EntitlementInfo.kt`，与 iOS `EntitlementInfo` 对齐。
 *
 * 注意契约 §2.2：`entitlements[<id>]` 下行**只有 4 个字段**，
 * `store` / `periodType` / `ownershipType` / `isSandbox` 等一律从关联的
 * `subscriptions[productIdentifier]`（或 `non_subscriptions` 的最后一笔）算出来。
 */
@Poko
public class EntitlementInfo internal constructor(
    public val identifier: String,
    /** 已按 3 天 grace 判定（`DateHelper.isDateActive`）。 */
    public val isActive: Boolean,
    /** 五项否定 + `isPrepaid`（`EntitlementInfoHelper.getWillRenew`）。 */
    public val willRenew: Boolean,
    public val periodType: PeriodType,
    public val latestPurchaseDate: Date?,
    public val originalPurchaseDate: Date?,
    /** 终身权益为 `null`。 */
    public val expirationDate: Date?,
    public val store: Store,
    public val productIdentifier: String,
    /**
     * 解锁该权益的 base plan（**Google only**）。
     * 契约 §2.2 的 `entitlements[<id>].product_plan_identifier`（考古 §2.10 标「必须新增」的一项）。
     */
    public val productPlanIdentifier: String?,
    public val isSandbox: Boolean,
    public val unsubscribeDetectedAt: Date?,
    public val billingIssueDetectedAt: Date?,
    public val ownershipType: OwnershipType,
) {
    public val isLifetime: Boolean get() = expirationDate == null
}

/**
 * 权益集合。结构对照 RC `EntitlementInfos.kt`。
 */
@Poko
public class EntitlementInfos internal constructor(
    public val all: Map<String, EntitlementInfo>,
) {
    /** 当前有效的权益（`isActive` 已在构造时按 3 天 grace 算好）。 */
    public val active: Map<String, EntitlementInfo> get() = all.filterValues { it.isActive }

    public operator fun get(identifier: String): EntitlementInfo? = all[identifier]
}

/**
 * 单条订阅。契约 §2.2 `subscriptions[<product_id>]`。
 * `autoResumeDate` 与 `productPlanIdentifier` 是 **Google only**。
 */
@Poko
public class SubscriptionInfo internal constructor(
    public val productIdentifier: String,
    public val purchaseDate: Date?,
    public val originalPurchaseDate: Date?,
    public val expiresDate: Date?,
    public val store: Store,
    public val isSandbox: Boolean,
    public val periodType: PeriodType,
    public val ownershipType: OwnershipType,
    public val unsubscribeDetectedAt: Date?,
    public val billingIssuesDetectedAt: Date?,
    public val gracePeriodExpiresDate: Date?,
    public val refundedAt: Date?,
    /** 暂停后自动恢复时间，**Google Play only**。 */
    public val autoResumeDate: Date?,
    public val storeTransactionId: String?,
    /** Google base plan id。 */
    public val productPlanIdentifier: String?,
    public val managementURL: Uri?,
)

/**
 * 一次性购买（契约 §2.2 `non_subscriptions[<product_id>][]`）。
 */
@Poko
public class NonSubscriptionTransaction internal constructor(
    public val transactionIdentifier: String,
    public val productIdentifier: String,
    public val purchaseDate: Date?,
    public val originalPurchaseDate: Date?,
    public val store: Store,
    public val storeTransactionId: String?,
    public val isSandbox: Boolean,
)

/**
 * 用户的完整权益快照。结构对照 RC `CustomerInfo.kt`，字段口径与 iOS `CustomerInfo` 对齐。
 *
 * [rawData] 保留原始 JSON：磁盘缓存存的就是它（考古 §5.2），
 * 重新序列化一个 `JSONObject` 会多出好几倍响应大小的连续分配，在低堆设备上 OOM（坑 31）。
 */
@Suppress("LongParameterList")
public class CustomerInfo internal constructor(
    public val entitlements: EntitlementInfos,
    public val subscriptions: Map<String, SubscriptionInfo>,
    public val nonSubscriptions: Map<String, List<NonSubscriptionTransaction>>,
    /**
     * `allExpirationDatesByProduct` 的 key 在 Google 下是 **`"productId:basePlanId"`**
     * （考古 §2.10：`CustomerInfoFactory.parseDates` 会用 `product_plan_identifier` 重写 key），
     * iOS 下是裸 `productId`。宿主读这张表时要按平台区分。
     */
    public val allExpirationDatesByProduct: Map<String, Date?>,
    public val allPurchaseDatesByProduct: Map<String, Date?>,
    public val requestDate: Date,
    public val firstSeen: Date?,
    public val lastSeen: Date?,
    public val originalAppUserId: String,
    public val managementURL: Uri?,
    public val originalPurchaseDate: Date?,
    /** 服务端签发的 32hex 账户令牌（契约 §2.2 ⟦决策21⟧）；Android 侧转 `obfuscatedAccountId` 用。 */
    public val accountToken: String?,
    /** 原始响应 JSON。缓存与排障用，**不要**在业务里读它。 */
    @property:org.revdog.purchases.InternalRevenueDogAPI
    public val rawData: JSONObject,
    /** 该条是否读自磁盘缓存。 */
    public val loadedFromCache: Boolean,
) {
    public val activeSubscriptions: Set<String>
        get() = entitlements.active.values.map { it.productIdentifier }.toSet()

    public val allPurchasedProductIdentifiers: Set<String>
        get() = subscriptions.keys + nonSubscriptions.keys

    // equals / hashCode **手写**，不用 @Poko（结构对照 RC `CustomerInfo.equals` 的 ComparableData）。
    // 三个字段被刻意排除：
    // - `requestDate`：同一份权益在不同时刻刷新出来的两个对象是**相同的**，
    //   否则 `CustomerInfoUpdateHandler` 的去重永远不生效，宿主每次刷新都被通知一遍。
    // - `rawData`：`JSONObject` 不实现 equals（比的是引用），带上它两个对象永远不相等。
    // - `loadedFromCache`：元数据，不是用户状态。
    override fun equals(other: Any?): Boolean =
        other is CustomerInfo && ComparableData(this) == ComparableData(other)

    override fun hashCode(): Int = ComparableData(this).hashCode()

    override fun toString(): String =
        "CustomerInfo(originalAppUserId=$originalAppUserId, entitlements=${entitlements.all.keys}, " +
            "subscriptions=${subscriptions.keys}, requestDate=$requestDate)"
}

private data class ComparableData(
    val entitlements: EntitlementInfos,
    val allExpirationDatesByProduct: Map<String, Date?>,
    val allPurchaseDatesByProduct: Map<String, Date?>,
    val firstSeen: Date?,
    val originalAppUserId: String,
    val originalPurchaseDate: Date?,
    val accountToken: String?,
) {
    constructor(customerInfo: CustomerInfo) : this(
        entitlements = customerInfo.entitlements,
        allExpirationDatesByProduct = customerInfo.allExpirationDatesByProduct,
        allPurchaseDatesByProduct = customerInfo.allPurchaseDatesByProduct,
        firstSeen = customerInfo.firstSeen,
        originalAppUserId = customerInfo.originalAppUserId,
        originalPurchaseDate = customerInfo.originalPurchaseDate,
        accountToken = customerInfo.accountToken,
    )
}
