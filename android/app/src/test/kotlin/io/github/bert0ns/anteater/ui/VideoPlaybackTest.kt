package io.github.bert0ns.anteater.ui

import androidx.media3.common.C
import androidx.media3.common.Player
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
    fun `a video that has ended plays again from its start`() {
        playback.play(one, 30_000)

        players.single().ended()
        idle()
        assertFalse(playback.playing)
        assertEquals(61_000, playback.positionMs)

        playback.toggle(one)

        assertEquals(0, players.single().positionMs)
        assertTrue(playback.playing)
        assertEquals(1, players.size)
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
    fun `a muted video lets go of audio focus, so the user's music plays on, and unmuting takes it again`() {
        playback.play(one)
        assertTrue(players.single().focus)

        playback.toggleMute()
        assertFalse(players.single().focus)

        playback.release()
        playback.play(two)
        assertFalse(players.last().focus) // muted from its first frame: the music never pauses

        playback.toggleMute()
        assertTrue(players.last().focus)
    }

    @Test
    fun `a video of the full size view keeps its sound track and loops`() {
        playback.play(one)

        assertFalse(C.TRACK_TYPE_AUDIO in players.single().trackSelectionParameters.disabledTrackTypes)
        assertEquals(Player.REPEAT_MODE_ONE, players.single().repeatMode)
        assertTrue(players.single().repeating)

        playback.release()
        playback.play(two)

        assertEquals(Player.REPEAT_MODE_ONE, players.last().repeatMode)
        assertTrue(players.last().repeating)
    }

    @Test
    fun `a video plays at normal speed, unlocked, and a speed reaches the player within a quarter and four times`() {
        playback.play(one)
        assertEquals(1f, playback.speed, 0f)
        assertFalse(playback.speedLocked)
        assertEquals(1f, players.single().playbackSpeed, 0f)

        playback.setSpeed(1.5f)
        assertEquals(1.5f, players.single().playbackSpeed, 0f)
        playback.setSpeed(10f)
        assertEquals(4f, players.single().playbackSpeed, 0f)
        playback.setSpeed(0.1f)
        assertEquals(0.25f, playback.speed, 0f)
        assertEquals(0.25f, players.single().playbackSpeed, 0f)
    }

    @Test
    fun `a locked speed stays, locking at normal speed unlocks, and a reset goes back to normal and unlocks`() {
        playback.play(one)
        playback.setSpeed(2f)
        playback.lockSpeed()
        assertTrue(playback.speedLocked)

        playback.setSpeed(1f)
        playback.lockSpeed()
        assertFalse(playback.speedLocked)

        playback.setSpeed(0.5f)
        playback.lockSpeed()
        playback.resetSpeed()
        assertFalse(playback.speedLocked)
        assertEquals(1f, players.single().playbackSpeed, 0f)
    }

    @Test
    fun `another video, and a stop, start again at normal speed and unlocked`() {
        playback.play(one)
        playback.setSpeed(2f)
        playback.lockSpeed()
        playback.seekTo(one, 5_000) // the same video keeps its speed
        assertEquals(2f, players.single().playbackSpeed, 0f)

        playback.play(two)
        assertEquals(1f, playback.speed, 0f)
        assertFalse(playback.speedLocked)
        assertEquals(1f, players.single().playbackSpeed, 0f)

        playback.setSpeed(3f)
        playback.lockSpeed()
        playback.stop()
        assertEquals(1f, playback.speed, 0f)
        assertFalse(playback.speedLocked)
    }

    @Test
    fun `the screen reader's steps go through the usual speeds, lock them, and stop at the ends`() {
        playback.play(one)
        playback.stepSpeed(faster = true)
        assertEquals(1.25f, playback.speed, 0f)
        assertTrue(playback.speedLocked)

        repeat(10) { playback.stepSpeed(faster = true) }
        assertEquals(4f, playback.speed, 0f)
        repeat(20) { playback.stepSpeed(faster = false) }
        assertEquals(0.25f, playback.speed, 0f)
        repeat(3) { playback.stepSpeed(faster = true) }
        assertEquals(1f, playback.speed, 0f)
        assertFalse(playback.speedLocked)
    }

    @Test
    fun `a drag moves the speed on a log scale in steps of 0_05, a quarter to four times`() {
        assertEquals(2f, heldSpeed(1f, 100f, 100f), 0f)
        assertEquals(4f, heldSpeed(1f, 200f, 100f), 0f)
        assertEquals(4f, heldSpeed(1f, 900f, 100f), 0f)
        assertEquals(0.5f, heldSpeed(1f, -100f, 100f), 0f)
        assertEquals(0.25f, heldSpeed(1f, -900f, 100f), 0f)
        assertEquals(1.5f, heldSpeed(1f, 58.5f, 100f), 0f) // 2^0.585 = 1.4999
        assertEquals(1.05f, heldSpeed(1f, 7f, 100f), 0f)
        assertEquals(1f, heldSpeed(1f, 0f, 100f), 0f)
    }

    @Test
    fun `the strip's frames are the middles of five equal parts of the video`() {
        assertEquals(listOf(6_100L, 18_300L, 30_500L, 42_700L, 54_900L), stripTimes(61_000))
    }
}
