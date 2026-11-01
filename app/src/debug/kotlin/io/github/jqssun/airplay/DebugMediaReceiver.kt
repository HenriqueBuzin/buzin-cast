package io.github.jqssun.airplay

import android.content.*
import android.os.IBinder
import io.buzincast.media.NalUnits
import io.github.jqssun.airplay.service.AirPlayService
import org.json.JSONObject

/** ADB test harness. Debug source set only, restricted to android.permission.DUMP. */
class DebugMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val connection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName?) { }
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as AirPlayService.LocalBinder).service
                Thread({
                    try {
                        when (intent.getStringExtra("command")) {
                            "calibrate" -> app.startActivity(Intent(app, io.github.jqssun.airplay.media.CalibrationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            "approvalsetting" -> Prefs.APPROVE_CONNECTIONS.write(app.getSharedPreferences(Prefs.NAME,0),intent.getBooleanExtra("enabled",false))
                            "approve", "deny" -> service.approval.request.value?.let { service.approval.answer(it.id,intent.getStringExtra("command") == "approve") }
                            "profile" -> io.github.jqssun.airplay.media.ExperienceProfiles.apply(app.getSharedPreferences(Prefs.NAME,0),intent.getStringExtra("name")!!)
                            "playurl" -> android.os.Handler(android.os.Looper.getMainLooper()).post { service.playNetworkVideo(intent.getStringExtra("url")!!,"debug") }
                            "subtitlefixture" -> {
                                val kind = intent.getStringExtra("kind") ?: "vtt"
                                val file = java.io.File(app.cacheDir,"test-caption.$kind")
                                file.writeText(if (kind == "srt") "1\n00:00:00,000 --> 00:00:30,000\nLegenda SRT Buzin\n" else "WEBVTT\n\n00:00.000 --> 00:30.000\nLegenda VTT Buzin\n")
                                val uri = androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",file)
                                val (local,mime) = io.github.jqssun.airplay.media.ExternalSubtitles.import(app,uri)
                                service.airPlayVideoPlayer.setSubtitle(local,mime)
                            }
                            "record" -> service.capture.start()
                            "stop" -> { service.capture.stop(); service.relay.stop() }
                            "listen" -> service.relay.listen()
                            "connect" -> service.relay.connect(intent.getStringExtra("host")!!, intent.getIntExtra("port", 0), intent.getStringExtra("code")!!)
                            "fixture", "nativefixture" -> {
                                check(service.connectionCount.value == 0) { "Do not inject test data during real AirPlay playback" }
                                val nativeAudio = intent.getStringExtra("command") == "nativefixture"
                                val audioFrames = mutableListOf<ByteArray>()
                                var nativeHandle = 0L
                                if (nativeAudio) {
                                    nativeHandle = AirPlayService::class.java.getDeclaredField("nativeHandle").let { field -> field.isAccessible = true; field.getLong(service) }
                                    val audio = context.assets.open("mirror/sample.aac").use { it.readBytes() }
                                    var offset = 0
                                    while (offset + 7 <= audio.size) {
                                        val header = if (audio[offset + 1].toInt() and 1 == 1) 7 else 9
                                        val length = ((audio[offset + 3].toInt() and 3) shl 11) or ((audio[offset + 4].toInt() and 255) shl 3) or ((audio[offset + 5].toInt() and 224) shr 5)
                                        require(length > header && offset + length <= audio.size)
                                        audioFrames.add(audio.copyOfRange(offset + header, offset + length)); offset += length
                                    }
                                    service.onAudioFormat(4, 1024, true)
                                }
                                val hevc = intent.getBooleanExtra("hevc", false)
                                val bytes = context.assets.open("mirror/sample.${if (hevc) "h265" else "h264"}").use { it.readBytes() }
                                val frames = mutableListOf<MutableList<ByteArray>>()
                                NalUnits.split(bytes).forEach { nal ->
                                    if (NalUnits.type(nal, hevc) == if (hevc) 35 else 9) frames.add(mutableListOf())
                                    if (frames.isEmpty()) frames.add(mutableListOf())
                                    frames.last().add(nal)
                                }
                                service.onVideoSize(64f, 48f, 64f, 48f)
                                val base = System.nanoTime()
                                var audioIndex = 0
                                frames.forEachIndexed { index, nals ->
                                    val packet = nals.fold(byteArrayOf()) { all, nal -> all + byteArrayOf(0, 0, 0, 1) + nal }
                                    service.onVideoData(packet, base + index * 200_000_000L, hevc)
                                    if (nativeAudio) {
                                        while (audioIndex < audioFrames.size && audioIndex * 1024L * 1_000_000_000L / 44100 < (index + 1) * 200_000_000L) {
                                            DebugNativeBridge.nativeFeedAudio(nativeHandle, audioFrames[audioIndex], base + audioIndex * 1024L * 1_000_000_000L / 44100)
                                            audioIndex++
                                        }
                                    } else service.onAudioPcm(ByteArray(35280), base + index * 200_000_000L, 44100, 2)
                                    Thread.sleep(200)
                                }
                            }
                        }
                        val relay = service.relay.state.value
                        pending.resultCode = -1
                        pending.resultData = JSONObject().put("recording", service.capture.state.value.recording)
                            .put("approvalPeer",service.approval.request.value?.peer).put("approvalProtocol",service.approval.request.value?.protocol)
                            .put("caption",service.airPlayVideoPlayer.subtitleText.value)
                            .put("networkMessage",service.networkMessage.value)
                            .put("playing",service.videoPlaybackInfo.value.playing).put("positionMs",service.videoPlaybackInfo.value.positionMs)
                            .put("activeVideo",service.videoPlaybackActive.value)
                            .put("margin",Prefs.TV_MARGIN.read(app.getSharedPreferences(Prefs.NAME,0)))
                            .put("captureMessage", service.capture.state.value.message)
                            .put("relayPort", relay.port).put("relayCode", relay.code)
                            .put("sending", relay.sending).put("receiving", relay.receiving).put("relayMessage", relay.message).toString()
                    } catch (e: Exception) { pending.resultCode = 1; pending.resultData = e.toString() }
                    finally { app.unbindService(this); pending.finish() }
                }, "buzin-debug-test").start()
            }
        }
        if (!app.bindService(Intent(app, AirPlayService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            pending.resultCode = 1; pending.resultData = "service unavailable"; pending.finish()
        }
    }
}

object DebugNativeBridge {
    external fun nativeFeedAudio(handle: Long, data: ByteArray, ptsNs: Long)
}
