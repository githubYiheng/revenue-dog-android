@file:JvmName("CoroutinesExtensions")

package org.revdog.purchases

import kotlinx.coroutines.suspendCancellableCoroutine
import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.offerings.Offerings
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 协程糖。结构对照 RC `defaults/coroutinesExtensions.kt`。
 *
 * 一律 `@JvmSynthetic`：Java 调用方看不到它们（Kotlin-only API 出现在 Java 补全里只会添乱）。
 * 失败抛 [PurchasesException]，`error` 字段里是原始的 [PurchasesError]。
 *
 * coroutines 只用在这一层「糖」上（设计 §0）：核心实现全是 callback + `synchronized`，
 * 与 RC 的并发模型一致，好调试、好在单测里跑。
 */

@JvmSynthetic
public suspend fun Purchases.awaitCustomerInfo(
    fetchPolicy: CacheFetchPolicy = CacheFetchPolicy.default(),
): CustomerInfo = suspendCancellableCoroutine { continuation ->
    getCustomerInfo(
        fetchPolicy,
        object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                if (continuation.isActive) continuation.resume(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
            }
        },
    )
}

@JvmSynthetic
public suspend fun Purchases.awaitOfferings(): Offerings = suspendCancellableCoroutine { continuation ->
    getOfferings(
        object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                if (continuation.isActive) continuation.resume(offerings)
            }

            override fun onError(error: PurchasesError) {
                if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
            }
        },
    )
}

@JvmSynthetic
public suspend fun Purchases.awaitLogIn(appUserID: String): LogInResult =
    suspendCancellableCoroutine { continuation ->
        logIn(
            appUserID,
            object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                    if (continuation.isActive) continuation.resume(LogInResult(customerInfo, created))
                }

                override fun onError(error: PurchasesError) {
                    if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
                }
            },
        )
    }

@JvmSynthetic
public suspend fun Purchases.awaitLogOut(): CustomerInfo = suspendCancellableCoroutine { continuation ->
    logOut(
        object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                if (continuation.isActive) continuation.resume(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
            }
        },
    )
}

/**
 * 购买的协程形态。
 *
 * **用户取消也会抛** [PurchasesException]（`code = purchaseCancelledError`）——
 * 挂起函数没有「第二个返回值」的位置，把 `userCancelled` 塞进错误码是唯一诚实的做法。
 * 需要区分「取消」与「失败」的宿主按 `error.code` 判断，或用 callback / `purchaseWith` 形态。
 */
@JvmSynthetic
public suspend fun Purchases.awaitPurchase(purchaseParams: PurchaseParams): PurchaseResult =
    suspendCancellableCoroutine { continuation ->
        purchase(
            purchaseParams,
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) {
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
                }
            },
        )
    }

@JvmSynthetic
public suspend fun Purchases.awaitRestore(): CustomerInfo = suspendCancellableCoroutine { continuation ->
    restorePurchases(
        object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                if (continuation.isActive) continuation.resume(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
            }
        },
    )
}

@JvmSynthetic
public suspend fun Purchases.awaitSyncPurchases(): CustomerInfo = suspendCancellableCoroutine { continuation ->
    syncPurchases(
        object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                if (continuation.isActive) continuation.resume(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                if (continuation.isActive) continuation.resumeWithException(PurchasesException(error))
            }
        },
    )
}
