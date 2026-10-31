package io.github.jqssun.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R

@Composable
fun BuzinWelcome(serverName: String, running: Boolean) {
    var about by remember { mutableStateOf(false) }
    val tv = isTv()
    val identity: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Cast, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.buzin_tagline), fontSize = 15.sp)
            Text(stringResource(R.string.buzin_free), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(if (running) R.string.buzin_ready else R.string.buzin_stopped), fontSize = 13.sp)
            Text(serverName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    val instructions: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.buzin_choose, serverName), style = MaterialTheme.typography.titleMedium)
            listOf(R.string.buzin_step_wifi, R.string.buzin_step_mirror, R.string.buzin_step_choose)
                .forEachIndexed { index, text ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(text), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            Text(stringResource(R.string.buzin_help), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { about = true }, modifier = Modifier.dpadFocus()) {
                Text(stringResource(R.string.buzin_about))
            }
        }
    }
    if (tv) {
        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.9f)) { identity() }
            Box(Modifier.weight(1.1f)) { instructions() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            identity()
            instructions()
        }
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.buzin_about)) },
            text = { Text(stringResource(R.string.buzin_credits), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { about = false }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.btn_ok)) } }
        )
    }
}
