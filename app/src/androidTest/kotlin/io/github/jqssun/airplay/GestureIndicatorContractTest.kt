package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect as PixelRect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.ui.DpadSeekIndicator
import io.github.jqssun.airplay.ui.PlaybackSpeedSelector
import io.github.jqssun.airplay.ui.PlayerButton
import io.github.jqssun.airplay.ui.VideoControlsBottom
import io.github.jqssun.airplay.ui.VideoControlsTop
import io.github.jqssun.airplay.ui.formatVideoTime
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual Compose pixels and accessibility, not a second implementation of the UI. */
@RunWith(AndroidJUnit4::class)
class GestureIndicatorContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var lastTexts = emptyList<String>()
    private var originalScale = 1f
    private var store: ViewModelStore? = null
    private var scope: CoroutineScope? = null
    private var files: File? = null
    private var preferences: String? = null

    @Before fun setup() {
        originalScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale(1f)
    }
    private fun scale(value: Float) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "settings put global animator_duration_scale $value")).use { it.readBytes() }
        await("Animator scale not applied") {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, -1f) == value
        }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { scope?.cancel(); store?.clear() }
        preferences?.let { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        files?.deleteRecursively()
        scale(originalScale)
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("$message; last accessible texts=$lastTexts")
    }
    private fun activity(block: (ActivityScenario<DebugTvPreviewActivity>) -> Unit) {
        ActivityScenario.launch<DebugTvPreviewActivity>(Intent(context, DebugTvPreviewActivity::class.java).putExtra("screen", "contract_host")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use(block)
    }
    @Suppress("DEPRECATION")
    private fun texts(): List<String>? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != context.packageName || root.childCount == 0) return null
            val result = mutableListOf<String>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                if (node.isVisibleToUser) node.text?.toString()?.let { result += it }
                repeat(node.childCount) { index -> node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                } }
            }
            visit(root)
            lastTexts = result
            return result.takeIf { it.isNotEmpty() }
        } finally { root.recycle() }
    }
    private fun textVisible(value: String) = texts()?.contains(value) == true

    @Suppress("DEPRECATION")
    private fun <T> withNode(matches: (AccessibilityNodeInfo) -> Boolean, action: (AccessibilityNodeInfo) -> T): T? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): T? {
            if (node.isVisibleToUser && matches(node)) return action(node)
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child ->
                    try { visit(child)?.let { return it } } finally { child.recycle() }
                }
            }
            return null
        }
        return try {
            if (root.packageName?.toString() == context.packageName) visit(root) else null
        } finally { root.recycle() }
    }

    private fun controlLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString() == label

    @Suppress("DEPRECATION")
    private fun activateControl(label: String) {
        await("Could not activate player control $label") {
            withNode({ controlLabel(it, label) }) { leaf ->
                var owner = AccessibilityNodeInfo.obtain(leaf)
                try {
                    while (!owner.isClickable) {
                        val parent = owner.parent ?: return@withNode false
                        owner.recycle()
                        owner = parent
                    }
                    owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } finally { owner.recycle() }
            } == true
        }
    }

    private fun tapPlayerControl(scenario: ActivityScenario<DebugTvPreviewActivity>, label: String) {
        val bounds = PixelRect()
        await("Could not locate player control $label") {
            withNode({ controlLabel(it, label) }) { it.getBoundsInScreen(bounds) }
            !bounds.isEmpty
        }
        scenario.onActivity { activity ->
            val origin = IntArray(2)
            val decor = activity.window.decorView
            decor.getLocationOnScreen(origin)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX() - origin[0], bounds.exactCenterY() - origin[1], 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue("Playback window did not consume touch for $label", decor.dispatchTouchEvent(event))
                } finally { event.recycle() }
            }
        }
    }
    private fun shot(bounds: Rect): Bitmap {
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            requireNotNull(crop.copy(Bitmap.Config.ARGB_8888, false))
        } finally { if (crop !== screen) crop?.recycle(); screen.recycle() }
    }
    private fun Bitmap.point(x: Float, y: Float) = getPixel((width * x).toInt(), (height * y).toInt())
    private fun near(actual: Int, red: Int, green: Int, blue: Int) =
        abs(android.graphics.Color.red(actual) - red) <= 5 && abs(android.graphics.Color.green(actual) - green) <= 5 &&
            abs(android.graphics.Color.blue(actual) - blue) <= 5

    @Test fun verticalProgressClampsFillAndPreservesTheDisplayedValue() = activity { scenario ->
        val value = mutableStateOf(100)
        val bounds = AtomicReference<Rect>()
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    VerticalProgressIndicator(value.value, Icons.Rounded.PlayArrow,
                        Modifier.width(64.dp).height(250.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) })
                }
            }
        } }
        await("Progress indicator missing") { bounds.get() != null && textVisible("100") }
        fun filledRows(): Int {
            val bitmap = shot(bounds.get())
            return try { (0 until bitmap.height).count { near(bitmap.getPixel(bitmap.width / 2, it), 124, 244, 205) } }
            finally { bitmap.recycle() }
        }
        var full = 0
        await("Full progress bar was not painted") { full = filledRows(); full > 30 }
        for ((input, fraction) in listOf(0 to 0f, 25 to .25f, 50 to .5f, 100 to 1f, -20 to 0f, 120 to 1f)) {
            instrumentation.runOnMainSync { value.value = input }
            await("Displayed progress did not update to $input") { textVisible(input.toString()) }
            await("Progress fill did not match $input (full=$full)") { abs(filledRows() - full * fraction) <= 3f }
        }
    }

    @Test fun gestureInformationUpdatesAndStaysCentered() = activity { scenario ->
        val info = mutableStateOf("Gesture contract alpha")
        val bounds = AtomicReference<Rect>()
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    GestureInfoText(info.value, Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow()) })
                }
            }
        } }
        for (text in listOf("Gesture contract alpha", "Zoom 125%")) {
            instrumentation.runOnMainSync { info.value = text }
            await("Gesture information missing: $text") { bounds.get() != null && textVisible(text) }
            await("Gesture information was not centered") {
                val bitmap = shot(bounds.get())
                try {
                    var left = bitmap.width; var right = -1
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        if (near(bitmap.getPixel(x, y), 255, 255, 255)) { left = minOf(left, x); right = maxOf(right, x) }
                    }
                    right > left && abs((left + right) / 2f - bitmap.width / 2f) < bitmap.width * .08f
                } finally { bitmap.recycle() }
            }
        }
        await("Old gesture information remained accessible") {
            texts()?.let { "Zoom 125%" in it && "Gesture contract alpha" !in it } == true
        }
    }

    @Test fun remoteSeekFeedbackShowsSignedOffsetsAndTheRunningTarget() = activity { scenario ->
        val visible = mutableStateOf(false)
        val offset = mutableStateOf(10_000L)
        val position = mutableStateOf(3_590_000L)
        val ready = "Remote seek test ready"
        val forward = "+" + context.getString(R.string.seconds_short, 10L)
        val backward = "-" + context.getString(R.string.seconds_short, 20L)
        val zero = "+" + context.getString(R.string.seconds_short, 0L)
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                Box(Modifier.fillMaxSize()) {
                    Text(ready, Modifier.align(Alignment.TopStart))
                    DpadSeekIndicator(visible.value, offset.value, position.value)
                }
            }
        } }
        await("Hidden indicator exposed the forward offset") { texts()?.let { ready in it && forward !in it } == true }
        scenario.onActivity { visible.value = true }
        await("Forward seek offset and target were not displayed") {
            texts()?.let { forward in it && formatVideoTime(3_590_000L) in it } == true
        }
        scenario.onActivity {
            offset.value = -20_000L
            position.value = 3_570_000L
        }
        await("Backward seek retained the old offset or target") {
            texts()?.let { backward in it && formatVideoTime(3_570_000L) in it && forward !in it } == true
        }
        scenario.onActivity {
            offset.value = 0L
            position.value = 3_600_000L
        }
        await("Zero offset and hour-long target were not displayed") {
            texts()?.let { zero in it && formatVideoTime(3_600_000L) in it && backward !in it } == true
        }
        scenario.onActivity { visible.value = false }
        await("Seek feedback remained accessible after closing") {
            texts()?.let { ready in it && zero !in it && formatVideoTime(3_600_000L) !in it } == true
        }
    }

    @Test fun playerButtonActivatesOnceByTouchRemoteAndAccessibility() = activity { scenario ->
        val label = "Playback command"
        val focus = FocusRequester()
        val focused = AtomicBoolean()
        val presses = AtomicInteger()
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerButton(
                        modifier = Modifier.width(180.dp).focusRequester(focus).onFocusChanged { focused.set(it.isFocused) },
                        onClick = { presses.incrementAndGet() },
                    ) { Text(label) }
                }
            }
        } }
        activateControl(label)
        await("Accessibility click did not activate the button") { presses.get() == 1 }
        tapPlayerControl(scenario, label)
        await("Touch did not activate the button exactly once") { presses.get() == 2 }
        scenario.onActivity { focus.requestFocus() }
        await("Remote button did not receive keyboard focus") { focused.get() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Remote OK did not activate the button exactly once") { presses.get() == 3 }
        instrumentation.waitForIdleSync()
        assertEquals(3, presses.get())
    }

    @Suppress("DEPRECATION")
    @Test fun playerToolbarShowsCopyAndDownloadOnlyWhenAvailable() = activity { scenario ->
        val url = mutableStateOf<String?>(null)
        val showDownload = mutableStateOf(false)
        val progress = mutableStateOf<Int?>(null)
        val copied = AtomicReference<AnnotatedString>()
        val download = AtomicInteger()
        val back = AtomicInteger()
        val speed = AtomicInteger()
        val subtitles = AtomicInteger()
        val clipboard = object : ClipboardManager {
            override fun getText() = copied.get()
            override fun setText(annotatedString: AnnotatedString) { copied.set(annotatedString) }
        }
        val title = "Playback toolbar"
        val copyLabel = context.getString(R.string.cd_copy_url)
        val downloadLabel = context.getString(R.string.cd_download)
        fun hasControl(label: String) = withNode({ controlLabel(it, label) }) { true } == true
        fun hasProgress() = withNode({ it.className == "android.widget.ProgressBar" }) { true } == true
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    VideoControlsTop(title, url.value, progress.value, showDownload.value,
                        onBackClick = { back.incrementAndGet() }, onSpeedClick = { speed.incrementAndGet() },
                        onDownloadClick = { download.incrementAndGet() }, onSubtitlesClick = { subtitles.incrementAndGet() },
                        modifier = Modifier.fillMaxSize())
                }
            }
        } }
        await("Toolbar exposed unavailable actions") { textVisible(title) && !hasControl(copyLabel) && !hasControl(downloadLabel) }
        activateControl(context.getString(R.string.cd_back))
        activateControl(context.getString(R.string.select_playback_speed))
        activateControl("CC")
        assertEquals(listOf(1, 1, 1), listOf(back.get(), speed.get(), subtitles.get()))

        val mediaUrl = "https://example.invalid/movie.m3u8"
        scenario.onActivity {
            url.value = mediaUrl
            showDownload.value = true
        }
        activateControl(copyLabel)
        activateControl(downloadLabel)
        assertEquals(mediaUrl, copied.get()?.text)
        assertEquals(1, download.get())
        instrumentation.runOnMainSync { progress.value = -1 }
        await("Indeterminate download progress was not shown") { hasProgress() && !hasControl(downloadLabel) }
        instrumentation.runOnMainSync { progress.value = 60 }
        await("Determinate download progress was not shown") {
            withNode({ it.className == "android.widget.ProgressBar" }) { node ->
                android.os.Build.VERSION.SDK_INT == 22 || node.rangeInfo?.current == 0.6f
            } == true
        }
        instrumentation.runOnMainSync { showDownload.value = false }
        await("Hidden download still exposed progress") { hasControl(copyLabel) && !hasProgress() }
    }

    @Test fun speedSelectorAppliesPresetsAndDismissesInBothOrientations() = activity { scenario ->
        val visible = mutableStateOf(true)
        val speed = mutableStateOf(1f)
        val portrait = mutableStateOf(false)
        val changes = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val dismissals = AtomicInteger()
        val ready = "Speed selector test ready"
        val title = context.getString(R.string.select_playback_speed)
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    orientation = if (portrait.value) Configuration.ORIENTATION_PORTRAIT
                        else Configuration.ORIENTATION_LANDSCAPE
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    Box(Modifier.fillMaxSize()) {
                        Text(ready, Modifier.align(Alignment.TopStart))
                        PlaybackSpeedSelector(
                            show = visible.value, speed = speed.value, skipSilence = false,
                            onSpeedChange = { value -> speed.value = value; changes += value },
                            onSkipSilenceChange = {},
                            onDismiss = { dismissals.incrementAndGet(); visible.value = false }
                        )
                    }
                }
            }
        } }
        await("Landscape speed selector did not open") { textVisible(title) }
        activateControl("2.5")
        await("Playback preset did not reach the player") { changes.toList() == listOf(2.5f) }
        activateControl(context.getString(R.string.cd_reset_speed))
        await("Reset did not restore normal speed") { changes.toList() == listOf(2.5f, 1f) }
        if (Build.VERSION.SDK_INT >= 30 && Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2) {
            // Use the platform Back gesture on gesture-navigation images and the remote key on TV images.
            val window = PixelRect()
            scenario.onActivity { window.set(it.windowManager.currentWindowMetrics.bounds) }
            val x = window.left + 1
            val y = window.centerY()
            val end = window.left + window.width() / 4
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input swipe $x $y $end $y 300")
            ).use { it.readBytes() }
        } else {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        await("Back did not dismiss the landscape selector exactly once") {
            dismissals.get() == 1 && texts()?.let { ready in it && title !in it } == true
        }

        scenario.onActivity { portrait.value = true; visible.value = true }
        await("Portrait speed selector did not open") { textVisible(title) }
        tapPlayerControl(scenario, ready)
        await("Touch outside did not dismiss the portrait selector exactly once") {
            dismissals.get() == 2 && texts()?.let { ready in it && title !in it } == true
        }
        assertEquals(listOf(2.5f, 1f), changes.toList())
    }

    @Test fun playbackBottomControlsToggleTimeAndExposePictureInPictureWhenSupported() = activity { scenario ->
        val pictureInPicture = mutableStateOf(false)
        val rotate = AtomicInteger()
        val lock = AtomicInteger()
        val scale = AtomicInteger()
        val picture = AtomicInteger()
        val elapsed = formatVideoTime(20_000L)
        val remaining = "-" + formatVideoTime(100_000L)
        val duration = formatVideoTime(120_000L)
        val pipLabel = context.getString(R.string.cd_pip)
        fun hasControl(label: String) = withNode({ controlLabel(it, label) }) { true } == true
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                VideoControlsBottom(
                    positionMs = 20_000, durationMs = 120_000, contentScale = VideoContentScale.CROP,
                    isPipSupported = pictureInPicture.value,
                    onRotateClick = { rotate.incrementAndGet() },
                    onLockClick = { lock.incrementAndGet() },
                    onContentScaleClick = { scale.incrementAndGet() },
                    onPipClick = { picture.incrementAndGet() },
                    onSeek = {}, onSeekEnd = {},
                )
            }
        } }
        await("Elapsed time and duration were not rendered") { hasControl(elapsed) && hasControl(duration) }
        assertFalse("PiP action appeared on a device without support", hasControl(pipLabel))
        activateControl(elapsed)
        await("Remaining time was not rendered") { hasControl(remaining) && !hasControl(elapsed) && hasControl(duration) }
        activateControl(remaining)
        await("Elapsed time was not restored") { hasControl(elapsed) && !hasControl(remaining) }

        activateControl(context.getString(R.string.cd_rotate))
        activateControl(context.getString(R.string.cd_lock_controls))
        activateControl(context.getString(R.string.scale_crop))
        assertEquals(listOf(1, 1, 1), listOf(rotate.get(), lock.get(), scale.get()))
        scenario.onActivity { pictureInPicture.value = true }
        activateControl(pipLabel)
        assertEquals(1, picture.get())
    }

    @Test fun playbackSeekBarReportsRemotePositionInAnRtlLayout() = activity { scenario ->
        val label = "Playback position"
        val duration = mutableStateOf(0L)
        val position = mutableStateOf(0L)
        val focused = AtomicBoolean()
        val completed = AtomicInteger()
        val requester = FocusRequester()
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    VideoControlsBottom(
                        positionMs = position.value, durationMs = duration.value,
                        contentScale = VideoContentScale.BEST_FIT, isPipSupported = false,
                        onRotateClick = {}, onLockClick = {}, onContentScaleClick = {}, onPipClick = {},
                        onSeek = { position.value = it },
                        onSeekEnd = { completed.incrementAndGet() },
                        seekBarModifier = Modifier.focusRequester(requester)
                            .onFocusChanged { focused.set(it.isFocused) }
                            .semantics { contentDescription = label },
                    )
                }
            }
        } }
        await("The seek bar was missing before the duration was known") {
            withNode({ controlLabel(it, label) }) { true } == true
        }
        assertEquals(0, completed.get())
        scenario.onActivity {
            duration.value = 120_000
            position.value = 30_000
            requester.requestFocus()
        }
        await("Seek bar did not receive remote focus") { focused.get() }
        fun press(direction: Int) {
            scenario.onActivity { activity ->
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction))
            }
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        var advanced = 0L
        await("Right did not advance playback in the RTL layout") {
            instrumentation.runOnMainSync { advanced = position.value }
            advanced in 30_001L..120_000L && completed.get() == 1
        }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        await("Left did not rewind playback in the RTL layout") {
            var rewound = 0L
            instrumentation.runOnMainSync { rewound = position.value }
            rewound in 0L until advanced && completed.get() == 2
        }
    }

    private class TestApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    @Suppress("UNCHECKED_CAST")
    private fun playbackState(model: MainViewModel) {
        (MainViewModel::class.java.getDeclaredField("_videoDurationMs").apply { isAccessible = true }.get(model) as MutableStateFlow<Long>).value = 100_000L
        (MainViewModel::class.java.getDeclaredField("_videoPlaybackActive").apply { isAccessible = true }.get(model) as MutableStateFlow<Boolean>).value = true
    }
    @Test fun doubleTapPaintsOnlyItsDirectionAndDisappearsAfterTimeout() = activity { scenario ->
        val name = "gesture-indicator-${System.nanoTime()}"
        val directory = File(context.cacheDir, name).apply { mkdirs() }; files = directory; preferences = name
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = directory
            override fun getSharedPreferences(key: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        lateinit var tap: TapGestureState
        val bounds = AtomicReference<Rect>()
        instrumentation.runOnMainSync {
            val model = MainViewModel(TestApplication(isolated))
            store = ViewModelStore().also { it.put("indicator", model) }
            playbackState(model)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            tap = TapGestureState(model, requireNotNull(scope))
        }
        scenario.onActivity { target -> target.setContent {
            AirPlayTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                    DoubleTapIndicator(tap)
                }
            }
        } }
        await("Indicator surface missing") { bounds.get() != null }
        fun pixels(predicate: (Bitmap) -> Boolean): Boolean {
            val bitmap = shot(bounds.get())
            return try { predicate(bitmap) } finally { bitmap.recycle() }
        }
        await("Inactive double-tap indicator was visible") { pixels { near(it.point(.1f, .2f), 0, 0, 0) && near(it.point(.9f, .2f), 0, 0, 0) } }
        for (forward in listOf(true, false)) {
            await("Double-tap indicator did not paint its direction: $forward") {
                instrumentation.runOnMainSync {
                    if (tap.seekMillis == 0L || (tap.seekMillis > 0) != forward) {
                        tap.handleDoubleTap(Offset(if (forward) 900f else 100f, 100f), IntSize(1000, 1000))
                    }
                }
                pixels {
                    val selected = it.point(if (forward) .9f else .1f, .2f)
                    val unselected = it.point(if (forward) .1f else .9f, .2f)
                    android.graphics.Color.red(selected) in 40..110 && near(unselected, 0, 0, 0) && near(it.point(.5f, .2f), 0, 0, 0)
                }
            }
        }
        await("Seek accumulator did not expire") {
            var expired = false; instrumentation.runOnMainSync { expired = tap.seekMillis == 0L }; expired
        }
        await("Expired double-tap indicator remained painted") { pixels { near(it.point(.1f, .2f), 0, 0, 0) && near(it.point(.9f, .2f), 0, 0, 0) } }
    }
}
