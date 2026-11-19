package io.buzincast.legacy

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Platform lifecycle for the shared photo UI, without ComponentActivity's API floor. */
open class LegacyPhotoActivityBase : Activity() {
    protected val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = Any()
    private var service: LegacyReceiverService? = null
    private var bound = false
    private var resumed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as LegacyReceiverService.LocalBinder).service()
            if (resumed) service?.attach(owner) { }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        bound = bindService(Intent(this, LegacyReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onResume() {
        super.onResume(); resumed = true
        service?.attach(owner) { }
    }
    override fun onStop() {
        resumed = false; service?.detach(owner); super.onStop()
    }
    override fun onPostCreate(state: Bundle?) {
        super.onPostCreate(state)
        val buttons = mutableListOf<Button>()
        fun visit(view: View) {
            if (view is Button) buttons += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(findViewById(android.R.id.content))
        buttons.forEachIndexed { index, button ->
            button.tag = "photo-control-$index"
            button.setTextColor(LegacyUi.WHITE)
            LegacyUi.focus(button, false)
            button.layoutParams = LinearLayout.LayoutParams(0, LegacyUi.dp(this, 54f), 1f)
        }
        buttons.firstOrNull()?.requestFocus()
    }
    override fun onDestroy() {
        lifecycleScope.cancel()
        service?.detach(owner)
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onDestroy()
    }
}
