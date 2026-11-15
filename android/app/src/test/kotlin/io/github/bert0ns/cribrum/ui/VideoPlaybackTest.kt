package io.github.bert0ns.cribrum.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoPlaybackTest {
    private val players = mutableListOf<FakePlayer>()
    private val playback = VideoPlayback { FakePlayer().also(players::add) }
    private val one = File("/sdcard/Movies/one.mp4")
    private val two = File("/sdcard/Movies/two.mp4")

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `nothing plays, and no player exists, until a video is asked for`() {
        assertTrue(players.isEmpty())
        assertNull(playback.loaded)
        assertFalse(playback.playing)
    }

    @Test
    fun `playing a video loads it, starts it at its first frame and reads its length`() {
        playback.play(one)

        assertEquals(one.path, playback.loaded)
        assertEquals(listOf(one.path), players.single().paths)
        assertTrue(playback.playing)
        assertEquals(61_000, playback.durationMs)
        assertEquals(1920, playback.videoSize?.width)
    }

    @Test
    fun `the player never plays two videos, nor is a second one made`() {
        playback.play(one)
        playback.play(two)

        assertEquals(1, players.size)
        assertEquals(listOf(two.path), players.single().paths)
        assertEquals(two.path, playback.loaded)
    }

    @Test
    fun `a video that is not loaded starts where it was sent, and one that is loaded moves there`() {
        playback.seekTo(one, 30_500)
        assertEquals(30_500, players.single().positionMs)
        assertTrue(playback.playing)

        playback.seekTo(one, 6_100)
        assertEquals(6_100, players.single().positionMs)
        assertEquals(1, players.size)
    }

    @Test
    fun `toggle pauses a video that plays, plays it again, and starts one that is not loaded`() {
        playback.toggle(one)
        assertTrue(playback.playing)

        playback.toggle(one)
        assertFalse(playback.playing)
        assertEquals(one.path, playback.loaded) // paused, not dropped

        playback.toggle(one)
        assertTrue(playback.playing)

        playback.toggle(two)
        assertEquals(two.path, playback.loaded)
    }

    @Test
    fun `stop lets go of the video and keeps the player for the next one`() {
        playback.play(one)

        playback.stop()

        assertNull(playback.loaded)
        assertFalse(playback.playing)
        assertEquals(emptyList<String>(), players.single().paths)
        assertFalse(players.single().released)
        playback.play(two)
        assertEquals(1, players.size)
    }

    @Test
    fun `release lets go of the player, and a later play makes a new one`() {
        playback.play(one)

        playback.release()

        assertTrue(players.single().released)
        assertNull(playback.loaded)
        playback.play(two)
        assertEquals(2, players.size)
        assertFalse(players.last().released)
    }

    @Test
    fun `releasing before anything played does nothing`() {
        playback.release()

        assertTrue(players.isEmpty())
    }

    @Test
    fun `pause stops the sound and keeps the place`() {
        playback.play(one, 5_000)

        playback.pause()
        idle()

        assertFalse(players.single().playing)
        assertEquals(5_000, players.single().positionMs)
        assertEquals(one.path, playback.loaded)
    }

    @Test
    fun `a video that fails to play is dropped and named, and the next play tries again`() {
        playback.play(one)

        players.single().fail()
        idle()

        assertNull(playback.loaded)
        assertEquals(one.path, playback.failed)
        playback.play(one)
        assertNull(playback.failed)
        assertEquals(one.path, playback.loaded)
    }

    @Test
    fun `mute silences the player, and a player made later starts silent too`() {
        playback.play(one)

        playback.toggleMute()
        assertEquals(0f, players.single().volume, 0f)
        assertTrue(playback.muted)

        playback.release()
        playback.play(two)
        assertEquals(0f, players.last().volume, 0f)

        playback.toggleMute()
        assertEquals(1f, players.last().volume, 0f)
    }

    @Test
    fun `the strip's frames are the middles of five equal parts of the video`() {
        assertEquals(listOf(6_100L, 18_300L, 30_500L, 42_700L, 54_900L), stripTimes(61_000))
    }
}
