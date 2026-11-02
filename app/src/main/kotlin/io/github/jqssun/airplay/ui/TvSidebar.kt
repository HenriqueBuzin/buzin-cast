package io.github.jqssun.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R

internal enum class AppDestination(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    TOOLS(R.string.tab_tools, Icons.Default.Build),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
internal fun TvSidebar(destinations: List<AppDestination>, selected: AppDestination, onSelect: (AppDestination) -> Unit) {
                Column(Modifier.width(172.dp).fillMaxHeight().background(Color(0xFF080F18))
                    .padding(end = 12.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    destinations.forEach { t ->
                        Surface(onClick = { onSelect(t) }, shape = RoundedCornerShape(13.dp),
                            color = if (selected == t) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                            contentColor = if (selected == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().height(54.dp).dpadFocus(RoundedCornerShape(13.dp))) {
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(t.icon, null, Modifier.size(21.dp))
                                Text(stringResource(t.labelRes), style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
}
