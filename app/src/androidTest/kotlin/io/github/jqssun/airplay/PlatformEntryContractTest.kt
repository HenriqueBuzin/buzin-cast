package io.github.jqssun.airplay

import android.app.Activity
import android.content.BroadcastReceiver
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
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlatformEntryContractTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = target.getSharedPreferences("coverage-platform-entry", Context.MODE_PRIVATE)
    private val settings get() = target.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun settingsBroadcast(action: String?, key: String? = null, value: String? = null): Pair<Int, String?> {
        val complete = CountDownLatch(1)
        var response: Pair<Int, String?>? = null
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                response = resultCode to resultData
                complete.countDown()
            }
        }
        val request = Intent(target, SettingsReceiver::class.java).setAction(action)
        key?.let { request.putExtra("key", it) }
        value?.let { request.putExtra("value", it) }
        target.sendOrderedBroadcast(request, null, result, null, Activity.RESULT_FIRST_USER, "unchanged", null)
        assertTrue("Timed out waiting for settings broadcast result", complete.await(10, TimeUnit.SECONDS))
        return requireNotNull(response)
    }

    @Test fun settingsBroadcastListsTypedDefaultsAndIgnoresUnknownActions() {
        val (code, data) = settingsBroadcast("io.github.jqssun.airplay.LIST_SETTINGS")
        assertEquals(Activity.RESULT_OK, code)
        val listed = JSONObject(requireNotNull(data))
        assertEquals(Prefs.all.keys, listed.keys().asSequence().toSet())
        assertEquals(Prefs.SERVER_PORT.def, listed.getJSONObject(Prefs.SERVER_PORT.key).get("default"))
        assertEquals(Prefs.SERVER_PORT.read(settings), listed.getJSONObject(Prefs.SERVER_PORT.key).get("value"))
        assertEquals(Prefs.DLNA_ENABLED.def, listed.getJSONObject(Prefs.DLNA_ENABLED.key).get("default"))
        assertEquals(Prefs.DLNA_ENABLED.read(settings), listed.getJSONObject(Prefs.DLNA_ENABLED.key).get("value"))
        assertEquals(Prefs.SERVER_NAME.read(settings), listed.getJSONObject(Prefs.SERVER_NAME.key).get("value"))
        // A matching JVM hash must still be rejected if the action text differs.
        val collidingActions = listOf(
            "io.github.jqssun.airplay.SET_SETTING" to "io.github.jqssun.airplay.SET_SETTIMf",
            "io.github.jqssun.airplay.LIST_SETTINGS" to "io.github.jqssun.airplay.LIST_SETTINFr",
        )
        for ((known, unknown) in collidingActions) assertEquals(known.hashCode(), unknown.hashCode())
        for (action in listOf(null, "io.github.jqssun.airplay.UNRELATED_SETTING") + collidingActions.map { it.second }) {
            assertEquals(Activity.RESULT_FIRST_USER to "unchanged", settingsBroadcast(action))
        }
    }

    @Test fun settingsBroadcastValidatesBeforePersistingAndReportsEachOutcome() {
        val action = "io.github.jqssun.airplay.SET_SETTING"
        val port = Prefs.SERVER_PORT.key
        val enabled = Prefs.DLNA_ENABLED.key
        val originalPort = settings.all[port] as Int?
        val originalEnabled = settings.all[enabled] as Boolean?
        try {
            assertEquals(Activity.RESULT_CANCELED to "unknown key: null", settingsBroadcast(action))
            assertEquals(Activity.RESULT_CANCELED to "unknown key: invalid", settingsBroadcast(action, "invalid", "7001"))
            for (value in listOf(null, "not-a-port", "65536")) {
                assertEquals(Activity.RESULT_CANCELED to "invalid value for $port: $value", settingsBroadcast(action, port, value))
                assertEquals(originalPort, settings.all[port])
            }
            assertEquals(Activity.RESULT_OK to "$port=7001", settingsBroadcast(action, port, "7001"))
            assertEquals(7001, settings.all[port])
            assertEquals(Activity.RESULT_CANCELED to "invalid value for $enabled: TRUE", settingsBroadcast(action, enabled, "TRUE"))
            assertEquals(originalEnabled, settings.all[enabled])
            assertEquals(Activity.RESULT_OK to "$enabled=false", settingsBroadcast(action, enabled, "false"))
            assertEquals(false, settings.all[enabled])
        } finally {
            val restore = settings.edit()
            if (originalPort == null) restore.remove(port) else restore.putInt(port, originalPort)
            if (originalEnabled == null) restore.remove(enabled) else restore.putBoolean(enabled, originalEnabled)
            assertTrue(restore.commit())
        }
    }

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

    @Test fun localNetworkPermissionDecidesWhetherToAskAgainOrOpenSettings() {
        val ask = LocalNetworkAccess.ASK
        val settings = LocalNetworkAccess.SETTINGS
        val granted = LocalNetworkAccess.GRANTED

        assertEquals(granted, resolveLocalNetworkAccess(granted = true, requested = true, previous = settings) {
            error("Granted permission should not query rationale")
        })
        assertEquals(ask, resolveLocalNetworkAccess(granted = false, requested = false, previous = ask) { false })
        assertEquals(ask, resolveLocalNetworkAccess(granted = false, requested = false, previous = granted) { false })
        assertEquals(ask, resolveLocalNetworkAccess(granted = false, requested = true, previous = settings) { true })
        assertEquals(settings, resolveLocalNetworkAccess(granted = false, requested = true, previous = ask) { false })
        assertEquals(settings, resolveLocalNetworkAccess(granted = false, requested = false, previous = settings) { false })
    }
}
