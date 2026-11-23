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

    fun init(context: Context) = init(
        context,
        registerListener = { PurchasingService.registerListener(it, this) },
        verifyLicense = { LicensingService.verifyLicense(it, LicenseCheck) },
    )

    internal fun init(
        context: Context,
        registerListener: (Context) -> Unit,
        verifyLicense: (Context) -> Unit,
    ) {
        try {
            registerListener(context)
        } catch (e: Exception) {
            Log.w(TAG, "Appstore purchasing listener registration failed", e)
        }
        try {
            verifyLicense(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Appstore license verification could not be started", e)
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

    override fun onPurchaseResponse(response: PurchaseResponse) =
        onPurchaseResponse(response, PurchasingService::notifyFulfillment)

    internal fun onPurchaseResponse(
        response: PurchaseResponse,
        notifyFulfillment: (String, FulfillmentResult) -> Unit,
    ) {
        when (response.requestStatus) {
            PurchaseResponse.RequestStatus.SUCCESSFUL ->
                notifyFulfillment(response.receipt.receiptId, FulfillmentResult.FULFILLED)
            else -> Log.d(TAG, "Purchase not completed: ${response.requestStatus}")
        }
    }

    override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
        Log.d(TAG, "Purchase updates: ${response.requestStatus}")
    }
}
