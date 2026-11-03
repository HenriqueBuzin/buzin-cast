package io.github.jqssun.airplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun BuzinWelcome(serverName: String, running: Boolean, onToggle: (() -> Unit)? = null) {
    var about by remember { mutableStateOf(false) }
    val tv = isTv()
    val mint = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val toggleFocus = remember { FocusRequester() }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
    LaunchedEffect(running, onToggle != null) { if (tv && onToggle != null) toggleFocus.requestFocusUntilLanded() }
    val identity: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.buzin_hero), fontSize = if (tv) 32.sp else 28.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.buzin_hero_detail), color = muted, fontSize = 13.sp, lineHeight = 20.sp)
            Column(Modifier.fillMaxWidth().border(1.dp, mint.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                .background(Color(0xFF14282D), RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.buzin_receiver_label), fontSize = 11.sp, color = mint, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Cast, null, tint = mint, modifier = Modifier.size(23.dp))
                    Text(serverName, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.buzin_rename_hint), color = muted, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onToggle != null) {
                    if (running) OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(12.dp), modifier = Modifier.focusRequester(toggleFocus).dpadFocus(RoundedCornerShape(12.dp))) { Text(stringResource(R.string.btn_stop)) }
                    else Button(onClick = onToggle, shape = RoundedCornerShape(12.dp), modifier = Modifier.focusRequester(toggleFocus).dpadFocus(RoundedCornerShape(12.dp))) { Text(stringResource(R.string.btn_start)) }
                }
                TextButton(onClick = { about = true }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.Default.Info, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.buzin_about))
                }
            }
        }
    }
    val instructions: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xFF111E2A), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF25333E), RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.buzin_connect_heading), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            listOf(R.string.buzin_step_wifi, R.string.buzin_step_mirror, R.string.buzin_step_choose)
                .forEachIndexed { index, text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(29.dp).background(mint.copy(alpha = 0.10f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Text("0${index + 1}", color = mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(text), fontSize = 12.sp, lineHeight = 18.sp, color = muted, modifier = Modifier.weight(1f))
                    }
                }
            HorizontalDivider(color = Color(0xFF25333E))
            Text("AirPlay  •  DLNA", color = mint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0D1D26), Color(0xFF080F19))))) {
    BuzinSignal(Modifier.matchParentSize(), active = running)
    Column(Modifier.fillMaxSize().graphicsLayer {
        alpha = entrance.value
        translationY = (1f - entrance.value) * 16.dp.toPx()
    }
        .verticalScroll(rememberScrollState()).padding(if (tv) 28.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.buzin_mark), null, Modifier.size(35.dp))
                Text("Buzin Cast", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(if (running) mint else muted, CircleShape))
                Text(stringResource(if (running) R.string.buzin_ready else R.string.stopped_label), color = if (running) mint else muted, fontSize = 11.sp)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (tv && maxWidth >= 680.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1.1f)) { identity() }
                Box(Modifier.weight(1f)) { instructions() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { identity(); instructions() }
        }
        }
    }
    }
    if (about) {
        val context = LocalContext.current
        @Suppress("DEPRECATION")
        val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
        AlertDialog(onDismissRequest = { about = false }, title = { Text(stringResource(R.string.buzin_about)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.buzin_version, version), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.buzin_credits))
                }
            },
            confirmButton = { TextButton(onClick = { about = false }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.btn_ok)) } })
    }
}
