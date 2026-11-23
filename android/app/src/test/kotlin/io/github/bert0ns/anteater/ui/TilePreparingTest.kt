package io.github.bert0ns.anteater.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import coil3.size.Size
import io.github.bert0ns.anteater.AnteaterApp
import io.github.bert0ns.anteater.compress.BatchState
import io.github.bert0ns.anteater.compress.CompressBatch
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.GROUP_KEY
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ROLE_KEY
import io.github.bert0ns.anteater.model.Role
import io.github.bert0ns.anteater.model.Signal
import io.github.bert0ns.anteater.pipeline.IndexWorker
import io.github.bert0ns.anteater.review.ReviewOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TilePreparingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photos = List(120) { File("/photos/$it.jpg") }
    private val prepared = mutableListOf<File>()
    private var size: Size? = Size(300, 300)

    /** The moving video tiles of the first page of each video grid, and those whose preview the job made. */
    private var movers = emptyList<Mover>()
    private val previewed = mutableListOf<Mover>()

    /** The size a video the index has not sized turns out to be, by path: a 4K video when not given. */
    private val headers = mutableMapOf<String, Long>()

    /** How many times the job ranked the video grids, and whether it ranks the real index. */
    private var ranked = 0
    private var realMovers = false

    /** True from the photo or preview at this position on. */
    private var hotFrom = Int.MAX_VALUE

    /** Runs before the photo or preview at its position is made. */
    private val before = mutableMapOf<Int, () -> Unit>()
    private val done get() = prepared.size + previewed.size

    private fun video(n: Int, pixels: Long?) = Mover(MediaRef("/videos/$n.mp4", 1_000_000, 0, MediaType.VIDEO), pixels, 60_000, LONG_LOOP_MS)

    private inner class Fake(context: Context, parameters: WorkerParameters) : TilePrepareWorker(context, parameters) {
        override fun tileSize() = size

        override suspend fun photos() = this@TilePreparingTest.photos

        override suspend fun prepare(file: File, size: Size, thread: CoroutineContext) {
            assertEquals(this@TilePreparingTest.size, size)
            prepared += file
            before[done]?.invoke()
        }

        override suspend fun movers(): List<Mover> {
            ranked++
            return if (realMovers) super.movers() else this@TilePreparingTest.movers
        }

        override suspend fun preview(mover: Mover): Boolean {
            previewed += mover
            before[done]?.invoke()
            return (mover.pixels ?: headers[mover.video.path] ?: UHD) > FILE_LOOP_PIXELS
        }

        override fun hot() = done >= hotFrom
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TilePrepareWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = Fake(appContext, workerParameters)
        }).build().doWork()
    }

    @After
    fun tearDown() {
        if (IndexWorker.running.isLocked) IndexWorker.running.unlock()
        CompressBatch.reset()
    }

    @Test
    fun `it prepares the first two Compress pages, in the list's order`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(2 * COMPRESS_PAGE_SIZE), prepared)
    }

    @Test
    fun `before the Compress page has shown a tile it has no size, and prepares no photo`() {
        size = null
        movers = listOf(video(1, UHD))
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(emptyList<File>(), prepared)
        assertEquals(movers, previewed)
    }

    @Test
    fun `after the photos it makes the previews of the moving videos larger than 1080p, in the grids' order`() {
        movers = listOf(video(1, UHD), video(2, FULL_HD), video(3, null), video(4, 2560L * 1440), video(5, 1280L * 720))

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(photos.take(2 * COMPRESS_PAGE_SIZE), prepared)
        // A video the index has not sized may be larger: making its preview reads its header first.
        assertEquals(listOf(1, 3, 4).map { "/videos/$it.mp4" }, previewed.map { it.video.path })
    }

    @Test
    fun `it makes at most 48 previews, which fit in their cache`() {
        movers = (1..100).map { video(it, UHD) }

        run()

        assertEquals(movers.take(48), previewed)
    }

    @Test
    fun `a video the index has not sized that turns out to be 1080p or less takes none of the 48 previews`() {
        size = null
        movers = (1..80).map { video(it, null) }
        for (n in 1..20) headers["/videos/$n.mp4"] = FULL_HD

        run()

        assertEquals(68, previewed.size)
        assertEquals("/videos/68.mp4", previewed.last().video.path)
    }

    @Test
    fun `when it has to stop it does so before it ranks the video grids`() {
        size = null
        movers = listOf(video(1, UHD))
        hotFrom = 0

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(0, ranked)
        assertTrue(previewed.isEmpty())
    }

    @Test
    fun `the moving tiles are those of Largest videos' first page, then of each video review's, in the order the user picked, readable and once each`() = runBlocking {
        val app = context as AnteaterApp
        suspend fun clip(name: String, size: Long, durationMs: Long, vararg signals: Signal, day: Long = 0) = "/videos/$name.mp4".also {
            app.index.record(MediaRef(it, size, day * 86_400_000_000_000, MediaType.VIDEO))
            app.index.setVideoMetadata(it, VideoMetadata(3840, 2160, durationMs, 30.0, 1000, true, "video/avc", false))
            for (signal in signals) app.index.setSignals(it, signal.detectorId, signal.detectorVersion, listOf(signal))
        }
        fun copy(group: String, role: Role) = Signal("exact_duplicate", 1, Category.EXACT_DUPLICATE, 1f, mapOf(GROUP_KEY to group, ROLE_KEY to role.value))
        fun blurry(score: Float) = Signal("blur", 1, Category.BLURRY, score)
        fun shortClip(score: Float) = Signal("short_clips", 1, Category.SHORT_CLIPS, score)
        app.index.setUnreadable(clip("broken", 5_000, 60_000)) // it takes its place on the page, as a placeholder
        val largest = (15 downTo 2).map { clip("large$it", 1_000L + it, 60_000) }
        val loopedWhole = clip("short", 1_001, 8_000, shortClip(0.9f)) // loops whole in both grids: one preview
        clip("large0", 999, 60_000) // the 17th, past the page
        val best = clip("best", 600, 60_000, copy("a", Role.BEST))
        clip("copy", 600, 60_000, copy("a", Role.INFERIOR))
        val older = clip("older", 500, 60_000, blurry(0.9f))
        val newer = clip("newer", 400, 60_000, blurry(0.8f), day = 1)
        val shortClip = clip("clip", 300, 30_000, shortClip(0.8f))
        app.reviewOrder[Category.BLURRY] = ReviewOrder.DAY
        realMovers = true
        size = null

        run()

        val page = (largest + loopedWhole).map { it to LONG_LOOP_MS }
        val copies = listOf(best to LONG_LOOP_MS)
        val blurred = listOf(newer, older).map { it to LONG_LOOP_MS } // the newer day first, as the user picked
        val shortClips = listOf(shortClip to null)
        assertEquals(page + copies + blurred + shortClips, previewed.map { it.video.path to it.endMs })
    }

    @Test
    fun `it stops between two previews when a scan starts or the phone is hot, and gives way to a compression batch`() {
        size = null
        movers = (1..10).map { video(it, UHD) }
        before[2] = { runBlocking { IndexWorker.running.lock() } }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(2, previewed.size)

        IndexWorker.running.unlock()
        previewed.clear()
        before.clear()
        hotFrom = 3
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(3, previewed.size)

        previewed.clear()
        hotFrom = Int.MAX_VALUE
        before[1] = { CompressBatch.publish(BatchState.Running(0, 5, 0)) }
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(1, previewed.size)
    }

    @Test
    fun `it stops when a scan starts`() {
        before[3] = { runBlocking { IndexWorker.running.lock() } }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(3), prepared)
    }

    @Test
    fun `it gives way to a compression batch and is retried`() {
        before[2] = { CompressBatch.publish(BatchState.Running(0, 5, 0)) }
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(photos.take(2), prepared)
    }

    @Test
    fun `it stops when the phone is hot`() {
        hotFrom = 4
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(4), prepared)
    }
}
