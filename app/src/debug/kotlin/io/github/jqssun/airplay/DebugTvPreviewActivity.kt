package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jqssun.airplay.ui.BuzinWelcome
import io.github.jqssun.airplay.ui.theme.AirPlayTheme

/** ADB-only render fixture; no service, network session, preference change or release entry. */
class DebugTvPreviewActivity : ComponentActivity() {
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
            AirPlayTheme {
                Surface {
                Row(Modifier.fillMaxSize().background(Color(0xFF080E1A))) {
                    Spacer(Modifier.width(172.dp))
                    Box(Modifier.weight(1f)) { BuzinWelcome("Buzin Cast",true,onToggle = {}) }
                }
                }
            }
        }
    }
}
