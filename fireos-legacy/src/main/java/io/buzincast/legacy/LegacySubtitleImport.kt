package io.buzincast.legacy

import android.content.Context
import android.net.Uri
import io.github.jqssun.airplay.media.ExternalSubtitles
import java.io.File

/** Same UTF-8/SRT/VTT/2 MiB validation as the modern frontend. */
object LegacySubtitleImport {
    @JvmStatic @Throws(Exception::class)
    fun read(context: Context, uri: Uri): Pair<ByteArray, String> {
        val imported = ExternalSubtitles.import(context, uri)
        val file = File(requireNotNull(imported.first.path))
        try { return file.readBytes() to imported.second }
        finally { if (!file.delete() && file.exists()) android.util.Log.w("LegacySubtitleImport", "Subtitle cache cleanup failed") }
    }
}
