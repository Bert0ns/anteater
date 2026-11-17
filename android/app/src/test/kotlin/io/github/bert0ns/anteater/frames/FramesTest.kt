package io.github.bert0ns.anteater.frames

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import io.github.bert0ns.anteater.metadata.UnreadableVideo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import kotlin.math.abs

/**
 * The times both sides sample, and what the app does with the retriever's frames. Robolectric decodes no video, so the
 * shadows serve the frames and the keyframe times; VideoFramesDeviceTest compares the phone's frames with the lab's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [OptionRecordingRetriever::class, KeyframeExtractor::class])
class FramesTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun reset() {
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaExtractor.reset()
        OptionRecordingRetriever.options.clear()
        KeyframeExtractor.keyframesUs = emptyList()
        KeyframeExtractor.modes.clear()
    }

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
        transfer?.let {
            ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER, "$it")
            ShadowMediaExtractor.addTrack(source, MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 4, 3), ByteArray(0))
        }
        for ((timeUs, color) in frames) {
            ShadowMediaMetadataRetriever.addFrame(source, timeUs, Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }
    }

    @Test
    fun `hands over the pixels of the exact frame at each time, in microseconds`() {
        val file = video(mapOf(300_000L to Color.RED, 700_000L to Color.BLUE))

        val frames = readFrames(file, listOf(300, 700)) { Triple(it.width, it.height, it.pixels.distinct()) }

        assertEquals(mapOf(300L to Triple(4, 3, listOf(Color.RED)), 700L to Triple(4, 3, listOf(Color.BLUE))), frames)
        assertEquals(listOf(MediaMetadataRetriever.OPTION_CLOSEST, MediaMetadataRetriever.OPTION_CLOSEST), OptionRecordingRetriever.options)
    }

    @Test
    fun `an SDR video keeps a frame for each time, even one like the frame before`() {
        val file = video(mapOf(300_000L to Color.RED, 400_000L to Color.RED))

        assertEquals(listOf(300L, 400L), readFrames(file, listOf(300, 400)) { it }.keys.toList())
    }

    @Test
    fun `an HDR video takes the keyframe the extractor finds for each time, once, under the first time`() {
        // A static clip: both keyframes look the same, and are still two frames, as in the lab.
        KeyframeExtractor.keyframesUs = listOf(0, 1_000_000)
        val file = video(mapOf(0L to Color.GRAY, 1_000_000L to Color.GRAY), transfer = MediaFormat.COLOR_TRANSFER_HLG)

        val frames = readFrames(file, listOf(300, 400, 700)) { it.pixels.distinct() }

        assertEquals(mapOf(300L to listOf(Color.GRAY), 700L to listOf(Color.GRAY)), frames)
        assertEquals(List(3) { MediaExtractor.SEEK_TO_CLOSEST_SYNC }, KeyframeExtractor.modes)
        assertEquals(List(2) { MediaMetadataRetriever.OPTION_CLOSEST_SYNC }, OptionRecordingRetriever.options)
    }

    @Test
    fun `a time the retriever gives no frame for fails the video`() {
        val file = video(mapOf(300_000L to Color.RED))

        assertThrows(UnreadableVideo::class.java) { readFrames(file, listOf(300, 700)) { it } }
    }

    @Test
    fun `a frame a decoder handed over empty fails the video`() {
        // YUV of zeros through BT.709 and BT.601, and a buffer of zeros.
        for (blank in listOf(Color.rgb(0, 77, 0), Color.rgb(0, 135, 0), Color.TRANSPARENT)) {
            val file = video(mapOf(300_000L to blank))

            assertThrows(UnreadableVideo::class.java) { readFrames(file, listOf(300)) { it } }
            file.delete()
            ShadowMediaMetadataRetriever.reset()
        }
    }

    @Test
    fun `a black or a bright green frame is a frame`() {
        for (color in listOf(Color.BLACK, Color.rgb(0, 255, 0))) {
            val file = video(mapOf(300_000L to color))

            assertEquals(listOf(300L), readFrames(file, listOf(300)) { it }.keys.toList())
            file.delete()
            ShadowMediaMetadataRetriever.reset()
        }
    }
}

/** The shadow retriever, which serves a frame by its time whatever the option, recording each option asked. */
@Implements(MediaMetadataRetriever::class)
class OptionRecordingRetriever : ShadowMediaMetadataRetriever() {
    @Implementation
    override fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? {
        options += option
        return super.getFrameAtTime(timeUs, option)
    }

    companion object {
        val options = mutableListOf<Int>()
    }
}

/** The shadow extractor with keyframes: a seek lands on the nearest one, a tie going to the later, as AOSP's MP4 one does. */
@Implements(MediaExtractor::class)
class KeyframeExtractor : ShadowMediaExtractor() {
    private var sampleUs = -1L

    @Implementation
    fun seekTo(timeUs: Long, mode: Int) {
        modes += mode
        sampleUs = keyframesUs.minWith(compareBy<Long> { abs(it - timeUs) }.thenByDescending { it })
    }

    @Implementation
    fun getSampleTime(): Long = sampleUs

    companion object {
        var keyframesUs = emptyList<Long>()
        val modes = mutableListOf<Int>()
    }
}
