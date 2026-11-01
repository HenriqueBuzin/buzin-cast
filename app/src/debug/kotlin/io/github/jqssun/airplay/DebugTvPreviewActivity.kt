package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jqssun.airplay.ui.BuzinWelcome
import io.github.jqssun.airplay.ui.SettingsScreen
import io.github.jqssun.airplay.ui.ToolsScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel

/** ADB-only UI fixture; never binds a receiver service and has no release entry. */
class DebugTvPreviewActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel> {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
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
                    Box(Modifier.weight(1f)) {
                        when (intent.getStringExtra("screen")) {
                            "settings" -> SettingsScreen(model)
                            "tools" -> ToolsScreen(model)
                            else -> BuzinWelcome("Buzin Cast",true,onToggle = {})
                        }
                    }
                }
                }
            }
        }
    }
}
