package org.revdog.purchases

import dev.drewhamilton.poko.Poko

/**
 * 错误码。**码位表的唯一真相源是 `sdk/error-codes.json`**（两端共用），
 * `ErrorCodesContractTest` 逐项对账；改这里不改 JSON 会红。
 *
 * 结构对照 RC `generated/.../PurchasesErrorCode.kt`（RC 从 purchases-error-codes 仓库生成），
 * 但**不是 enum**：后端新增码位不能让宿主里穷尽的 `when` 编译不过（`ForbiddenPublicEnum`）。
 * 与 iOS `PurchasesErrorCode`（struct + static 常量）逐字同形。
 */
@Poko
public class PurchasesErrorCode private constructor(
    public val code: Int,
    public val name: String,
) {

    override fun toString(): String = "$name($code)"

    public companion object {
        @JvmField public val UnknownError: PurchasesErrorCode = PurchasesErrorCode(0, "unknownError")
        @JvmField public val PurchaseCancelledError: PurchasesErrorCode =
            PurchasesErrorCode(1, "purchaseCancelledError")
        @JvmField public val StoreProblemError: PurchasesErrorCode = PurchasesErrorCode(2, "storeProblemError")
        @JvmField public val PurchaseNotAllowedError: PurchasesErrorCode =
            PurchasesErrorCode(3, "purchaseNotAllowedError")
        @JvmField public val PurchaseInvalidError: PurchasesErrorCode = PurchasesErrorCode(4, "purchaseInvalidError")
        @JvmField public val ProductNotAvailableForPurchaseError: PurchasesErrorCode =
            PurchasesErrorCode(5, "productNotAvailableForPurchaseError")
        @JvmField public val ProductAlreadyPurchasedError: PurchasesErrorCode =
            PurchasesErrorCode(6, "productAlreadyPurchasedError")
        @JvmField public val ReceiptAlreadyInUseError: PurchasesErrorCode =
            PurchasesErrorCode(7, "receiptAlreadyInUseError")
        @JvmField public val InvalidReceiptError: PurchasesErrorCode = PurchasesErrorCode(8, "invalidReceiptError")
        @JvmField public val MissingReceiptFileError: PurchasesErrorCode =
            PurchasesErrorCode(9, "missingReceiptFileError")
        @JvmField public val NetworkError: PurchasesErrorCode = PurchasesErrorCode(10, "networkError")
        @JvmField public val InvalidCredentialsError: PurchasesErrorCode =
            PurchasesErrorCode(11, "invalidCredentialsError")
        @JvmField public val UnexpectedBackendResponseError: PurchasesErrorCode =
            PurchasesErrorCode(12, "unexpectedBackendResponseError")
        @JvmField public val InvalidAppUserIdError: PurchasesErrorCode = PurchasesErrorCode(14, "invalidAppUserIdError")
        @JvmField public val OperationAlreadyInProgressError: PurchasesErrorCode =
            PurchasesErrorCode(15, "operationAlreadyInProgressError")
        @JvmField public val UnknownBackendError: PurchasesErrorCode = PurchasesErrorCode(16, "unknownBackendError")
        @JvmField public val InvalidAppleSubscriptionKeyError: PurchasesErrorCode =
            PurchasesErrorCode(17, "invalidAppleSubscriptionKeyError")

        /**
         * 付款尚在进行中：Play 的 `PENDING` / `UNSPECIFIED_STATE` 交易（现金支付、待家长批准、
         * 预付费套餐）。**钱还没扣** —— 宿主既不该发权益，也不该提示失败。
         * 码位与 RC 的 `PaymentPendingError` 同位（20）。
         */
        @JvmField public val PaymentPendingError: PurchasesErrorCode = PurchasesErrorCode(20, "paymentPendingError")
        @JvmField public val ConfigurationError: PurchasesErrorCode = PurchasesErrorCode(23, "configurationError")
        @JvmField public val UnsupportedError: PurchasesErrorCode = PurchasesErrorCode(24, "unsupportedError")
        @JvmField public val EmptySubscriberAttributesError: PurchasesErrorCode =
            PurchasesErrorCode(25, "emptySubscriberAttributesError")
        @JvmField public val ProductDiscountMissingIdentifierError: PurchasesErrorCode =
            PurchasesErrorCode(26, "productDiscountMissingIdentifierError")
        @JvmField public val CustomerInfoError: PurchasesErrorCode = PurchasesErrorCode(28, "customerInfoError")
        @JvmField public val SystemInfoError: PurchasesErrorCode = PurchasesErrorCode(29, "systemInfoError")
        @JvmField public val OfflineConnectionError: PurchasesErrorCode =
            PurchasesErrorCode(35, "offlineConnectionError")

        /** 我方专有：当前里程碑尚未实现的路径。 */
        @JvmField public val NotImplementedError: PurchasesErrorCode = PurchasesErrorCode(900, "notImplementedError")

        /**
         * 我方专有：**扣款已经发生**、服务端尚未确认这笔收据。
         * 交易未完成、上下文已落盘、SDK 会重放。宿主提示「稍后到账」，**绝不引导重买**。
         */
        @JvmField public val PurchasePendingServerConfirmation: PurchasesErrorCode =
            PurchasesErrorCode(901, "purchasePendingServerConfirmation")

        /**
         * 我方专有：**扣款已经发生**、服务端确定性拒绝了这笔收据。不会再有权益，走客服 / 退款路径。
         */
        @JvmField public val PurchaseRejectedByServer: PurchasesErrorCode =
            PurchasesErrorCode(902, "purchaseRejectedByServer")

        /** 全表。`ErrorCodesContractTest` 拿它与 `sdk/error-codes.json` 对账。 */
        @JvmField
        public val ALL: List<PurchasesErrorCode> = listOf(
            UnknownError, PurchaseCancelledError, StoreProblemError, PurchaseNotAllowedError, PurchaseInvalidError,
            ProductNotAvailableForPurchaseError, ProductAlreadyPurchasedError, ReceiptAlreadyInUseError,
            InvalidReceiptError, MissingReceiptFileError, NetworkError, InvalidCredentialsError,
            UnexpectedBackendResponseError, InvalidAppUserIdError, OperationAlreadyInProgressError,
            UnknownBackendError, InvalidAppleSubscriptionKeyError, PaymentPendingError,
            ConfigurationError, UnsupportedError,
            EmptySubscriberAttributesError, ProductDiscountMissingIdentifierError, CustomerInfoError,
            SystemInfoError, OfflineConnectionError, NotImplementedError, PurchasePendingServerConfirmation,
            PurchaseRejectedByServer,
        )

        /**
         * 后端 / 商店给出我们不认识的码位时的兜底：**不抛异常**，降级成 `unknownError` 但保留原始数值。
         * 容忍未知值是契约 §1.8 的硬要求。
         */
        @JvmStatic
        public fun fromCode(code: Int): PurchasesErrorCode =
            ALL.firstOrNull { it.code == code } ?: PurchasesErrorCode(code, UnknownError.name)
    }
}

/**
 * 单一错误面（设计 §1）。结构对照 RC `PurchasesError`，多带两个我方字段：
 * `backendCode`（契约 §1.4 错误体里的数值码，如 7243）与 `httpStatusCode`。
 * 与 iOS `PurchasesError` 逐字段对齐。
 */
@Poko
public class PurchasesError @JvmOverloads constructor(
    public val code: PurchasesErrorCode,
    public val underlyingErrorMessage: String? = null,
    /** 契约 §1.4 的 `code`（例如 7243 = 在 app 里用了 secret key）。 */
    public val backendCode: Int? = null,
    public val httpStatusCode: Int? = null,
    /**
     * 响应头 `X-Request-Id`（有就带）。排障时它是把宿主报的一句「买不了」接到
     * 服务端日志与客户端诊断事件上的**唯一**线索（诊断事件的 `request_id` 取的是同一个值）。
     */
    public val requestId: String? = null,
) {

    public val message: String
        get() = underlyingErrorMessage ?: code.name

    override fun toString(): String = buildString {
        append("[").append(code).append("] ").append(message)
        backendCode?.let { append(" backend_code=").append(it) }
        httpStatusCode?.let { append(" http=").append(it) }
        requestId?.let { append(" request_id=").append(it) }
    }

    public companion object {
        internal fun notImplemented(symbol: String, milestone: String): PurchasesError = PurchasesError(
            PurchasesErrorCode.NotImplementedError,
            "$symbol 尚未实现（计划：$milestone）",
        )

        internal fun configuration(message: String): PurchasesError =
            PurchasesError(PurchasesErrorCode.ConfigurationError, message)
    }
}

/**
 * 把 [PurchasesError] 包成异常，供 `await…` 挂起函数抛出（对照 RC `PurchasesException`）。
 */
public class PurchasesException(
    public val error: PurchasesError,
) : Exception(error.toString())
