package io.github.jqssun.airplay.ui

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
fun ToolsScreen(viewModel: MainViewModel) {
    val capture by viewModel.captureState.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val context = LocalContext.current
    var diagnostic by remember { mutableStateOf<String?>(null) }
    val receiverState by viewModel.serverState.collectAsState()
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var mirroringUnavailable by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<String?>(null) }
    var connectDialog by remember { mutableStateOf(false) }
    var restoreConnectFocus by remember { mutableStateOf(false) }
    val connectFocus = remember { FocusRequester() }
    LaunchedEffect(connectDialog) {
        if (!connectDialog && restoreConnectFocus) {
            connectFocus.requestFocusUntilLanded()
            restoreConnectFocus = false
        }
    }
    val busy by viewModel.connectionCount.collectAsState()
    val video by viewModel.videoPlaybackActive.collectAsState()
    LaunchedEffect(capture.armed) { viewModel.refreshRecordings() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tab_tools), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context,io.github.jqssun.airplay.media.CalibrationActivity::class.java)) }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_calibrate)) }
            OutlinedButton(onClick = { diagnostic = io.github.jqssun.airplay.media.NetworkDiagnostics.report(context,receiverState == io.github.jqssun.airplay.service.AirPlayService.ServerState.RUNNING) }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_connection_diagnostics)) }
        }
        if (io.github.jqssun.airplay.BuildConfig.RECORDING_ENABLED) {
            HorizontalDivider()
            Text(stringResource(R.string.tools_record_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.tools_record_help))
            Button(onClick = viewModel::toggleRecording, enabled = !relay.receiving && !video, modifier = Modifier.dpadFocus()) {
                Text(stringResource(if (capture.armed) R.string.tools_record_stop else R.string.tools_record_prepare))
            }
            if (capture.message.isNotBlank()) Text(capture.message, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider()
        Text(stringResource(R.string.tools_other_tv), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.tools_relay_help))
        Text(stringResource(R.string.tools_this_tv_ip, viewModel.localAddress()))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::listenRelay, enabled = busy == 0 && !video && !capture.armed && !relay.sending, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_relay_receive)) }
            OutlinedButton(onClick = { connectDialog = true },
                enabled = !relay.receiving && !relay.sending && !relay.listening,
                modifier = Modifier.focusRequester(connectFocus).dpadFocus()) { Text(stringResource(R.string.tools_relay_connect)) }
            if (relay.listening || relay.sending || relay.receiving) OutlinedButton(onClick = viewModel::stopRelay, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_relay_stop)) }
        }
        if (relay.listening) Text(stringResource(R.string.tools_relay_details, relay.port, relay.code.chunked(4).joinToString("-")), style = MaterialTheme.typography.titleLarge)
        if (relay.message.isNotBlank()) Text(relay.message)
        HorizontalDivider()
        Text(stringResource(R.string.tools_dlna_photos), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.tools_dlna_help))
        HorizontalDivider()
        Text("Miracast", style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.tools_native_help))
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent().setComponent(ComponentName("com.amazon.cast.sink", "com.amazon.cast.sink.DisplayMirroringSinkActivity"))) }
                .onFailure { mirroringUnavailable = true }
        }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_native_open)) }
        if (mirroringUnavailable) Text(stringResource(R.string.tools_native_unavailable))
        if (io.github.jqssun.airplay.BuildConfig.RECORDING_ENABLED) {
            HorizontalDivider()
            Text(stringResource(R.string.tools_recordings), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = viewModel::refreshRecordings, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_refresh)) }
            if (recordings.isEmpty()) Text(stringResource(R.string.tools_recordings_empty))
            recordings.forEach { recording ->
                Text("${recording.name} • ${recording.bytes / 1024 / 1024} MB")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.playRecording(recording.name) }, enabled = !capture.armed && busy == 0 && !relay.receiving, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_play)) }
                    OutlinedButton(onClick = { viewModel.shareRecording(recording.name) }, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.btn_export)) }
                    TextButton(onClick = { delete = recording.name }, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_delete)) }
                }
            }
        }
    }
    diagnostic?.let { report ->
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { diagnostic = null }, title = { Text("${stringResource(R.string.tools_connection_diagnostics)} · ↑↓") },
            modifier = Modifier.onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val direction = when (key.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                    else -> 0
                }
                if (direction == 0) false else {
                    if (key.action == android.view.KeyEvent.ACTION_DOWN) scope.launch {
                        scroll.animateScrollTo((scroll.value + direction * 160).coerceIn(0,scroll.maxValue))
                    }
                    true
                }
            },
            text = { Text(report,Modifier.verticalScroll(scroll)) },
            confirmButton = { TextButton(onClick = { diagnostic = null },modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.buzin_close)) } })
    }
    if (connectDialog) {
        RelayConnectDialog(address, port, code,
            onDismiss = { connectDialog = false; restoreConnectFocus = true },
            onConnect = { host, receiverPort, pairingCode ->
                address = host; port = receiverPort; code = pairingCode
                connectDialog = false; restoreConnectFocus = true
                viewModel.connectRelay(host, receiverPort.toInt(), pairingCode)
            })
    }
    delete?.let { name -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.tools_delete_confirm)) }, text = { Text(name) },
        confirmButton = { TextButton(onClick = { viewModel.deleteRecording(name); delete = null }) { Text(stringResource(R.string.tools_delete)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.tv_edit_cancel)) } }) }
}

@Composable
private fun RelayConnectDialog(
    initialAddress: String, initialPort: String, initialCode: String,
    onDismiss: () -> Unit, onConnect: (String, String, String) -> Unit,
) {
    var address by remember { mutableStateOf(initialAddress) }
    var port by remember { mutableStateOf(initialPort) }
    var code by remember { mutableStateOf(initialCode) }
    val addressFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val tv = isTv()
    val valid = address.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
        io.buzincast.media.SecureMirrorChannel.normalizeCode(code).length == 12
    fun dismiss() { keyboard?.hide(); onDismiss() }
    fun connect() { if (valid) { keyboard?.hide(); onConnect(address.trim(), port, code) } }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.tools_relay_connect)) },
        text = {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tools_relay_input_help), style = MaterialTheme.typography.bodyMedium)
                if (tv) {
                    Text(stringResource(R.string.tools_relay_keyboard_help), style = MaterialTheme.typography.bodySmall)
                    TvFormTextField(stringResource(R.string.tools_other_tv_ip), address, { address = it.take(64) },
                        Modifier.fillMaxWidth().focusRequester(addressFocus).focusProperties { down = portFocus },
                        onNext = { portFocus.requestFocus() })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvFormTextField(stringResource(R.string.tools_port), port, { port = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(0.34f).focusRequester(portFocus).focusProperties { up = addressFocus; down = codeFocus; right = codeFocus },
                        keyboardType = KeyboardType.Number,
                        onNext = { codeFocus.requestFocus() })
                    TvFormTextField(stringResource(R.string.tools_pairing_code), code, { code = it.take(14) },
                        Modifier.weight(0.66f).focusRequester(codeFocus).focusProperties { up = portFocus; left = portFocus },
                        imeAction = ImeAction.Done,
                        onDone = { connect() })
                    }
                } else {
                OutlinedTextField(value = address, onValueChange = { address = it.take(64) }, label = { Text(stringResource(R.string.tools_other_tv_ip)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(addressFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }))
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.tools_port)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(portFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }))
                OutlinedTextField(value = code, onValueChange = { code = it.take(14) }, label = { Text(stringResource(R.string.tools_pairing_code)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { connect() }))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::connect, enabled = valid, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tools_connect)) } },
        dismissButton = { TextButton(onClick = ::dismiss, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tv_edit_cancel)) } },
    )
    LaunchedEffect(Unit) { addressFocus.requestFocusUntilLanded(); if (!tv) keyboard?.show() }
}
