package io.github.jqssun.airplay.billing

import android.content.Context
import android.util.Log
import com.amazon.device.drm.LicensingListener
import com.amazon.device.drm.LicensingService
import com.amazon.device.drm.model.LicenseResponse
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.UserDataResponse

private const val TAG = "AppstoreBilling"

// Not created in the Developer Console yet; purchaseDonation() stays unused until it is.
const val DONATION_SKU = "buzincast_donation"

// Registers with the Amazon Appstore SDK for anti-piracy license checks. No SKU is sold yet:
// purchaseDonation() is scaffolding for a future optional donation, not wired to any UI.
object AppstoreBilling : PurchasingListener {

    fun init(context: Context) {
        try {
            PurchasingService.registerListener(context, this)
            LicensingService.verifyLicense(context.applicationContext, LicenseCheck)
        } catch (e: Exception) {
            Log.w(TAG, "Appstore SDK unavailable (missing AppstoreAuthenticationKey.pem?)", e)
        }
    }

    fun purchaseDonation() {
        PurchasingService.purchase(DONATION_SKU)
    }

    private object LicenseCheck : LicensingListener {
        override fun onLicenseCommandResponse(licenseResponse: LicenseResponse) {
            Log.d(TAG, "License check: ${licenseResponse.requestStatus}")
        }
    }

    override fun onUserDataResponse(response: UserDataResponse) {
        Log.d(TAG, "User data: ${response.requestStatus}")
    }

    override fun onProductDataResponse(response: ProductDataResponse) {
        Log.d(TAG, "Product data: ${response.requestStatus}")
    }

    override fun onPurchaseResponse(response: PurchaseResponse) {
        when (response.requestStatus) {
            PurchaseResponse.RequestStatus.SUCCESSFUL ->
                PurchasingService.notifyFulfillment(response.receipt.receiptId, FulfillmentResult.FULFILLED)
            else -> Log.d(TAG, "Purchase not completed: ${response.requestStatus}")
        }
    }

    override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
        Log.d(TAG, "Purchase updates: ${response.requestStatus}")
    }
}
