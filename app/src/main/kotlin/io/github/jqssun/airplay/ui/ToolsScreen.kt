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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    var message by remember { mutableStateOf("") }
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
        Text("Ferramentas", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context,io.github.jqssun.airplay.media.CalibrationActivity::class.java)) }, modifier = Modifier.dpadFocus()) { Text("Calibrar imagem") }
            OutlinedButton(onClick = { diagnostic = io.github.jqssun.airplay.media.NetworkDiagnostics.report(context,receiverState == io.github.jqssun.airplay.service.AirPlayService.ServerState.RUNNING) }, modifier = Modifier.dpadFocus()) { Text("Diagnóstico de conexão") }
        }
        HorizontalDivider()
        Text("Gravar AirPlay", style = MaterialTheme.typography.titleLarge)
        Text("Salve o espelhamento com áudio. Prepare a gravação antes de conectar.")
        Button(onClick = viewModel::toggleRecording, enabled = !relay.receiving && !video, modifier = Modifier.dpadFocus()) {
            Text(if (capture.armed) "■ Parar gravação" else "Preparar gravação")
        }
        if (capture.message.isNotBlank()) Text(capture.message, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Text("Outra TV", style = MaterialTheme.typography.titleLarge)
        Text("Retransmita o AirPlay para outro Buzin Cast na mesma rede. No destino, escolha Receber e use os dados exibidos.")
        Text("IP desta TV: ${viewModel.localAddress()}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::listenRelay, enabled = busy == 0 && !video && !capture.armed && !relay.sending, modifier = Modifier.dpadFocus()) { Text("Receber nesta TV") }
            OutlinedButton(onClick = { connectDialog = true },
                enabled = !relay.receiving && !relay.sending && !relay.listening,
                modifier = Modifier.focusRequester(connectFocus).dpadFocus()) { Text("Conectar a outra TV") }
            if (relay.listening || relay.sending || relay.receiving) OutlinedButton(onClick = viewModel::stopRelay, modifier = Modifier.dpadFocus()) { Text("Encerrar retransmissão") }
        }
        if (relay.listening) Text("Porta: ${relay.port}  •  Código: ${relay.code.chunked(4).joinToString("-")}", style = MaterialTheme.typography.titleLarge)
        if (relay.message.isNotBlank()) Text(relay.message)
        HorizontalDivider()
        Text("Fotos via DLNA", style = MaterialTheme.typography.titleLarge)
        Text("Ative DLNA nas Configurações para receber fotos e criar uma apresentação.")
        HorizontalDivider()
        Text("Miracast", style = MaterialTheme.typography.titleLarge)
        Text("Espelhamento do Fire TV. Disponível em modelos compatíveis.")
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent().setComponent(ComponentName("com.amazon.cast.sink", "com.amazon.cast.sink.DisplayMirroringSinkActivity"))) }
                .onFailure { message = "Miracast não disponível neste modelo. Consulte Configurações → Tela e sons → Espelhamento." }
        }, modifier = Modifier.dpadFocus()) { Text("Abrir espelhamento nativo") }
        if (message.isNotEmpty()) Text(message)
        HorizontalDivider()
        Text("Gravações locais", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = viewModel::refreshRecordings, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text("Atualizar lista") }
        if (recordings.isEmpty()) Text("Suas gravações aparecerão aqui.")
        recordings.forEach { recording ->
            Text("${recording.name} • ${recording.bytes / 1024 / 1024} MB")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.playRecording(recording.name) }, enabled = !capture.armed && busy == 0 && !relay.receiving, modifier = Modifier.dpadFocus()) { Text("Reproduzir") }
                OutlinedButton(onClick = { viewModel.shareRecording(recording.name) }, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text("Exportar") }
                TextButton(onClick = { delete = recording.name }, enabled = !capture.armed, modifier = Modifier.dpadFocus()) { Text("Excluir") }
            }
        }
    }
    diagnostic?.let { report ->
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { diagnostic = null }, title = { Text("Diagnóstico de conexão · ↑↓") },
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
            confirmButton = { TextButton(onClick = { diagnostic = null },modifier = Modifier.dpadFocus()) { Text("Fechar") } })
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
    delete?.let { name -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Excluir gravação?") }, text = { Text(name) },
        confirmButton = { TextButton(onClick = { viewModel.deleteRecording(name); delete = null }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancelar") } }) }
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
        title = { Text("Conectar a outra TV") },
        text = {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Informe os dados exibidos em Receber na outra TV.", style = MaterialTheme.typography.bodyMedium)
                if (tv) {
                    Text("↑↓ para navegar · OK para escrever", style = MaterialTheme.typography.bodySmall)
                    TvFormTextField("IP da outra TV", address, { address = it.take(64) },
                        Modifier.fillMaxWidth().focusRequester(addressFocus).focusProperties { down = portFocus },
                        onNext = { portFocus.requestFocus() })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvFormTextField("Porta", port, { port = it.filter(Char::isDigit).take(5) },
                        Modifier.weight(0.34f).focusRequester(portFocus).focusProperties { up = addressFocus; down = codeFocus; right = codeFocus },
                        keyboardType = KeyboardType.Number,
                        onNext = { codeFocus.requestFocus() })
                    TvFormTextField("Código de conexão", code, { code = it.take(14) },
                        Modifier.weight(0.66f).focusRequester(codeFocus).focusProperties { up = portFocus; left = portFocus },
                        imeAction = ImeAction.Done,
                        onDone = { connect() })
                    }
                } else {
                OutlinedTextField(value = address, onValueChange = { address = it.take(64) }, label = { Text("IP da outra TV") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(addressFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }))
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Porta") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(portFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }))
                OutlinedTextField(value = code, onValueChange = { code = it.take(14) }, label = { Text("Código de conexão") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { connect() }))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::connect, enabled = valid, modifier = Modifier.dpadFocus()) { Text("Conectar") } },
        dismissButton = { TextButton(onClick = ::dismiss, modifier = Modifier.dpadFocus()) { Text("Cancelar") } },
    )
    LaunchedEffect(Unit) { addressFocus.requestFocusUntilLanded(); if (!tv) keyboard?.show() }
}
