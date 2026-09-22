package org.revdog.purchases.diagnostics

import org.revdog.purchases.PurchasesError
import org.revdog.purchases.PurchasesErrorCode

/**
 * 失败类诊断事件的**统一原因字段**（0.1.2 新增，契约 `sdk-diagnostics.md` §8；
 * `error_class` 的词汇与判定顺序见 §1.3 —— 0.1.3 起全端只有一套）。
 *
 * 起因：2026-09-22 首个真实宿主在生产的走查里出现两条 error 级诊断，两条都停在
 * 「知道失败了，不知道为什么」：
 * - `sync` 失败 `error_code=purchaseNotAllowedError` —— 这一个码位同时对应 Play 的
 *   `BILLING_UNAVAILABLE` / `ITEM_NOT_OWNED` / `FEATURE_NOT_SUPPORTED`，分不出是哪一个；
 * - `identity_login` 失败 `error_code=unknownBackendError` —— 任意非 2xx 都落这里，
 *   没有 `status` 就判断不了是边缘层还是我方 API。
 *
 * 所以**每一个**失败诊断都附上同一组字段：调用点不再各写各的，全部走 [of]。
 * 这些字段一律**只增不改** —— 既有字段（`error_code` / `status` / `request_id` /
 * `receipt_post` 的 `error_class`）的取值一个字没动，后端的提列与巡检不变式照旧。
 *
 * **对照 RC**（`purchases-android` 10.22.1 `common/diagnostics/DiagnosticsTracker.kt`）：
 * `billing_response_code` / `billing_debug_message` 两个键名逐字取自 RC
 * （`BILLING_RESPONSE_CODE` / `BILLING_DEBUG_MESSAGE`，RC 在 6 个 Google 事件上都带它们）。
 * RC 的错误文案键叫 `error_message`（`ERROR_MESSAGE_KEY`）——
 * 我方叫 [KEY_UNDERLYING]，因为契约 §1.3 里 "message" 一词指的是后端响应体，
 * 避免两个概念同名。`error_class` 是我方独有，RC 没有对等物。
 *
 * **不进这些字段**（契约 §1.3 的红线不变）：邮箱、姓名、设备名、任何 token / 密钥、
 * 请求或响应 body、URL 查询串。[KEY_UNDERLYING] 与 [KEY_BILLING_DEBUG_MESSAGE] 取的都是
 * **SDK 自己或 Play 生成**的诊断文案，不是用户数据；两者都按
 * `DiagnosticsEvent.MAX_FIELD_STRING_LENGTH`（200）截断。
 */
internal object DiagnosticsErrorFields {

    // region 字段名

    const val KEY_ERROR_CODE: String = "error_code"

    /** wire 契约 §1.3 的提列名就是 `status`（不是 `http_status`）。 */
    const val KEY_STATUS: String = "status"

    /** 契约 §1.4 错误体里的数值码（例如 7243 = 在 app 里用了 secret key）。 */
    const val KEY_BACKEND_CODE: String = "backend_code"

    const val KEY_ERROR_CLASS: String = "error_class"

    /** `PurchasesError.underlyingErrorMessage`。 */
    const val KEY_UNDERLYING: String = "underlying"

    /** RC 同名（`DiagnosticsTracker.BILLING_RESPONSE_CODE`）。 */
    const val KEY_BILLING_RESPONSE_CODE: String = "billing_response_code"

    /** RC 同名（`DiagnosticsTracker.BILLING_DEBUG_MESSAGE`）。 */
    const val KEY_BILLING_DEBUG_MESSAGE: String = "billing_debug_message"

    // endregion

    // region error_class 取值（**全 SDK 唯一一套词汇**，契约 §1.3）

    /** 传输层失败：连不上、断网、DNS 挂了。 */
    const val NETWORK: String = "network"

    /** 传输层失败且底层异常是超时类。与 [NETWORK] 分开是因为两者的排查方向完全不同。 */
    const val TIMEOUT: String = "timeout"

    /**
     * 401 / 403 —— key 配错了、环境串了、或者 secret key 被塞进了 app。
     * 与 [CLIENT] / [SERVER] 一起**就是** [DiagnosticsErrorClass] 的原口径：
     * 0.1.3 把 0.1.2 的 `http` 拆成这三档，两套词汇因此合并成一套
     * （jobs 不变式 18/19 与 admin `launch-sync` 逐字依赖的取值一个字没变）。
     */
    const val AUTH: String = "auth"

    /** 401/403 之外的 4xx：确定性的客户端错误，重试不会有不同结果。 */
    const val CLIENT: String = "client"

    /** 5xx：服务端侧的失败，可重试。 */
    const val SERVER: String = "server"

    /** Play Billing 侧的失败。具体码位看 `billing_response_code`。 */
    const val BILLING: String = "billing"

    /** 响应不是我们要的形状（JSON 坏了 / 缺字段 / 缓存里没有）。 */
    const val PARSE: String = "parse"

    /** 接线或用法问题：宿主改代码才能修（缺权限、key 配错、重复调用、平台不支持）。 */
    const val CONFIG: String = "config"

    /** 以上都不是。出现得多就说明这张表该补了。 */
    const val UNKNOWN: String = "unknown"

    /**
     * 全集。单测拿它断言 [classify] 不会返回表外的值。
     * [DiagnosticsErrorClass] 的四个值（`network` / `auth` / `client` / `server`）是它的子集。
     */
    val ALL_CLASSES: List<String> =
        listOf(NETWORK, TIMEOUT, AUTH, CLIENT, SERVER, BILLING, PARSE, CONFIG, UNKNOWN)

    // endregion

    /**
     * 一个失败诊断该附的全部原因字段。`null` 的键由
     * `DiagnosticsEvent.normalizeFields` 丢掉（契约「fields 全部可选，缺失容忍」），
     * 所以这里不做任何条件拼装 —— 调用点写法统一，出来的事件仍然只带有值的那几个。
     */
    fun of(error: PurchasesError): Map<String, Any?> = mapOf(
        KEY_ERROR_CODE to error.code.name,
        KEY_STATUS to error.httpStatusCode,
        KEY_BACKEND_CODE to error.backendCode,
        KEY_ERROR_CLASS to classify(error),
        KEY_UNDERLYING to error.underlyingErrorMessage,
        KEY_BILLING_RESPONSE_CODE to error.billingResponseCode,
        KEY_BILLING_DEBUG_MESSAGE to error.billingDebugMessage,
    )

    /**
     * **唯一**的分类函数（全 SDK 只有这一处推 `error_class`）。
     *
     * 判定顺序是有讲究的，从「证据最硬」排到「只能靠码位猜」：
     * 1. 带了 Play 的整数响应码 → 板上钉钉是 Billing 链路；
     * 2. 底层异常类名带 Timeout → `timeout`（它的码位也是 `networkError`，必须排在 ③ 前面）；
     * 3. 码位是网络类 → `network`；
     * 4. 拿到了 HTTP 状态码 → 服务端确实应答了 → 交给 [DiagnosticsErrorClass.from] 分三档
     *    （401/403 = `auth`、其余 4xx = `client`、5xx = `server`）。**0.1.3 起没有 `http` 这个值**：
     *    它原本只说「服务端回了个非 2xx」，等于把最有用的那一刀（是鉴权挂了还是后端挂了）留给查询方去切；
     * 5.–7. 剩下的按码位归到 `parse` / `config` / `billing`，都落不上就 `unknown`。
     */
    fun classify(error: PurchasesError): String = when {
        error.billingResponseCode != null -> BILLING
        isTimeout(error) -> TIMEOUT
        error.code in NETWORK_CODES -> NETWORK
        error.httpStatusCode != null -> DiagnosticsErrorClass.from(error.httpStatusCode)
        error.code in PARSE_CODES -> PARSE
        error.code in CONFIG_CODES -> CONFIG
        error.code in BILLING_CODES -> BILLING
        else -> UNKNOWN
    }

    /**
     * **没有响应**时的 `error_class` —— `http_error` 的传输层分支专用
     * （那条路上根本没有 [PurchasesError]：`HTTPClient` 抛的是原始 `IOException`，
     * 翻译成码位是上层 `Backend.AsyncCall` 的事）。
     *
     * 与 [classify] 的 ②③ 两档同一把尺子：超时和断网在我方全都是 `networkError`，
     * 只有异常类名能把两者分开。
     */
    fun classifyTransport(throwable: Throwable?): String =
        if (isTimeoutName(throwable?.javaClass?.simpleName)) TIMEOUT else NETWORK

    private fun isTimeout(error: PurchasesError): Boolean = isTimeoutName(error.underlyingCauseName)

    /**
     * `HttpURLConnection` 的超时是 `java.net.SocketTimeoutException`；
     * 别的 HTTP 栈（宿主换实现 / 未来换 OkHttp）会给 `InterruptedIOException` 或
     * `*ConnectTimeoutException`。按类名匹配而不是按消息匹配：消息会随 JDK 与语言变，类名不会。
     */
    private fun isTimeoutName(causeName: String?): Boolean {
        if (causeName == null) return false
        return causeName.contains("Timeout", ignoreCase = true) || causeName == "InterruptedIOException"
    }

    private val NETWORK_CODES = setOf(
        PurchasesErrorCode.NetworkError,
        PurchasesErrorCode.OfflineConnectionError,
    )

    private val PARSE_CODES = setOf(
        PurchasesErrorCode.UnexpectedBackendResponseError,
        // 「缓存里没有 CustomerInfo」/「拼不出 CustomerInfo」都走它 —— 都是「拿到了东西但用不了」。
        PurchasesErrorCode.CustomerInfoError,
    )

    private val CONFIG_CODES = setOf(
        PurchasesErrorCode.ConfigurationError,
        PurchasesErrorCode.UnsupportedError,
        PurchasesErrorCode.NotImplementedError,
        // 没带 HTTP 状态码时它来自端上的守卫（logIn 传了空 id / 已是匿名还 logOut）= 用法问题。
        PurchasesErrorCode.InvalidAppUserIdError,
        PurchasesErrorCode.InvalidCredentialsError,
        PurchasesErrorCode.OperationAlreadyInProgressError,
        PurchasesErrorCode.InvalidAppleSubscriptionKeyError,
        PurchasesErrorCode.EmptySubscriberAttributesError,
    )

    /**
     * 端上**自己**判定出来的 Play 语义错误（没有响应码可带，比如「只有订阅支持升降级」、
     * 「这台设备的 Play 不支持升降级」、构造 `QueryProductDetailsParams` 炸了）。
     */
    private val BILLING_CODES = setOf(
        PurchasesErrorCode.StoreProblemError,
        PurchasesErrorCode.PurchaseNotAllowedError,
        PurchasesErrorCode.PurchaseCancelledError,
        PurchasesErrorCode.PurchaseInvalidError,
        PurchasesErrorCode.ProductNotAvailableForPurchaseError,
        PurchasesErrorCode.ProductAlreadyPurchasedError,
        PurchasesErrorCode.PaymentPendingError,
        PurchasesErrorCode.ProductDiscountMissingIdentifierError,
    )
}
