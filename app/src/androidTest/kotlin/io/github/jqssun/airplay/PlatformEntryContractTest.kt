package io.github.jqssun.airplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.service.AirPlayService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformEntryContractTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = target.getSharedPreferences("coverage-platform-entry", Context.MODE_PRIVATE)

    private inner class RecordingContext : ContextWrapper(target) {
        val launches = mutableListOf<Pair<Intent, Boolean>>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals(Prefs.NAME, name)
            return preferences
        }
        override fun startService(service: Intent): ComponentName {
            launches += Intent(service) to false
            return service.component!!
        }
        override fun startForegroundService(service: Intent): ComponentName {
            launches += Intent(service) to true
            return service.component!!
        }
    }

    @After fun cleanup() {
        preferences.edit().clear().commit()
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(target)
    }

    @Test fun bootReceiverIgnoresUnrelatedBroadcastsEvenWhenEnabled() {
        preferences.edit().putBoolean(Prefs.BOOT_AUTO_START.key, true).commit()
        val context = RecordingContext()
        for (intent in listOf(Intent(), Intent(Intent.ACTION_SCREEN_ON), Intent("example.BOOT_COMPLETED"))) {
            BootReceiver().onReceive(context, intent)
        }
        assertTrue(context.launches.isEmpty())
    }

    @Test fun bootReceiverHonorsOptInForBothSupportedBootBroadcasts() {
        val context = RecordingContext()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON")) {
            preferences.edit().clear().putBoolean(Prefs.BOOT_AUTO_START.key, false).commit()
            BootReceiver().onReceive(context, Intent(action))
            assertTrue(context.launches.isEmpty())
            preferences.edit().putBoolean(Prefs.BOOT_AUTO_START.key, true).commit()
            BootReceiver().onReceive(context, Intent(action))
            val (intent, foreground) = context.launches.single()
            assertEquals(AirPlayService::class.java.name, intent.component!!.className)
            assertEquals(target.packageName, intent.component!!.packageName)
            assertEquals(AirPlayService.ACTION_START_SERVER, intent.action)
            assertEquals(Build.VERSION.SDK_INT >= 26, foreground)
            context.launches.clear()
        }
    }

    @Test fun applicationSettingsLinkTargetsThisEdition() {
        val intent = target.appSettingsIntent()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package", intent.data!!.scheme)
        assertEquals(target.packageName, intent.data!!.schemeSpecificPart)
        assertNull(intent.data!!.fragment)
    }

    @Test fun localNetworkPermissionMatchesPlatformRequirement() {
        for (permissionResult in listOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED)) {
            val checked = mutableListOf<String>()
            val context = object : ContextWrapper(target) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                    checked += permission
                    assertEquals(android.os.Process.myPid(), pid)
                    assertEquals(android.os.Process.myUid(), uid)
                    return permissionResult
                }
            }
            assertEquals(Build.VERSION.SDK_INT < LOCAL_NETWORK_API || permissionResult == PackageManager.PERMISSION_GRANTED,
                context.hasLocalNetwork())
            assertEquals(if (Build.VERSION.SDK_INT < LOCAL_NETWORK_API) emptyList<String>() else listOf(LOCAL_NETWORK_PERMISSION), checked)
        }
    }
}
