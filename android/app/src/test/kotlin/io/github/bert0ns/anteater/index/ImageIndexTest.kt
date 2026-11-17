// The lab's tests/test_index.py, translated.
package io.github.bert0ns.anteater.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.anteater.compress.JpegInfo
import io.github.bert0ns.anteater.compress.JpegStatus
import io.github.bert0ns.anteater.metadata.ImageMetadata
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.model.GROUP_KEY
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.Protection
import io.github.bert0ns.anteater.model.ROLE_KEY
import io.github.bert0ns.anteater.model.ReviewState
import io.github.bert0ns.anteater.model.Role
import io.github.bert0ns.anteater.model.Signal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val SNAPSHOT_TEST = "snapshot-test.sqlite"
private val PHOTO = MediaRef("/photos/a.jpg", 100, 1)
private val OTHER = MediaRef("/photos/b.jpg", 5, 5)

/** The walk's memory of non-images: it never reaches the review, so it is neither in `reads` nor in `writes`. */
private val NOT_IMAGES_METHODS = setOf("notImages", "addNotImages", "forgetNotImages")

/** What the scan read from JPEG headers for the compress page: it never reaches the review either. */
private val JPEG_METHODS = setOf("jpegsToRead", "setJpeg", "setJpegBeforeScan", "compressible", "jpegCounts")
private val META = ImageMetadata(4000, 3000, "image/jpeg", "Google", "Pixel 8", LocalDateTime.of(2024, 5, 1, 10, 20))
private val VIDEO = MediaRef("/videos/v.mp4", 900, 1, MediaType.VIDEO)
private val VIDEO_META = VideoMetadata(1080, 1920, 12_345, 29.97, 8_000_000, true, "video/hevc", true, LocalDateTime.of(2024, 5, 1, 12, 20), 1_714_558_800_000)
private val BLUR = Signal("blur", 1, Category.BLURRY, 0.7f, mapOf("variance" to "12.5"))

fun memoryIndex() = ImageIndex(
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDatabase::class.java).build(),
)

@RunWith(RobolectricTestRunner::class)
class ImageIndexTest {
    private val index = memoryIndex()

    @After
    fun close() = index.close()

    private fun test(block: suspend ImageIndex.() -> Unit) = runBlocking { index.block() }

    @Test
    fun `indexedMedia does not wait for a write in progress, and reads what was committed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(SNAPSHOT_TEST)
        val db = Room.databaseBuilder(context, IndexDatabase::class.java, SNAPSHOT_TEST).build()
        ImageIndex(db).use { file ->
            runBlocking { file.record(PHOTO) }
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            // A scan's write: the write lock held, its change not committed yet.
            val writer = thread {
                val sql = db.openHelper.writableDatabase
                sql.beginTransactionNonExclusive()
                try {
                    sql.execSQL("UPDATE images SET review_state = 'kept'")
                    writing.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    sql.setTransactionSuccessful()
                } finally {
                    sql.endTransaction()
                }
            }
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS))
                val read = runBlocking { withTimeout(5_000) { file.indexedMedia() } }
                assertEquals(listOf(PHOTO to ReviewState.UNREVIEWED), read.map { it.ref to it.reviewState })
            } finally {
                release.countDown()
                writer.join()
            }
            assertEquals(ReviewState.KEPT, runBlocking { file.indexedMedia() }.single().reviewState)
        }
        context.deleteDatabase(SNAPSHOT_TEST)
    }

    @Test
    fun `notTrashed leaves out trashed images`() = test {
        record(PHOTO)
        record(OTHER)
        setReviewState(OTHER.path, ReviewState.TRASHED)
        assertEquals(listOf(PHOTO), notTrashed())
        setReviewState(OTHER.path, ReviewState.UNREVIEWED)
        assertEquals(listOf(PHOTO, OTHER), notTrashed())
    }

    @Test
    fun `notTrashedMedia holds the videos as well, and leaves out the trashed ones`() = test {
        recordAll(listOf(PHOTO, VIDEO))
        assertEquals(listOf(PHOTO, VIDEO), notTrashedMedia())
        assertEquals(listOf(PHOTO), notTrashed())

        setReviewState(VIDEO.path, ReviewState.TRASHED)
        assertEquals(listOf(PHOTO), notTrashedMedia())
    }

    @Test
    fun `notTrashedMedia keeps a kept video and an unreadable one, as it keeps an image`() = test {
        val kept = MediaRef("/videos/kept.mp4", 800, 1, MediaType.VIDEO)
        val broken = MediaRef("/videos/broken.mp4", 700, 1, MediaType.VIDEO)
        recordAll(listOf(kept, broken))
        setReviewState(kept.path, ReviewState.KEPT)
        setUnreadable(broken.path)

        assertEquals(listOf(broken, kept), notTrashedMedia())
    }

    @Test
    fun `a video is recorded with its media type and indexedMedia gives it with that type, for the categories of the videos`() = test {
        val video = MediaRef("/videos/v.mp4", 900, 1, MediaType.VIDEO)
        recordAll(listOf(PHOTO, video))

        assertEquals(listOf(PHOTO, video), images())
        assertEquals(listOf(PHOTO, video), indexedMedia().map { it.ref })
    }

    @Test
    fun `imageCount counts the images only, so an index of videos still reads as empty`() = test {
        record(MediaRef("/videos/v.mp4", 900, 1, MediaType.VIDEO))
        assertEquals(0, imageCount())

        record(PHOTO)
        assertEquals(1, imageCount())
    }

    @Test
    fun `the camera photos are the dated ones in DCIM, screenshots and other folders left out, and a read of them loads no embedding`() = test {
        val camera = MediaRef("/sdcard/DCIM/Camera/a.jpg", 1, 1)
        val upper = MediaRef("/sdcard/dcim/Camera/b.jpg", 1, 1)
        val shot = MediaRef("/sdcard/DCIM/Screenshots/s.jpg", 1, 1)
        val chat = MediaRef("/sdcard/WhatsApp/c.jpg", 1, 1)
        val undated = MediaRef("/sdcard/DCIM/Camera/d.jpg", 1, 1)
        for (photo in listOf(camera, upper, shot, chat, undated)) record(photo)
        for (photo in listOf(camera, upper, shot, chat)) setMetadata(photo.path, META)
        setMetadata(undated.path, META.copy(capturedAt = null))
        setEmbeddings("clip", 1, mapOf(camera.path to floatArrayOf(1f), shot.path to floatArrayOf(1f)))

        assertEquals(setOf(camera.path, upper.path), cameraCaptures().keys)
        assertEquals(setOf(camera.path), embeddingsOf("clip", 1, listOf(camera.path, upper.path, chat.path)).keys)
        assertEquals("0:2:1", shortClipInputs("clip", 1, 1))
    }

    @Test
    fun `a file whose media type changed with its size is recorded with the new one`() = test {
        record(PHOTO)
        val video = MediaRef(PHOTO.path, 900, 1, MediaType.VIDEO)

        assertTrue(record(video))

        assertEquals(listOf(video), images())
    }

    @Test
    fun `record reports new and changed images`() = test {
        assertTrue(record(PHOTO))
        assertFalse(record(PHOTO))
        assertTrue(record(MediaRef(PHOTO.path, 101, 1)))
        assertTrue(record(MediaRef(PHOTO.path, 101, 2)))
        assertEquals(listOf(MediaRef(PHOTO.path, 101, 2)), images())
    }

    @Test
    fun `recordAll counts the new and changed images and leaves the rest as they were`() = test {
        record(PHOTO)
        record(OTHER)
        setMetadata(OTHER.path, META)
        val fresh = MediaRef("/photos/c.jpg", 7, 7)

        assertEquals(2, recordAll(listOf(PHOTO, MediaRef(OTHER.path, 6, 5), fresh)))

        assertEquals(listOf(PHOTO, MediaRef(OTHER.path, 6, 5), fresh), images())
        assertNull(metadata(OTHER.path)) // the changed one was reset, like record does
    }

    @Test
    fun `recordAll looks up more paths than one statement takes, and a path listed twice sees its first record`() = test {
        val many = (0 until 1_200).map { MediaRef("/photos/$it.jpg", 1, 1) }
        val touched = MediaRef(PHOTO.path, 101, 2)

        assertEquals(1_203, recordAll(many + PHOTO + touched + PHOTO)) // new, then changed twice

        assertEquals(0, recordAll(many))
        assertEquals(PHOTO, images().single { it.path == PHOTO.path })
    }

    @Test
    fun `recordAll of images already recorded keeps the version`() = test {
        recordAll(listOf(PHOTO, OTHER))
        val before = version

        assertEquals(0, recordAll(listOf(PHOTO, OTHER)))

        assertEquals(before, version)
    }

    @Test
    fun `a batch that fails part way leaves the index as it was`() = test {
        record(PHOTO)
        val before = version
        val batch = object : AbstractList<MediaRef>() {
            override val size = 3
            override fun get(index: Int) = if (index == 2) error("disk full") else MediaRef("/photos/new-$index.jpg", 1, 1)
        }

        assertThrows(IllegalStateException::class.java) { runBlocking { recordAll(batch) } }

        assertEquals(listOf(PHOTO), images())
        assertEquals(before, version)
        assertEquals(2, recordAll(batch.take(2))) // the index still takes the same files
    }

    @Test
    fun `a new file at the path of a trashed image starts unreviewed`() = test {
        record(PHOTO)
        setReviewState(PHOTO.path, ReviewState.TRASHED)

        assertTrue(record(MediaRef(PHOTO.path, 101, 1)))

        assertEquals(ReviewState.UNREVIEWED, reviewState(PHOTO.path))
    }

    @Test
    fun `recording a trashed image again unchanged leaves it trashed`() = test {
        record(PHOTO)
        setReviewState(PHOTO.path, ReviewState.TRASHED)

        assertFalse(record(PHOTO))

        assertEquals(ReviewState.TRASHED, reviewState(PHOTO.path))
    }

    @Test
    fun `forgetTrashed forgets trashed rows and leaves the others`() = test {
        record(PHOTO)
        record(OTHER)
        setReviewState(PHOTO.path, ReviewState.TRASHED)
        setReviewState(OTHER.path, ReviewState.KEPT)

        forgetTrashed(listOf(PHOTO.path, OTHER.path))

        assertEquals(listOf(OTHER), images())
        assertEquals(ReviewState.KEPT, reviewState(OTHER.path))
    }

    @Test
    fun `changed image drops its stale data but keeps the review decision`() = test {
        record(PHOTO)
        setMetadata(PHOTO.path, META)
        setUnreadable(PHOTO.path)
        setContentHash(PHOTO.path, "0123abcd")
        setPerceptualHash(PHOTO.path, 42u)
        setEmbeddings("clip", 1, mapOf(PHOTO.path to FloatArray(4) { 1f }))
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        record(MediaRef(PHOTO.path, 200, 5)) // edited, or just touched by a sync tool

        assertNull(metadata(PHOTO.path))
        assertEquals(emptyList<String>(), unreadable())
        assertNull(contentHash(PHOTO.path))
        assertNull(perceptualHash(PHOTO.path))
        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
        assertFalse(isAnalyzed(PHOTO.path, "blur", 1))
        assertEquals(ReviewState.KEPT, reviewState(PHOTO.path))
    }

    @Test
    fun `metadata round trip`() = test {
        record(PHOTO)
        for (meta in listOf(META, ImageMetadata(1080, 2400, "image/png"))) {
            setMetadata(PHOTO.path, meta)

            assertEquals(meta, metadata(PHOTO.path))
        }
    }

    @Test
    fun `video metadata round trip`() = test {
        record(VIDEO)
        for (meta in listOf(VIDEO_META, VideoMetadata(640, 480, null, null, null, false, null, false))) {
            setVideoMetadata(VIDEO.path, meta)

            assertEquals(meta, videoMetadata(VIDEO.path))
        }
        assertEquals(emptyList<MediaRef>(), withoutMetadata())
    }

    @Test
    fun `a video shares the capture time column of an image`() = test {
        record(VIDEO)
        setVideoMetadata(VIDEO.path, VIDEO_META)

        assertEquals(VIDEO_META.capturedAt, metadata(VIDEO.path)?.capturedAt)
    }

    @Test
    fun `the videos that wait for Scan videos leave out the read, the unreadable, the trashed and the images`() = test {
        val read = MediaRef("/videos/read.mp4", 1, 1, MediaType.VIDEO)
        val broken = MediaRef("/videos/broken.mp4", 2, 1, MediaType.VIDEO)
        val trashed = MediaRef("/videos/trashed.mp4", 3, 1, MediaType.VIDEO)
        recordAll(listOf(PHOTO, VIDEO, read, broken, trashed))
        setVideoMetadata(read.path, VIDEO_META)
        setUnreadable(broken.path)
        setReviewState(trashed.path, ReviewState.TRASHED)

        assertEquals(1, videosWaiting())
    }

    @Test
    fun `a changed video drops its metadata`() = test {
        record(VIDEO)
        setVideoMetadata(VIDEO.path, VIDEO_META)
        val changed = VIDEO.copy(size = 901, mtimeNs = 2)

        record(changed)

        assertNull(videoMetadata(VIDEO.path))
        assertEquals(listOf(changed), withoutMetadata())
    }

    @Test
    fun `allMetadata holds the metadata of every image that has it`() = test {
        record(PHOTO)
        record(OTHER)
        setMetadata(PHOTO.path, META)

        assertEquals(mapOf(PHOTO.path to META), allMetadata())
    }

    @Test
    fun `content hash round trip`() = test {
        record(PHOTO)
        assertNull(contentHash(PHOTO.path))

        setContentHash(PHOTO.path, "0123abcd")

        assertEquals("0123abcd", contentHash(PHOTO.path))
    }

    @Test
    fun `perceptual hash round trip uses all 64 bits`() = test {
        record(PHOTO)
        assertNull(perceptualHash(PHOTO.path))

        setPerceptualHash(PHOTO.path, ULong.MAX_VALUE)

        assertEquals(ULong.MAX_VALUE, perceptualHash(PHOTO.path))
    }

    @Test
    fun `perceptualHashes holds the hash of every image that has one`() = test {
        record(PHOTO)
        record(OTHER)
        setPerceptualHash(PHOTO.path, ULong.MAX_VALUE)

        assertEquals(mapOf(PHOTO.path to ULong.MAX_VALUE), perceptualHashes())
    }

    @Test
    fun `embeddings are stored per model version`() = test {
        record(PHOTO)
        val vector = floatArrayOf(0.5f, -0.25f, 1f)

        setEmbeddings("clip", 2, mapOf(PHOTO.path to vector))

        val stored = embeddings("clip", 2)
        assertEquals(setOf(PHOTO.path), stored.keys)
        assertArrayEquals(vector, stored.getValue(PHOTO.path), 0f)
        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
    }

    @Test
    fun `the read videos are the readable ones with metadata out of the trash`() = test {
        val (waiting, unreadable, trashed) = listOf("waiting", "unreadable", "trashed").map { MediaRef("/videos/$it.mp4", 5, 5, MediaType.VIDEO) }
        recordAll(listOf(VIDEO, waiting, unreadable, trashed, PHOTO))
        for (video in listOf(VIDEO, trashed)) setVideoMetadata(video.path, VIDEO_META)
        setUnreadable(unreadable.path)
        setReviewState(trashed.path, ReviewState.TRASHED)

        assertEquals(listOf(VIDEO to 12_345L), readVideos(1).map { it.ref to it.durationMs })
    }

    @Test
    fun `a video whose frames did not read waits for a new frame version or a change`() = test {
        record(VIDEO)
        setVideoMetadata(VIDEO.path, VIDEO_META)

        setFramesUnreadable(VIDEO.path, 1)

        assertEquals(emptyList<IndexedVideo>(), readVideos(1))
        assertEquals(listOf(VIDEO), readVideos(2).map { it.ref })
        val changed = VIDEO.copy(size = 901, mtimeNs = 2)
        record(changed)
        setVideoMetadata(VIDEO.path, VIDEO_META)
        assertEquals(listOf(changed), readVideos(1).map { it.ref })
    }

    @Test
    fun `frame embeddings are stored per video time, model version and frame version`() = test {
        record(VIDEO)
        val first = floatArrayOf(1f, 0f)
        val last = floatArrayOf(0f, 1f)

        setFrameEmbeddings(VIDEO.path, "clip", 2, 1, mapOf(300L to first, 700L to last))

        val stored = frameEmbeddings("clip", 2, 1)
        assertEquals(setOf(VIDEO.path), stored.keys)
        assertEquals(listOf(300L, 700L), stored.getValue(VIDEO.path).keys.toList())
        assertArrayEquals(last, stored.getValue(VIDEO.path).getValue(700L), 0f)
        assertEquals(mapOf(VIDEO.path to listOf(300L, 700L)), sampledTimes("clip", 2, 1))
        assertEquals(emptyMap<String, Map<Long, FloatArray>>(), frameEmbeddings("clip", 1, 1))
        assertEquals(emptyMap<String, Map<Long, FloatArray>>(), frameEmbeddings("clip", 2, 2))
        assertEquals(emptyMap<String, List<Long>>(), sampledTimes("clip", 2, 2))
    }

    @Test
    fun `storing a video's frames again replaces them all`() = test {
        record(VIDEO)
        setFrameEmbeddings(VIDEO.path, "clip", 1, 1, mapOf(300L to floatArrayOf(1f)))

        setFrameEmbeddings(VIDEO.path, "clip", 1, 2, mapOf(500L to floatArrayOf(0f)))

        assertEquals(mapOf(VIDEO.path to listOf(500L)), sampledTimes("clip", 1, 2))
        assertEquals(emptyMap<String, List<Long>>(), sampledTimes("clip", 1, 1))
    }

    @Test
    fun `the measures of the frames are stored with them and replaced with them`() = test {
        record(VIDEO)
        val vector = floatArrayOf(1f)
        val sharp = FrameMeasures(900.0, 5.0, 20.0, 200.0, 240.0, faces = 2, faceConfidence = 0.8, faceArea = 0.1)
        val dark = FrameMeasures(0.0, 0.0, 0.0, 2.0, 3.0)

        setFrameEmbeddings(VIDEO.path, "clip", 1, 3, mapOf(700L to vector, 300L to vector), mapOf(700L to dark, 300L to sharp))

        assertEquals(mapOf(VIDEO.path to listOf(sharp, dark)), frameMeasures(3)) // in the order of the times
        assertEquals(emptyMap<String, List<FrameMeasures>>(), frameMeasures(2))
        setFrameEmbeddings(VIDEO.path, "clip", 1, 4, mapOf(500L to vector), mapOf(500L to dark))
        assertEquals(emptyMap<String, List<FrameMeasures>>(), frameMeasures(3))
        assertEquals(mapOf(VIDEO.path to listOf(dark)), frameMeasures(4))
    }

    @Test
    fun `a changed video drops the measures of its frames`() = test {
        record(VIDEO)
        setFrameEmbeddings(VIDEO.path, "clip", 1, 1, mapOf(300L to floatArrayOf(1f)), mapOf(300L to FrameMeasures(1.0, 1.0, 2.0, 3.0, 4.0)))

        record(VIDEO.copy(size = 901, mtimeNs = 2))

        assertEquals(emptyMap<String, List<FrameMeasures>>(), frameMeasures(1))
    }

    @Test
    fun `a changed video drops its frames`() = test {
        record(VIDEO)
        setFrameEmbeddings(VIDEO.path, "clip", 1, 1, mapOf(300L to floatArrayOf(1f)))

        record(VIDEO.copy(size = 901, mtimeNs = 2))

        assertEquals(emptyMap<String, Map<Long, FloatArray>>(), frameEmbeddings("clip", 1, 1))
    }

    @Test
    fun `the analysis data counts the videos with frames and deletes their frames`() = test {
        record(PHOTO)
        record(VIDEO)
        setEmbeddings("clip", 1, mapOf(PHOTO.path to floatArrayOf(1f)))
        setFrameEmbeddings(VIDEO.path, "clip", 1, 1, mapOf(300L to floatArrayOf(1f), 700L to floatArrayOf(0f)))

        assertEquals(2, embeddingCount()) // the photo and the video, not each frame
        deleteEmbeddings()

        assertEquals(0, embeddingCount())
        assertEquals(emptyMap<String, Map<Long, FloatArray>>(), frameEmbeddings("clip", 1, 1))
        assertEquals(listOf(PHOTO, VIDEO), images())
    }

    @Test
    fun `deleting the embeddings leaves the rest of the index as it was`() = test {
        record(PHOTO)
        record(OTHER)
        setMetadata(PHOTO.path, META)
        setEmbeddings("clip", 1, mapOf(PHOTO.path to floatArrayOf(1f), OTHER.path to floatArrayOf(2f)))
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)
        excludeFolder("/photos/memes")

        deleteEmbeddings()

        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
        assertEquals(listOf(PHOTO, OTHER), images())
        assertEquals(META, metadata(PHOTO.path))
        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertTrue(isAnalyzed(PHOTO.path, "blur", 1))
        assertEquals(ReviewState.KEPT, reviewState(PHOTO.path))
        assertEquals(listOf("/photos/memes"), excludedFolders())
    }

    @Test
    fun `the embeddings estimate is one page per embedding`() = test {
        record(PHOTO)
        record(OTHER)
        assertEquals(0L, embeddingBytes())

        setEmbeddings("clip", 1, mapOf(PHOTO.path to FloatArray(512), OTHER.path to FloatArray(512)))
        assertEquals(2 * 4096L, embeddingBytes())

        deleteEmbeddings()
        assertEquals(0L, embeddingBytes())
    }

    @Test
    fun `the file size grows with the data and is zero without a file`() = test {
        assertEquals(0L, fileBytes()) // in memory
        val onDisk = ImageIndex.open(ApplicationProvider.getApplicationContext(), "size-test.sqlite")
        try {
            val empty = onDisk.fileBytes()
            repeat(300) { onDisk.record(MediaRef("/photos/$it.jpg", 1, 1)) }
            assertTrue(onDisk.fileBytes() > empty)
        } finally {
            onDisk.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase("size-test.sqlite")
        }
    }

    /** The index line (file minus the estimate) is what the file is left with after the delete. */
    @Test
    fun `the embeddings estimate is close to what deleting them frees`() = test {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vectors = (0 until 300).associate { "/photos/$it.jpg" to FloatArray(512) { i -> i.toFloat() } }

        // Closing checkpoints the write-ahead log, as the system does in time, so the files hold what the data takes.
        fun <T> onDisk(block: suspend ImageIndex.() -> T): T {
            val opened = ImageIndex.open(context, "estimate-test.sqlite")
            return try {
                runBlocking { opened.block() }
            } finally {
                opened.close()
            }
        }
        try {
            onDisk {
                vectors.keys.forEach { record(MediaRef(it, 1, 1)) }
                setEmbeddings("clip", 1, vectors)
            }
            val shown = onDisk { fileBytes() - embeddingBytes() }
            onDisk { deleteEmbeddings() }
            val left = onDisk { fileBytes() }

            assertTrue("shown $shown, left $left", kotlin.math.abs(shown - left) <= left / 5)
        } finally {
            context.deleteDatabase("estimate-test.sqlite")
        }
    }

    @Test
    fun `deleting the embeddings gives their pages back to the file`() = test {
        repeat(100) {
            record(MediaRef("/photos/$it.jpg", 1, 1))
            setEmbeddings("clip", 1, mapOf("/photos/$it.jpg" to FloatArray(512) { value -> value.toFloat() }))
        }
        assertEquals(100, embeddingCount())

        deleteEmbeddings()

        assertEquals(0, embeddingCount())
        val db = database.openHelper.readableDatabase
        db.query("PRAGMA freelist_count").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0)) // a plain DELETE leaves the pages in the file
        }
    }

    @Test
    fun `forgetting analyses drops the signals of the chosen detectors only`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setSignals(PHOTO.path, "document", 1, listOf(Signal("document", 1, Category.DOCUMENT, 0.9f)))

        forgetAnalyses(listOf(PHOTO.path), listOf("document"))

        assertFalse(isAnalyzed(PHOTO.path, "document", 1))
        assertEquals(listOf(BLUR), signals(PHOTO.path))
    }

    @Test
    fun `the analyzed paths of several detectors are those of each one at its version`() = test {
        record(PHOTO)
        record(OTHER)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setSignals(OTHER.path, "blur", 2, emptyList())
        setSignals(OTHER.path, "document", 1, emptyList())

        val versions = mapOf("blur" to 1, "document" to 1, "faces" to 1)

        assertEquals(mapOf("blur" to setOf(PHOTO.path), "document" to setOf(OTHER.path), "faces" to emptySet<String>()), analyzedPaths(versions))
        assertEquals(versions.mapValues { (id, version) -> analyzedPaths(id, version) }, analyzedPaths(versions))
    }

    @Test
    fun `signals are stored per detector version`() = test {
        record(PHOTO)
        val screenshot = Signal("screenshot", 3, Category.SCREENSHOT, 0.9f)
        setSignals(PHOTO.path, "screenshot", 3, listOf(screenshot))
        setSignals(PHOTO.path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.2f)))

        setSignals(PHOTO.path, "blur", 2, listOf(BLUR))

        assertEquals(listOf(BLUR, screenshot), signals(PHOTO.path).sortedBy { it.detectorId })
        assertTrue(isAnalyzed(PHOTO.path, "blur", 2))
        assertFalse(isAnalyzed(PHOTO.path, "blur", 1))
    }

    @Test
    fun `detector with no signals still counts as analyzed`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, emptyList())

        assertTrue(isAnalyzed(PHOTO.path, "blur", 1))
    }

    @Test
    fun `review state persists`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking {
            ImageIndex.open(context, "persist.sqlite").use {
                it.record(PHOTO)
                assertEquals(ReviewState.UNREVIEWED, it.reviewState(PHOTO.path))
                it.setReviewState(PHOTO.path, ReviewState.KEPT)
            }
            ImageIndex.open(context, "persist.sqlite").use {
                assertEquals(ReviewState.KEPT, it.reviewState(PHOTO.path))
            }
        }
    }

    @Test
    fun `unrecorded image throws`() {
        val calls: List<suspend ImageIndex.() -> Unit> = listOf(
            { setMetadata(PHOTO.path, META) },
            { setVideoMetadata(PHOTO.path, VIDEO_META) },
            { setContentHash(PHOTO.path, "0123abcd") },
            { setUnreadable(PHOTO.path) },
            { setPerceptualHash(PHOTO.path, 42u) },
            { perceptualHash(PHOTO.path) },
            { contentHash(PHOTO.path) },
            { setSignals(PHOTO.path, "blur", 1, listOf(BLUR)) },
            { setReviewState(PHOTO.path, ReviewState.KEPT) },
            { reviewState(PHOTO.path) },
        )
        for (call in calls) {
            assertThrows(NoSuchElementException::class.java) { test(call) }
        }
    }

    @Test
    fun `keeping records the current signals`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        setReviewState(PHOTO.path, ReviewState.KEPT)
        setSignals(PHOTO.path, "blur", 2, listOf(Signal("blur", 2, Category.BLURRY, 0.9f)))

        val image = indexedMedia().single()
        assertEquals(ReviewState.KEPT, image.reviewState)
        assertEquals(listOf(Signal("blur", 2, Category.BLURRY, 0.9f)), image.signals)
        assertEquals(setOf(BLUR.identity), image.seenWhenKept)
        assertEquals(setOf("blur"), keptDetectors(PHOTO.path))
    }

    /** The signal ids of each image, in their order. */
    private fun signalIds(): Map<String, List<Long>> = index.database.openHelper.readableDatabase.query("SELECT path, id FROM signals ORDER BY id").use { cursor ->
        List(cursor.count) { cursor.moveToNext().let { cursor.getString(0) to cursor.getLong(1) } }.groupBy({ it.first }, { it.second })
    }

    private fun group(id: String, role: Role) = Signal(
        "near_duplicate",
        1,
        Category.NEAR_DUPLICATE,
        if (role == Role.BEST) 0f else 0.8f,
        mapOf(GROUP_KEY to id, ROLE_KEY to role.value),
    )

    @Test
    fun `a detector's first result after the keep counts as seen, a later group does not`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        setSignals(PHOTO.path, "near_duplicate", 1, listOf(group("g", Role.BEST)))
        assertEquals(setOf(BLUR.identity, group("g", Role.BEST).identity), indexedMedia().single().seenWhenKept)
        assertEquals(setOf("blur", "near_duplicate"), keptDetectors(PHOTO.path))

        setSignals(PHOTO.path, "near_duplicate", 1, listOf(group("g", Role.INFERIOR)))
        assertEquals(setOf(BLUR.identity, group("g", Role.BEST).identity), indexedMedia().single().seenWhenKept)
    }

    @Test
    fun `a first result for an image that is not kept is not recorded`() = test {
        record(PHOTO)

        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        assertEquals(emptySet<Any>(), indexedMedia().single().seenWhenKept)
        assertEquals(emptySet<Any>(), keptDetectors(PHOTO.path))
    }

    @Test
    fun `leaving kept forgets the recorded signals`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        setReviewState(PHOTO.path, ReviewState.UNREVIEWED)

        assertEquals(emptySet<Any>(), indexedMedia().single().seenWhenKept)
        assertEquals(emptySet<Any>(), keptDetectors(PHOTO.path))
    }

    @Test
    fun `indexed images lists unanalyzed images too`() = test {
        record(PHOTO)
        record(OTHER)
        setSignals(OTHER.path, "blur", 1, listOf(BLUR))

        assertEquals(
            listOf(PHOTO to emptyList(), OTHER to listOf(BLUR)),
            indexedMedia().map { it.ref to it.signals },
        )
    }

    @Test
    fun `images without metadata leave out unreadable ones`() = test {
        val broken = MediaRef("/photos/c.jpg", 5, 5)
        for (image in listOf(PHOTO, OTHER, broken)) record(image)
        setMetadata(PHOTO.path, META)
        setUnreadable(broken.path)

        assertEquals(listOf(OTHER), withoutMetadata())
        assertEquals(listOf(broken.path), unreadable())
    }

    @Test
    fun `forget removes an image and its data`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        forget(listOf(PHOTO.path))

        assertEquals(emptyList<MediaRef>(), images())
        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
    }

    @Test
    fun `forget takes more paths than one statement can bind`() = test {
        val many = (0 until 1200).map { MediaRef("/photos/$it.jpg", 1, 1) }
        many.forEach { record(it) }

        forget(many.map { it.path })

        assertEquals(emptyList<MediaRef>(), images())
    }

    @Test
    fun `signals of many images at once`() = test {
        record(PHOTO)
        record(OTHER)

        setSignalsOfMany("blur", 1, mapOf(PHOTO.path to listOf(BLUR), OTHER.path to emptyList()))

        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertTrue(isAnalyzed(OTHER.path, "blur", 1))
    }

    @Test
    fun `signals of many store nothing if one image is missing`() = test {
        record(PHOTO)

        assertThrows(NoSuchElementException::class.java) {
            runBlocking { setSignalsOfMany("blur", 1, mapOf(PHOTO.path to listOf(BLUR), "/nope.jpg" to emptyList())) }
        }

        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
    }

    @Test
    fun `group signals keep the rows that would be written again, ids included, and replace the others`() = test {
        record(PHOTO)
        record(OTHER)
        val best = group("g", Role.BEST)
        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best), OTHER.path to listOf(group("g", Role.INFERIOR))))
        val ids = signalIds()

        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best), OTHER.path to emptyList()))

        assertEquals(ids.getValue(PHOTO.path), signalIds().getValue(PHOTO.path))
        assertEquals(listOf(best), signals(PHOTO.path))
        assertEquals(emptyList<Signal>(), signals(OTHER.path))
        assertTrue(isAnalyzed(OTHER.path, "near_duplicate", 1))
    }

    @Test
    fun `group signals are written again when another detector wrote after them, so the image keeps its order`() = test {
        record(PHOTO)
        val best = group("g", Role.BEST)
        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best)))
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best)))

        assertEquals(listOf(BLUR, best), signals(PHOTO.path))
    }

    @Test
    fun `group signals record the first result of a kept image that lacks the detector, whether or not its rows change`() = test {
        record(PHOTO)
        val best = group("g", Role.BEST)
        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best)))
        // An undo puts back a keep from before the detector ran.
        restoreReviewState(PHOTO.path, ReviewState.KEPT, emptySet(), emptySet())

        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(best)))

        assertEquals(setOf(best.identity), keptSignals(PHOTO.path))
        assertEquals(setOf("near_duplicate"), keptDetectors(PHOTO.path))
    }

    @Test
    fun `group signals store nothing if one image is missing`() = test {
        record(PHOTO)
        setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to emptyList()))

        assertThrows(NoSuchElementException::class.java) {
            runBlocking { setGroupSignals("near_duplicate", 1, mapOf(PHOTO.path to listOf(group("g", Role.BEST)), "/nope.jpg" to emptyList())) }
        }

        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
    }

    /**
     * The statements a group detector runs on a library whose groups did not change, against the per-image replace that
     * came before: four reads in all, where that ran four statements an image and one per signal.
     */
    @Test
    fun `group signals of an unchanged library run four statements`() {
        var statements = 0
        val counted = ImageIndex(
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDatabase::class.java)
                .setQueryCallback({ sql, _ -> if ("TRANSACTION" !in sql && "room_" !in sql) statements++ }, Runnable::run)
                .build(),
        )
        counted.use { index ->
            val images = (0 until 1000).map { MediaRef("/photos/$it.jpg", 1, 1) }
            // One image in ten is in a group of two.
            val signals = images.associate { image ->
                val n = image.path.filter(Char::isDigit).toInt()
                image.path to if (n % 10 < 2) listOf(group("g${n / 10}", if (n % 10 == 0) Role.BEST else Role.INFERIOR)) else emptyList()
            }
            runBlocking {
                index.recordAll(images)
                index.setGroupSignals("near_duplicate", 1, signals)

                statements = 0
                index.setSignalsOfMany("near_duplicate", 1, signals)
                assertEquals(4 * 1000 + 200, statements)

                statements = 0
                index.setGroupSignals("near_duplicate", 1, signals)
                assertEquals(4, statements)
            }
        }
    }

    @Test
    fun `group fingerprint is per detector version`() = test {
        assertNull(groupFingerprint("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "abc")

        assertEquals("abc", groupFingerprint("exact_duplicate", 1))
        assertNull(groupFingerprint("exact_duplicate", 2))
    }

    @Test
    fun `a group fingerprint keeps the paths whose content hash failed, until the next one replaces it`() = test {
        assertEquals(emptyList<String>(), groupHashFailed("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "abc", listOf("/photos/a [1].jpg", "/photos/b\"\n.jpg"))

        assertEquals("abc", groupFingerprint("exact_duplicate", 1))
        assertEquals(listOf("/photos/a [1].jpg", "/photos/b\"\n.jpg"), groupHashFailed("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "def")

        assertEquals("def", groupFingerprint("exact_duplicate", 1))
        assertEquals(emptyList<String>(), groupHashFailed("exact_duplicate", 1))
    }

    @Test
    fun `protection signals round trip`() = test {
        record(PHOTO)
        val faces = Signal("faces", 1, Protection.FACES, 0.8f, mapOf("faces" to "3"))

        setSignals(PHOTO.path, "faces", 1, listOf(faces))

        assertEquals(listOf(faces), signals(PHOTO.path))
        assertEquals(listOf(faces), indexedMedia().single().signals)
    }

    @Test
    fun `excluded folders are listed, added once and removed`() = test {
        excludeFolder("/photos/b")
        excludeFolder("/photos/a")
        excludeFolder("/photos/a")

        assertEquals(listOf("/photos/a", "/photos/b"), excludedFolders())

        removeExcludedFolder("/photos/a")

        assertEquals(listOf("/photos/b"), excludedFolders())
    }

    @Test
    fun `excluding a folder leaves its images and their signals alone`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        excludeFolder("/photos")
        removeExcludedFolder("/photos")

        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertEquals(ReviewState.UNREVIEWED, reviewState(PHOTO.path))
    }

    @Test
    fun `a trailing separator does not change the excluded folder`() = test {
        excludeFolder("/photos/a/")
        excludeFolder("/photos/a")

        assertEquals(listOf("/photos/a"), excludedFolders())
    }

    @Test
    fun `not images are stored by path, the newest size and mtime winning`() = test {
        addNotImages(listOf(PHOTO, OTHER))
        addNotImages(listOf(PHOTO.copy(size = 7)))

        assertEquals(setOf(PHOTO.copy(size = 7), OTHER), notImages().toSet())

        forgetNotImages(listOf(OTHER.path))

        assertEquals(listOf(PHOTO.copy(size = 7)), notImages())
    }

    @Test
    fun `not images are no images`() = test {
        addNotImages(listOf(PHOTO))

        assertEquals(emptyList<MediaRef>(), images())
        assertEquals(0, imageCount())
    }

    @Test
    fun `forgetting an image leaves the not images alone`() = test {
        record(PHOTO)
        addNotImages(listOf(OTHER))

        forget(listOf(PHOTO.path))

        assertEquals(listOf(OTHER), notImages())
    }

    @Test
    fun `the not images do not touch the version, since the review shows none of them`() = test {
        val before = version

        addNotImages(listOf(PHOTO))
        notImages()
        forgetNotImages(listOf(PHOTO.path))

        assertEquals(before, version)
    }

    @Test
    fun `the JPEG rows do not touch the version, since the review shows none of them`() = test {
        record(PHOTO)
        setMetadata(PHOTO.path, META)
        val before = version

        jpegsToRead()
        setJpeg(PHOTO.path, JpegInfo(JpegStatus.ELIGIBLE))
        setJpegBeforeScan(PHOTO.path, JpegInfo(JpegStatus.ELIGIBLE), 4000, 3000)
        compressible(34)
        jpegCounts()

        assertEquals(before, version)
    }

    @Test
    fun `forgetting an image forgets its JPEG row`() = test {
        record(PHOTO)
        setMetadata(PHOTO.path, META)
        setJpeg(PHOTO.path, JpegInfo(JpegStatus.ELIGIBLE))

        forget(listOf(PHOTO.path))
        record(PHOTO)
        setMetadata(PHOTO.path, META)

        assertEquals(listOf(PHOTO), jpegsToRead())
    }

    /** Every public write of the index, each run once on an index holding [PHOTO] with [BLUR]. */
    private val writes: Map<String, suspend ImageIndex.() -> Unit> = mapOf(
        "record" to { record(OTHER) },
        "recordAll" to { recordAll(listOf(OTHER)) },
        "forget" to { forget(listOf(PHOTO.path)) },
        "forgetTrashed" to { forgetTrashed(listOf(PHOTO.path)) },
        "setUnreadable" to { setUnreadable(PHOTO.path) },
        "setMetadata" to { setMetadata(PHOTO.path, META) },
        "setVideoMetadata" to { setVideoMetadata(PHOTO.path, VIDEO_META) },
        "setContentHash" to { setContentHash(PHOTO.path, "abc") },
        "setPerceptualHash" to { setPerceptualHash(PHOTO.path, 1uL) },
        "deleteEmbeddings" to { deleteEmbeddings() },
        "forgetAnalyses" to { forgetAnalyses(listOf(PHOTO.path), listOf("blur")) },
        "setEmbeddings" to { setEmbeddings("clip", 1, mapOf(PHOTO.path to floatArrayOf(1f))) },
        "setFrameEmbeddings" to { setFrameEmbeddings(PHOTO.path, "clip", 1, 1, mapOf(300L to floatArrayOf(1f))) },
        "setFramesUnreadable" to { setFramesUnreadable(PHOTO.path, 1) },
        "setSignals" to { setSignals(PHOTO.path, "blur", 1, emptyList()) },
        "setSignalsOfMany" to { setSignalsOfMany("blur", 1, mapOf(PHOTO.path to emptyList())) },
        "setGroupSignals" to { setGroupSignals("blur", 1, mapOf(PHOTO.path to emptyList())) },
        "setGroupFingerprint" to { setGroupFingerprint("exact_duplicate", 1, "abc") },
        "setReviewState" to { setReviewState(PHOTO.path, ReviewState.KEPT) },
        "restoreReviewState" to { restoreReviewState(PHOTO.path, ReviewState.KEPT, emptySet(), emptySet()) },
        "excludeFolder" to { excludeFolder("/photos") },
        "removeExcludedFolder" to { removeExcludedFolder("/photos") },
    )

    /** What a write needs on that index to have something to do, before the version is read. */
    private val setups: Map<String, suspend ImageIndex.() -> Unit> = mapOf(
        "forgetTrashed" to { setReviewState(PHOTO.path, ReviewState.TRASHED) },
    )

    /**
     * Every public read, each run once on the same index. A method that writes anything must never go here: the review
     * would keep showing what it ranked before the write. It goes in [writes].
     */
    private val reads: Map<String, suspend ImageIndex.() -> Unit> = mapOf(
        "images" to { images() },
        "withoutMetadata" to { withoutMetadata() },
        "unreadable" to { unreadable() },
        "metadata" to { metadata(PHOTO.path) },
        "videoMetadata" to { videoMetadata(PHOTO.path) },
        "allMetadata" to { allMetadata() },
        "contentHash" to { contentHash(PHOTO.path) },
        "perceptualHash" to { perceptualHash(PHOTO.path) },
        "perceptualHashes" to { perceptualHashes() },
        "hashedPaths" to { hashedPaths() },
        "reviewStates" to { reviewStates() },
        "embeddingCount" to { embeddingCount() },
        "imageCount" to { imageCount() },
        "embeddingBytes" to { embeddingBytes() },
        "fileBytes" to { fileBytes() },
        "embeddings" to { embeddings("clip", 1) },
        "frameEmbeddings" to { frameEmbeddings("clip", 1, 1) },
        "frameMeasures" to { frameMeasures(1) },
        "sampledTimes" to { sampledTimes("clip", 1, 1) },
        "readVideos" to { readVideos(1) },
        "analyzedPaths" to { analyzedPaths("blur", 1) },
        "embeddedPaths" to { embeddedPaths("clip", 1) },
        "readableNotTrashed" to { readableNotTrashed() },
        "groupFingerprint" to { groupFingerprint("exact_duplicate", 1) },
        "groupHashFailed" to { groupHashFailed("exact_duplicate", 1) },
        "isAnalyzed" to { isAnalyzed(PHOTO.path, "blur", 1) },
        "signals" to { signals(PHOTO.path) },
        "keptSignals" to { keptSignals(PHOTO.path) },
        "keptDetectors" to { keptDetectors(PHOTO.path) },
        "reviewState" to { reviewState(PHOTO.path) },
        "excludedFolders" to { excludedFolders() },
        "notTrashed" to { notTrashed() },
        "notTrashedMedia" to { notTrashedMedia() },
        "unreviewedVideos" to { unreviewedVideos() },
        "hasVideos" to { hasVideos() },
        "videosWaiting" to { videosWaiting() },
        "capturedAt" to { capturedAt() },
        "cameraCaptures" to { cameraCaptures() },
        "embeddingsOf" to { embeddingsOf("mobileclip2_s0", 1, listOf(PHOTO.path)) },
        "shortClipInputs" to { shortClipInputs("mobileclip2_s0", 1, 1) },
        "allVideoMetadata" to { allVideoMetadata() },
        "indexedMedia" to { indexedMedia() },
        "getVersion" to { version },
    )

    @Test
    fun `every public method is known as a read or a write`() {
        val methods = ImageIndex::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .map { it.name.substringBefore('-') } // Kotlin mangles names that take inline classes like ULong
            .toSet()
        // A new write must bump the version, or the review shows a stale ranking: add it to [writes].
        // [ImageIndex.batch] writes only through the methods its block calls, and its tests are below.
        assertEquals(writes.keys + reads.keys + "close" + "batch" + NOT_IMAGES_METHODS + JPEG_METHODS, methods)
    }

    @Test
    fun `every write bumps the version after its last statement`() {
        for ((name, write) in writes) {
            lateinit var index: ImageIndex
            val seen = mutableListOf<Pair<String, Long>>()
            index = ImageIndex(
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDatabase::class.java)
                    // Room reports its transaction markers late and checks its own tables after the commit: only the index's statements count.
                    .setQueryCallback({ sql, _ -> if ("TRANSACTION" !in sql && "room_" !in sql) seen += sql to index.version }, Runnable::run)
                    .build(),
            )
            index.use {
                runBlocking {
                    index.record(PHOTO)
                    index.setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
                    setups[name]?.invoke(index)
                    val before = index.version
                    seen.clear()
                    index.write()
                    assertTrue(name, seen.isNotEmpty())
                    // The statements run at the old version: a reader that sees the new one finds the write.
                    assertEquals(name, emptyList<Pair<String, Long>>(), seen.filter { it.second != before })
                    assertTrue(name, index.version > before)
                }
            }
        }
    }

    @Test
    fun `a batch gets the paths still recorded, and bumps the version again after its commit`() = test {
        record(PHOTO)
        val before = version
        var inside = 0L

        batch(listOf(PHOTO.path, OTHER.path, PHOTO.path)) { recorded ->
            assertEquals(setOf(PHOTO.path), recorded)
            setMetadata(PHOTO.path, META)
            setPerceptualHash(PHOTO.path, 1uL)
            inside = version
        }

        // A read that took the version inside the batch, before the commit, finds it changed.
        assertTrue(inside > before)
        assertTrue(version > inside)
        assertEquals(META, metadata(PHOTO.path))
    }

    @Test
    fun `a batch that throws stores none of its writes`() = test {
        record(PHOTO)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                batch(listOf(PHOTO.path)) {
                    setMetadata(PHOTO.path, META)
                    error("stop")
                }
            }
        }

        assertEquals(listOf(PHOTO), withoutMetadata())
    }

    @Test
    fun `a batch of JPEG headers keeps the version, as setJpeg does`() = test {
        record(PHOTO)
        val before = version

        batch(listOf(PHOTO.path)) { setJpeg(PHOTO.path, JpegInfo(JpegStatus.ELIGIBLE)) }

        assertEquals(before, version)
    }

    @Test
    fun `a delete that removes no row keeps the version`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        val before = version

        forget(listOf(OTHER.path))
        forget(emptyList())
        forgetTrashed(listOf(PHOTO.path)) // not trashed
        forgetAnalyses(listOf(PHOTO.path), listOf("document"))
        forgetAnalyses(listOf(OTHER.path), listOf("blur"))

        assertEquals(before, version)
        assertEquals(listOf(BLUR), signals(PHOTO.path))
    }

    @Test
    fun `an unchanged record keeps the version`() = test {
        record(PHOTO)
        val before = version

        assertFalse(record(PHOTO))

        assertEquals(before, version)
    }

    @Test
    fun `reads leave the version alone`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        val before = version
        for ((name, read) in reads) {
            read()
            assertEquals(name, before, version)
        }
    }
}
