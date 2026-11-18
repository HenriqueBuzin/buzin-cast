package io.github.jqssun.airplay

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jqssun.airplay.download.VideoDownloader
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/** Publication uses isolated cache files and a private in-process store on Q+. */
@RunWith(AndroidJUnit4::class)
class DownloadPublicationContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val roots = mutableListOf<File>()
    private enum class Destination { NORMAL, MISSING, UNWRITABLE }
    private enum class QueryResult { PATH, NULL_CURSOR, EMPTY_CURSOR, NULL_PATH }

    private class Store(val output: File, val destination: Destination, val queryResult: QueryResult) : ContentProvider() {
        val inserts = mutableListOf<ContentValues>()
        val updates = mutableListOf<ContentValues>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            inserts += ContentValues(requireNotNull(values))
            return if (destination == Destination.MISSING) null else Uri.withAppendedPath(uri, "1")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updates += ContentValues(requireNotNull(values)); return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = if (output.delete()) 1 else 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
            if (queryResult == QueryResult.NULL_CURSOR) return null
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply {
                if (queryResult != QueryResult.EMPTY_CURSOR)
                    addRow(arrayOf<Any?>(if (queryResult == QueryResult.NULL_PATH) null else output.absolutePath))
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (destination == Destination.UNWRITABLE) throw FileNotFoundException("Controlled unavailable destination")
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
    }

    private data class Fixture(val downloader: VideoDownloader, val root: File, val store: Store?)
    private fun fixture(destination: Destination = Destination.NORMAL, queryResult: QueryResult = QueryResult.PATH): Fixture {
        val appContext = context
        val root = File(context.cacheDir, "publication-contract-${System.nanoTime()}").also { it.mkdirs(); roots += it }
        val external = File(root, "downloads")
        if (destination == Destination.UNWRITABLE) external.writeText("Not a directory") else external.mkdirs()
        val store = if (Build.VERSION.SDK_INT >= 29) Store(File(root, "published.mp4"), destination, queryResult).apply {
            attachInfo(appContext, ProviderInfo().apply { authority = "media"; applicationInfo = appContext.applicationInfo })
        } else null
        val wrapped = object : ContextWrapper(context) {
            override fun getCacheDir() = root
            override fun getExternalFilesDir(type: String?): File? = if (destination == Destination.MISSING) null else external
            override fun getContentResolver(): ContentResolver =
                if (Build.VERSION.SDK_INT >= 29) ContentResolver.wrap(requireNotNull(store)) else super.getContentResolver()
        }
        return Fixture(VideoDownloader(wrapped), root, store)
    }
    private fun publish(fixture: Fixture, source: File): String? =
        VideoDownloader::class.java.getDeclaredMethod("_publish", File::class.java).apply { isAccessible = true }
            .invoke(fixture.downloader, source) as String?

    @After fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        if (runCatching { Class.forName("org.jacoco.agent.rt.RT") }.isSuccess) DebugCoverage.dump(context)
    }

    @Test fun publicationCopiesExactBytesAndCompletesThePendingStoreEntry() {
        val formats = if (Build.VERSION.SDK_INT >= 29) QueryResult.entries else listOf(QueryResult.PATH)
        for (format in formats) {
            val fixture = fixture(queryResult = format)
            val source = File(fixture.root, "source.bin")
            val original = ByteArray(512) { it.toByte() }
            source.writeBytes(original)
            val path = requireNotNull(publish(fixture, source))
            val output = fixture.store?.output ?: File(path)
            assertArrayEquals(original, output.readBytes())
            assertArrayEquals("Publication consumed the export before completion", original, source.readBytes())
            fixture.store?.let { store ->
                assertEquals(1, store.inserts.size)
                assertEquals("video/mp4", store.inserts.single().getAsString(MediaStore.MediaColumns.MIME_TYPE))
                assertEquals(1, store.inserts.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
                assertEquals(0, store.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
                if (format == QueryResult.PATH) assertEquals(output.absolutePath, path)
                else assertEquals("${Environment.DIRECTORY_DOWNLOADS}/${store.inserts.single().getAsString(MediaStore.MediaColumns.DISPLAY_NAME)}", path)
            }
        }
    }

    @Test fun missingAndUnwritableDestinationsReportFailureAndPreserveTheExport() {
        for (destination in listOf(Destination.MISSING, Destination.UNWRITABLE)) {
            val fixture = fixture(destination)
            val source = File(fixture.root, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertNull(publish(fixture, source))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
            fixture.store?.let { assertTrue("Failed publication was marked complete", it.updates.isEmpty()) }
        }
    }

    @Test fun cancellingAnIdleDownloaderKeepsItsStateAndExistingTemporaryFile() {
        val fixture = fixture()
        val file = File(fixture.root, "video_download.mp4").apply { writeText("Existing export") }
        assertNull(fixture.downloader.progress.value)
        fixture.downloader.cancel()
        instrumentation.runOnMainSync { }
        assertNull(fixture.downloader.progress.value)
        assertEquals("Existing export", file.readText())
    }
}
