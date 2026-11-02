package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import io.github.jqssun.airplay.ui.*
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.MediaMetadata
import android.os.Looper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.jqssun.airplay.ui.AppDestination
import io.github.jqssun.airplay.ui.TvSidebar
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jqssun.airplay.ui.BuzinWelcome
import io.github.jqssun.airplay.ui.SettingsScreen
import io.github.jqssun.airplay.ui.ToolsScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** ADB-only UI fixture; never binds a receiver service and has no release entry. */
class DebugTvPreviewActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel> {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            var destination by remember { mutableStateOf(when(intent.getStringExtra("screen")) {
                "settings" -> AppDestination.SETTINGS; "tools" -> AppDestination.TOOLS; else -> AppDestination.OVERVIEW
            }) }
            AirPlayTheme {
                Surface {
                if (intent.getStringExtra("screen") == "audio") AudioFixture() else Row(Modifier.fillMaxSize().background(Color(0xFF080E1A))) {
                    TvSidebar(listOf(AppDestination.OVERVIEW,AppDestination.TOOLS,AppDestination.SETTINGS), destination) { destination = it }
                    Box(Modifier.weight(1f)) {
                        when (destination) {
                            AppDestination.SETTINGS -> SettingsScreen(model)
                            AppDestination.TOOLS -> ToolsScreen(model)
                            else -> BuzinWelcome("Buzin Cast",true,onToggle = {})
                        }
                    }
                }
                }
            }
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class DebugPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var event by mutableStateOf("ready")
        private var playing = false
        private var position = 15000L
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA).build())
            .setPlaylist(listOf(MediaItemData.Builder("previous").build(), MediaItemData.Builder("current").setDurationUs(120000000)
                .setIsSeekable(true).setMediaMetadata(MediaMetadata.Builder().setTitle("Faixa de teste").build()).build(), MediaItemData.Builder("next").build()))
            .setCurrentMediaItemIndex(1).setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(PositionSupplier { position }).setMaxSeekToPreviousPositionMs(Long.MAX_VALUE).build()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady; event = if (playing) "play" else "pause"; invalidateState(); return Futures.immediateVoidFuture()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            event = when (seekCommand) { Player.COMMAND_SEEK_TO_NEXT -> "next"; Player.COMMAND_SEEK_TO_PREVIOUS -> "previous"; else -> "seek" }
            position = if (event == "seek") positionMs else 30000L
            invalidateState(); return Futures.immediateVoidFuture()
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable private fun AudioFixture() {
        val player = remember { DebugPlayer() }
        DisposableEffect(player) { onDispose { player.release() } }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Comando: ${player.event}")
            AudioMiniRow(player, "Faixa de teste", {})
            AudioPositionText(player); AudioProgressSlider(player); AudioDurationText(player)
        }
    }
}
