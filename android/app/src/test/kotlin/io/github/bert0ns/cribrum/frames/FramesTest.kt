package io.github.bert0ns.cribrum.frames

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/**
 * The times both sides sample, and what the app does with the retriever's frames. Robolectric decodes no video, so the
 * shadow serves the frames; VideoFramesDeviceTest compares the phone's frames with the lab's.
 */
@RunWith(RobolectricTestRunner::class)
class FramesTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun reset() = ShadowMediaMetadataRetriever.reset()

    @Test
    fun `five frames at the middle of five slices away from the ends`() {
        // 10 s: half a second off each end leaves 9 s, cut in five slices of 1.8 s.
        assertEquals(listOf(1400L, 3200, 5000, 6800, 8600), frameTimes(10_000))
    }

    @Test
    fun `a clip shorter than two seconds keeps a quarter off each end`() {
        assertEquals(listOf(300L, 400, 500, 600, 700), frameTimes(1000))
    }

    @Test
    fun `a clip too short for five distinct times gets fewer`() {
        assertEquals(listOf(0L, 1, 2), frameTimes(3))
    }

    @Test
    fun `a video with no duration gets its first frame`() {
        assertEquals(listOf(0L), frameTimes(null))
    }

    private fun video(frames: Map<Long, Int>, transfer: Int? = null) = folder.newFile("clip.mp4").also { file ->
        val source = DataSource.toDataSource(file.path)
        transfer?.let { ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER, "$it") }
        for ((timeUs, color) in frames) {
            ShadowMediaMetadataRetriever.addFrame(source, timeUs, Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }
    }

    @Test
    fun `hands over the pixels of the frame at each time, in microseconds`() {
        val file = video(mapOf(300_000L to Color.RED, 700_000L to Color.BLUE))

        val frames = readFrames(file, listOf(300, 700)) { Triple(it.width, it.height, it.pixels.distinct()) }

        assertEquals(mapOf(300L to Triple(4, 3, listOf(Color.RED)), 700L to Triple(4, 3, listOf(Color.BLUE))), frames)
    }

    @Test
    fun `an SDR video keeps a frame for each time, even one like the frame before`() {
        val file = video(mapOf(300_000L to Color.RED, 400_000L to Color.RED))

        assertEquals(listOf(300L, 400L), readFrames(file, listOf(300, 400)) { it }.keys.toList())
    }

    @Test
    fun `an HDR video gives a keyframe once, under the first time that took it`() {
        // The shadow serves a frame by its time whatever the option, so the keyframe of 300 and 400 ms is a frame at each.
        val file = video(mapOf(300_000L to Color.RED, 400_000L to Color.RED, 700_000L to Color.BLUE), transfer = MediaFormat.COLOR_TRANSFER_HLG)

        val frames = readFrames(file, listOf(300, 400, 700)) { it.pixels.distinct() }

        assertEquals(mapOf(300L to listOf(Color.RED), 700L to listOf(Color.BLUE)), frames)
    }

    @Test
    fun `a time the retriever gives no frame for fails the video`() {
        val file = video(mapOf(300_000L to Color.RED))

        assertThrows(UnreadableVideo::class.java) { readFrames(file, listOf(300, 700)) { it } }
    }

    @Test
    fun `a frame a decoder handed over empty fails the video`() {
        // YUV of zeros, as BT.601 turns it into RGB, and a buffer of zeros.
        for (blank in listOf(Color.rgb(0, 135, 0), Color.TRANSPARENT)) {
            val file = video(mapOf(300_000L to blank))

            assertThrows(UnreadableVideo::class.java) { readFrames(file, listOf(300)) { it } }
            file.delete()
            ShadowMediaMetadataRetriever.reset()
        }
    }

    @Test
    fun `a black frame is a frame`() {
        val file = video(mapOf(300_000L to Color.BLACK))

        assertEquals(listOf(300L), readFrames(file, listOf(300)) { it }.keys.toList())
    }
}
