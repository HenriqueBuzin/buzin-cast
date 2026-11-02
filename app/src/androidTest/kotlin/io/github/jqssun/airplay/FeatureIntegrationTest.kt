package io.github.jqssun.airplay

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.media.ConnectionApproval
import io.github.jqssun.airplay.media.ExternalSubtitles
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FeatureIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun checkpoint() {
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private fun awaitRequest(approval: ConnectionApproval): ConnectionApproval.Request {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (approval.request.value == null) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10) }
        return requireNotNull(approval.request.value)
    }
    @Test fun approvalAcceptDenialAndForeignPeerIsolation() {
        val executor = Executors.newSingleThreadExecutor()
        val approval = ConnectionApproval {}
        try {
            val accepted = executor.submit<Boolean> { approval.ask("FCast", "192.0.2.1") }
            val request = awaitRequest(approval)
            assertFalse(approval.ask("DLNA", "192.0.2.2"))
            approval.answer(request.id + 1, true); assertNotNull(approval.request.value)
            approval.answer(request.id, true); assertTrue(accepted.get(5, TimeUnit.SECONDS))
            assertTrue(approval.ask("FCast", "192.0.2.1"))
            val denied = executor.submit<Boolean> { approval.ask("DLNA", "192.0.2.1") }
            approval.answer(awaitRequest(approval).id, false)
            assertFalse(denied.get(5, TimeUnit.SECONDS)); assertFalse(approval.ask("DLNA", "192.0.2.1"))
        } finally { approval.close(); executor.shutdownNow() }
    }
    @Test fun approvalShutdownUnblocksPendingAndCanReopen() {
        val executor = Executors.newSingleThreadExecutor()
        val approval = ConnectionApproval {}
        try {
            val pending = executor.submit<Boolean> { approval.ask("AirPlay", "192.0.2.3") }
            awaitRequest(approval); approval.close()
            assertFalse(pending.get(5, TimeUnit.SECONDS)); assertNull(approval.request.value)
            assertFalse(approval.ask("AirPlay", "192.0.2.3"))
            approval.open()
            val fresh = executor.submit<Boolean> { approval.ask("AirPlay", "192.0.2.3") }
            approval.answer(awaitRequest(approval).id, true)
            assertTrue(fresh.get(5, TimeUnit.SECONDS))
        } finally { approval.close(); executor.shutdownNow() }
    }
    private fun subtitle(bytes: ByteArray): Pair<Uri, String> {
        val file = File(context.cacheDir, "integration-subtitle.txt")
        try {
            file.writeBytes(bytes)
            return ExternalSubtitles.import(context, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } finally { file.delete() }
    }
    @Test fun subtitlesImportSrtAndBomVttThroughContentResolver() {
        val srt = subtitle("1\n00:00:00,000 --> 00:00:05,000\nBuzin\n".toByteArray())
        assertEquals("application/x-subrip", srt.second); assertTrue(File(srt.first.path!!).readText().contains("Buzin"))
        val vtt = subtitle("\uFEFFWEBVTT\n\n00:00.000 --> 00:05.000\nCast\n".toByteArray())
        assertEquals("text/vtt", vtt.second); assertTrue(File(vtt.first.path!!).readText().startsWith("WEBVTT"))
    }
    @Test fun subtitlesRejectRemoteInvalidUtf8MalformedAndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) { ExternalSubtitles.import(context, Uri.parse("https://example.test/sub.srt")) }
        assertThrows(IllegalArgumentException::class.java) { subtitle("not subtitles".toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { subtitle(byteArrayOf(-61,40)) }
        assertThrows(IllegalArgumentException::class.java) { subtitle(ByteArray(2*1024*1024+1)) }
    }
    @Test fun securityAndReceiverNameDefaultsAreStable() {
        val name = "integration-defaults"
        val prefs = context.getSharedPreferences(name,0)
        prefs.edit().clear().commit()
        try {
            assertFalse(Prefs.REQUIRE_PIN.read(prefs)); assertFalse(Prefs.APPROVE_CONNECTIONS.read(prefs))
            assertEquals("Buzin Cast", Prefs.SERVER_NAME.read(prefs))
            Prefs.REQUIRE_PIN.write(prefs,true); assertTrue(Prefs.REQUIRE_PIN.read(prefs))
            Prefs.SERVER_NAME.write(prefs,"Sala"); assertEquals("Sala",Prefs.SERVER_NAME.read(prefs))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun diagnosticsAndPhysicalDisplayWorkOnLegacyAndModernAndroid() {
        val report = io.github.jqssun.airplay.media.NetworkDiagnostics.report(context, true)
        assertTrue(report.contains("Receptor iniciado.")); assertTrue(report.contains("AirPlay:"))
        val size = context.realDisplaySize()
        assertTrue(size.first > 0 && size.second > 0)
    }
    @Test fun networkWatcherRegistersAndClosesWithoutRestartingAnUnchangedNetwork() {
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val watcher = io.github.jqssun.airplay.media.NetworkRecovery(context) { notifications.incrementAndGet() }
        try { watcher.start(); Thread.sleep(1800); assertEquals(0, notifications.get()) }
        finally { watcher.close(); watcher.close() }
    }
    @Test fun volumeAdapterUsesAvailablePlatformApiAndClampsItsRange() {
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val original = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        try {
            val state = io.github.jqssun.airplay.ui.gestures.ServerVolumeState(context)
            state.update(-20); assertEquals(0, state.percentage)
            state.update(150); assertEquals(100, state.percentage)
            state.adjust(false); state.sync(); assertTrue(state.percentage in 0..100)
        } finally { manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,original,0) }
    }
}
