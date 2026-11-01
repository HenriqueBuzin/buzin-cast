package io.github.jqssun.airplay.media

import android.content.Context
import android.net.Uri
import java.io.File

object ExternalSubtitles {
    fun import(context: Context, uri: Uri): Pair<Uri,String> {
        require(uri.scheme == "content") { "Selecione um arquivo local SRT ou VTT" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 2 * 1024 * 1024) { "Legenda excede 2 MB" }
                out.write(buffer,0,n)
            }
            out.toByteArray()
        } ?: error("Não foi possível ler a legenda")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val vtt = text.trimStart().startsWith("WEBVTT")
        require(vtt || Regex("\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2},\\d{3}").containsMatchIn(text)) { "Arquivo não é SRT/VTT UTF-8" }
        val file = File(context.cacheDir,"external-subtitle.${if (vtt) "vtt" else "srt"}")
        file.writeText(text)
        return Uri.fromFile(file) to if (vtt) "text/vtt" else "application/x-subrip"
    }
}
