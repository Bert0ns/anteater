package io.github.bert0ns.anteater.ui

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideoPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val file = File("/sdcard/Movies/clip.mp4")
    private val players = mutableListOf<FakePlayer>()
    private val playback = VideoPlayback(startMuted = true) { FakePlayer().also(players::add) }

    @Test
    fun `a page whose video is not known to be readable yet starts nothing, and starts it when it is`() {
        var autoplay by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalPageSettled provides true) { VideoPage(file, "Test.fullSize", playback, unreadable = false, onClose = {}, autoplay = autoplay) }
        }
        compose.waitForIdle()
        assertTrue(players.isEmpty())

        autoplay = true
        compose.waitForIdle()

        assertEquals(listOf(file.path), players.single().paths)
        assertTrue(players.single().playing)
    }

    @Test
    fun `a page the pager is not resting on starts nothing`() {
        compose.setContent {
            CompositionLocalProvider(LocalPageSettled provides false) { VideoPage(file, "Test.fullSize", playback, unreadable = false, onClose = {}) }
        }
        compose.waitForIdle()

        assertTrue(players.isEmpty())
    }

    @Test
    fun `the surface of a video that starts asks for another frame once it has its place, so its surface does not wait for the next redraw`() {
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalPageSettled provides true) { VideoPage(file, "Test.fullSize", playback, unreadable = false, onClose = {}) }
        }
        compose.waitForIdle()

        val surface = root.rootView.surfaceView()!!
        assertTrue("laid out", surface.width > 0)
        assertTrue("invalidated", shadowOf(surface).wasInvalidated())
    }

    @Test
    fun `a playing video's surface takes the video's shape from the player's event, with no position poll`() {
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalPageSettled provides true) { VideoPage(file, "Test.fullSize", playback, unreadable = false, onClose = {}) }
        }
        compose.waitForIdle()

        // The page has no controls, so nothing polls the player: the fake one reports 1920 x 1080 once it is ready.
        val surface = root.rootView.surfaceView()!!
        assertEquals(root.width, surface.width)
        assertEquals(root.width * 1080f / 1920, surface.height.toFloat(), 1f)
    }

    private fun View.surfaceView(): SurfaceView? = when (this) {
        is SurfaceView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).surfaceView() }
        else -> null
    }
}
