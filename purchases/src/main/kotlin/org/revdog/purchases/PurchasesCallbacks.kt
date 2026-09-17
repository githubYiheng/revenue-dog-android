package org.revdog.purchases

import org.revdog.purchases.customerinfo.CustomerInfo
import org.revdog.purchases.offerings.Offerings

// 结构对照 RC `PurchasesCallbackTypes.kt` / `interfaces/`：
// **callback 接口是主形态**（Java 可用），Kotlin 的 `…With { }` 与 `await…` 是糖（设计 §1）。

/** 任何只会失败的回调面。 */
public fun interface PurchasesErrorCallback {
    public fun onError(error: PurchasesError)
}

public interface ReceiveCustomerInfoCallback {
    public fun onReceived(customerInfo: CustomerInfo)
    public fun onError(error: PurchasesError)
}

public interface ReceiveOfferingsCallback {
    public fun onReceived(offerings: Offerings)
    public fun onError(error: PurchasesError)
}

public interface LogInCallback {
    /** [created] = 服务端 `201`，即这次 identify 新建了 customer。 */
    public fun onReceived(customerInfo: CustomerInfo, created: Boolean)
    public fun onError(error: PurchasesError)
}

/**
 * CustomerInfo 变更监听（对照 RC `UpdatedCustomerInfoListener`）。
 *
 * 两道守卫在 `CustomerInfoUpdateHandler` 里（考古 §6.5）：
 * ① 上一个用户的迟到响应**绝不**通知；② 与上次发出的 CustomerInfo 相同则不重复回调。
 */
public fun interface UpdatedCustomerInfoListener {
    public fun onReceived(customerInfo: CustomerInfo)
}
