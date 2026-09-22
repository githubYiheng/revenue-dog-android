package org.revdog.purchases.networking

import android.net.Uri

/**
 * 端点清单 + 逐端点策略声明（结构对照 RC `common/networking/Endpoint.kt`，
 * 策略声明的形状对齐 iOS `EndpointPolicy`）。
 *
 * 路径参数（尤其 `app_user_id`）**必须 URL 编码**后再拼接（契约 §1.1）—— 匿名 ID 里带 `$` 和 `:`。
 */
internal sealed class Endpoint(
    /** 是否参与 ETag 协商缓存（契约 §1.3）。 */
    val usesETag: Boolean,
    /**
     * 是否发 `X-Platform`。契约 §1.5：`GET /subscribers` **给了才更新 `last_seen`**，
     * 所以这是端点级决定，不是全局头。
     */
    val sendsPlatformHeader: Boolean,
) {

    abstract val path: String

    /** GET 没有 body。 */
    open val isPost: Boolean get() = false

    /**
     * 非 2xx 时是否补一条 `http_error` 诊断（契约 §1.3 的记录点：「非 receipts 端点的非 2xx」）。
     *
     * 只有两个端点是 `false`，理由各自独立（与 iOS `recordHTTPAttempt` 的头两个分支逐条同口径）：
     * - [PostReceipt] 每次尝试都已经记了 `receipt_post`（含成功），再记一条就是**双计**，
     *   不变式 18/19 的分母会被同一次失败污染两遍；
     * - [PostDiagnosticsEvents] 是诊断上传自己 —— 为它记事件会**自激**
     *   （上传失败 → 记一条 → 下次连它一起传 → 再失败 → 再记）。上传失败只在本地计数，
     *   成功之后补一条 `sdk_warning{diag_upload_failed}`（契约 §6-13）。
     */
    open val recordsHTTPError: Boolean get() = true

    /**
     * 进日志 / 诊断的**脱敏路径**：`app_user_id` 段一律换成 `*`（可能是宿主 uid，不进字段）。
     */
    abstract val diagnosticsPath: String

    /** `GET /v1/subscribers/{app_user_id}` —— 查询或创建 Customer（契约 §2.2）。 */
    class GetCustomerInfo(private val appUserID: String) :
        Endpoint(usesETag = true, sendsPlatformHeader = true) {
        override val path: String get() = "/v1/subscribers/${encode(appUserID)}"
        override val diagnosticsPath: String get() = "/v1/subscribers/*"
    }

    /** `GET /v1/subscribers/{app_user_id}/offerings`（契约 §2.3）。 */
    class GetOfferings(private val appUserID: String) :
        Endpoint(usesETag = true, sendsPlatformHeader = true) {
        override val path: String get() = "/v1/subscribers/${encode(appUserID)}/offerings"
        override val diagnosticsPath: String get() = "/v1/subscribers/*/offerings"
    }

    /**
     * `POST /v1/subscribers/identify` —— logIn 合并（服务端四分支矩阵，契约 §1.6）。
     * 不走 ETag：它是写操作。
     */
    object LogIn : Endpoint(usesETag = false, sendsPlatformHeader = true) {
        override val path: String get() = "/v1/subscribers/identify"
        override val isPost: Boolean get() = true
        override val diagnosticsPath: String get() = path
    }

    // 以下端点 M1 不调用，先把路径与策略钉在这里，M2/M3 接上去时不必重新定契约。

    /** `POST /v1/receipts` —— 上报购买（契约 §2.1）。M2。 */
    object PostReceipt : Endpoint(usesETag = false, sendsPlatformHeader = true) {
        override val path: String get() = "/v1/receipts"
        override val isPost: Boolean get() = true
        override val diagnosticsPath: String get() = path

        /** 它有自己的 `receipt_post`（成功也记），再发 `http_error` = 双计。 */
        override val recordsHTTPError: Boolean get() = false
    }

    /** `POST /v1/subscribers/{app_user_id}/attributes`（契约 §2.4）。M3。 */
    class PostAttributes(private val appUserID: String) :
        Endpoint(usesETag = false, sendsPlatformHeader = false) {
        override val path: String get() = "/v1/subscribers/${encode(appUserID)}/attributes"
        override val isPost: Boolean get() = true
        override val diagnosticsPath: String get() = "/v1/subscribers/*/attributes"
    }

    /** `POST /v1/diagnostics/events`（契约 §5.4）。M3。 */
    object PostDiagnosticsEvents : Endpoint(usesETag = false, sendsPlatformHeader = true) {
        override val path: String get() = "/v1/diagnostics/events"
        override val isPost: Boolean get() = true
        override val diagnosticsPath: String get() = path

        /** 诊断上传自己失败**绝不**再记诊断事件（否则自激成事件雪崩）。 */
        override val recordsHTTPError: Boolean get() = false
    }

    companion object {
        /**
         * `Uri.encode` 的默认 allow 集合会放过 `$`、`:` 等字符。我方保守到底：
         * 只留 unreserved 字符，其余一律百分号编码（与 iOS `encodePathComponent` 同口径，
         * 这样两端的出站快照能逐字对照）。
         */
        fun encode(value: String): String = Uri.encode(value, null)
    }
}
