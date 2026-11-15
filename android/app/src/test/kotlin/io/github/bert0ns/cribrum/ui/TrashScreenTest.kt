package io.github.bert0ns.cribrum.ui

import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class TrashScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val trashed = listOf("a.jpg", "b.jpg").map {
        val trashedAt = Instant.now() - Duration.ofDays(3)
        TrashedImage(MediaRef("/sdcard/DCIM/$it", 1, 0), trashedAt, File("/sdcard/.cribrum-trash/x/$it"), trashedAt + Duration.ofDays(30) - Duration.ofHours(12))
    }

    @Test
    fun `an image is restored in two taps`() {
        var restored = emptySet<String>()
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = { selected = selected + it.file.path }, onSelectAll = { _, _ -> }, onRestore = { restored = selected }, onDelete = {})
        }

        compose.onNodeWithText("Restore", substring = true).assertDoesNotExist() // nothing to restore yet
        compose.onNodeWithContentDescription("a.jpg. 27 days left").performClick()
        compose.onNodeWithText("Restore 1").performClick()

        assertEquals(setOf("/sdcard/.cribrum-trash/x/a.jpg"), restored)
    }

    @Test
    fun `an empty trash says so, but not while its list is still being read`() {
        var loading by mutableStateOf(true)
        compose.setContent { TrashScreen(emptyList(), emptySet(), {}, { _, _ -> }, {}, {}, loading = loading) }

        compose.onNodeWithText("The trash is empty.").assertDoesNotExist()
        loading = false
        compose.onNodeWithText("The trash is empty.").assertExists()
    }

    @Test
    fun `the hint says how to select and how to see an image full size`() {
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithText("Tap to select, hold to see full size.").assertExists()
    }

    @Test
    fun `with nothing selected emptying the trash is the only button, and a selection brings Restore and Delete`() {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = { selected = selected + it.file.path }, onSelectAll = { _, _ -> }, onRestore = {}, onDelete = {})
        }

        compose.onNodeWithText("Empty trash").assertExists()
        compose.onNodeWithText("Restore", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("a.jpg. 27 days left").performClick()

        compose.onNodeWithText("Empty trash").assertDoesNotExist()
        compose.onNodeWithText("Restore 1").assertExists()
        compose.onNodeWithText("Delete 1").assertExists()
    }

    @Test
    fun `the full size checkbox selects the image, as a tap on its tile does`() {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = {
                selected = if (it.file.path in selected) selected - it.file.path else selected + it.file.path
            }, onSelectAll = { _, _ -> }, onRestore = {}, onDelete = {})
        }

        compose.onNodeWithContentDescription("a.jpg. 27 days left").performTouchInput { longClick() }
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Close").performClick()

        compose.onNodeWithContentDescription("a.jpg. 27 days left").assertIsSelected()
        compose.onNodeWithText("Restore 1").assertExists()
    }

    @Test
    fun `a screen reader hears which images are selected`() {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = { selected = selected + it.file.path }, onSelectAll = { _, _ -> }, onRestore = {}, onDelete = {})
        }

        compose.onNodeWithContentDescription("a.jpg. 27 days left").assertIsNotSelected()
        compose.onNodeWithContentDescription("a.jpg. 27 days left").performClick()
        compose.onNodeWithContentDescription("a.jpg. 27 days left").assertIsSelected()
        compose.onNodeWithContentDescription("b.jpg. 27 days left").assertIsNotSelected()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and hasContentDescription("a.jpg. 27 days left")).assertExists()
    }

    @Test
    fun `each image says the days before it is purged`() {
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        assertEquals(2, compose.onAllNodes(hasContentDescription("27 days left", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `a screen reader hears the days left once and not the file path`() {
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        val tile = compose.onNodeWithContentDescription("a.jpg. 27 days left").fetchSemanticsNode()
        assertEquals(listOf("a.jpg. 27 days left"), tile.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(null, tile.config.getOrNull(SemanticsProperties.Text))
    }

    @Test
    fun `one day left is spoken in the singular`() {
        val at = Instant.now()
        val last = TrashedImage(MediaRef("/sdcard/DCIM/c.jpg", 1, 0), at, File("/sdcard/.cribrum-trash/x/c.jpg"), at + Duration.ofHours(20))
        compose.setContent { TrashScreen(listOf(last), emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithContentDescription("c.jpg. 1 day left").assertExists()
    }

    @Test
    fun `emptying the trash asks first and says the space it frees`() {
        var deleted: Set<String>? = null
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, onDelete = { deleted = it }) }

        compose.onNodeWithText("Empty trash").performClick()
        assertNull(deleted)
        compose.onNodeWithText("Delete 2 images for good?").assertExists()
        compose.onNodeWithText("This frees 2 B and cannot be undone.").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(trashed.map { it.file.path }.toSet(), deleted)
    }

    @Test
    fun `with a selection the button deletes only the selected images`() {
        var deleted: Set<String>? = null
        compose.setContent { TrashScreen(trashed, setOf(trashed[1].file.path), {}, { _, _ -> }, {}, onDelete = { deleted = it }) }

        compose.onNodeWithText("Empty trash").assertDoesNotExist()
        compose.onNodeWithText("Delete 1").performClick()
        compose.onNodeWithText("Delete 1 image for good?").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(setOf(trashed[1].file.path), deleted)
    }

    @Test
    fun `two items with the same path are selected and deleted one by one`() {
        val at = Instant.now() - Duration.ofDays(3)
        val original = TrashedImage(MediaRef("/sdcard/DCIM/a.jpg", 9, 0), at, File("/sdcard/.cribrum-trash/1/a.jpg"), at + Duration.ofDays(30))
        val copy = TrashedImage(MediaRef("/sdcard/DCIM/a.jpg", 4, 1), at, File("/sdcard/.cribrum-trash/2/a.jpg"), at + Duration.ofDays(30))
        var deleted: Set<String>? = null
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(listOf(original, copy), selected, onSelect = { selected = selected + it.file.path }, onSelectAll = { _, _ -> }, onRestore = {}, onDelete = { deleted = it })
        }

        compose.onAllNodesWithContentDescription("a.jpg", substring = true)[0].performClick()
        compose.onNodeWithText("Delete 1").performClick()
        compose.onNodeWithText("Delete 1 image for good?").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(setOf(original.file.path), deleted)
    }

    @Test
    fun `emptying the trash counts every item, also two with the same path`() {
        val at = Instant.now() - Duration.ofDays(3)
        val items = listOf(9L to 0L, 4L to 1L).mapIndexed { i, (size, mtime) -> TrashedImage(MediaRef("/sdcard/DCIM/a.jpg", size, mtime), at, File("/sdcard/.cribrum-trash/$i/a.jpg"), at + Duration.ofDays(30)) }
        compose.setContent { TrashScreen(items, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithText("Empty trash").performClick()

        compose.onNodeWithText("Delete 2 images for good?").assertExists()
    }

    @Test
    fun `a favourite shows its heart at full size`() {
        val at = Instant.now() - Duration.ofDays(3)
        val favourite = TrashedImage(MediaRef("/sdcard/DCIM/a.jpg", 1, 0), at, File("/sdcard/.cribrum-trash/x/a.jpg"), at + Duration.ofDays(30), favorite = true)
        compose.setContent { TrashScreen(listOf(favourite), emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithContentDescription("a.jpg", substring = true).performTouchInput { longClick() }

        compose.onNodeWithContentDescription("Favourite").assertExists()
    }

    @Test
    fun `holding an image opens it full size, with its days left`() {
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithContentDescription("a.jpg. 27 days left").performTouchInput { longClick() }

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onNodeWithText("27 days left").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("fullSize").assertDoesNotExist()
    }

    @Test
    fun `stickers have their own section, selected all at once`() {
        val sticker = TrashedImage(
            MediaRef("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/s.webp", 1, 0),
            Instant.now(),
            File("/sdcard/.cribrum-trash/y/s.webp"),
            Instant.now() + Duration.ofDays(30),
        )
        var chosen: List<TrashedImage> = emptyList()
        compose.setContent { TrashScreen(trashed + sticker, emptySet(), {}, { items, _ -> chosen = items }, {}, {}) }

        compose.onNodeWithText("2 images").assertExists()
        compose.onNodeWithText("1 WhatsApp sticker").assertExists()
        compose.onAllNodesWithText("Select all")[1].performClick()

        assertEquals(listOf(sticker), chosen)
    }

    private val clip = run {
        val at = Instant.now() - Duration.ofDays(3)
        TrashedImage(MediaRef("/sdcard/DCIM/clip.mp4", 412_000_000, 0, MediaType.VIDEO), at, File("/sdcard/.cribrum-trash/v/clip.mp4"), at + Duration.ofDays(30) - Duration.ofHours(12))
    }

    @Test
    fun `a trashed video has its own section and shows its duration and size`() {
        // A trash file of its own: the lengths read are kept for the process, and the other tests read none for theirs.
        val clip = clip.copy(file = File("/sdcard/.cribrum-trash/d/clip.mp4"))
        ShadowMediaMetadataRetriever.addMetadata(clip.file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "102000")
        compose.setContent { TrashScreen(trashed + clip, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithText("2 images").assertExists()
        compose.onNodeWithText("1 video").assertExists()
        compose.onNodeWithContentDescription("clip.mp4. Video, 01:42, 412 MB. 27 days left").assertExists()
    }

    @Test
    fun `a video whose duration cannot be read still says its size`() {
        compose.setContent { TrashScreen(listOf(clip), emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithContentDescription("clip.mp4. Video, 412 MB. 27 days left").assertExists()
    }

    @Test
    fun `holding a video opens its frame full size, with its size and days left`() {
        compose.setContent { TrashScreen(listOf(clip), emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithContentDescription("clip.mp4", substring = true).performTouchInput { longClick() }

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onNodeWithText("Video, 412 MB").assertExists()
        compose.onNodeWithText("27 days left").assertExists()
    }

    @Test
    fun `deleting a video for good counts files, not images`() {
        compose.setContent { TrashScreen(trashed + clip, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithText("Empty trash").performClick()

        compose.onNodeWithText("Delete 3 files for good?").assertExists()
    }

    @Test
    fun `next to Empty trash the screen says how much space the trash still takes`() {
        compose.setContent { TrashScreen(trashed + clip, emptySet(), {}, { _, _ -> }, {}, {}) }

        compose.onNodeWithText("The trash still takes up 412 MB.").assertExists()
    }

    @Test
    fun `a video's length is read once, so a tile that comes back opens no new retriever`() {
        val again = clip.copy(file = File("/sdcard/.cribrum-trash/w/clip.mp4"))
        ShadowMediaMetadataRetriever.addMetadata(again.file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "102000")
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) TrashScreen(listOf(again), emptySet(), {}, { _, _ -> }, {}, {}) }
        compose.onNodeWithContentDescription("clip.mp4. Video, 01:42, 412 MB. 27 days left").assertExists()

        shown = false
        compose.waitForIdle()
        // A retriever opened again would read this.
        ShadowMediaMetadataRetriever.addMetadata(again.file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "5000")
        shown = true

        compose.onNodeWithContentDescription("clip.mp4. Video, 01:42, 412 MB. 27 days left").assertExists()
    }
}
