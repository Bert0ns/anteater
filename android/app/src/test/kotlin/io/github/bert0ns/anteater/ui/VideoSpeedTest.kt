package io.github.bert0ns.anteater.ui

import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A hold on a playing video in the full size view changes its speed, and a swipe sideways keeps it (#527). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideoSpeedTest {
    @get:Rule
    val compose = createComposeRule()

    private val players = Players()
    private val player get() = players.made.single { it.builtOn == Looper.getMainLooper().thread }
    private val page get() = compose.onNodeWithTag("fullSize")
    private val dial get() = compose.onNodeWithTag(SPEED_DIAL_TAG, useUnmergedTree = true)

    private fun clip(name: String, unreadable: Boolean = false) = VideoItem(MediaRef("/sdcard/Movies/$name", 1_000_000, 0, MediaType.VIDEO), 61_000, unreadable, false)

    private fun open(vararg videos: VideoItem = arrayOf(clip("one.mp4"), clip("two.mp4")), touchExploration: Boolean = false) {
        if (touchExploration) {
            shadowOf(ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        }
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            CompositionLocalProvider(LocalPlayerFactory provides players.factory) {
                VideosScreen(videos.toList(), selected, onToggle = { selected = if (it in selected) selected - it else selected + it }, onApply = { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription(videos.first().video.path.substringAfterLast('/'), substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    /** Puts a finger down on the page, in the middle unless [at] says where, and keeps it there past the long press timeout. */
    private fun press(at: ((width: Float, height: Float) -> Offset)? = null) {
        page.performTouchInput { down(at?.invoke(width.toFloat(), height.toFloat()) ?: center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    private fun move(x: Float = 0f, y: Float = 0f) {
        page.performTouchInput { repeat(4) { moveBy(Offset(x / 4, y / 4)) } }
        compose.waitForIdle()
    }

    private fun lift() {
        page.performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun assertOpen() = page.assertExists()

    @Test
    fun `a hold on a playing video opens the speed slider, and lifting the finger closes it with nothing else changed`() {
        open()
        dial.assertDoesNotExist()

        press()
        dial.assertExists()
        compose.onNodeWithText("1x").assertExists()

        lift()
        dial.assertDoesNotExist()
        assertTrue(player.playing) // the lift is not the tap that pauses
        assertEquals(1f, player.playbackSpeed, 0f)
    }

    @Test
    fun `up is faster and down is slower, in quarters from a quarter to four times, and lifting goes back to the speed before`() {
        open()
        press()

        move(y = -px(72)) // four steps of a quarter
        compose.onNodeWithText("2x").assertExists()
        assertEquals(2f, player.playbackSpeed, 0f)

        move(y = -px(400))
        compose.onNodeWithText("4x").assertExists()
        assertEquals(4f, player.playbackSpeed, 0f)

        move(y = px(1_000))
        compose.onNodeWithText("0.25x").assertExists()
        assertEquals(0.25f, player.playbackSpeed, 0f)

        lift()
        assertEquals(1f, player.playbackSpeed, 0f)
        compose.onNodeWithText("0.25x").assertDoesNotExist()
        assertOpen()
    }

    @Test
    fun `a swipe sideways locks the speed, which stays after the lift, without changing page, and the badge sets it back`() {
        open()
        press()
        move(y = -px(72))

        move(x = -px(80))
        compose.onNodeWithContentDescription("Speed locked").assertExists()
        lift()

        assertEquals(2f, player.playbackSpeed, 0f)
        assertEquals(listOf("/sdcard/Movies/one.mp4"), player.paths)
        assertTrue(player.playing)
        compose.onNodeWithText("2x").assertExists()

        compose.onNodeWithContentDescription("Speed locked", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2x").performClick()
        assertEquals(1f, player.playbackSpeed, 0f)
        assertTrue(player.playing) // the badge is not the picture's tap
        compose.onNodeWithText("2x").assertDoesNotExist()
    }

    @Test
    fun `bringing the finger back unlocks the speed, which moves again from where it was, and the other side locks it again`() {
        open()
        press()
        move(y = -px(72))
        move(x = px(80))
        move(y = -px(72)) // locked: nothing changes
        assertEquals(2f, player.playbackSpeed, 0f)

        move(x = -px(60))
        compose.onNodeWithContentDescription("Speed locked").assertDoesNotExist()
        move(y = -px(36))
        assertEquals(2.5f, player.playbackSpeed, 0f)
        compose.onNodeWithText("2.5x").assertExists()

        move(x = -px(80))
        compose.onNodeWithContentDescription("Speed locked").assertExists()
        lift()

        assertEquals(2.5f, player.playbackSpeed, 0f)
        compose.onNodeWithText("2.5x").assertExists() // the badge
    }

    @Test
    fun `an unlocked hold that ends goes back to the speed before, even after a lock and an unlock`() {
        open()
        press()
        move(y = -px(72))
        move(x = px(80))
        move(x = -px(80))
        lift()

        assertEquals(1f, player.playbackSpeed, 0f)
        compose.onNodeWithText("2x").assertDoesNotExist()
    }

    @Test
    fun `the slider opens next to the finger, on the side with more room`() {
        open()
        val bounds = page.fetchSemanticsNode().boundsInRoot

        press { width, height -> Offset(width * 0.8f, height * 0.4f) }
        val left = compose.onNodeWithText("1x", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$left", left.right < bounds.left + bounds.width * 0.8f)
        assertEquals(bounds.top + bounds.height * 0.4f, left.center.y, px(20))
        lift()

        press { width, height -> Offset(width * 0.2f, height * 0.6f) }
        val right = compose.onNodeWithText("1x", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$right", right.left > bounds.left + bounds.width * 0.2f)
        assertEquals(bounds.top + bounds.height * 0.6f, right.center.y, px(20))
        lift()
    }

    @Test
    fun `locking normal speed is no lock, and shows no badge`() {
        open()
        press()
        move(x = px(80))
        lift()

        assertEquals(1f, player.playbackSpeed, 0f)
        compose.onNodeWithText("1x").assertDoesNotExist()
    }

    @Test
    fun `a new hold on a locked speed starts from it and goes back to it`() {
        open()
        press()
        move(y = -px(72))
        move(x = px(80))
        lift()

        press()
        move(y = -px(72))
        assertEquals(3f, player.playbackSpeed, 0f)
        lift()

        assertEquals(2f, player.playbackSpeed, 0f)
        compose.onNodeWithText("2x").assertExists()
    }

    @Test
    fun `the next video plays at normal speed`() {
        open()
        press()
        move(y = -px(72))
        move(x = px(80))
        lift()

        page.performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("/sdcard/Movies/two.mp4"), player.paths)
        assertEquals(1f, player.playbackSpeed, 0f)
        compose.onNodeWithText("2x").assertDoesNotExist()
    }

    @Test
    fun `a drag down while holding neither hides the bar nor closes the view`() {
        open()
        compose.mainClock.advanceTimeBy(1_500) // the playing video hid the bar, so a swipe down would close the view
        compose.onNodeWithContentDescription("Position in the video").assertDoesNotExist()

        repeat(2) {
            press()
            move(y = px(200))
            lift()
        }

        assertOpen()
        assertTrue(player.playing)
    }

    @Test
    fun `a quick tap still pauses and a quick swipe down still closes the view`() {
        open()
        page.performTouchInput { click() }
        compose.waitForIdle()
        assertFalse(player.playing)
        dial.assertDoesNotExist()

        page.performTouchInput { swipeDown() }
        page.performTouchInput { swipeDown() }
        compose.waitForIdle()
        page.assertDoesNotExist()
    }

    @Test
    fun `a hold on a paused video opens nothing and keeps the speed`() {
        open()
        page.performTouchInput { click() }
        compose.waitForIdle()
        assertFalse(player.playing)

        press()
        dial.assertDoesNotExist()
        move(y = -px(72))
        lift()

        assertEquals(1f, player.playbackSpeed, 0f)
    }

    @Test
    fun `a hold on a video that cannot be read opens nothing`() {
        open(clip("broken.mp4", unreadable = true))

        press()

        dial.assertDoesNotExist()
    }

    @Test
    fun `with a screen reader on, a hold opens nothing, and the bar's buttons step the speed`() {
        open(touchExploration = true)
        press()
        dial.assertDoesNotExist()
        lift()

        compose.onNodeWithText("Speed 1x").assertExists()
        compose.onNodeWithContentDescription("Faster").performClick()
        assertEquals(1.25f, player.playbackSpeed, 0f)
        compose.onNodeWithText("Speed 1.25x").assertExists()
        compose.onNodeWithContentDescription("Slower").performClick()
        compose.onNodeWithContentDescription("Slower").performClick()
        assertEquals(0.75f, player.playbackSpeed, 0f)
    }

    @Test
    fun `without a screen reader the bar has no speed buttons`() {
        open()

        compose.onNodeWithContentDescription("Faster").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w340dp-h640dp")
    fun `the badge of a locked speed is a 48 dp target clear of the close, share and checkbox buttons at a large font`() {
        RuntimeEnvironment.setFontScale(1.3f)
        open()
        press()
        move(y = -px(72))
        move(x = px(80))
        lift()

        val badge = compose.onNodeWithText("2x").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("$badge", badge.width >= px(48) - 1 && badge.height >= px(48) - 1)
        for (other in listOf(compose.onNodeWithContentDescription("Close"), compose.onNodeWithContentDescription("Share"), compose.onNodeWithText("Selected"))) {
            val bounds: Rect = other.fetchSemanticsNode().touchBoundsInRoot
            assertFalse("$badge overlaps $bounds", badge.overlaps(bounds))
        }
    }
}
