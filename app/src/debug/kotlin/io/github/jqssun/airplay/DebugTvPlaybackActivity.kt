package io.github.jqssun.airplay

import android.content.*
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Real service and production playback UI, with TV resources on an isolated emulator. */
class DebugTvPlaybackActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel> { ViewModelProvider.AndroidViewModelFactory.getInstance(application) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { model.bindService((binder as AirPlayService.LocalBinder).service) }
        override fun onServiceDisconnected(name: ComponentName?) { model.unbindService() }
    }
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
        lifecycleScope.launch { while (true) { model.updateFromService(); delay(200) } }
        setContent {
            val config = Configuration(resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION }
            val tvContext = remember { createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides tvContext, LocalConfiguration provides config,
                androidx.activity.compose.LocalActivity provides this@DebugTvPlaybackActivity,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this@DebugTvPlaybackActivity,
                androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides this@DebugTvPlaybackActivity) {
                AirPlayTheme { MainScreen(model, onSurfaceAvailable=model::onSurfaceAvailable, onSurfaceDestroyed=model::onSurfaceDestroyed) }
            }
        }
    }
    override fun onDestroy() { model.unbindService(); unbindService(connection); super.onDestroy() }
}
