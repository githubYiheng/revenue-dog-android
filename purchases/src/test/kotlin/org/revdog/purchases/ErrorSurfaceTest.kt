package org.revdog.purchases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.revdog.purchases.diagnostics.DiagnosticsTracker
import org.revdog.purchases.google.IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE
import org.revdog.purchases.google.PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT
import org.revdog.purchases.google.billingResponseToPurchasesError
import org.revdog.purchases.google.getBillingResponseCodeName
import org.revdog.purchases.google.toSetupError
import org.revdog.purchases.google.toStoreTransaction
import org.revdog.purchases.support.BillingHarness
import org.revdog.purchases.support.OrchestratorHarness
import org.revdog.purchases.support.StoreProductBuilders
import org.revdog.purchases.support.purchaseFixture
import org.revdog.purchases.support.withMockDetails
import org.robolectric.RobolectricTestRunner

/**
 * 错误面收口（任务书 M3 第 5 项）：
 * - Billing 响应码 → `PurchasesError` 的**全量**映射（PBL 9.1.0 的 13 个码位一个不漏）；
 * - `BILLING_UNAVAILABLE` 的两个 debugMessage 特判；
 * - `PurchasesError` 带 `requestId`（响应头 `X-Request-Id`）；
 * - PENDING 路径的错误码是 `paymentPendingError`（20，与 RC 同位）。
 */
@RunWith(RobolectricTestRunner::class)
class ErrorSurfaceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // region Billing 响应码映射

    /** PBL 9.1.0 的 `BillingClient.BillingResponseCode` 全集（`javap` 过一遍确认就这 13 个）。 */
    private val allBillingResponseCodes = listOf(
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.OK,
        BillingClient.BillingResponseCode.USER_CANCELED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        BillingClient.BillingResponseCode.ERROR,
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        BillingClient.BillingResponseCode.NETWORK_ERROR,
    )

    @Test
    fun `13 个 Billing 响应码逐个映射，一个不漏到 unknownError`() {
        val expected = mapOf(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE to PurchasesErrorCode.PurchaseNotAllowedError,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED to PurchasesErrorCode.PurchaseNotAllowedError,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED to PurchasesErrorCode.PurchaseNotAllowedError,
            BillingClient.BillingResponseCode.ERROR to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT to PurchasesErrorCode.StoreProblemError,
            BillingClient.BillingResponseCode.USER_CANCELED to PurchasesErrorCode.PurchaseCancelledError,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
                to PurchasesErrorCode.ProductNotAvailableForPurchaseError,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR to PurchasesErrorCode.PurchaseInvalidError,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED to PurchasesErrorCode.ProductAlreadyPurchasedError,
            BillingClient.BillingResponseCode.NETWORK_ERROR to PurchasesErrorCode.NetworkError,
            // `OK` 走到错误映射上本身就是调用错误 → 兜底 unknownError（RC 同款）。
            BillingClient.BillingResponseCode.OK to PurchasesErrorCode.UnknownError,
        )
        assertThat(expected.keys).containsExactlyInAnyOrderElementsOf(allBillingResponseCodes)

        expected.forEach { (code, errorCode) ->
            assertThat(code.billingResponseToPurchasesError("debug").code)
                .describedAs("响应码 ${code.getBillingResponseCodeName()}")
                .isEqualTo(errorCode)
        }
    }

    @Test
    fun `响应码名字表覆盖全部 13 个码位且未知码带上数值`() {
        allBillingResponseCodes.forEach { code ->
            assertThat(code.getBillingResponseCodeName()).doesNotContain("UNKNOWN_BILLING_RESPONSE_CODE")
        }
        assertThat(999.getBillingResponseCodeName()).isEqualTo("UNKNOWN_BILLING_RESPONSE_CODE (999)")
    }

    @Test
    fun `映射一律带上 debugMessage（错误文案要能指导用户）`() {
        val error = BillingClient.BillingResponseCode.DEVELOPER_ERROR
            .billingResponseToPurchasesError("DebugMessage: bad params.")
        assertThat(error.underlyingErrorMessage).isEqualTo("DebugMessage: bad params.")
        assertThat(error.message).isEqualTo("DebugMessage: bad params.")
    }

    @Test
    fun `BILLING_UNAVAILABLE 的两个 debugMessage 特判落到 storeProblem`() {
        val lessThan3 = billingResult(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            IN_APP_BILLING_LESS_THAN_3_ERROR_MESSAGE,
        ).toSetupError()
        assertThat(lessThan3.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        assertThat(lessThan3.message).contains("未登录 Google 账号")

        val blocked = billingResult(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            "$PLAY_STORE_BLOCKED_ERROR_MESSAGE_FRAGMENT by policy",
        ).toSetupError()
        assertThat(blocked.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        assertThat(blocked.message).contains("被系统屏蔽")

        // 没命中特判 → 走通用映射（purchaseNotAllowed）。
        val plain = billingResult(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, "whatever").toSetupError()
        assertThat(plain.code).isEqualTo(PurchasesErrorCode.PurchaseNotAllowedError)
    }

    private fun billingResult(code: Int, debugMessage: String): BillingResult = mockk<BillingResult>().also {
        every { it.responseCode } returns code
        every { it.debugMessage } returns debugMessage
    }

    // endregion

    // region requestId

    @Test
    fun `后端错误带 requestId（X-Request-Id）并进 toString`() {
        val billing = BillingHarness()
        val harness = OrchestratorHarness(context, billing)
        harness.httpClient.enqueue(500, """{"code":7500,"message":"boom"}""")
        var captured: PurchasesError? = null

        harness.orchestrator.getCustomerInfo(
            CacheFetchPolicy.FETCH_CURRENT,
            object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: org.revdog.purchases.customerinfo.CustomerInfo) = Unit
                override fun onError(error: PurchasesError) {
                    captured = error
                }
            },
        )

        val error = requireNotNull(captured)
        assertThat(error.requestId).isEqualTo("req-test")
        assertThat(error.httpStatusCode).isEqualTo(500)
        assertThat(error.backendCode).isEqualTo(7500)
        assertThat(error.toString()).contains("request_id=req-test")
    }

    @Test
    fun `901 902 也把 requestId 带给宿主`() {
        val billing = BillingHarness()
        val harness = OrchestratorHarness(context, billing)
        val product = StoreProductBuilders.subscription("sub_premium", "monthly-base").withMockDetails()
        billing.subscriptionProducts = listOf(product)
        var captured: PurchasesError? = null
        harness.orchestrator.purchase(
            PurchaseParams.Builder(billing.activity, product).build(),
            object : PurchaseCallback {
                override fun onCompleted(result: PurchaseResult) = Unit
                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    captured = error
                }
            },
        )
        harness.httpClient.enqueue(503, """{"message":"unavailable"}""")
        billing.deliverPurchases(
            purchaseFixture(productIds = listOf("sub_premium"), purchaseToken = "token-sub")
                .toStoreTransaction(ProductType.SUBS, "monthly-base"),
        )

        val error = requireNotNull(captured)
        assertThat(error.code).isEqualTo(PurchasesErrorCode.PurchasePendingServerConfirmation)
        assertThat(error.requestId).isEqualTo("req-test")
        assertThat(error.httpStatusCode).isEqualTo(503)
    }

    // endregion

    // region PENDING 码位

    @Test
    fun `PENDING 交易的上报被挡住并回 paymentPendingError`() {
        val billing = BillingHarness()
        val harness = OrchestratorHarness(context, billing)
        val pending = purchaseFixture(
            productIds = listOf("sub_premium"),
            purchaseToken = "token-pending",
            purchaseState = Purchase.PurchaseState.PENDING,
        ).toStoreTransaction(ProductType.SUBS, "monthly-base")
        var captured: PurchasesError? = null

        harness.postReceiptHelper.postTransactionAndConsumeIfNeeded(
            purchase = pending,
            storeProduct = null,
            subscriptionOptionsForProductIds = null,
            isRestore = false,
            appUserID = "user-42",
            initiationSource = org.revdog.purchases.posting.InitiationSource.PURCHASE,
            sdkOriginated = true,
            onSuccess = { _, _ -> },
            onError = { _, error -> captured = error },
        )

        assertThat(requireNotNull(captured).code).isEqualTo(PurchasesErrorCode.PaymentPendingError)
        assertThat(captured!!.message).contains("尚未扣款")
        // 一个字节都没发出去。
        assertThat(harness.receiptRequests()).isEmpty()
        // 诊断留痕。
        assertThat(harness.diagnostics.named(DiagnosticsTracker.EVENT_PURCHASE_PENDING).single()["source"])
            .isEqualTo("post_receipt_guard")
    }

    @Test
    fun `只上报路径同样回 paymentPendingError`() {
        val billing = BillingHarness()
        val harness = OrchestratorHarness(context, billing)
        val pending = purchaseFixture(
            purchaseToken = "token-pending",
            purchaseState = Purchase.PurchaseState.PENDING,
        ).toStoreTransaction(ProductType.INAPP, null)
        var captured: PurchasesError? = null

        harness.postReceiptHelper.postTokenWithoutConsuming(
            purchase = pending,
            appUserID = "user-42",
            initiationSource = org.revdog.purchases.posting.InitiationSource.UNSYNCED_ACTIVE_PURCHASES,
            isRestore = false,
            onSuccess = { },
            onError = { error -> captured = error },
        )

        assertThat(requireNotNull(captured).code).isEqualTo(PurchasesErrorCode.PaymentPendingError)
        assertThat(harness.receiptRequests()).isEmpty()
    }

    // endregion
}
