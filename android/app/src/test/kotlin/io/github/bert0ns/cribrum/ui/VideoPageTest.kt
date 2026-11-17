package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
