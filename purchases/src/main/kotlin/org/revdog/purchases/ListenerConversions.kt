@file:JvmName("ListenerConversions")
@file:Suppress("TooManyFunctions")

package org.revdog.purchases

import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.offerings.Offerings

/**
 * Kotlin lambda 形态。结构对照 RC `defaults/listenerConversions.kt`。
 *
 * `@JvmSynthetic` 把它们对 Java 隐藏 —— Java 调用方用本体的 callback 接口
 * （考古 §8.2：RC 用了 22 处 `@JvmSynthetic` 做这件事）。
 */

private fun receiveCustomerInfoCallback(
    onSuccess: (CustomerInfo) -> Unit,
    onError: (PurchasesError) -> Unit,
) = object : ReceiveCustomerInfoCallback {
    override fun onReceived(customerInfo: CustomerInfo) = onSuccess(customerInfo)
    override fun onError(error: PurchasesError) = onError(error)
}

@JvmSynthetic
public fun Purchases.getCustomerInfoWith(
    fetchPolicy: CacheFetchPolicy = CacheFetchPolicy.default(),
    onError: (error: PurchasesError) -> Unit = LogErrorsDefault,
    onSuccess: (customerInfo: CustomerInfo) -> Unit,
) {
    getCustomerInfo(fetchPolicy, receiveCustomerInfoCallback(onSuccess, onError))
}

@JvmSynthetic
public fun Purchases.getOfferingsWith(
    onError: (error: PurchasesError) -> Unit = LogErrorsDefault,
    onSuccess: (offerings: Offerings) -> Unit,
) {
    getOfferings(
        object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) = onSuccess(offerings)
            override fun onError(error: PurchasesError) = onError(error)
        },
    )
}

@JvmSynthetic
public fun Purchases.logInWith(
    appUserID: String,
    onError: (error: PurchasesError) -> Unit = LogErrorsDefault,
    onSuccess: (customerInfo: CustomerInfo, created: Boolean) -> Unit,
) {
    logIn(
        appUserID,
        object : LogInCallback {
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) = onSuccess(customerInfo, created)
            override fun onError(error: PurchasesError) = onError(error)
        },
    )
}

@JvmSynthetic
public fun Purchases.logOutWith(
    onError: (error: PurchasesError) -> Unit = LogErrorsDefault,
    onSuccess: (customerInfo: CustomerInfo) -> Unit,
) {
    logOut(receiveCustomerInfoCallback(onSuccess, onError))
}

/** 不传 `onError` 时的默认处置：**记 error 级日志**，绝不静默吞掉。 */
private val LogErrorsDefault: (PurchasesError) -> Unit = { error ->
    Logger.error { error.toString() }
}
