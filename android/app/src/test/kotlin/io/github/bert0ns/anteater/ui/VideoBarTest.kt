package io.github.bert0ns.anteater.ui

import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.review.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The bar of a video's full size view hides while it plays and on a swipe (#473), through "Largest videos", whose view the review and the trash share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideoBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val players = Players()
    private val viewPlayers get() = players.made.filter { it.builtOn == Looper.getMainLooper().thread }

    private fun clip(name: String) = VideoItem(MediaRef("/sdcard/Movies/$name", 1_000_000, 0, MediaType.VIDEO), 61_000, false, false)

    private fun open(touchExploration: Boolean = false) {
        if (touchExploration) {
            val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)
            shadowOf(manager).setTouchExplorationEnabled(true)
        }
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            CompositionLocalProvider(LocalPlayerFactory provides players.factory) {
                VideosScreen(
                    listOf(clip("one.mp4"), clip("two.mp4")),
                    selected,
                    onToggle = { selected = if (it in selected) selected - it else selected + it },
                    onApply = { _, _ -> },
                )
            }
        }
        compose.onNodeWithContentDescription("one.mp4", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun assertBar(shown: Boolean) {
        compose.waitForIdle()
        val bar = compose.onNodeWithContentDescription("Position in the video")
        if (shown) bar.assertExists() else bar.assertDoesNotExist()
        // The checkbox and the close button never hide.
        compose.onNodeWithText("Selected").assertExists()
        compose.onNodeWithContentDescription("Close").assertExists()
    }

    private fun playAboutASecond() {
        compose.waitForIdle() // the player's state reaches the screen first
        compose.mainClock.advanceTimeBy(1_200)
    }

    @Test
    fun `the bar fades out about a second after the video starts, and not before`() {
        open()
        compose.mainClock.advanceTimeBy(600)
        assertBar(shown = true)

        playAboutASecond()

        assertTrue(viewPlayers.single().playing)
        assertBar(shown = false)
    }

    @Test
    fun `a tap on the picture pauses the video and brings the bar back, and playing it again hides it again`() {
        open()
        playAboutASecond()
        assertBar(shown = false)

        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        assertFalse(viewPlayers.single().playing)
        assertBar(shown = true)

        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        playAboutASecond()
        assertTrue(viewPlayers.single().playing)
        assertBar(shown = false)
    }

    @Test
    fun `the bar comes back when the video ends`() {
        open()
        playAboutASecond()
        assertBar(shown = false)

        viewPlayers.single().ended()

        assertBar(shown = true)
    }

    @Test
    fun `a swipe up shows the bar and a swipe down hides it, playing or paused`() {
        open()
        playAboutASecond()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeUp() }
        assertBar(shown = true)
        playAboutASecond() // a bar the user asked for stays while the video plays
        assertBar(shown = true)

        compose.onNodeWithTag("fullSize").performTouchInput { swipeDown() }
        assertBar(shown = false)

        compose.onNodeWithTag("fullSize").performTouchInput { click() } // paused: the bar is back, and a swipe down hides it again
        assertBar(shown = true)
        compose.onNodeWithTag("fullSize").performTouchInput { swipeDown() }
        assertFalse(viewPlayers.single().playing)
        assertBar(shown = false)
        compose.onNodeWithTag("fullSize").performTouchInput { swipeUp() }
        assertBar(shown = true)
    }

    @Test
    fun `a vertical swipe does not pause the video, and a horizontal one still pages`() {
        open()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(viewPlayers.single().playing)

        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("/sdcard/Movies/two.mp4"), viewPlayers.single().paths)
    }

    @Test
    fun `with a touch exploration service on, the bar neither hides on its own nor on a swipe`() {
        open(touchExploration = true)
        playAboutASecond()
        assertBar(shown = true)

        compose.onNodeWithTag("fullSize").performTouchInput { swipeDown() }

        assertBar(shown = true)
    }
}
