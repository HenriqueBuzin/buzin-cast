package io.github.jqssun.airplay

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.device.iap.internal.model.ProductDataResponseBuilder
import com.amazon.device.iap.internal.model.PurchaseResponseBuilder
import com.amazon.device.iap.internal.model.PurchaseUpdatesResponseBuilder
import com.amazon.device.iap.internal.model.ReceiptBuilder
import com.amazon.device.iap.internal.model.UserDataBuilder
import com.amazon.device.iap.internal.model.UserDataResponseBuilder
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.ProductType
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.RequestId
import com.amazon.device.iap.model.UserDataResponse
import io.github.jqssun.airplay.billing.AppstoreBilling
import io.github.jqssun.airplay.billing.DONATION_SKU
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class AppstoreBillingContractTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val startupContext = ContextWrapper(app)

    @After fun dump() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(app)
    }

    @Test fun duplicatePurchaseRegistrationStillStartsLicenseVerification() {
        val calls = mutableListOf<String>()
        AppstoreBilling.init(
            startupContext,
            registerListener = {
                assertSame(startupContext, it)
                calls += "register"
                throw IllegalStateException("Resource already registered")
            },
            verifyLicense = {
                assertSame(app, it)
                calls += "verify"
            },
        )
        assertEquals(listOf("register", "verify"), calls)
    }

    @Test fun unavailableLicensingServiceDoesNotPropagateAnExceptionToStartup() {
        val calls = mutableListOf<String>()
        AppstoreBilling.init(
            startupContext,
            registerListener = {
                assertSame(startupContext, it)
                calls += "register"
            },
            verifyLicense = {
                assertSame(app, it)
                calls += "verify"
                throw IllegalStateException("License service unavailable")
            },
        )
        assertEquals(listOf("register", "verify"), calls)
    }

    @Test fun onlySuccessfulPurchasesRequestFulfillmentForTheirReceipt() {
        val request = RequestId()
        val user = UserDataBuilder().setUserId("local-test-user").setMarketplace("US").build()
        val receipt = ReceiptBuilder()
            .setReceiptId("local-test-receipt")
            .setSku(DONATION_SKU)
            .setProductType(ProductType.CONSUMABLE)
            .setPurchaseDate(Date(0))
            .build()
        val fulfillments = mutableListOf<Pair<String, FulfillmentResult>>()
        for (status in PurchaseResponse.RequestStatus.entries) {
            val response = PurchaseResponseBuilder().setRequestId(request).setRequestStatus(status)
                .setUserData(user).setReceipt(receipt)
            AppstoreBilling.onPurchaseResponse(response.build()) { id, result ->
                fulfillments += id to result
            }
        }
        assertEquals(listOf(receipt.receiptId to FulfillmentResult.FULFILLED), fulfillments)
    }

    @Test fun missingAppstoreDataDoesNotInterruptThePurchasingListener() {
        val request = RequestId()
        AppstoreBilling.onUserDataResponse(UserDataResponseBuilder()
            .setRequestId(request).setRequestStatus(UserDataResponse.RequestStatus.FAILED).build())
        AppstoreBilling.onProductDataResponse(ProductDataResponseBuilder()
            .setRequestId(request).setRequestStatus(ProductDataResponse.RequestStatus.FAILED).build())
        AppstoreBilling.onPurchaseUpdatesResponse(PurchaseUpdatesResponseBuilder()
            .setRequestId(request).setRequestStatus(PurchaseUpdatesResponse.RequestStatus.FAILED).build())
        AppstoreBilling.onPurchaseResponse(PurchaseResponseBuilder()
            .setRequestId(request).setRequestStatus(PurchaseResponse.RequestStatus.NOT_SUPPORTED).build())
    }
}
