@file:Suppress("UNUSED_VARIABLE", "unused", "UnusedPrivateMember")

package org.revdog.apitester

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.revdog.purchases.CacheFetchPolicy
import org.revdog.purchases.InAppMessageType
import org.revdog.purchases.LogInCallback
import org.revdog.purchases.LogInResult
import org.revdog.purchases.LogLevel
import org.revdog.purchases.OwnershipType
import org.revdog.purchases.PeriodType
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchaseCallback
import org.revdog.purchases.PurchaseParams
import org.revdog.purchases.PurchaseResult
import org.revdog.purchases.Purchases
import org.revdog.purchases.PurchasesAreCompletedBy
import org.revdog.purchases.PurchasesConfiguration
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode
import org.revdog.purchases.PurchasesException
import org.revdog.purchases.ReceiveCustomerInfoCallback
import org.revdog.purchases.ReceiveOfferingsCallback
import org.revdog.purchases.ReplacementMode
import org.revdog.purchases.Store
import org.revdog.purchases.UncheckedPurchasesException
import org.revdog.purchases.UpdatedCustomerInfoListener
import org.revdog.purchases.awaitCustomerInfo
import org.revdog.purchases.awaitLogIn
import org.revdog.purchases.awaitLogOut
import org.revdog.purchases.awaitOfferings
import org.revdog.purchases.awaitPurchase
import org.revdog.purchases.awaitRestore
import org.revdog.purchases.awaitSyncPurchases
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.customerinfo.EntitlementInfo
import org.revdog.purchases.customerinfo.EntitlementInfos
import org.revdog.purchases.customerinfo.NonSubscriptionTransaction
import org.revdog.purchases.customerinfo.SubscriptionInfo
import org.revdog.purchases.getCustomerInfoWith
import org.revdog.purchases.getOfferingsWith
import org.revdog.purchases.logInWith
import org.revdog.purchases.logOutWith
import org.revdog.purchases.models.PurchaseState
import org.revdog.purchases.models.StoreTransaction
import org.revdog.purchases.purchaseWith
import org.revdog.purchases.restorePurchasesWith
import org.revdog.purchases.syncPurchasesWith
import org.revdog.purchases.models.Period
import org.revdog.purchases.models.Price
import org.revdog.purchases.models.PricingPhase
import org.revdog.purchases.models.RecurrenceMode
import org.revdog.purchases.models.StoreProduct
import org.revdog.purchases.models.SubscriptionOption
import org.revdog.purchases.models.SubscriptionOptions
import org.revdog.purchases.offerings.Offering
import org.revdog.purchases.offerings.Offerings
import org.revdog.purchases.offerings.Package
import org.revdog.purchases.offerings.PackageType

/**
 * Kotlin 侧的公开 API 编译验证（结构对照 RC `api-tester/`）。
 * **只编译、不运行。** 覆盖三套形态里的 lambda 与 `await…` 两套。
 */
@Suppress("LongMethod", "TooManyFunctions")
internal object KotlinPurchasesAPI {

    fun checkConfiguration(context: Context) {
        val configuration = PurchasesConfiguration.Builder(context, apiKey = "pk_test")
            .appUserID("user")
            .appUserID(null)
            .purchasesCompletedBy(PurchasesAreCompletedBy.REVENUE_DOG)
            .diagnosticsEnabled(true)
            .logLevel(LogLevel.INFO)
            .baseURL(PurchasesConfiguration.DEFAULT_BASE_URL)
            .pendingTransactionsForPrepaidPlansEnabled(false)
            .showInAppMessagesAutomatically(true)
            .build()

        val ctx: Context = configuration.context
        val apiKey: String = configuration.apiKey
        val appUserID: String? = configuration.appUserID
        val completedBy: PurchasesAreCompletedBy = configuration.purchasesCompletedBy
        val diagnostics: Boolean = configuration.diagnosticsEnabled
        val level: LogLevel = configuration.logLevel
        val baseURL: String = configuration.baseURL
        val prepaid: Boolean = configuration.pendingTransactionsForPrepaidPlansEnabled
        val autoInAppMessages: Boolean = configuration.showInAppMessagesAutomatically

        val purchases: Purchases = Purchases.configure(configuration)
        val configured: Boolean = Purchases.isConfigured
        val shared: Purchases = Purchases.sharedInstance
        Purchases.logLevel = LogLevel.DEBUG
        val currentLevel: LogLevel = Purchases.logLevel
        Purchases.logHandler = object : org.revdog.purchases.LogHandler {
            override fun log(level: LogLevel, message: String, throwable: Throwable?) = Unit
        }
    }

    fun checkIdentity(purchases: Purchases) {
        val appUserID: String = purchases.appUserID
        val anonymous: Boolean = purchases.isAnonymous

        purchases.logIn(
            "new-user",
            object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )
        purchases.logInWith("new-user", onError = { }) { _: CustomerInfo, _: Boolean -> }
        purchases.logInWith("new-user") { _: CustomerInfo, _: Boolean -> }

        purchases.logOut(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )
        purchases.logOutWith(onError = { }) { _: CustomerInfo -> }
        purchases.logOutWith { _: CustomerInfo -> }
    }

    fun checkCustomerInfo(purchases: Purchases) {
        val callback = object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) = Unit
            override fun onError(error: PurchasesError) = Unit
        }
        purchases.getCustomerInfo(callback = callback)
        purchases.getCustomerInfo(CacheFetchPolicy.CACHE_ONLY, callback)
        purchases.getCustomerInfoWith { _: CustomerInfo -> }
        purchases.getCustomerInfoWith(CacheFetchPolicy.FETCH_CURRENT, onError = { }) { _: CustomerInfo -> }

        val listener = UpdatedCustomerInfoListener { _: CustomerInfo -> }
        purchases.updatedCustomerInfoListener = listener
        val readBack: UpdatedCustomerInfoListener? = purchases.updatedCustomerInfoListener
        val flow: Flow<CustomerInfo> = purchases.customerInfoFlow
        val cached: CustomerInfo? = purchases.cachedCustomerInfo
        purchases.invalidateCustomerInfoCache()
        purchases.setAppBackgrounded(true)
    }

    fun checkCustomerInfoModel(customerInfo: CustomerInfo) {
        val entitlements: EntitlementInfos = customerInfo.entitlements
        val all: Map<String, EntitlementInfo> = entitlements.all
        val active: Map<String, EntitlementInfo> = entitlements.active
        val one: EntitlementInfo? = entitlements["pro"]
        val subscriptions: Map<String, SubscriptionInfo> = customerInfo.subscriptions
        val nonSubscriptions: Map<String, List<NonSubscriptionTransaction>> = customerInfo.nonSubscriptions
        val expirations: Map<String, java.util.Date?> = customerInfo.allExpirationDatesByProduct
        val purchaseDates: Map<String, java.util.Date?> = customerInfo.allPurchaseDatesByProduct
        val requestDate: java.util.Date = customerInfo.requestDate
        val firstSeen: java.util.Date? = customerInfo.firstSeen
        val lastSeen: java.util.Date? = customerInfo.lastSeen
        val originalAppUserId: String = customerInfo.originalAppUserId
        val managementURL: android.net.Uri? = customerInfo.managementURL
        val originalPurchaseDate: java.util.Date? = customerInfo.originalPurchaseDate
        val accountToken: String? = customerInfo.accountToken
        val loadedFromCache: Boolean = customerInfo.loadedFromCache
        val activeSubscriptions: Set<String> = customerInfo.activeSubscriptions
        val allProducts: Set<String> = customerInfo.allPurchasedProductIdentifiers
    }

    fun checkEntitlementInfo(info: EntitlementInfo) {
        val identifier: String = info.identifier
        val isActive: Boolean = info.isActive
        val willRenew: Boolean = info.willRenew
        val periodType: PeriodType = info.periodType
        val latestPurchaseDate: java.util.Date? = info.latestPurchaseDate
        val originalPurchaseDate: java.util.Date? = info.originalPurchaseDate
        val expirationDate: java.util.Date? = info.expirationDate
        val store: Store = info.store
        val productIdentifier: String = info.productIdentifier
        val productPlanIdentifier: String? = info.productPlanIdentifier
        val isSandbox: Boolean = info.isSandbox
        val unsubscribeDetectedAt: java.util.Date? = info.unsubscribeDetectedAt
        val billingIssueDetectedAt: java.util.Date? = info.billingIssueDetectedAt
        val ownershipType: OwnershipType = info.ownershipType
        val isLifetime: Boolean = info.isLifetime
    }

    fun checkSubscriptionInfo(info: SubscriptionInfo) {
        val productIdentifier: String = info.productIdentifier
        val autoResumeDate: java.util.Date? = info.autoResumeDate
        val productPlanIdentifier: String? = info.productPlanIdentifier
        val managementURL: android.net.Uri? = info.managementURL
        val gracePeriodExpiresDate: java.util.Date? = info.gracePeriodExpiresDate
        val refundedAt: java.util.Date? = info.refundedAt
        val storeTransactionId: String? = info.storeTransactionId
        val store: Store = info.store
        val periodType: PeriodType = info.periodType
        val ownershipType: OwnershipType = info.ownershipType
        val isSandbox: Boolean = info.isSandbox
        val purchaseDate: java.util.Date? = info.purchaseDate
        val originalPurchaseDate: java.util.Date? = info.originalPurchaseDate
        val expiresDate: java.util.Date? = info.expiresDate
        val unsubscribeDetectedAt: java.util.Date? = info.unsubscribeDetectedAt
        val billingIssuesDetectedAt: java.util.Date? = info.billingIssuesDetectedAt
    }

    fun checkNonSubscription(transaction: NonSubscriptionTransaction) {
        val transactionIdentifier: String = transaction.transactionIdentifier
        val productIdentifier: String = transaction.productIdentifier
        val purchaseDate: java.util.Date? = transaction.purchaseDate
        val originalPurchaseDate: java.util.Date? = transaction.originalPurchaseDate
        val store: Store = transaction.store
        val storeTransactionId: String? = transaction.storeTransactionId
        val isSandbox: Boolean = transaction.isSandbox
    }

    fun checkOfferings(purchases: Purchases) {
        purchases.getOfferings(
            object : ReceiveOfferingsCallback {
                override fun onReceived(offerings: Offerings) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )
        purchases.getOfferingsWith { _: Offerings -> }
        purchases.getOfferingsWith(onError = { }) { _: Offerings -> }
        val cached: Offerings? = purchases.cachedOfferings
    }

    fun checkOfferingsModel(offerings: Offerings) {
        val all: Map<String, Offering> = offerings.all
        val currentId: String? = offerings.currentOfferingIdentifier
        val notFound: List<String> = offerings.notFoundProductIds
        val current: Offering? = offerings.current
        val byId: Offering? = offerings["default"]
    }

    fun checkOffering(offering: Offering) {
        val identifier: String = offering.identifier
        val description: String = offering.serverDescription
        val packages: List<Package> = offering.availablePackages
        val byIdentifier: Package? = offering["\$rc_monthly"]
        val byType: Package? = offering.getPackage(PackageType.MONTHLY)
        val lifetime: Package? = offering.lifetime
        val annual: Package? = offering.annual
        val monthly: Package? = offering.monthly
        val weekly: Package? = offering.weekly
    }

    fun checkPackage(pkg: Package) {
        val identifier: String = pkg.identifier
        val packageType: PackageType = pkg.packageType
        val offeringIdentifier: String = pkg.offeringIdentifier
        val platformProductIdentifier: String = pkg.platformProductIdentifier
        val platformProductPlanIdentifier: String? = pkg.platformProductPlanIdentifier
        val product: StoreProduct? = pkg.product
    }

    fun checkStoreProduct(product: StoreProduct) {
        val productId: String = product.productId
        val basePlanId: String? = product.basePlanId
        val type: ProductType = product.type
        val price: Price = product.price
        val name: String = product.name
        val title: String = product.title
        val description: String = product.description
        val period: Period? = product.period
        val options: SubscriptionOptions? = product.subscriptionOptions
        val defaultOption: SubscriptionOption? = product.defaultOption
        val id: String = product.id
    }

    fun checkSubscriptionOptions(options: SubscriptionOptions) {
        val basePlan: SubscriptionOption? = options.basePlan
        val freeTrial: SubscriptionOption? = options.freeTrial
        val introOffer: SubscriptionOption? = options.introOffer
        val defaultOffer: SubscriptionOption? = options.defaultOffer
        val tagged: List<SubscriptionOption> = options.withTag("promo")
        val size: Int = options.size
    }

    fun checkSubscriptionOption(option: SubscriptionOption) {
        val productId: String = option.productId
        val basePlanId: String = option.basePlanId
        val offerId: String? = option.offerId
        val phases: List<PricingPhase> = option.pricingPhases
        val tags: List<String> = option.tags
        val offerToken: String = option.offerToken
        val id: String = option.id
        val isBasePlan: Boolean = option.isBasePlan
        val fullPrice: PricingPhase? = option.fullPricePhase
        val billingPeriod: Period? = option.billingPeriod
        val isPrepaid: Boolean = option.isPrepaid
        val freePhase: PricingPhase? = option.freePhase
        val introPhase: PricingPhase? = option.introPhase
    }

    fun checkPricingPhase(phase: PricingPhase) {
        val billingPeriod: Period = phase.billingPeriod
        val recurrenceMode: RecurrenceMode = phase.recurrenceMode
        val billingCycleCount: Int? = phase.billingCycleCount
        val price: Price = phase.price
        val formatted: String = price.formatted
        val amountMicros: Long = price.amountMicros
        val currencyCode: String = price.currencyCode
        val identifier: Int? = recurrenceMode.identifier
        val name: String = recurrenceMode.name
        val parsed: RecurrenceMode = RecurrenceMode.fromIdentifier(1)
        val allModes: List<RecurrenceMode> = RecurrenceMode.ALL
    }

    fun checkPeriod() {
        val period: Period = Period.create("P1M")
        val value: Int = period.value
        val unit: Period.Unit = period.unit
        val iso8601: String = period.iso8601
        val raw: String = unit.rawValue
        val units: List<Period.Unit> = Period.Unit.ALL
    }

    fun checkPublicTypes() {
        val stores: List<Store> = Store.ALL
        val parsedStore: Store = Store.fromString("play_store")
        val storeRaw: String = parsedStore.rawValue
        val periodTypes: List<PeriodType> = PeriodType.ALL
        val parsedPeriod: PeriodType = PeriodType.fromString("trial")
        val ownershipTypes: List<OwnershipType> = OwnershipType.ALL
        val parsedOwnership: OwnershipType = OwnershipType.fromString("PURCHASED")
        val productTypes: List<ProductType> = ProductType.ALL
        val parsedProductType: ProductType = ProductType.fromString("subs")
        val completedBy: PurchasesAreCompletedBy = PurchasesAreCompletedBy.MY_APP
        val defaultPolicy: CacheFetchPolicy = CacheFetchPolicy.default()
        val levels: List<LogLevel> = LogLevel.ALL
        val severity: Int = LogLevel.INFO.severity
        val levelName: String = LogLevel.INFO.name
    }

    fun checkError(error: PurchasesError) {
        val code: PurchasesErrorCode = error.code
        val underlying: String? = error.underlyingErrorMessage
        val backendCode: Int? = error.backendCode
        val httpStatusCode: Int? = error.httpStatusCode
        val message: String = error.message
        val rawCode: Int = code.code
        val name: String = code.name
        val all: List<PurchasesErrorCode> = PurchasesErrorCode.ALL
        val parsed: PurchasesErrorCode = PurchasesErrorCode.fromCode(901)
        val exception = PurchasesException(error)
        val wrapped: PurchasesError = exception.error
        val unchecked = UncheckedPurchasesException(error)
        val uncheckedError: PurchasesError = unchecked.error
        val constructed = PurchasesError(PurchasesErrorCode.NetworkError)
        val full = PurchasesError(PurchasesErrorCode.NetworkError, "offline", 7000, 503)
    }

    suspend fun checkCoroutines(purchases: Purchases) {
        val customerInfo: CustomerInfo = purchases.awaitCustomerInfo()
        val fresh: CustomerInfo = purchases.awaitCustomerInfo(CacheFetchPolicy.FETCH_CURRENT)
        val offerings: Offerings = purchases.awaitOfferings()
        val logInResult: LogInResult = purchases.awaitLogIn("user")
        val loggedInInfo: CustomerInfo = logInResult.customerInfo
        val created: Boolean = logInResult.created
        val loggedOut: CustomerInfo = purchases.awaitLogOut()
    }

    suspend fun checkPurchaseCoroutines(purchases: Purchases, params: PurchaseParams) {
        val result: PurchaseResult = purchases.awaitPurchase(params)
        val restored: CustomerInfo = purchases.awaitRestore()
        val synced: CustomerInfo = purchases.awaitSyncPurchases()
    }

    fun checkPurchaseParams(activity: Activity, packageToPurchase: Package, product: StoreProduct) {
        val fromPackage: PurchaseParams = PurchaseParams.Builder(activity, packageToPurchase).build()
        val fromProduct: PurchaseParams = PurchaseParams.Builder(activity, product).build()
        val option: SubscriptionOption? = product.defaultOption
        if (option != null) {
            val fromOption: PurchaseParams = PurchaseParams.Builder(activity, option).build()
        }
        val upgrade: PurchaseParams = PurchaseParams.Builder(activity, product)
            .oldProductId("sub_basic")
            .replacementMode(ReplacementMode.CHARGE_PRORATED_PRICE)
            .isPersonalizedPrice(true)
            .build()
        val oldProductId: String? = upgrade.oldProductId
        val replacementMode: ReplacementMode? = upgrade.replacementMode
        val personalized: Boolean? = upgrade.isPersonalizedPrice
    }

    fun checkReplacementMode() {
        val modes: List<ReplacementMode> = ReplacementMode.ALL
        val all = arrayOf(
            ReplacementMode.WITHOUT_PRORATION,
            ReplacementMode.WITH_TIME_PRORATION,
            ReplacementMode.CHARGE_FULL_PRICE,
            ReplacementMode.CHARGE_PRORATED_PRICE,
            ReplacementMode.DEFERRED,
        )
        val name: String = ReplacementMode.DEFERRED.name
        val wireName: String = ReplacementMode.DEFERRED.wireName
        val parsed: ReplacementMode? = ReplacementMode.fromWireName("DEFERRED")
    }

    fun checkPurchase(purchases: Purchases, params: PurchaseParams) {
        purchases.purchase(
            params,
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) = Unit
            },
        )
        purchases.purchaseWith(params) { result -> checkPurchaseResult(result) }
        purchases.purchaseWith(params, onError = { _, _ -> }) { }

        purchases.restorePurchases(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )
        purchases.restorePurchasesWith { }
        purchases.restorePurchasesWith(onError = { }) { }

        purchases.syncPurchases(
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) = Unit
                override fun onError(error: PurchasesError) = Unit
            },
        )
        purchases.syncPurchasesWith { }
        purchases.syncPurchasesWith(onError = { }) { }
    }

    fun checkInAppMessages(purchases: Purchases, activity: Activity) {
        purchases.showInAppMessagesIfNeeded(activity)
        purchases.showInAppMessagesIfNeeded(activity, InAppMessageType.ALL)
        purchases.showInAppMessagesIfNeeded(activity, listOf(InAppMessageType.BILLING_ISSUES))

        val name: String = InAppMessageType.BILLING_ISSUES.name
        val all: List<InAppMessageType> = InAppMessageType.ALL
    }

    fun checkPurchaseResult(result: PurchaseResult) {
        val customerInfo: CustomerInfo = result.customerInfo
        val transaction: StoreTransaction? = result.storeTransaction
        val isPending: Boolean = result.isPending
        if (transaction != null) checkStoreTransaction(transaction)
    }

    fun checkStoreTransaction(transaction: StoreTransaction) {
        val orderId: String? = transaction.orderId
        val productIds: List<String> = transaction.productIds
        val type: ProductType = transaction.type
        val purchaseTime: Long = transaction.purchaseTime
        val purchaseToken: String = transaction.purchaseToken
        val state: PurchaseState = transaction.purchaseState
        val isAutoRenewing: Boolean? = transaction.isAutoRenewing
        val isAcknowledged: Boolean = transaction.isAcknowledged
        val offering: String? = transaction.presentedOfferingIdentifier
        val optionId: String? = transaction.subscriptionOptionId
        val replacementMode: ReplacementMode? = transaction.replacementMode
    }

    /** M3：订阅者属性 setter 全集 + 诊断开关。 */
    @Suppress("LongMethod")
    fun checkAttributes(purchases: Purchases) {
        purchases.setAttributes(mapOf("favorite_food" to "pizza"))
        purchases.setAttributes(mapOf("tombstone" to null))
        purchases.collectDeviceIdentifiers()
        purchases.syncAttributes()

        purchases.setEmail("a@b.c")
        purchases.setEmail(null)
        purchases.setPhoneNumber("+15550000000")
        purchases.setDisplayName("Ada")
        purchases.setPushToken("fcm-token")
        purchases.setFCMToken("fcm-token")
        purchases.setAPNSToken("apns-token")
        purchases.setATTConsentStatus("authorized")
        purchases.setIDFA("idfa")
        purchases.setIDFV("idfv")
        purchases.setAppleRefundHandlingPreference("CUSTOMER_SUPPORT")
        purchases.setGPSAdID("gps-ad-id")
        purchases.setAndroidID("android-id")
        purchases.setAmazonAdID("amazon-ad-id")
        purchases.setIP("true")
        purchases.setDeviceVersion("true")
        purchases.setAdjustID("adjust")
        purchases.setAmplitudeDeviceID("amplitude-device")
        purchases.setAmplitudeUserID("amplitude-user")
        purchases.setAppsflyerID("appsflyer")
        purchases.setAppstackID("appstack")
        purchases.setBrazeAliasName("braze-name")
        purchases.setBrazeAliasLabel("braze-label")
        purchases.setCleverTapID("clevertap")
        purchases.setCustomerioID("customerio")
        purchases.setFBAnonymousID("fb-anon")
        purchases.setFirebaseAppInstanceID("firebase")
        purchases.setKochavaDeviceID("kochava")
        purchases.setMixpanelDistinctID("mixpanel")
        purchases.setMparticleID("mparticle")
        purchases.setOnesignalID("onesignal")
        purchases.setAirshipChannelID("airship")
        purchases.setIterableUserID("iterable-user")
        purchases.setIterableCampaignID("iterable-campaign")
        purchases.setIterableTemplateID("iterable-template")
        purchases.setPostHogUserID("posthog")
        purchases.setTenjinID("tenjin")
        purchases.setMediaSource("media")
        purchases.setCampaign("campaign")
        purchases.setAdGroup("ad-group")
        purchases.setAd("ad")
        purchases.setKeyword("keyword")
        purchases.setCreative("creative")

        val diagnosticsEnabled: Boolean = purchases.diagnosticsEnabled
    }

    fun checkPurchaseState() {
        val states: List<PurchaseState> = PurchaseState.ALL
        val all = arrayOf(PurchaseState.UNSPECIFIED_STATE, PurchaseState.PURCHASED, PurchaseState.PENDING)
        val raw: String = PurchaseState.PURCHASED.rawValue
        val parsed: PurchaseState = PurchaseState.fromPlayCode(1)
    }
}
