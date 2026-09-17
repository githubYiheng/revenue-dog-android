package org.revdog.purchases.google

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.revdog.purchases.Logger
import org.revdog.purchases.ProductType
import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode

/**
 * 重试分类表。结构对照 RC `google/BillingResponse.kt`。
 *
 * **它与 [billingResponseToPurchasesError] 的错误映射表不是同一张，不要合并**（考古 §3.9）：
 * 一张管「要不要重试、怎么退避」，一张管「给宿主看什么错误」。
 * 最明显的例子是 `SERVICE_TIMEOUT`：这里归 [ServiceUnavailable]（可退避重试），
 * 那边归 `storeProblemError`（给宿主的语义）。
 */
internal sealed class BillingResponse {

    object FeatureNotSupported : BillingResponse()
    object ServiceDisconnected : BillingResponse()
    object OK : BillingResponse()
    object UserCanceled : BillingResponse()
    object ServiceUnavailable : BillingResponse()
    object BillingUnavailable : BillingResponse()
    object ItemUnavailable : BillingResponse()
    object DeveloperError : BillingResponse()
    object Error : BillingResponse()
    object ItemAlreadyOwned : BillingResponse()
    object ItemNotOwned : BillingResponse()
    object NetworkError : BillingResponse()
    object Unknown : BillingResponse()

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun fromCode(code: Int): BillingResponse = when (code) {
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> ServiceUnavailable
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> FeatureNotSupported
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> ServiceDisconnected
            BillingClient.BillingResponseCode.OK -> OK
            BillingClient.BillingResponseCode.USER_CANCELED -> UserCanceled
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> ServiceUnavailable
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> BillingUnavailable
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ItemUnavailable
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> DeveloperError
            BillingClient.BillingResponseCode.ERROR -> Error
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ItemAlreadyOwned
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> ItemNotOwned
            BillingClient.BillingResponseCode.NETWORK_ERROR -> NetworkError
            else -> Unknown
        }
    }
}

/** 结构对照 RC `google/errors.kt`。 */
@Suppress("CyclomaticComplexMethod")
internal fun Int.getBillingResponseCodeName(): String = when (this) {
    BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> "SERVICE_TIMEOUT"
    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
    BillingClient.BillingResponseCode.OK -> "OK"
    BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
    BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
    BillingClient.BillingResponseCode.ERROR -> "ERROR"
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
    BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
    BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
    else -> "UNKNOWN_BILLING_RESPONSE_CODE ($this)"
}

/**
 * Play 在「设备没配 Google 账号 / 没有 Play Store / Play 缓存损坏」时会用这句
 * **debugMessage** 报错（坑 7，purchases-android#1288）。Version 3 是 2012 年的东西，
 * 这条消息本身毫无意义 —— 但它是区分「真的不可用」与「设备没登录」的唯一线索。
 *
 * ⚠️ PBL 9.0 起，「Play Store 被系统屏蔽（如 OEM 儿童模式）」从 `ERROR` 改成了
 * `BILLING_UNAVAILABLE` + "Play Store is blocked" debugMessage（官方 release notes，
 * 需 androidx.core ≥ 1.9）。两者都落在 `BILLING_UNAVAILABLE` 上，靠 debugMessage 区分。
 */
internal const val IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE: String =
    "Google Play In-app Billing API version is less than 3"

/** PBL 9.0 新增：Play Store 被系统屏蔽时的 debugMessage 片段。 */
internal const val PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT: String = "Play Store is blocked"

/**
 * Billing 响应码 → [PurchasesError]。结构与分组对照 RC `google/errors.kt`。
 */
@Suppress("CyclomaticComplexMethod")
internal fun Int.billingResponseToPurchasesError(underlyingErrorMessage: String): PurchasesError {
    val errorCode = when (this) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> PurchasesErrorCode.PurchaseNotAllowedError

        BillingClient.BillingResponseCode.ERROR,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        -> PurchasesErrorCode.StoreProblemError

        BillingClient.BillingResponseCode.OK -> PurchasesErrorCode.UnknownError
        BillingClient.BillingResponseCode.USER_CANCELED -> PurchasesErrorCode.PurchaseCancelledError
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> PurchasesErrorCode.ProductNotAvailableForPurchaseError
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> PurchasesErrorCode.PurchaseInvalidError
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchasesErrorCode.ProductAlreadyPurchasedError
        BillingClient.BillingResponseCode.NETWORK_ERROR -> PurchasesErrorCode.NetworkError
        else -> PurchasesErrorCode.UnknownError
    }
    return PurchasesError(errorCode, underlyingErrorMessage)
}

/**
 * `BILLING_UNAVAILABLE` 的两个特判（坑 7 + PBL 9.0 的新语义）。
 *
 * 两者都映射成 `storeProblemError` 并把 debugMessage 带上：
 * 错误文案必须引导用户「检查 Play 账号 / 清 Play 缓存」，**不是**「升级 Play」。
 */
internal fun BillingResult.toSetupError(): PurchasesError {
    val description = toHumanReadableDescription()
    return when {
        debugMessage == IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE -> PurchasesError(
            PurchasesErrorCode.StoreProblemError,
            "Play 账单不可用。常见原因：设备未登录 Google 账号、没有 Play Store（模拟器）、" +
                "或改语言后 Play 缓存损坏（打开 Play 商店或清它的缓存可修复）。$description",
        )
        debugMessage.contains(PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT, ignoreCase = true) -> PurchasesError(
            PurchasesErrorCode.StoreProblemError,
            "Play Store 被系统屏蔽（如 OEM 定制的儿童模式）。$description",
        )
        else -> responseCode.billingResponseToPurchasesError(description)
    }
}

/** 结构对照 RC `google/BillingResultExtensionsBillingIndependent.kt`。 */
internal fun BillingResult.toHumanReadableDescription(): String =
    "DebugMessage: $debugMessage. ErrorCode: ${responseCode.getBillingResponseCodeName()}."

internal fun BillingResult.isSuccessful(): Boolean = responseCode == BillingClient.BillingResponseCode.OK

internal fun ProductType.toGoogleProductType(): String? = when (this) {
    ProductType.SUBS -> BillingClient.ProductType.SUBS
    ProductType.INAPP -> BillingClient.ProductType.INAPP
    else -> null
}

internal fun String.toRevenueDogProductType(): ProductType = when (this) {
    BillingClient.ProductType.SUBS -> ProductType.SUBS
    BillingClient.ProductType.INAPP -> ProductType.INAPP
    else -> ProductType.UNKNOWN
}

/** 构造 `QueryProductDetailsParams` 时可能抛的包装异常（坑 12）。 */
internal class QueryProductDetailsParamsBuilderException(message: String, cause: Throwable?) :
    RuntimeException(message, cause)

/**
 * 结构对照 RC `google/billingClientParamBuilders.kt`。
 *
 * 坑 12：`setProductList` 在部分 Chromebook 上会抛 `ExceptionInInitializerError`。
 * 包成自家异常，让上层回一个正经的 `storeProblemError` 而不是崩溃。
 */
internal fun String.buildQueryProductDetailsParams(productIds: Set<String>): QueryProductDetailsParams {
    val productList = productIds.map { productId ->
        QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(this)
            .build()
    }
    return try {
        QueryProductDetailsParams.newBuilder().setProductList(productList).build()
    } catch (@Suppress("SwallowedException") e: ExceptionInInitializerError) {
        val errorMessage = "构造 QueryProductDetailsParams 失败（已知发生在部分 Chromebook 上）"
        Logger.error(e) { "$errorMessage: ${e.message}. Caused by: ${e.cause?.message}" }
        throw QueryProductDetailsParamsBuilderException(errorMessage, e.cause)
    }
}

/** M2 用。PBL 8 起 `queryPurchasesAsync` 必须带 `QueryPurchasesParams`。 */
internal fun String.buildQueryPurchasesParams(): QueryPurchasesParams? = when (this) {
    BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS ->
        QueryPurchasesParams.newBuilder().setProductType(this).build()
    else -> null
}
