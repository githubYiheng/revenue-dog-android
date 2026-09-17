package org.revdog.apitester;

import android.content.Context;
import android.net.Uri;

import org.revdog.purchases.CacheFetchPolicy;
import org.revdog.purchases.LogInCallback;
import org.revdog.purchases.LogLevel;
import org.revdog.purchases.OwnershipType;
import org.revdog.purchases.PeriodType;
import org.revdog.purchases.ProductType;
import org.revdog.purchases.Purchases;
import org.revdog.purchases.PurchasesAreCompletedBy;
import org.revdog.purchases.PurchasesConfiguration;
import org.revdog.purchases.PurchasesError;
import org.revdog.purchases.PurchasesErrorCode;
import org.revdog.purchases.ReceiveCustomerInfoCallback;
import org.revdog.purchases.ReceiveOfferingsCallback;
import org.revdog.purchases.Store;
import org.revdog.purchases.UpdatedCustomerInfoListener;
import org.revdog.purchases.customerinfo.CustomerInfo;
import org.revdog.purchases.customerinfo.EntitlementInfo;
import org.revdog.purchases.customerinfo.EntitlementInfos;
import org.revdog.purchases.customerinfo.NonSubscriptionTransaction;
import org.revdog.purchases.customerinfo.SubscriptionInfo;
import org.revdog.purchases.models.Period;
import org.revdog.purchases.models.Price;
import org.revdog.purchases.models.PricingPhase;
import org.revdog.purchases.models.RecurrenceMode;
import org.revdog.purchases.models.StoreProduct;
import org.revdog.purchases.models.SubscriptionOption;
import org.revdog.purchases.models.SubscriptionOptions;
import org.revdog.purchases.offerings.Offering;
import org.revdog.purchases.offerings.Offerings;
import org.revdog.purchases.offerings.Package;
import org.revdog.purchases.offerings.PackageType;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 侧的公开 API 编译验证（结构对照 RC {@code api-tester/}）。
 *
 * <p><b>只编译、不运行。</b>编译不过就说明公开面出现了破坏性变更 —— 这比 api.txt 更硬：
 * api.txt 只记录符号，这里验证 Java 调用方**真的能用**（{@code @JvmStatic} /
 * {@code @JvmField} / {@code @JvmOverloads} 漏了都会在这里炸掉）。
 */
@SuppressWarnings({"unused", "UnusedAssignment", "deprecation"})
final class PurchasesAPI {

    static void checkConfiguration(Context context) {
        PurchasesConfiguration configuration = new PurchasesConfiguration.Builder(context, "pk_test")
                .appUserID("user")
                .appUserID(null)
                .purchasesCompletedBy(PurchasesAreCompletedBy.REVENUE_DOG)
                .purchasesCompletedBy(PurchasesAreCompletedBy.MY_APP)
                .diagnosticsEnabled(true)
                .logLevel(LogLevel.INFO)
                .baseURL(PurchasesConfiguration.DEFAULT_BASE_URL)
                .pendingTransactionsForPrepaidPlansEnabled(false)
                .build();

        Context ctx = configuration.getContext();
        String apiKey = configuration.getApiKey();
        String appUserID = configuration.getAppUserID();
        PurchasesAreCompletedBy completedBy = configuration.getPurchasesCompletedBy();
        boolean diagnostics = configuration.getDiagnosticsEnabled();
        LogLevel level = configuration.getLogLevel();
        String baseURL = configuration.getBaseURL();
        boolean prepaid = configuration.getPendingTransactionsForPrepaidPlansEnabled();

        Purchases purchases = Purchases.configure(configuration);
        boolean configured = Purchases.isConfigured();
        Purchases shared = Purchases.getSharedInstance();
        Purchases.setLogLevel(LogLevel.DEBUG);
        LogLevel current = Purchases.getLogLevel();
        Purchases.setLogHandler((logLevel, message, throwable) -> { });
    }

    static void checkIdentity(Purchases purchases) {
        String appUserID = purchases.getAppUserID();
        boolean anonymous = purchases.isAnonymous();

        purchases.logIn("new-user", new LogInCallback() {
            @Override
            public void onReceived(CustomerInfo customerInfo, boolean created) { }

            @Override
            public void onError(PurchasesError error) { }
        });

        purchases.logOut(new ReceiveCustomerInfoCallback() {
            @Override
            public void onReceived(CustomerInfo customerInfo) { }

            @Override
            public void onError(PurchasesError error) { }
        });
    }

    static void checkCustomerInfo(Purchases purchases) {
        ReceiveCustomerInfoCallback callback = new ReceiveCustomerInfoCallback() {
            @Override
            public void onReceived(CustomerInfo customerInfo) { }

            @Override
            public void onError(PurchasesError error) { }
        };
        // @JvmOverloads：不传 fetchPolicy 的重载必须对 Java 可见。
        purchases.getCustomerInfo(callback);
        purchases.getCustomerInfo(CacheFetchPolicy.CACHE_ONLY, callback);
        purchases.getCustomerInfo(CacheFetchPolicy.FETCH_CURRENT, callback);
        purchases.getCustomerInfo(CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT, callback);
        purchases.getCustomerInfo(CacheFetchPolicy.CACHED_OR_FETCHED, callback);
        purchases.getCustomerInfo(CacheFetchPolicy.getDefault(), callback);

        UpdatedCustomerInfoListener listener = customerInfo -> { };
        purchases.setUpdatedCustomerInfoListener(listener);
        UpdatedCustomerInfoListener readBack = purchases.getUpdatedCustomerInfoListener();

        CustomerInfo cached = purchases.getCachedCustomerInfo();
        purchases.invalidateCustomerInfoCache();
        purchases.setAppBackgrounded(false);
    }

    static void checkCustomerInfoModel(CustomerInfo customerInfo) {
        EntitlementInfos entitlements = customerInfo.getEntitlements();
        Map<String, EntitlementInfo> all = entitlements.getAll();
        Map<String, EntitlementInfo> active = entitlements.getActive();
        EntitlementInfo one = entitlements.get("pro");

        Map<String, SubscriptionInfo> subscriptions = customerInfo.getSubscriptions();
        Map<String, List<NonSubscriptionTransaction>> nonSubscriptions = customerInfo.getNonSubscriptions();
        Map<String, Date> expirations = customerInfo.getAllExpirationDatesByProduct();
        Map<String, Date> purchaseDates = customerInfo.getAllPurchaseDatesByProduct();
        Date requestDate = customerInfo.getRequestDate();
        Date firstSeen = customerInfo.getFirstSeen();
        Date lastSeen = customerInfo.getLastSeen();
        String originalAppUserId = customerInfo.getOriginalAppUserId();
        Uri managementURL = customerInfo.getManagementURL();
        Date originalPurchaseDate = customerInfo.getOriginalPurchaseDate();
        String accountToken = customerInfo.getAccountToken();
        boolean loadedFromCache = customerInfo.getLoadedFromCache();
        Set<String> activeSubscriptions = customerInfo.getActiveSubscriptions();
        Set<String> allProducts = customerInfo.getAllPurchasedProductIdentifiers();
    }

    static void checkEntitlementInfo(EntitlementInfo info) {
        String identifier = info.getIdentifier();
        boolean isActive = info.isActive();
        boolean willRenew = info.getWillRenew();
        PeriodType periodType = info.getPeriodType();
        Date latestPurchaseDate = info.getLatestPurchaseDate();
        Date originalPurchaseDate = info.getOriginalPurchaseDate();
        Date expirationDate = info.getExpirationDate();
        Store store = info.getStore();
        String productIdentifier = info.getProductIdentifier();
        String productPlanIdentifier = info.getProductPlanIdentifier();
        boolean isSandbox = info.isSandbox();
        Date unsubscribeDetectedAt = info.getUnsubscribeDetectedAt();
        Date billingIssueDetectedAt = info.getBillingIssueDetectedAt();
        OwnershipType ownershipType = info.getOwnershipType();
        boolean isLifetime = info.isLifetime();
    }

    static void checkSubscriptionInfo(SubscriptionInfo info) {
        String productIdentifier = info.getProductIdentifier();
        Date purchaseDate = info.getPurchaseDate();
        Date originalPurchaseDate = info.getOriginalPurchaseDate();
        Date expiresDate = info.getExpiresDate();
        Store store = info.getStore();
        boolean isSandbox = info.isSandbox();
        PeriodType periodType = info.getPeriodType();
        OwnershipType ownershipType = info.getOwnershipType();
        Date unsubscribeDetectedAt = info.getUnsubscribeDetectedAt();
        Date billingIssuesDetectedAt = info.getBillingIssuesDetectedAt();
        Date gracePeriodExpiresDate = info.getGracePeriodExpiresDate();
        Date refundedAt = info.getRefundedAt();
        Date autoResumeDate = info.getAutoResumeDate();
        String storeTransactionId = info.getStoreTransactionId();
        String productPlanIdentifier = info.getProductPlanIdentifier();
        Uri managementURL = info.getManagementURL();
    }

    static void checkNonSubscription(NonSubscriptionTransaction transaction) {
        String transactionIdentifier = transaction.getTransactionIdentifier();
        String productIdentifier = transaction.getProductIdentifier();
        Date purchaseDate = transaction.getPurchaseDate();
        Date originalPurchaseDate = transaction.getOriginalPurchaseDate();
        Store store = transaction.getStore();
        String storeTransactionId = transaction.getStoreTransactionId();
        boolean isSandbox = transaction.isSandbox();
    }

    static void checkOfferings(Purchases purchases) {
        purchases.getOfferings(new ReceiveOfferingsCallback() {
            @Override
            public void onReceived(Offerings offerings) { }

            @Override
            public void onError(PurchasesError error) { }
        });
        Offerings cached = purchases.getCachedOfferings();
    }

    static void checkOfferingsModel(Offerings offerings) {
        Map<String, Offering> all = offerings.getAll();
        String currentId = offerings.getCurrentOfferingIdentifier();
        List<String> notFound = offerings.getNotFoundProductIds();
        Offering current = offerings.getCurrent();
        Offering byId = offerings.get("default");
    }

    static void checkOffering(Offering offering) {
        String identifier = offering.getIdentifier();
        String description = offering.getServerDescription();
        List<Package> packages = offering.getAvailablePackages();
        Package byIdentifier = offering.get("$rc_monthly");
        Package byType = offering.getPackage(PackageType.MONTHLY);
        Package lifetime = offering.getLifetime();
        Package annual = offering.getAnnual();
        Package monthly = offering.getMonthly();
        Package weekly = offering.getWeekly();
    }

    static void checkPackage(Package pkg) {
        String identifier = pkg.getIdentifier();
        PackageType packageType = pkg.getPackageType();
        String offeringIdentifier = pkg.getOfferingIdentifier();
        String platformProductIdentifier = pkg.getPlatformProductIdentifier();
        String platformProductPlanIdentifier = pkg.getPlatformProductPlanIdentifier();
        StoreProduct product = pkg.getProduct();
    }

    static void checkPackageType() {
        PackageType[] types = {
                PackageType.UNKNOWN,
                PackageType.CUSTOM,
                PackageType.LIFETIME,
                PackageType.ANNUAL,
                PackageType.SIX_MONTH,
                PackageType.THREE_MONTH,
                PackageType.TWO_MONTH,
                PackageType.MONTHLY,
                PackageType.WEEKLY,
        };
        List<PackageType> known = PackageType.KNOWN;
        PackageType parsed = PackageType.fromIdentifier("$rc_monthly");
        String identifier = parsed.getIdentifier();
        String name = parsed.getName();
    }

    static void checkStoreProduct(StoreProduct product) {
        String productId = product.getProductId();
        String basePlanId = product.getBasePlanId();
        ProductType type = product.getType();
        Price price = product.getPrice();
        String name = product.getName();
        String title = product.getTitle();
        String description = product.getDescription();
        Period period = product.getPeriod();
        SubscriptionOptions options = product.getSubscriptionOptions();
        SubscriptionOption defaultOption = product.getDefaultOption();
        String id = product.getId();
    }

    static void checkSubscriptionOptions(SubscriptionOptions options) {
        SubscriptionOption basePlan = options.getBasePlan();
        SubscriptionOption freeTrial = options.getFreeTrial();
        SubscriptionOption introOffer = options.getIntroOffer();
        SubscriptionOption defaultOffer = options.getDefaultOffer();
        List<SubscriptionOption> tagged = options.withTag("promo");
        int size = options.size();
    }

    static void checkSubscriptionOption(SubscriptionOption option) {
        String productId = option.getProductId();
        String basePlanId = option.getBasePlanId();
        String offerId = option.getOfferId();
        List<PricingPhase> phases = option.getPricingPhases();
        List<String> tags = option.getTags();
        String offerToken = option.getOfferToken();
        String id = option.getId();
        boolean isBasePlan = option.isBasePlan();
        PricingPhase fullPrice = option.getFullPricePhase();
        Period billingPeriod = option.getBillingPeriod();
        boolean isPrepaid = option.isPrepaid();
        PricingPhase freePhase = option.getFreePhase();
        PricingPhase introPhase = option.getIntroPhase();
    }

    static void checkPricingPhase(PricingPhase phase) {
        Period billingPeriod = phase.getBillingPeriod();
        RecurrenceMode recurrenceMode = phase.getRecurrenceMode();
        Integer billingCycleCount = phase.getBillingCycleCount();
        Price price = phase.getPrice();

        String formatted = price.getFormatted();
        long amountMicros = price.getAmountMicros();
        String currencyCode = price.getCurrencyCode();

        Integer identifier = recurrenceMode.getIdentifier();
        String name = recurrenceMode.getName();
        RecurrenceMode parsed = RecurrenceMode.fromIdentifier(1);
        List<RecurrenceMode> allModes = RecurrenceMode.ALL;
    }

    static void checkPeriod() {
        Period period = Period.create("P1M");
        int value = period.getValue();
        Period.Unit unit = period.getUnit();
        String iso8601 = period.getIso8601();
        String raw = unit.getRawValue();
        List<Period.Unit> units = Period.Unit.ALL;
        Period.Unit[] all = {
                Period.Unit.DAY,
                Period.Unit.WEEK,
                Period.Unit.MONTH,
                Period.Unit.YEAR,
                Period.Unit.UNKNOWN,
        };
    }

    static void checkPublicTypes() {
        Store[] stores = {
                Store.APP_STORE, Store.MAC_APP_STORE, Store.PLAY_STORE, Store.AMAZON,
                Store.STRIPE, Store.PROMOTIONAL, Store.ROKU, Store.PADDLE, Store.UNKNOWN,
        };
        Store parsedStore = Store.fromString("play_store");
        String storeRaw = parsedStore.getRawValue();
        List<Store> allStores = Store.ALL;

        PeriodType[] periodTypes = {
                PeriodType.NORMAL, PeriodType.TRIAL, PeriodType.INTRO, PeriodType.PREPAID,
        };
        PeriodType parsedPeriod = PeriodType.fromString("trial");
        List<PeriodType> allPeriodTypes = PeriodType.ALL;

        OwnershipType[] ownershipTypes = {
                OwnershipType.PURCHASED, OwnershipType.FAMILY_SHARED, OwnershipType.UNKNOWN,
        };
        OwnershipType parsedOwnership = OwnershipType.fromString("PURCHASED");
        List<OwnershipType> allOwnership = OwnershipType.ALL;

        ProductType[] productTypes = { ProductType.SUBS, ProductType.INAPP, ProductType.UNKNOWN };
        ProductType parsedProductType = ProductType.fromString("subs");
        List<ProductType> allProductTypes = ProductType.ALL;

        PurchasesAreCompletedBy[] completedBy = {
                PurchasesAreCompletedBy.REVENUE_DOG, PurchasesAreCompletedBy.MY_APP,
        };

        CacheFetchPolicy[] policies = {
                CacheFetchPolicy.CACHE_ONLY,
                CacheFetchPolicy.FETCH_CURRENT,
                CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT,
                CacheFetchPolicy.CACHED_OR_FETCHED,
        };

        LogLevel[] levels = {
                LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR,
        };
        List<LogLevel> allLevels = LogLevel.ALL;
        int severity = LogLevel.INFO.getSeverity();
        String levelName = LogLevel.INFO.getName();
    }

    static void checkError(PurchasesError error) {
        PurchasesErrorCode code = error.getCode();
        String underlying = error.getUnderlyingErrorMessage();
        Integer backendCode = error.getBackendCode();
        Integer httpStatusCode = error.getHttpStatusCode();
        String message = error.getMessage();

        int rawCode = code.getCode();
        String name = code.getName();
        List<PurchasesErrorCode> all = PurchasesErrorCode.ALL;
        PurchasesErrorCode parsed = PurchasesErrorCode.fromCode(901);

        PurchasesErrorCode[] codes = {
                PurchasesErrorCode.UnknownError,
                PurchasesErrorCode.PurchaseCancelledError,
                PurchasesErrorCode.StoreProblemError,
                PurchasesErrorCode.PurchaseNotAllowedError,
                PurchasesErrorCode.PurchaseInvalidError,
                PurchasesErrorCode.ProductNotAvailableForPurchaseError,
                PurchasesErrorCode.ProductAlreadyPurchasedError,
                PurchasesErrorCode.ReceiptAlreadyInUseError,
                PurchasesErrorCode.InvalidReceiptError,
                PurchasesErrorCode.MissingReceiptFileError,
                PurchasesErrorCode.NetworkError,
                PurchasesErrorCode.InvalidCredentialsError,
                PurchasesErrorCode.UnexpectedBackendResponseError,
                PurchasesErrorCode.InvalidAppUserIdError,
                PurchasesErrorCode.OperationAlreadyInProgressError,
                PurchasesErrorCode.UnknownBackendError,
                PurchasesErrorCode.InvalidAppleSubscriptionKeyError,
                PurchasesErrorCode.ConfigurationError,
                PurchasesErrorCode.UnsupportedError,
                PurchasesErrorCode.EmptySubscriberAttributesError,
                PurchasesErrorCode.ProductDiscountMissingIdentifierError,
                PurchasesErrorCode.CustomerInfoError,
                PurchasesErrorCode.SystemInfoError,
                PurchasesErrorCode.OfflineConnectionError,
                PurchasesErrorCode.NotImplementedError,
                PurchasesErrorCode.PurchasePendingServerConfirmation,
                PurchasesErrorCode.PurchaseRejectedByServer,
        };

        PurchasesError constructed = new PurchasesError(PurchasesErrorCode.NetworkError);
        PurchasesError withMessage = new PurchasesError(PurchasesErrorCode.NetworkError, "offline");
        PurchasesError full = new PurchasesError(PurchasesErrorCode.NetworkError, "offline", 7000, 503);
    }

    private PurchasesAPI() { }
}
