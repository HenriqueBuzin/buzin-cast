package io.github.jqssun.airplay

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.billing.AppstoreBilling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

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
}
