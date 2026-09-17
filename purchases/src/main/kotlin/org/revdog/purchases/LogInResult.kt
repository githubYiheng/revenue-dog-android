package org.revdog.purchases

import org.revdog.purchases.customerinfo.CustomerInfo

/** logIn 结果。`created` = 服务端 201，即这次 identify 新建了 customer。 */
public class LogInResult internal constructor(
    public val customerInfo: CustomerInfo,
    public val created: Boolean,
)
