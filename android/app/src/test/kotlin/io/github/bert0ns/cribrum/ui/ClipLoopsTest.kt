package io.github.bert0ns.cribrum.ui

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class ClipLoopsTest {
    @get:Rule
    val compose = createComposeRule()

    private val players = Players(3_000)
    private fun clips(count: Int) = (1..count).map { MediaRef("/sdcard/DCIM/Camera/clip$it.mp4", 2_000_000, 0, MediaType.VIDEO) }
    private fun page(clips: List<MediaRef>, unreadable: Set<Int> = emptySet()) = clips.mapIndexed { i, clip -> ReviewGroup(clip.path, listOf(ReviewItem(clip, suggestTrash = true, isCandidate = true, unreadable = i in unreadable))) }

    private fun show(page: List<ReviewGroup>, owner: LifecycleOwner? = null, loopClips: Boolean = true) = compose.setContent {
        val locals = listOfNotNull(LocalPlayerFactory provides players.factory, owner?.let { LocalLifecycleOwner provides it })
        CompositionLocalProvider(*locals.toTypedArray()) {
            CategoryScreen(page, page.size, emptySet(), false, {}, { _, _ -> }, {}, mediaType = MediaType.VIDEO, loopClips = loopClips)
        }
    }

    private val running get() = players.made.filterNot { it.released }

    @Test
    fun `the first tiles on screen play their clip muted and in a loop, a few at a time`() {
        show(page(clips(8)))
        compose.waitForIdle()

        assertEquals(LOOPING_CLIPS, players.made.size)
        assertEquals((1..LOOPING_CLIPS).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, players.made.flatMap { it.paths })
        for (player in players.made) {
            assertTrue(player.playing)
            assertEquals(0f, player.volume, 0f)
            assertFalse(player.focus) // the user's music plays on
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        }
    }

    @Test
    fun `a tile that leaves the screen releases its player, and the tiles that come in take the free places`() {
        show(page(clips(60)))
        compose.waitForIdle()
        val first = players.made.toList()

        compose.onNode(hasScrollAction()).performScrollToIndex(59)
        compose.waitForIdle()

        assertTrue(first.all { it.released })
        assertEquals(LOOPING_CLIPS, running.size)
        assertTrue(running.none { it.paths.single() in first.flatMap { old -> old.paths } })
    }

    @Test
    fun `an unreadable clip keeps its placeholder and gives its place to the next one`() {
        show(page(clips(8), unreadable = setOf(0)))
        compose.waitForIdle()

        assertEquals((2..LOOPING_CLIPS + 1).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, players.made.flatMap { it.paths })
    }

    @Test
    fun `a clip that fails to play falls back to its still frame, releases its player and gives its place to the next one`() {
        show(page(clips(8)))
        compose.waitForIdle()
        val failed = players.made.first()

        failed.fail()
        compose.waitForIdle()

        assertTrue(failed.released)
        assertEquals((2..LOOPING_CLIPS + 1).map { "/sdcard/DCIM/Camera/clip$it.mp4" }, running.flatMap { it.paths })
        compose.onAllNodesWithTag("clipLoop").assertCountEquals(LOOPING_CLIPS)
    }

    @Test
    fun `nothing plays while the grid scrolls, and the clips start once it settles`() {
        show(page(clips(60)))
        compose.waitForIdle()
        val grid = compose.onNode(hasScrollAction())

        grid.performTouchInput {
            down(center)
            moveBy(Offset(0f, -200f))
        }
        compose.waitForIdle()
        assertEquals(LOOPING_CLIPS, players.made.size)
        assertTrue(running.isEmpty())

        grid.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(LOOPING_CLIPS, running.size)
    }

    @Test
    fun `a loop lets go of its player when the app leaves the screen, and plays again when it comes back`() {
        val owner = object : LifecycleOwner {
            override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        show(page(clips(8)), owner)
        compose.waitForIdle()
        val first = players.made.toList()

        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED } // ON_PAUSE, then ON_STOP
        compose.waitForIdle()
        assertTrue(first.all { it.released })
        assertTrue(running.isEmpty())

        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(first.flatMap { it.paths }, running.flatMap { it.paths })
        assertTrue(running.all { it.playing })
    }

    @Test
    fun `remove animations turns the loops off`() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(page(clips(8)))
        compose.waitForIdle()

        assertTrue(players.made.isEmpty())
    }

    @Test
    fun `the grid of another video category plays nothing`() {
        show(page(clips(8)), loopClips = false)
        compose.waitForIdle()

        assertTrue(players.made.isEmpty())
    }

    @Test
    fun `the full size view stops the loops, and closing it starts them again`() {
        show(page(clips(8)))
        compose.waitForIdle()

        compose.onNodeWithContentDescription("clip1.mp4", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        // Only the video of the full size view plays, which starts on its own.
        assertEquals(listOf("/sdcard/DCIM/Camera/clip1.mp4"), running.single().paths)

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(LOOPING_CLIPS, running.size)
    }
}
