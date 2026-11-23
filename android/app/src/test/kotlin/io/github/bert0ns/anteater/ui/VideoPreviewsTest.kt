package io.github.bert0ns.anteater.ui

import coil3.disk.DiskCache
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections

/** The previews of the videos above 1080p (#482), made by a fake transcode: Robolectric decodes and encodes no video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoPreviewsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val disk by lazy { DiskCache.Builder().directory(folder.newFolder("previews").toOkioPath()).maxSizeBytes(PREVIEWS_MAX_BYTES).build() }
    private val queue = FrameQueue(FRAME_DECODES)

    /** What each transcode was asked: the input's name and the end of the loop. */
    private val made: MutableList<Pair<String, Long?>> = Collections.synchronizedList(mutableListOf())

    /** The size a header gives, by file name: what [headerPixels] reads on the phone. */
    private val headers = mutableMapOf<String, Long>()

    /** Runs inside each transcode, after it is counted: a failure, a wait or a change to the file. */
    private var during: suspend (File) -> Unit = {}

    private val previews by lazy {
        VideoPreviews(disk, queue, header = { headers[it.name] }) { input, endMs, output ->
            made += input.name to endMs
            during(input)
            output.writeText("preview of ${input.name} to ${endMs ?: "whole"}")
        }
    }

    private fun video(name: String, bytes: Int = 1_000) = File(folder.root, name).apply { writeBytes(ByteArray(bytes)) }

    private fun mover(file: File, pixels: Long? = UHD, durationMs: Long? = 60_000, endMs: Long? = LONG_LOOP_MS) = Mover(MediaRef(file.path, file.length(), 0, MediaType.VIDEO), pixels, durationMs, endMs)

    private fun find(mover: Mover) = runBlocking { previews.find(mover) }
    private fun make(mover: Mover) = runBlocking { previews.make(mover) }

    @Test
    fun `a video up to 1080p loops its own file and makes no preview`() {
        val clip = video("clip.mp4")
        val fullHd = mover(clip, pixels = 1920L * 1080)

        assertEquals(clip, find(fullHd))
        assertEquals(clip, make(fullHd))
        assertEquals(clip, find(mover(clip, pixels = 1080L * 1920))) // portrait
        assertTrue(made.isEmpty())
    }

    @Test
    fun `a video larger than 1080p has nothing to loop until its preview is made, and then loops the preview`() {
        val clip = video("clip.mp4")
        for (pixels in listOf(UHD, 2560L * 1440, 2704L * 1520)) {
            assertNull(find(mover(clip, pixels = pixels)))
        }

        val preview = make(mover(clip))!!

        assertNotEquals(clip, preview)
        assertEquals("preview of clip.mp4 to 10000", preview.readText())
        assertEquals(preview, find(mover(clip)))
        assertEquals(listOf("clip.mp4" to LONG_LOOP_MS), made)
    }

    @Test
    fun `a preview is made once, and an evicted one is made again`() {
        val clip = video("clip.mp4")
        val first = make(mover(clip))
        assertEquals(first, make(mover(clip)))
        assertEquals(1, made.size)

        disk.remove(previewKey(clip, LONG_LOOP_MS)!!) // what the cap does to the least recently used
        assertNull(find(mover(clip)))
        make(mover(clip))

        assertEquals(2, made.size)
        assertTrue(find(mover(clip)) != null)
    }

    @Test
    fun `a tile that loops the whole clip has a preview of its own, which a clip no longer than the loop shares`() {
        val long = video("long.mp4")
        val short = video("short.mp4")

        make(mover(long, durationMs = 60_000, endMs = LONG_LOOP_MS))
        make(mover(long, durationMs = 60_000, endMs = null))
        make(mover(short, durationMs = 8_000, endMs = null))
        make(mover(short, durationMs = 8_000, endMs = LONG_LOOP_MS))

        assertEquals(listOf("long.mp4" to LONG_LOOP_MS, "long.mp4" to null, "short.mp4" to null), made)
    }

    @Test
    fun `the key changes with the file's length, its time and the loop's end`() {
        val clip = video("clip.mp4")
        val key = previewKey(clip, LONG_LOOP_MS)
        assertEquals(key, previewKey(clip, LONG_LOOP_MS))
        assertNotEquals(key, previewKey(clip, null))

        clip.setLastModified(clip.lastModified() - 60_000)
        val touched = previewKey(clip, LONG_LOOP_MS)
        assertNotEquals(key, touched)

        clip.appendBytes(ByteArray(10))
        assertNotEquals(touched, previewKey(clip, LONG_LOOP_MS))
        assertNull(previewKey(File(folder.root, "gone.mp4"), LONG_LOOP_MS))
    }

    @Test
    fun `an edited video misses its old preview and gets a new one`() {
        val clip = video("clip.mp4")
        make(mover(clip))

        clip.appendBytes(ByteArray(10))

        assertNull(find(mover(clip)))
        make(mover(clip))
        assertEquals(2, made.size)
    }

    @Test
    fun `a video that changes while its preview is made stores nothing`() {
        val clip = video("clip.mp4")
        val key = previewKey(clip, LONG_LOOP_MS)!!
        during = { it.appendBytes(ByteArray(10)) }

        assertNull(make(mover(clip)))

        assertNull(disk.openSnapshot(key)) // the old file's key would hold another file's preview
        assertNull(find(mover(clip)))
    }

    @Test
    fun `a transcode that fails stores nothing and leaves the tile nothing to loop, and the next view tries again`() {
        val clip = video("clip.mp4")
        during = { throw IOException("no H.264 encoder free") }

        assertNull(make(mover(clip)))
        assertNull(find(mover(clip)))

        during = {}
        assertTrue(make(mover(clip)) != null)
        assertEquals(2, made.size)
    }

    @Test
    fun `a video the index has not read is sized from its header`() {
        val small = video("small.mp4")
        val large = video("large.mp4")
        val broken = video("broken.mp4")
        headers["small.mp4"] = 1280L * 720
        headers["large.mp4"] = UHD

        assertNull(find(mover(small, pixels = null))) // the header is read only in make, which waits for the grid to settle
        assertEquals(small, make(mover(small, pixels = null)))
        assertTrue(make(mover(large, pixels = null)) != null)
        assertNull(make(mover(broken, pixels = null)))

        assertEquals(listOf("large.mp4" to LONG_LOOP_MS), made)
    }

    @Test
    fun `a transcode takes one of the frame decode permits, and previews are made one at a time`() = runBlocking {
        val first = video("first.mp4")
        val second = video("second.mp4")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        during = {
            if (it == first) {
                started.complete(Unit)
                finish.await()
            }
        }

        val one = async { previews.make(mover(first)) }
        started.await()
        assertEquals(FRAME_DECODES - 1, queue.free)
        val two = async { previews.make(mover(second)) }
        repeat(10) { yield() }
        assertEquals(listOf("first.mp4" to LONG_LOOP_MS), made.toList()) // the second waits for the first

        finish.complete(Unit)
        withTimeout(5_000) {
            one.await()
            two.await()
        }
        assertEquals(listOf("first.mp4", "second.mp4"), made.map { it.first })
        assertEquals(FRAME_DECODES, queue.free)
    }

    @Test
    fun `a cancelled transcode stores nothing and gives its permit back`() = runBlocking {
        val clip = video("clip.mp4")
        val started = CompletableDeferred<Unit>()
        during = {
            started.complete(Unit)
            awaitCancellation()
        }

        val making = async { previews.make(mover(clip)) }
        started.await()
        making.cancel()
        making.join()

        assertEquals(FRAME_DECODES, queue.free)
        during = {}
        assertNull(previews.find(mover(clip)))
    }
}
