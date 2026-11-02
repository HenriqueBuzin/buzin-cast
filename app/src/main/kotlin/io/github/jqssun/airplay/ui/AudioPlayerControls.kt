package io.github.jqssun.airplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import io.leancast.domain.cast.OsdFormatter
import kotlinx.coroutines.delay

/** App-owned controls shared by modern and API 22 builds, using Media3's stable Player port. */
private data class AudioControlState(val playing: Boolean, val position: Long, val duration: Long, val commands: Player.Commands)
@Composable private fun audioState(player: Player): AudioControlState {
    fun snapshot() = AudioControlState(player.playWhenReady, player.currentPosition.coerceAtLeast(0), player.duration, player.availableCommands)
    var state by remember(player) { mutableStateOf(snapshot()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { state = snapshot() } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) { while (true) { state = snapshot(); delay(250) } }
    return state
}
@Composable internal fun AudioPlayPauseButton(player: Player, modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    val state = audioState(player)
    IconButton(onClick = { if (state.playing) player.pause() else player.play() }, modifier = modifier,
        enabled = state.commands.contains(Player.COMMAND_PLAY_PAUSE)) {
        Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (state.playing) "Pausar" else "Reproduzir", Modifier.size(iconSize))
    }
}
@Composable internal fun AudioPreviousButton(player: Player, modifier: Modifier = Modifier) {
    val state = audioState(player)
    IconButton(onClick = { player.seekToPrevious() }, modifier = modifier, enabled = state.commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS)) {
        Icon(Icons.Default.SkipPrevious, "Anterior")
    }
}
@Composable internal fun AudioNextButton(player: Player, modifier: Modifier = Modifier) {
    val state = audioState(player)
    IconButton(onClick = { player.seekToNext() }, modifier = modifier, enabled = state.commands.contains(Player.COMMAND_SEEK_TO_NEXT)) {
        Icon(Icons.Default.SkipNext, "Próxima")
    }
}
@Composable internal fun AudioPositionText(player: Player, modifier: Modifier = Modifier) {
    Text(OsdFormatter.formatClock(audioState(player).position), modifier)
}
@Composable internal fun AudioDurationText(player: Player, modifier: Modifier = Modifier) {
    val duration = audioState(player).duration
    Text(if (duration == C.TIME_UNSET || duration < 0) "--:--" else OsdFormatter.formatClock(duration), modifier)
}
@Composable internal fun AudioProgressSlider(player: Player) {
    val state = audioState(player)
    var draft by remember(player) { mutableStateOf<Float?>(null) }
    val duration = state.duration.coerceAtLeast(0)
    Slider(value = draft ?: if (duration > 0) (state.position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f,
        onValueChange = { draft = it },
        onValueChangeFinished = { draft?.let { player.seekTo((it*duration).toLong()) }; draft = null },
        enabled = duration > 0 && state.commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
}
@Composable internal fun AudioMiniRow(player: Player, title: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, Modifier.padding(end = 12.dp))
            Text(title, Modifier.weight(1f), maxLines = 1)
            AudioPreviousButton(player); AudioPlayPauseButton(player); AudioNextButton(player)
        }
    }
}
