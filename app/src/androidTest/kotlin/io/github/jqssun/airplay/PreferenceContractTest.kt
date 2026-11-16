package io.github.jqssun.airplay

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class PreferenceContractTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=context.getSharedPreferences("coverage-preference-contracts",0)
    @After fun cleanup() {
        prefs.edit().clear().commit()
        if(runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun typedPreferencesRoundTripAndRejectOutOfRangeStoredValues() {
        prefs.edit().clear().commit()
        assertTrue(Prefs.all.isNotEmpty())
        for(pref in Prefs.all.values) {
            assertEquals(pref.key,pref.def,pref.read(prefs))
            when(pref) {
                is BoolPref -> {
                    assertEquals(true,pref.parse("true"));assertEquals(false,pref.parse("false"))
                    assertNull(pref.parse("TRUE"));assertNull(pref.parse("1"))
                    assertTrue(pref.write(prefs,!pref.def));assertEquals(!pref.def,prefs[pref])
                    assertTrue(pref.write(prefs,pref.def));assertEquals(pref.def,prefs[pref])
                }
                is IntPref -> {
                    assertEquals(pref.range.first,pref.parse(pref.range.first.toString()))
                    assertNull(pref.parse("1.5"));assertNull(pref.parse("2147483648"))
                    for(valid in listOf(pref.range.first,pref.range.last)) {
                        assertTrue(pref.write(prefs,valid));assertEquals(valid,prefs[pref])
                    }
                    val invalid=pref.range.first-1
                    assertFalse(pref.write(prefs,invalid));assertEquals(pref.range.last,prefs[pref])
                    prefs.edit().putInt(pref.key,invalid).commit()
                    assertEquals(pref.def,prefs[pref])
                    if(pref.range.last<Int.MAX_VALUE) assertFalse(pref.write(prefs,pref.range.last+1))
                }
                is StrPref -> {
                    assertEquals(pref.def,pref.parse(pref.def))
                    assertTrue(pref.write(prefs,pref.def));assertEquals(pref.def,prefs[pref])
                }
            }
        }
        assertTrue(Prefs.SERVER_NAME.restart)
        assertFalse(Prefs.AUTO_START.restart)
    }

    @Test fun receiverNameEnforcesUtf8ByteLimitAndControlCharacterRules() {
        for(name in listOf("A".repeat(63),"é".repeat(31)+"x"," Sala ")) {
            assertTrue(Prefs.SERVER_NAME.write(prefs,name))
            assertEquals(name,prefs[Prefs.SERVER_NAME])
        }
        val previous=prefs[Prefs.SERVER_NAME]
        for(name in listOf(""," ","A".repeat(64),"é".repeat(32),"TV\n","TV\u0000","TV\u007f","TV\u0085")) {
            assertFalse(name,Prefs.SERVER_NAME.write(prefs,name))
            assertEquals(previous,prefs[Prefs.SERVER_NAME])
        }
        prefs.edit().putString(Prefs.SERVER_NAME.key,"\n").commit()
        assertEquals(Prefs.SERVER_NAME.def,prefs[Prefs.SERVER_NAME])
    }

    @Test fun resolutionProfileAndTriStatePreferencesRejectUnsupportedValues() {
        for(value in listOf(Prefs.AUTO,Prefs.PORTRAIT,Prefs.LANDSCAPE,"1x1","99999x99999"))
            assertTrue(value,Prefs.RESOLUTION.write(prefs,value))
        for(value in listOf("0x720","1920X1080","1920x0","100000x720","1920x1080\n"))
            assertFalse(value,Prefs.RESOLUTION.write(prefs,value))
        for(pref in listOf(Prefs.H265,Prefs.OPERATING_RATE)) {
            for(value in listOf(Prefs.AUTO,Prefs.ON,Prefs.OFF)) assertTrue(pref.write(prefs,value))
            assertFalse(pref.write(prefs,"enabled"))
        }
        for(value in listOf(Prefs.DEFAULT_PROFILE,"Cinema","Apresentação","Jogos"))
            assertTrue(Prefs.EXPERIENCE_PROFILE.write(prefs,value))
        assertFalse(Prefs.EXPERIENCE_PROFILE.write(prefs,"Unknown"))
        assertEquals(true,Prefs.forced(Prefs.ON));assertEquals(false,Prefs.forced(Prefs.OFF))
        assertNull(Prefs.forced(Prefs.AUTO));assertNull(Prefs.forced("unknown"))
    }

    @Test fun stringStorageReturningNullFallsBackToItsDefault() {
        val delegate=prefs
        val nullable=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args->
            if(method.name=="getString") null else method.invoke(delegate,*(args?:emptyArray()))
        } as SharedPreferences
        assertEquals(Prefs.SERVER_NAME.def,Prefs.SERVER_NAME.read(nullable))
    }

    @Test fun observingPreferencesEmitsInitialAndDistinctChangesAndReleasesItsListener()=runBlocking {
        val delegate=prefs
        delegate.edit().clear().commit()
        val registered=CompletableDeferred<Unit>()
        val removed=CompletableDeferred<Unit>()
        var listener:SharedPreferences.OnSharedPreferenceChangeListener?=null
        val wrapped=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args->
            val result=method.invoke(delegate,*(args?:emptyArray()))
            when(method.name) {
                "registerOnSharedPreferenceChangeListener" -> { listener=args!![0] as SharedPreferences.OnSharedPreferenceChangeListener;registered.complete(Unit) }
                "unregisterOnSharedPreferenceChangeListener" -> removed.complete(Unit)
            }
            result
        } as SharedPreferences
        val values=mutableListOf<String>()
        val collector=launch { wrapped.observe { this[Prefs.SERVER_NAME] }.take(3).toList(values) }
        try {
            withTimeout(5000) {
                registered.await()
                Prefs.SERVER_NAME.write(wrapped,"First")
                while(values.size<2) delay(10)
                // Android can notify null for clear(), or notify an unchanged key.
                // Neither may create a bogus preference emission.
                listener!!.onSharedPreferenceChanged(wrapped,null)
                listener!!.onSharedPreferenceChanged(wrapped,Prefs.SERVER_NAME.key)
                Prefs.SERVER_NAME.write(wrapped,"Second")
                collector.join()
                removed.await()
            }
            assertEquals(listOf(Prefs.SERVER_NAME.def,"First","Second"),values)
        } finally { collector.cancelAndJoin() }
    }
}
