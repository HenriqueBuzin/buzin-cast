package io.github.jqssun.airplay.media

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Bounded local-network photo viewer. Downloads only the URL supplied by DLNA. */
class PhotoActivity : ComponentActivity() {
    private var bitmap: Bitmap? = null
    private lateinit var image: ImageView
    private var rotation = 0f
    private lateinit var status: TextView
    private val history = mutableListOf<String>()
    private var index = -1
    private var slideShow: Job? = null
    private val requests = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply { text = "Carregando foto…"; setTextColor(Color.WHITE); setPadding(24, 12, 24, 12) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Foto recebida por DLNA" }
        root.addView(status); root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply { text = "Anterior"; setOnClickListener { navigate(-1) } })
        controls.addView(Button(this).apply { text = "Próxima"; setOnClickListener { navigate(1) } })
        controls.addView(Button(this).apply { text = "Apresentação"; setOnClickListener {
            if (slideShow?.isActive == true) { slideShow?.cancel(); text = "Apresentação" }
            else { text = "Parar"; slideShow = lifecycleScope.launch { while (isActive) { delay(5000); navigate(1) } } }
        } })
        controls.addView(Button(this).apply { text = "Girar"; setOnClickListener { rotation = (rotation + 90) % 360; image.rotation = rotation } })
        controls.addView(Button(this).apply { text = "Ajustar / preencher"; setOnClickListener { image.scaleType = if (image.scaleType == ImageView.ScaleType.FIT_CENTER) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER } })
        controls.addView(Button(this).apply { text = "Fechar"; setOnClickListener { finish() } })
        root.addView(controls); setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        lifecycleScope.launch {
          for (url in requests) {
            try {
                status.text = "Carregando foto…"
                val loaded = withContext(Dispatchers.IO) { loadPhoto(url) }
                if (isFinishing || isDestroyed) { loaded.recycle(); return@launch }
                image.setImageDrawable(null); bitmap?.recycle(); rotation = 0f; image.rotation = 0f
                bitmap = loaded; image.setImageBitmap(loaded)
                status.text = "Foto ${index + 1} de ${history.size} • use o controle para navegar, girar ou fechar"
            } catch (e: Exception) { status.text = "Não foi possível abrir a foto: ${e.message}" }
          }
        }
        enqueue(intent.getStringExtra("url"))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); enqueue(intent.getStringExtra("url")) }
    private fun enqueue(url: String?) {
        if (url == null) return
        if (history.lastOrNull() != url) { history.add(url); if (history.size > 20) history.removeAt(0) }
        index = history.lastIndex; requests.trySend(url)
    }
    private fun navigate(delta: Int) {
        if (history.isEmpty()) return
        index = (index + delta + history.size) % history.size
        requests.trySend(history[index])
    }
    override fun onDestroy() { if (current.get() === this) current.clear(); image.setImageDrawable(null); bitmap?.recycle(); bitmap = null; super.onDestroy() }
    companion object {
        private var current = java.lang.ref.WeakReference<PhotoActivity>(null)
        fun closePhoto() { current.get()?.let { activity -> activity.runOnUiThread { activity.finish() } } }
        fun isVisible() = current.get() != null
        fun loadPhoto(location: String): Bitmap {
            var url = URL(location)
            repeat(5) {
                require(url.protocol in listOf("http", "https") && url.userInfo == null)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 8000; instanceFollowRedirects = false
                }
                try {
                    if (connection.responseCode in 300..399) { url = URL(url, connection.getHeaderField("Location") ?: error("Redirecionamento inválido")); return@repeat }
                    require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                    require(connection.contentLength <= 16 * 1024 * 1024) { "Foto maior que 16 MB" }
                    val data = connection.inputStream.use { input ->
                        val buffer = ByteArray(16384); val out = ByteArrayOutputStream()
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 16 * 1024 * 1024); out.write(buffer, 0, n) }
                        out.toByteArray()
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    require(options.outWidth > 0 && options.outHeight > 0)
                    var sample = 1
                    while (options.outWidth / sample > 3840 || options.outHeight / sample > 2160) sample *= 2
                    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Formato de imagem inválido")
                } finally { connection.disconnect() }
            }
            error("Redirecionamentos em excesso")
        }
    }
}
