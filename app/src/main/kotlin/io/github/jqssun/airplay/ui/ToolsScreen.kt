package io.github.jqssun.airplay.ui

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
fun ToolsScreen(viewModel: MainViewModel) {
    val capture by viewModel.captureState.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    val recordings by viewModel.recordings.collectAsState()
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<String?>(null) }
    val busy by viewModel.connectionCount.collectAsState()
    val video by viewModel.videoPlaybackActive.collectAsState()
    LaunchedEffect(capture.armed) { viewModel.refreshRecordings() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ferramentas", style = MaterialTheme.typography.headlineSmall)
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
            if (relay.listening || relay.sending || relay.receiving) OutlinedButton(onClick = viewModel::stopRelay, modifier = Modifier.dpadFocus()) { Text("Encerrar retransmissão") }
        }
        if (relay.listening) Text("Porta: ${relay.port}  •  Código: ${relay.code.chunked(4).joinToString("-")}", style = MaterialTheme.typography.titleLarge)
        if (relay.message.isNotBlank()) Text(relay.message)
        OutlinedTextField(value = address, onValueChange = { address = it.take(64) }, label = { Text("IP da TV de destino") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Porta") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = code, onValueChange = { code = it.take(14) }, label = { Text("Código da TV de destino") }, singleLine = true, modifier = Modifier.weight(2f))
        }
        Button(onClick = { viewModel.connectRelay(address, port.toIntOrNull() ?: 0, code) },
            enabled = address.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 && io.buzincast.media.SecureMirrorChannel.normalizeCode(code).length == 12 && !relay.receiving && !relay.sending && !relay.listening,
            modifier = Modifier.dpadFocus()) { Text("Conectar e retransmitir") }
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
    delete?.let { name -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Excluir gravação?") }, text = { Text(name) },
        confirmButton = { TextButton(onClick = { viewModel.deleteRecording(name); delete = null }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancelar") } }) }
}
