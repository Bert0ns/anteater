package io.github.bert0ns.cribrum.ui

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.video.videoFrameMicros
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
// Compose tests reach InputManager.getInstance, which SDK 37 removed. A phone's screen, so a few groups fit on it.
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ClipLoopsTest {
    @get:Rule
    val compose = createComposeRule()

    private val players = Players(3_000)
    private fun clips(count: Int) = (1..count).map { MediaRef("/sdcard/DCIM/Camera/clip$it.mp4", 2_000_000, 0, MediaType.VIDEO) }
    private fun page(clips: List<MediaRef>, unreadable: Set<Int> = emptySet()) = clips.mapIndexed { i, clip ->
        ReviewGroup(clip.path, listOf(ReviewItem(clip, suggestTrash = true, isCandidate = true, unreadable = i in unreadable, durationMs = 3_000)))
    }

    /** Groups of a best copy and a copy, the copy first in grid order so a test sees which of the two moves. */
    private fun copies(count: Int) = (1..count).map {
        val best = ReviewItem(MediaRef("/sdcard/DCIM/Camera/best$it.mp4", 2_000_000, 0, MediaType.VIDEO), suggestTrash = false, isCandidate = false, isBest = true, durationMs = 61_000)
        val copy = ReviewItem(MediaRef("/sdcard/Download/copy$it.mp4", 2_000_000, 0, MediaType.VIDEO), suggestTrash = true, isCandidate = true, durationMs = 61_000)
        ReviewGroup("g$it", listOf(copy, best))
    }

    /** The frames the tiles asked Coil for at a time, as path and ms: the slideshows'. The still frames ask for no time. */
    private val frames = ConcurrentLinkedQueue<Pair<String, Long>>()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun recordFrames() {
        val record = Interceptor { chain ->
            val micros = chain.request.videoFrameMicros
            if (micros >= 0) frames += (chain.request.data as File).path to micros / 1000
            chain.proceed()
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(record) }.build())
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    private fun show(page: List<ReviewGroup>, owner: LifecycleOwner? = null, category: Category = Category.SHORT_CLIPS, canPlay: Boolean = true) {
        compose.setContent {
            val locals = listOfNotNull(LocalPlayerFactory provides players.factory, owner?.let { LocalLifecycleOwner provides it })
            CompositionLocalProvider(*locals.toTypedArray()) {
                CategoryScreen(page, page.size, emptySet(), category != Category.SHORT_CLIPS, {}, { _, _ -> }, {}, mediaType = MediaType.VIDEO, category = category, canPlay = canPlay)
            }
        }
        settle()
    }

    /** Runs what the loops' thread holds, and [block] after it, there. */
    private fun onLoops(block: () -> Unit = {}) {
        val done = CountDownLatch(1)
        Handler(clipLoops).post {
            block()
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    /** Lets the screen and the loops' thread answer each other until both are quiet. */
    private fun settle() {
        repeat(3) {
            compose.waitForIdle()
            onLoops()
        }
        compose.waitForIdle()
    }

    private val running get() = players.made.filterNot { it.released }

    // The tile's click merges what is inside it, the slideshow too.
    private val slideshows get() = compose.onAllNodesWithTag("slideshow", useUnmergedTree = true).fetchSemanticsNodes().size
    private fun framesOf(path: String) = frames.filter { it.first == path }.map { it.second }

    @Test
    fun `the first tiles on screen play their clip muted and in a loop, on players that live on their own thread`() {
        show(page(clips(8)))

        assertEquals(LOOPING_CLIPS, players.made.size)
        assertEquals((1..LOOPING_CLIPS).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, players.made.flatMap { it.paths })
        for (player in players.made) {
            assertEquals(clipLoops.thread, player.builtOn)
            assertNotEquals(Looper.getMainLooper().thread, player.builtOn)
            assertTrue(player.playing)
            assertTrue(player.muted)
            assertFalse(player.focus) // the user's music plays on
            assertTrue(player.soundless)
            assertTrue(player.repeating)
            assertEquals(C.TIME_END_OF_SOURCE, player.endMs) // a short clip loops whole
        }
    }

    @Test
    fun `the other clips on screen show the slideshow, which steps through the strip's five times about once a second`() {
        show(page(clips(8)))
        val third = "/sdcard/DCIM/Camera/clip3.mp4"
        assertTrue(slideshows > 0)
        assertEquals(compose.onAllNodesWithContentDescription("clip", substring = true).fetchSemanticsNodes().size - LOOPING_CLIPS, slideshows)
        assertEquals(listOf(300L), framesOf(third))

        repeat(STRIP_FRAMES) {
            compose.mainClock.advanceTimeBy(SLIDE_MS)
            compose.waitForIdle()
        }

        assertEquals(stripTimes(3_000), framesOf(third).distinct())
        assertEquals(300L, framesOf(third).last()) // and round again
        assertTrue(framesOf("/sdcard/DCIM/Camera/clip1.mp4").isEmpty()) // a playing tile shows no slideshow
    }

    @Test
    fun `a tile that leaves the screen releases its player, and the tiles that come in take the free places`() {
        show(page(clips(16)))
        val first = players.made.toList()

        compose.onNode(hasScrollAction()).performScrollToIndex(15)
        settle()

        assertTrue(first.all { it.released })
        assertEquals(LOOPING_CLIPS, running.size)
        assertTrue(running.none { it.paths.single() in first.flatMap { old -> old.paths } })
    }

    @Test
    fun `an unreadable clip keeps its placeholder, with no slideshow, and gives its place to the next one`() {
        show(page(clips(8), unreadable = setOf(0)))

        assertEquals((2..LOOPING_CLIPS + 1).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, players.made.flatMap { it.paths })
        assertTrue(framesOf("/sdcard/DCIM/Camera/clip1.mp4").isEmpty())
    }

    @Test
    fun `a clip that fails to play shows the slideshow, releases its player and gives its place to the next one`() {
        show(page(clips(8)))
        val failed = players.made.first()

        onLoops { failed.fail() }
        settle()

        assertTrue(failed.released)
        assertEquals((2..LOOPING_CLIPS + 1).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, running.flatMap { it.paths })
        compose.onAllNodesWithTag("clipLoop").assertCountEquals(LOOPING_CLIPS)
        assertEquals(listOf(300L), framesOf("/sdcard/DCIM/Camera/clip1.mp4"))
    }

    @Test
    fun `nothing plays and no slideshow steps while the grid scrolls, and they start once it settles`() {
        show(page(clips(16)))
        val grid = compose.onNode(hasScrollAction())

        grid.performTouchInput {
            down(center)
            moveBy(Offset(0f, -200f))
        }
        settle()
        assertEquals(LOOPING_CLIPS, players.made.size)
        assertTrue(running.isEmpty())
        assertEquals(0, slideshows)
        val asked = frames.size
        compose.mainClock.advanceTimeBy(3 * SLIDE_MS)
        compose.waitForIdle()
        assertEquals(asked, frames.size)

        grid.performTouchInput { up() }
        settle()
        assertEquals(LOOPING_CLIPS, running.size)
        assertTrue(slideshows > 0)
    }

    @Test
    fun `the app in the background plays nothing and runs no slideshow, and both start again when it comes back`() {
        val owner = object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        show(page(clips(8)), owner)
        val first = players.made.toList()

        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED } // ON_PAUSE, then ON_STOP
        settle()
        assertTrue(first.all { it.released })
        assertTrue(running.isEmpty())
        assertEquals(0, slideshows)

        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        settle()
        assertEquals(first.flatMap { it.paths }, running.flatMap { it.paths })
        assertTrue(running.all { it.playing })
        assertTrue(slideshows > 0)
    }

    @Test
    fun `remove animations turns the loops and the slideshow off`() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(page(clips(8)))

        assertTrue(players.made.isEmpty())
        assertEquals(0, slideshows)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `a hot phone or battery saver turns the loops into slideshows`() {
        show(page(clips(8)), canPlay = false)

        assertTrue(players.made.isEmpty())
        assertEquals(compose.onAllNodesWithContentDescription("clip", substring = true).fetchSemanticsNodes().size, slideshows)
        assertEquals(listOf(300L), framesOf("/sdcard/DCIM/Camera/clip1.mp4"))
    }

    @Test
    fun `the grid of another video category keeps still frames`() {
        show(page(clips(8)), category = Category.FORWARDED)

        assertTrue(players.made.isEmpty())
        assertEquals(0, slideshows)
    }

    @Test
    fun `the full size view stops the loops and the slideshows, and closing it starts them again`() {
        show(page(clips(8)))

        compose.onNodeWithContentDescription("clip1.mp4", substring = true).performTouchInput { longClick() }
        settle()
        // Only the video of the full size view plays, which starts on its own, on the main thread.
        assertEquals(listOf("/sdcard/DCIM/Camera/clip1.mp4"), running.single().paths)
        assertEquals(Looper.getMainLooper().thread, running.single().builtOn)
        assertEquals(0, slideshows)

        compose.onNodeWithContentDescription("Close").performClick()
        settle()
        assertEquals(LOOPING_CLIPS, running.size)
        assertTrue(slideshows > 0)
    }

    private fun bestCopiesMove(category: Category) {
        show(copies(6), category = category)

        assertEquals(listOf("/sdcard/DCIM/Camera/best1.mp4", "/sdcard/DCIM/Camera/best2.mp4"), players.made.flatMap { it.paths })
        assertTrue(players.made.all { it.endMs == 10_000L && it.repeating })
        assertEquals(listOf(6_100L), framesOf("/sdcard/DCIM/Camera/best3.mp4"))
        assertTrue(frames.none { it.first.contains("copy") })
    }

    @Test
    fun `in exact copies the best copy of each group moves, the first two play their first 10 seconds and the copies keep their frame`() = bestCopiesMove(Category.EXACT_DUPLICATE)

    @Test
    fun `in similar copies the best copy of each group moves, the first two play their first 10 seconds and the copies keep their frame`() = bestCopiesMove(Category.NEAR_DUPLICATE)

    @Test
    fun `in takes the longest take of each group moves`() {
        fun take(group: Int, n: Int, length: Long) = ReviewItem(MediaRef("/sdcard/DCIM/Camera/take$group-$n.mp4", 2_000_000, 0, MediaType.VIDEO), suggestTrash = false, isCandidate = true, durationMs = length)
        val takes = (1..4).map { ReviewGroup("t$it", listOf(take(it, 1, 2_000), take(it, 2, 9_000), take(it, 3, 5_000))) }

        show(takes, category = Category.TAKES)

        assertEquals(listOf("/sdcard/DCIM/Camera/take1-2.mp4", "/sdcard/DCIM/Camera/take2-2.mp4"), players.made.flatMap { it.paths })
        assertTrue(players.made.all { it.endMs == C.TIME_END_OF_SOURCE })
        assertEquals(listOf(900L), framesOf("/sdcard/DCIM/Camera/take3-2.mp4"))
        assertTrue(frames.none { it.first.endsWith("-1.mp4") || it.first.endsWith("-3.mp4") })
    }

    @Test
    fun `a moment whose longest take cannot be read moves its longest readable take`() {
        fun take(group: Int, n: Int, length: Long, unreadable: Boolean = false) = ReviewItem(MediaRef("/sdcard/DCIM/Camera/take$group-$n.mp4", 2_000_000, 0, MediaType.VIDEO), suggestTrash = false, isCandidate = true, unreadable = unreadable, durationMs = length)
        val takes = listOf(ReviewGroup("t1", listOf(take(1, 1, 2_000), take(1, 2, 9_000, unreadable = true), take(1, 3, 5_000))))

        show(takes, category = Category.TAKES)

        assertEquals(listOf("/sdcard/DCIM/Camera/take1-3.mp4"), players.made.flatMap { it.paths })
    }

    private fun columns(): Int {
        val tops = compose.onAllNodesWithContentDescription(".mp4", substring = true).fetchSemanticsNodes().map { it.boundsInRoot.top }
        return tops.count { it == tops.min() }
    }

    @Test
    @Config(qualifiers = "w500dp-h900dp")
    fun `short clips have 2 columns on a compact width`() {
        show(page(clips(8)))

        assertEquals(2, columns())
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun `short clips have 4 columns on a medium width`() {
        show(page(clips(8)))

        assertEquals(4, columns())
    }
}
