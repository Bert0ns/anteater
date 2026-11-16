package io.github.bert0ns.cribrum.frames

import android.graphics.Bitmap
import android.graphics.Color
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

    private fun video(frames: Map<Long, Int>) = folder.newFile("clip.mp4").also { file ->
        val source = DataSource.toDataSource(file.path)
        for ((timeUs, color) in frames) {
            ShadowMediaMetadataRetriever.addFrame(source, timeUs, Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
        }
    }

    @Test
    fun `hands over the pixels of the frame at each time, in microseconds`() {
        val file = video(mapOf(300_000L to Color.RED, 700_000L to Color.BLUE))

        val frames = readFrames(file, listOf(300, 700)) { Triple(it.width, it.height, it.pixels.distinct()) }

        assertEquals(listOf(Triple(4, 3, listOf(Color.RED)), Triple(4, 3, listOf(Color.BLUE))), frames)
    }

    @Test
    fun `a time the retriever gives no frame for fails the video`() {
        val file = video(mapOf(300_000L to Color.RED))

        assertThrows(UnreadableVideo::class.java) { readFrames(file, listOf(300, 700)) { it } }
    }
}
