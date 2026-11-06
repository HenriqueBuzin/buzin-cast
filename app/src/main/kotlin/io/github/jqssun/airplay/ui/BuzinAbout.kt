package io.github.jqssun.airplay.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Calendar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jqssun.airplay.R

@Composable
internal fun BuzinAbout(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tv = isTv()
    val closeFocus = remember { FocusRequester() }
    var licenses by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val mint = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    val noBrowser = stringResource(R.string.buzin_no_browser)
    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(licenses) { if (tv) closeFocus.requestFocusUntilLanded() }

    if (licenses) {
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text(stringResource(R.string.buzin_licenses)) },
            text = {
                Text(stringResource(R.string.buzin_credits), Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { licenses = false }, modifier = Modifier.focusRequester(closeFocus).dpadFocus()) {
                    Text(stringResource(R.string.buzin_back_about))
                }
            },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f).heightIn(max = maxHeight),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF101922),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.background(Color(0xFF202B35), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(52.dp).background(mint.copy(alpha = 0.10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.buzin_mark), null, Modifier.size(34.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.app_name), fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.buzin_about_tagline), fontSize = 12.sp, lineHeight = 17.sp, color = muted)
                            Text(stringResource(R.string.buzin_version, version), fontSize = 11.sp, lineHeight = 15.sp, color = mint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF34434E))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Business, null, Modifier.size(20.dp), tint = mint)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.buzin_maintained_by), fontSize = 11.sp, lineHeight = 15.sp, color = muted)
                            Text(buildAnnotatedString {
                                append("Buzin Solutions\u2009")
                                withStyle(SpanStyle(fontSize = 10.sp, baselineShift = BaselineShift.Superscript)) { append("®") }
                            }, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    AboutLink(Icons.Default.Language, stringResource(R.string.buzin_website), "buzin.solutions", external = true) {
                        openLink("https://buzin.solutions")
                    }
                    AboutLink(Icons.Default.Code, stringResource(R.string.buzin_source), "GitHub · HenriqueBuzin/buzin-cast", external = true) {
                        openLink("https://github.com/HenriqueBuzin/buzin-cast")
                    }
                    AboutLink(Icons.Default.Gavel, stringResource(R.string.buzin_licenses), stringResource(R.string.buzin_licenses_detail), external = false) {
                        licenses = true
                    }
                }
                Text("© $currentYear Buzin Solutions LTDA", Modifier.fillMaxWidth(), fontSize = 10.sp, lineHeight = 14.sp, color = muted, textAlign = TextAlign.Center)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(closeFocus).dpadFocus(RoundedCornerShape(12.dp)), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.buzin_close))
                }
            }
        }
    }
}

@Composable
private fun AboutLink(icon: ImageVector, title: String, detail: String, external: Boolean, onClick: () -> Unit) {
    val mint = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider(color = Color(0xFF34434E))
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(4.dp).clip(shape).dpadFocus(shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = mint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, lineHeight = 16.sp, color = muted)
        }
        Icon(if (external) Icons.Default.OpenInNew else Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = muted)
    }
}
