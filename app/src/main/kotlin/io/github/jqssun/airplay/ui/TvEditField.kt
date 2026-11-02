package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R

/** TV navigation lands on a button; only an explicit click starts text input. */
@Composable
internal fun TvEditField(
    label: String,
    value: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    normalize: (String) -> String = { it },
    validate: (String) -> Boolean = { true },
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    var restoreFocus by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (!editing && restoreFocus) {
            buttonFocus.requestFocusUntilLanded()
            restoreFocus = false
        }
    }
    fun close() { keyboard?.hide(); editing = false; restoreFocus = true }
    fun save() {
        if (validate(draft)) { onSave(draft); close() }
    }
    OutlinedButton(
        onClick = { draft = value; editing = true },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.focusRequester(buttonFocus).dpadFocus(RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.ifBlank { stringResource(R.string.tv_edit_empty) }, color = MaterialTheme.colorScheme.onSurface)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = ::close,
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = normalize(it) },
                        label = { Text(label) }, singleLine = true,
                        isError = !validate(draft),
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onFocusChanged { inputFocused = it.isFocused },
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                    description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ShowTvKeyboardWhenReady(inputFocused)
                }
            },
            confirmButton = { TextButton(onClick = ::save, enabled = validate(draft), modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tv_edit_save)) } },
            dismissButton = { TextButton(onClick = ::close, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.tv_edit_cancel)) } },
        )
        LaunchedEffect(Unit) { inputFocus.requestFocusUntilLanded() }
    }
}

/** A field within one form: arrows browse fields and OK enables typing. */
@Composable
internal fun TvFormTextField(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onNext: () -> Unit = {}, onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    ShowTvKeyboardWhenReady(editing && inputFocused)
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
        singleLine = true, readOnly = !editing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { editing = false; keyboard?.hide(); onNext() },
            onDone = { keyboard?.hide(); editing = false; onDone() }),
        modifier = modifier.onFocusChanged { inputFocused = it.isFocused; if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                when (key.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (key.action == android.view.KeyEvent.ACTION_DOWN && key.repeatCount == 0) editing = true
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (key.action == android.view.KeyEvent.ACTION_DOWN) {
                            editing = false; keyboard?.hide()
                            focus.moveFocus(if (key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) FocusDirection.Down else FocusDirection.Up)
                        }
                        true
                    }
                    else -> false
                }
            })
}

/** A dialog can focus its field before Android has focused its window (notably API 22). */
@Composable
private fun ShowTvKeyboardWhenReady(enabled: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(enabled, windowFocused) {
        if (enabled && windowFocused) {
            // Let the focused field's text-input session reach the platform first.
            withFrameNanos { }
            keyboard?.show()
        }
    }
}
