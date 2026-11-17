// The measures of a frame, against the detectors of an image on the same pixels: a video of one frame must score as the image does.
package io.github.bert0ns.cribrum.frames

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import io.github.bert0ns.cribrum.detectors.FaceDetector
import io.github.bert0ns.cribrum.golden.Golden
import io.github.bert0ns.cribrum.imaging.Pixels
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameMeasuresTest {
    /** The golden images that are upright and small enough for a decode at full size, as a frame is. */
    private val frames = Golden.images.filter { Pixels(File(it.image.path)).orientation <= 1 }

    @Test
    fun `a video of one frame scores as the image of that frame`() = runBlocking {
        assertTrue(frames.isNotEmpty())
        for (expected in frames) {
            val file = File(expected.image.path)
            val frame = io.github.bert0ns.cribrum.imaging.decode(file, 512)
            val measures = measureFrame(frame)
            val video = MediaRef(expected.image.path, 0, 0, MediaType.VIDEO)
            val asVideo = AnalysisContext(frameMeasures = { listOf(measures) })
            val asImage = AnalysisContext()

            for (detector in listOf(BlurDetector(), ExposureDetector(), FaceDetector())) {
                val want = detector.analyze(expected.image, asImage).single()
                val got = detector.analyze(video, asVideo).single()
                assertEquals("${expected.relative} ${detector.id}", want.score, got.score, 1e-6f)
                if (detector.id == "exposure") assertEquals(expected.relative, want.evidence["case"], got.evidence["case"])
            }
        }
    }
}
