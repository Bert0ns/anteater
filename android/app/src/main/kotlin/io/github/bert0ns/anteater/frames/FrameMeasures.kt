// What the detectors that read frames keep of a video's sampled frame (docs/design.md §5). Mirrors
// lab/src/anteater_lab/frame_measures.py.
package io.github.bert0ns.anteater.frames

import io.github.bert0ns.anteater.detectors.BlurDetector
import io.github.bert0ns.anteater.detectors.ExposureDetector
import io.github.bert0ns.anteater.detectors.YuNet
import io.github.bert0ns.anteater.detectors.defaultRuntime
import io.github.bert0ns.anteater.imaging.Argb
import io.github.bert0ns.anteater.imaging.thumbnail
import io.github.bert0ns.anteater.imaging.turnedLuminance
import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.runtime.ModelRuntime

/**
 * The measures of an upright [frame], each as the detector of an image takes it on the image's own pixels: the grayscale is a
 * Lanczos thumbnail, and the faces a letterbox. The frame is measured while it is decoded for its embedding and then dropped, so
 * a video's BLURRY, BAD_EXPOSURE and FACES signals come from these numbers and not from pixels.
 */
internal fun measureFrame(frame: Argb, runtime: ModelRuntime = defaultRuntime): FrameMeasures {
    val luminance = turnedLuminance(frame, 1)
    val peakVariance = BlurDetector.measure(thumbnail(luminance, BlurDetector.MAX_SIDE))?.second ?: 0.0
    val (p1, p5, p95, p99) = ExposureDetector.percentiles(thumbnail(luminance, ExposureDetector.MAX_SIDE).pixels, ExposureDetector.PERCENTILES)
    val boxed = YuNet.letterbox(frame, 1)
    val faces = YuNet.detect(runtime, boxed.pixels)
    return FrameMeasures(
        peakVariance = peakVariance,
        p1 = p1,
        p5 = p5,
        p95 = p95,
        p99 = p99,
        faces = faces.size,
        faceConfidence = faces.maxOfOrNull { it.confidence.toDouble() } ?: 0.0,
        faceArea = (faces.maxOfOrNull { it.width * it.height } ?: 0.0) / (boxed.width.toDouble() * boxed.height),
    )
}
