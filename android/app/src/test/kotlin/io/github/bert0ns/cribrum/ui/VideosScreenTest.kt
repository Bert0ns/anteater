package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideosScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun clip(name: String, size: Long, durationMs: Long? = 61_000, unreadable: Boolean = false, favorite: Boolean = false) = VideoItem(MediaRef("/sdcard/Movies/$name", size, 0, MediaType.VIDEO), durationMs, unreadable, favorite)

    private val videos = listOf(clip("big.mp4", 1_500_000_000), clip("middle.mp4", 800_000_000), clip("small.mp4", 90_000_000))

    /** The screen with a selection of its own, and what it was asked to apply. */
    private class Applied(var paths: Set<String>? = null, var trash: Boolean? = null)

    private fun show(items: List<VideoItem>, applied: Applied = Applied()) {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            VideosScreen(items, selected, onToggle = { selected = if (it in selected) selected - it else selected + it }, onApply = { paths, trash ->
                applied.paths = paths
                applied.trash = trash
            })
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
    fun `a long press opens the video full size, with its size, and tapping it closes it`() {
        show(videos)

        tile("middle.mp4").performTouchInput { longClick() }

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onNodeWithText("Video, 01:01, 800 MB").assertExists()
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
    fun `the tabs switch between Photos and Videos`() {
        var tab = CleanTab.PHOTOS
        compose.setContent { CleanTabs(tab, { tab = it }) }

        compose.onNodeWithText("Videos").performClick()

        assertEquals(CleanTab.VIDEOS, tab)
    }
}
