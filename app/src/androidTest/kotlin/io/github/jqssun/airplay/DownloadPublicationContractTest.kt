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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer

/** Publication uses isolated cache files and a private in-process store on Q+. */
@RunWith(AndroidJUnit4::class)
class DownloadPublicationContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val roots = mutableListOf<File>()
    private enum class Destination { NORMAL, MISSING, UNWRITABLE, STREAM_UNAVAILABLE, FINALIZATION_REJECTED }
    private enum class QueryResult { PATH, NULL_CURSOR, EMPTY_CURSOR, NULL_PATH, PATH_LOOKUP_FAILED }

    private class Store(val output: File, val destination: Destination, val queryResult: QueryResult) : ContentProvider() {
        val inserts = mutableListOf<ContentValues>()
        val updates = mutableListOf<ContentValues>()
        val deletes = mutableListOf<Uri>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            inserts += ContentValues(requireNotNull(values))
            return if (destination == Destination.MISSING) null else Uri.withAppendedPath(uri, "1")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updates += ContentValues(requireNotNull(values))
            return if (destination == Destination.FINALIZATION_REJECTED) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes += uri
            if (output.exists()) check(output.delete())
            return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
            if (queryResult == QueryResult.PATH_LOOKUP_FAILED) throw UnsupportedOperationException("Path lookup unavailable")
            if (queryResult == QueryResult.NULL_CURSOR) return null
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply {
                if (queryResult != QueryResult.EMPTY_CURSOR)
                    addRow(arrayOf<Any?>(if (queryResult == QueryResult.NULL_PATH) null else output.absolutePath))
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (destination == Destination.STREAM_UNAVAILABLE) return null
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
        val formats = if (Build.VERSION.SDK_INT >= 29) {
            listOf(QueryResult.PATH, QueryResult.NULL_CURSOR, QueryResult.EMPTY_CURSOR, QueryResult.NULL_PATH)
        } else listOf(QueryResult.PATH)
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
                assertTrue("Successful publication deleted its destination", store.deletes.isEmpty())
                if (format == QueryResult.PATH) assertEquals(output.absolutePath, path)
                else assertEquals("${Environment.DIRECTORY_DOWNLOADS}/${store.inserts.single().getAsString(MediaStore.MediaColumns.DISPLAY_NAME)}", path)
            }
        }
    }

    @Test fun publishedDownloadUsesItsRelativePathWhenTheStoreRejectsPathLookup() {
        if (Build.VERSION.SDK_INT < 29) return
        val fixture = fixture(queryResult = QueryResult.PATH_LOOKUP_FAILED)
        val bytes = byteArrayOf(42, 23, 16, 8)
        val source = File(fixture.root, "source.bin").apply { writeBytes(bytes) }

        val path = publish(fixture, source)
        val store = requireNotNull(fixture.store)
        val name = store.inserts.single().getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        assertEquals("${Environment.DIRECTORY_DOWNLOADS}/$name", path)
        assertEquals(0, store.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        assertTrue(store.deletes.isEmpty())
        assertArrayEquals(bytes, store.output.readBytes())
    }

    @Test fun missingOutputStreamRemovesThePendingStoreEntry() {
        if (Build.VERSION.SDK_INT < 29) return
        val fixture = fixture(Destination.STREAM_UNAVAILABLE)
        val bytes = byteArrayOf(2, 4, 6, 8)
        val source = File(fixture.root, "source.bin").apply { writeBytes(bytes) }

        assertNull(publish(fixture, source))
        val store = requireNotNull(fixture.store)
        assertEquals(1, store.inserts.size)
        assertTrue(store.updates.isEmpty())
        assertEquals(listOf(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "1")), store.deletes)
        assertFalse(store.output.exists())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun missingAndUnwritableDestinationsReportFailureAndPreserveTheExport() {
        for (destination in listOf(Destination.MISSING, Destination.UNWRITABLE)) {
            val fixture = fixture(destination)
            val source = File(fixture.root, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertNull(publish(fixture, source))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
            fixture.store?.let { store ->
                assertTrue("Failed publication was marked complete", store.updates.isEmpty())
                val expectedDeletes = if (destination == Destination.MISSING) emptyList()
                    else listOf(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "1"))
                assertEquals("Only an inserted pending destination should be removed", expectedDeletes, store.deletes)
            }
        }
    }

    @Test fun rejectedFinalizationRemovesThePendingCopyAndPreservesTheExport() {
        if (Build.VERSION.SDK_INT < 29) return // App-specific storage has no pending MediaStore row.
        val fixture = fixture(Destination.FINALIZATION_REJECTED)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = File(fixture.root, "source.bin").apply { writeBytes(bytes) }

        assertNull(publish(fixture, source))
        val store = requireNotNull(fixture.store)
        assertEquals(1, store.inserts.size)
        assertEquals(0, store.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        assertEquals(listOf(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "1")), store.deletes)
        assertFalse("Failed publication left a pending copy", store.output.exists())
        assertArrayEquals(bytes, source.readBytes())
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

    @Test fun unreadableMediaResetsProgressAndRemovesTheFailedExport() {
        val fixture = fixture()
        val temporary = File(fixture.root, "video_download.mp4")
        val started = CountDownLatch(1)
        val initial = AtomicReference<Int?>()
        instrumentation.runOnMainSync {
            fixture.downloader.start(Uri.fromFile(File(fixture.root, "missing-input.mp4")).toString())
            Handler(Looper.getMainLooper()).post {
                initial.set(fixture.downloader.progress.value)
                started.countDown()
            }
        }
        assertTrue("Export did not start", started.await(10, TimeUnit.SECONDS))
        assertEquals(-1, initial.get())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (fixture.downloader.progress.value != null && System.nanoTime() < deadline) Thread.sleep(20)
        instrumentation.waitForIdleSync()
        assertNull("Failed export remained active", fixture.downloader.progress.value)
        assertFalse("Failed export left a temporary file", temporary.exists())
        assertTrue("Failed export published a destination", fixture.store?.inserts.orEmpty().isEmpty())
    }

    @Test fun repeatedStartKeepsTheActiveTransformerAndCancellationCleansItsFile() {
        val fixture = fixture()
        val transformer = VideoDownloader::class.java.getDeclaredField("transformer").apply { isAccessible = true }
        val temporary = File(fixture.root, "video_download.mp4")
        val checked = CountDownLatch(1)
        val first = AtomicReference<Any?>()
        val second = AtomicReference<Any?>()
        instrumentation.runOnMainSync {
            fixture.downloader.start(Uri.fromFile(File(fixture.root, "missing-first.mp4")).toString())
            Handler(Looper.getMainLooper()).post { first.set(transformer.get(fixture.downloader)) }
            fixture.downloader.start(Uri.fromFile(File(fixture.root, "missing-second.mp4")).toString())
            Handler(Looper.getMainLooper()).post {
                second.set(transformer.get(fixture.downloader))
                temporary.writeText("In-flight export")
            }
            fixture.downloader.cancel()
            Handler(Looper.getMainLooper()).post { checked.countDown() }
        }
        assertTrue("Cancellation did not finish", checked.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNotNull("No active transformer was created", first.get())
        assertSame("Repeated start replaced the active export", first.get(), second.get())
        assertNull(transformer.get(fixture.downloader))
        assertNull(fixture.downloader.progress.value)
        assertFalse("Cancelled export left its temporary file", temporary.exists())
        assertTrue("Cancelled export published a destination", fixture.store?.inserts.orEmpty().isEmpty())
    }

    @Test fun localVideoExportPublishesAPlayableFileWithEveryVideoSample() {
        val fixture = fixture()
        val input = File(fixture.root, "input.mp4")
        instrumentation.context.assets.open("download/sample.mp4").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        val started = CountDownLatch(1)
        val initial = AtomicReference<Int?>()
        instrumentation.runOnMainSync {
            fixture.downloader.start(Uri.fromFile(input).toString())
            Handler(Looper.getMainLooper()).post {
                initial.set(fixture.downloader.progress.value)
                started.countDown()
            }
        }
        assertTrue("Export did not start", started.await(10, TimeUnit.SECONDS))
        assertEquals(-1, initial.get())
        val temporary = File(fixture.root, "video_download.mp4")
        fun published(): File? = fixture.store?.output?.takeIf { it.isFile && it.length() > 0 }
            ?: File(fixture.root, "downloads").listFiles()?.singleOrNull { it.extension == "mp4" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while ((published() == null || temporary.exists() || fixture.downloader.progress.value != null) &&
            System.nanoTime() < deadline) Thread.sleep(20)
        assertNull("Completed export remained active", fixture.downloader.progress.value)
        assertFalse("Completed export left its temporary file", temporary.exists())
        val output = requireNotNull(published()) { "Export produced no published video" }
        assertTrue("Export removed its input", input.isFile)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            assertEquals(32, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(32, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            var samples = 0
            while (extractor.readSampleData(buffer, 0) >= 0) {
                assertEquals("Video timestamp changed", samples * 100_000L, extractor.sampleTime)
                samples++
                extractor.advance()
            }
            assertEquals("Video samples were lost", 10, samples)
        } finally { extractor.release() }
        fixture.store?.let { store ->
            assertEquals(1, store.inserts.size)
            assertEquals(0, store.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        }
    }
}
