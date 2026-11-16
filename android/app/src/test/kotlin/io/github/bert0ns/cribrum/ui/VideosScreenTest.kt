package io.github.bert0ns.cribrum.ui

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideosScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun clip(name: String, size: Long, durationMs: Long? = 61_000, unreadable: Boolean = false, favorite: Boolean = false) = VideoItem(MediaRef("/sdcard/Movies/$name", size, 0, MediaType.VIDEO), durationMs, unreadable, favorite)

    private val videos = listOf(clip("big.mp4", 1_500_000_000), clip("middle.mp4", 800_000_000), clip("small.mp4", 90_000_000))

    /** The screen with a selection of its own, and what it was asked to apply. */
    private class Applied(var paths: Set<String>? = null, var trash: Boolean? = null)

    private var players = Players()

    private fun show(items: List<VideoItem>, applied: Applied = Applied(), owner: LifecycleOwner? = null) {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            val locals = listOfNotNull(LocalPlayerFactory provides players.factory, owner?.let { LocalLifecycleOwner provides it })
            CompositionLocalProvider(*locals.toTypedArray()) {
                VideosScreen(items, selected, onToggle = { selected = if (it in selected) selected - it else selected + it }, onApply = { paths, trash ->
                    applied.paths = paths
                    applied.trash = trash
                })
            }
        }
    }

    private fun tile(name: String) = compose.onNodeWithContentDescription(name, substring = true)

    @Test
    fun `a tile says its name, length and size, and nothing is selected to begin with`() {
        show(videos)

        compose.onNodeWithText("3 videos · 2.4 GB").assertExists()
        tile("big.mp4").assertIsNotSelected()
        compose.onNodeWithContentDescription("big.mp4. Video, 01:01, 1.5 GB. Not selected").assertExists()
        compose.onNodeWithText("Trash", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a tap selects, and the main button says how many videos and how many GB it moves`() {
        val applied = Applied()
        show(videos, applied)

        tile("big.mp4").performClick()
        tile("middle.mp4").performClick()

        tile("big.mp4").assertIsSelected()
        compose.onNodeWithText("Trash 2 videos, 2.3 GB").performClick()
        assertEquals(setOf("/sdcard/Movies/big.mp4", "/sdcard/Movies/middle.mp4"), applied.paths)
        assertEquals(true, applied.trash)
    }

    @Test
    fun `Keep applies the selection as a keep`() {
        val applied = Applied()
        show(videos, applied)

        tile("small.mp4").performClick()
        compose.onNodeWithText("Keep").performClick()

        assertEquals(setOf("/sdcard/Movies/small.mp4"), applied.paths)
        assertEquals(false, applied.trash)
    }

    @Test
    fun `a long press opens the video full size, with its size`() {
        show(videos)

        tile("middle.mp4").performTouchInput { longClick() }

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onNodeWithText("Video, 01:01, 800 MB").assertExists()
        assertTrue(players.made.isEmpty()) // it does not play until asked
    }

    private fun openFullSize(name: String) {
        tile(name).performTouchInput { longClick() }
        compose.onNodeWithTag("fullSize").assertExists()
    }

    private fun play() = compose.onAllNodesWithContentDescription("Play video").onFirst().performClick()

    @Test
    fun `the play button plays the video, and the controls say where it is`() {
        show(videos)
        openFullSize("middle.mp4")

        play()
        compose.waitForIdle()

        assertEquals(listOf("/sdcard/Movies/middle.mp4"), players.made.single().paths)
        assertTrue(players.made.single().playing)
        compose.onNodeWithText("00:00 / 01:01").assertExists()
        compose.onNodeWithContentDescription("Pause video").assertExists()
        compose.onNodeWithContentDescription("Position in the video").assertExists()
    }

    @Test
    fun `a tap on the picture pauses the video and plays it again, and does not close the view`() {
        show(videos)
        openFullSize("middle.mp4")
        play()
        compose.waitForIdle()

        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        compose.waitForIdle()

        assertFalse(players.made.single().playing)
        compose.onNodeWithTag("fullSize").assertExists()
        compose.onAllNodesWithContentDescription("Play video").assertCountEquals(2) // the page's and the bar's

        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        compose.waitForIdle()

        assertTrue(players.made.single().playing)
    }

    @Test
    fun `a tap on the placeholder of a video that cannot be read closes the view`() {
        show(listOf(clip("broken.mp4", 700_000_000, durationMs = null, unreadable = true)))
        openFullSize("broken.mp4")

        compose.onNode(tapSays("Close")).assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        compose.waitForIdle()

        compose.onNodeWithTag("fullSize").assertDoesNotExist()
    }

    /** The surface the video draws on, which lives in the full size dialog's window. */
    private fun surface(): SurfaceView? {
        fun find(view: View): SurfaceView? = if (view is SurfaceView) view else (view as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        return ShadowDialog.getLatestDialog()?.window?.decorView?.let(::find)
    }

    @Test
    fun `the screen stays on while the video plays, and not while it is paused or has ended`() {
        show(videos)
        openFullSize("middle.mp4")
        play()
        compose.waitForIdle()
        assertTrue(surface()!!.keepScreenOn)

        compose.onNodeWithContentDescription("Pause video").performClick()
        compose.waitForIdle()
        assertFalse(surface()!!.keepScreenOn)

        compose.onAllNodesWithContentDescription("Play video").onFirst().performClick()
        compose.waitForIdle()
        assertTrue(surface()!!.keepScreenOn)

        players.made.single().ended()
        compose.waitForIdle()
        assertFalse(surface()!!.keepScreenOn)
    }

    @Test
    fun `the strip has five frames across the length, and a tap on one plays from there`() {
        show(videos)
        openFullSize("middle.mp4")

        compose.onAllNodesWithContentDescription("Go to", substring = true).assertCountEquals(5)
        compose.onNodeWithContentDescription("Go to 00:30").performClick()
        compose.waitForIdle()

        assertEquals(30_500, players.made.single().positionMs)
        assertTrue(players.made.single().playing)
    }

    private fun tapSays(label: String) = SemanticsMatcher("a tap that says $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun `the tap on the picture says what it does, and the strip's frames are buttons`() {
        show(videos)
        openFullSize("middle.mp4")

        compose.onNode(tapSays("Play video")).assertExists()
        compose.onAllNodesWithContentDescription("Go to", substring = true).onFirst().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        play()
        compose.waitForIdle()
        compose.onNode(tapSays("Pause video")).assertExists()
    }

    @Test
    fun `the strip keeps its frames when the player reads another length than the index did`() {
        players = Players(lengthMs = 59_000) // the index says 61,000
        show(videos)
        openFullSize("middle.mp4")
        fun labels() = compose.onAllNodesWithContentDescription("Go to", substring = true).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.single() }
        val before = labels()

        play()
        compose.waitForIdle()

        assertEquals(listOf("Go to 00:06", "Go to 00:18", "Go to 00:30", "Go to 00:42", "Go to 00:54"), before)
        assertEquals(before, labels())
        compose.onNodeWithText("00:00 / 00:59").assertExists() // the seek bar has the player's length
    }

    @Test
    fun `the sound button mutes the video and brings the sound back`() {
        show(videos)
        openFullSize("middle.mp4")
        play()

        compose.onNodeWithContentDescription("Mute").performClick()
        compose.waitForIdle()
        assertEquals(0f, players.made.single().volume, 0f)

        compose.onNodeWithContentDescription("Unmute").performClick()
        compose.waitForIdle()
        assertEquals(1f, players.made.single().volume, 0f)
    }

    @Test
    fun `a swipe to the next video stops the one that plays, with the same player`() {
        show(videos)
        openFullSize("big.mp4")
        play()
        compose.waitForIdle()

        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(1, players.made.size)
        assertEquals(emptyList<String>(), players.made.single().paths)
        assertFalse(players.made.single().released)
        compose.onNodeWithText("Video, 01:01, 800 MB").assertExists()
        compose.onNodeWithText("00:00 / 01:01").assertExists()
    }

    @Test
    fun `closing the view releases the player`() {
        show(videos)
        openFullSize("big.mp4")
        play()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("fullSize").assertDoesNotExist()
        assertTrue(players.made.single().released)
    }

    @Test
    fun `the video pauses when the app leaves the screen`() {
        val owner = object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        show(videos, owner = owner)
        openFullSize("middle.mp4")
        play()
        compose.waitForIdle()
        assertTrue(players.made.single().playing)

        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED } // ON_PAUSE, then ON_STOP
        compose.waitForIdle()

        assertFalse(players.made.single().playing)
        assertEquals(listOf("/sdcard/Movies/middle.mp4"), players.made.single().paths) // paused, not dropped
    }

    @Test
    fun `a video that leaves the list while it plays is stopped`() {
        var items by mutableStateOf(videos)
        compose.setContent { CompositionLocalProvider(LocalPlayerFactory provides players.factory) { VideosScreen(items, emptySet(), onToggle = {}, onApply = { _, _ -> }) } }
        openFullSize("big.mp4")
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        play()
        compose.waitForIdle()
        assertEquals(listOf("/sdcard/Movies/middle.mp4"), players.made.single().paths)

        items = items.filter { it.video.path != "/sdcard/Movies/middle.mp4" }
        compose.waitForIdle()

        assertEquals(emptyList<String>(), players.made.single().paths)
        assertFalse(players.made.single().released) // the view is still open
    }

    @Test
    fun `a video that fails to play says so, and the view stays`() {
        show(videos)
        openFullSize("big.mp4")
        play()
        compose.waitForIdle()

        players.made.single().fail()
        compose.waitForIdle()

        compose.onNodeWithText("This video can't be played.").assertExists()
        compose.onNodeWithTag("fullSize").assertExists()
        compose.onAllNodesWithContentDescription("Play video").onFirst().assertExists() // it can be tried again
    }

    @Test
    fun `a video that cannot be read has the placeholder, no player and no controls`() {
        show(listOf(clip("broken.mp4", 700_000_000, durationMs = null, unreadable = true)))

        openFullSize("broken.mp4")

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onAllNodesWithText("Can't be read").assertCountEquals(2) // the tile's and the page's
        compose.onAllNodesWithContentDescription("Play video").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Go to", substring = true).assertCountEquals(0)
        assertTrue(players.made.isEmpty())
    }

    @Test
    fun `a favourite keeps its place, shows a heart and asks before the trash takes it`() {
        val applied = Applied()
        show(listOf(clip("big.mp4", 1_500_000_000, favorite = true), clip("small.mp4", 90_000_000)), applied)

        compose.onNodeWithContentDescription("big.mp4. Video, 01:01, 1.5 GB. Not selected. Favourite").assertExists()
        tile("big.mp4").performClick()
        compose.onNodeWithText("Trash 1 video", substring = true).performClick()

        assertNull(applied.paths) // asked first
        compose.onNodeWithText("Move to trash anyway").performClick()
        assertEquals(setOf("/sdcard/Movies/big.mp4"), applied.paths)
        assertEquals(true, applied.trash)
    }

    @Test
    fun `cancelling the favourite dialog trashes nothing`() {
        val applied = Applied()
        show(listOf(clip("big.mp4", 1_500_000_000, favorite = true)), applied)

        tile("big.mp4").performClick()
        compose.onNodeWithText("Trash 1 video", substring = true).performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertNull(applied.paths)
    }

    @Test
    fun `a video that cannot be read stays in the list with a placeholder and a note`() {
        show(listOf(clip("broken.mp4", 700_000_000, durationMs = null, unreadable = true)))

        compose.onNodeWithText("Can't be read").assertExists()
        compose.onNodeWithContentDescription("broken.mp4. Video, 700 MB. Can't be read. Not selected").assertExists()
    }

    @Test
    fun `with nothing left it says so`() {
        show(emptyList())

        compose.onNodeWithText("No more videos to review.").assertExists()
    }

    @Test
    fun `the Videos tab shows the largest videos with their count and size, and opens them`() {
        var opened = false
        compose.setContent { VideosTab(videos, indexed = true, onOpen = { opened = true }) }

        compose.onNodeWithText("Up to 2.4 GB can be freed").assertExists()
        compose.onNodeWithText("3 videos · 2.4 GB").assertExists()
        compose.onNodeWithText("Largest videos").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun `before a scan found a video the tab says they appear after the next scan, and has no row`() {
        compose.setContent { VideosTab(emptyList(), indexed = false, onOpen = {}) }

        compose.onNodeWithText("Videos appear after the next scan.").assertExists()
        compose.onNodeWithText("Largest videos").assertDoesNotExist()
    }

    @Test
    fun `once a scan has counted no video the tab says the phone has none`() {
        compose.setContent { VideosTab(emptyList(), indexed = false, onOpen = {}, noVideos = true) }

        compose.onNodeWithText("No videos found on this phone.").assertExists()
        compose.onNodeWithText("Videos appear after the next scan.").assertDoesNotExist()
    }

    @Test
    fun `the tab says how many videos wait for Scan videos, and nothing once none waits`() {
        var waiting by mutableStateOf(1_419)
        compose.setContent { VideosTab(videos, indexed = true, onOpen = {}, waiting = waiting) }

        compose.onNodeWithText("1419 videos not analysed yet").assertExists()

        waiting = 0

        compose.onNodeWithText("not analysed yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `once every video is reviewed the row stays, with none to open`() {
        var opened = false
        compose.setContent { VideosTab(emptyList(), indexed = true, onOpen = { opened = true }) }

        compose.onNodeWithText("0 videos").assertExists()
        compose.onNodeWithText("Largest videos").performClick()
        assertEquals(false, opened)
    }

    @Test
    fun `a category with a video detector is a row of the Videos tab with its count and size, and opens`() {
        var opened: Category? = null
        val duplicates = CategorySummary(Category.EXACT_DUPLICATE, 2, bytes = 1_500_000_000)
        compose.setContent { VideosTab(videos, indexed = true, onOpen = {}, categories = listOf(duplicates), onOpenCategory = { opened = it }) }

        compose.onNodeWithText("Exact copies").assertExists()
        compose.onNodeWithText("2 videos · 1.5 GB").assertExists()
        compose.onNodeWithText("Exact copies").performClick()
        assertEquals(Category.EXACT_DUPLICATE, opened)
    }

    @Test
    fun `an empty video category opens nothing, and says it needs Scan videos until that scan has run`() {
        var opened: Category? = null
        var ran by mutableStateOf(false)
        val empty = CategorySummary(Category.EXACT_DUPLICATE, 0)
        compose.setContent { VideosTab(videos, indexed = true, onOpen = {}, categories = listOf(empty), onOpenCategory = { opened = it }, scanRan = ran) }

        compose.onNodeWithText("Needs Scan videos").assertExists()
        compose.onNodeWithText("Exact copies").performClick()
        assertNull(opened)

        ran = true

        compose.onNodeWithText("Needs Scan videos").assertDoesNotExist()
        compose.onNodeWithText("0 videos", substring = true).assertExists()
    }

    @Test
    fun `the tabs switch between Photos and Videos`() {
        var tab = CleanTab.PHOTOS
        compose.setContent { CleanTabs(tab, { tab = it }) }

        compose.onNodeWithText("Videos").performClick()

        assertEquals(CleanTab.VIDEOS, tab)
    }
}
