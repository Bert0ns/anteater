// BAD_EXPOSURE: pocket shots, black frames, blown-out and flat frames (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/exposure.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.FrameMeasures
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.Signal
import java.util.Locale

/**
 * Each case looks at the opposite end of the histogram too, so a night shot with a lit subject is
 * not "dark" and a page of text is not "bright". All thresholds are starting points, to be tuned
 * for recall in the lab.
 */
class ExposureDetector : Detector {
    override val id = "exposure"
    override val version = 1
    override val category = Category.BAD_EXPOSURE
    override val decodeSide = MAX_SIDE
    override val mediaTypes = setOf(MediaType.IMAGE, MediaType.VIDEO)

    /** Of a video. */
    override val usesFrames = true

    override suspend fun analyze(image: MediaRef, context: AnalysisContext): List<Signal> {
        if (image.mediaType == MediaType.VIDEO) return video(context.frameMeasures(image))
        val (p1, p5, p95, p99) = percentiles(context.pixels(image).gray(MAX_SIDE).pixels, PERCENTILES)
        return listOf(signal(cases(p1, p5, p95, p99), emptyMap()))
    }

    /**
     * Each case scores as the median frame's, so a video is dark when most of its frames are, and the strongest case names it
     * (docs/design.md §5).
     */
    private fun video(frames: List<FrameMeasures>): List<Signal> {
        if (frames.isEmpty()) return emptyList()
        val perFrame = frames.map { cases(it.p1, it.p5, it.p95, it.p99) }
        val typical = linkedMapOf<String, Double>()
        for (name in perFrame.first().keys) typical[name] = median(perFrame.map { it.getValue(name) })
        return listOf(signal(typical, mapOf("frames" to frames.size.toString())))
    }

    private fun signal(scores: Map<String, Double>, extra: Map<String, String>): Signal {
        // The strongest case, whether or not it is bad enough: the ranker decides that.
        val case = scores.maxBy { it.value }.key
        val evidence = mapOf("case" to case) +
            scores.map { (name, value) -> "${name}_score" to "%.3f".format(Locale.ROOT, value) } + extra
        return Signal(id, version, Category.BAD_EXPOSURE, scores.getValue(case).toFloat(), evidence)
    }

    companion object {
        const val MAX_SIDE = 256

        /** Dark scores 1 when 95% of the pixels are at or below this luminance. */
        const val DARK_LEVEL = 30.0

        /** Bright scores 1 when 95% of the pixels are at or above this luminance. */
        const val BRIGHT_LEVEL = 235.0

        /** Luminance distance over which each score fades from 1 to 0. */
        const val RAMP = 64.0

        /** The luminance percentiles the cases read, which a video's frames keep ([FrameMeasures]). */
        val PERCENTILES = listOf(1.0, 5.0, 95.0, 99.0)

        private fun cases(p1: Double, p5: Double, p95: Double, p99: Double): Map<String, Double> = linkedMapOf(
            "dark" to fade(p95 - DARK_LEVEL),
            "bright" to fade(BRIGHT_LEVEL - p5),
            "flat" to fade(p99 - p1),
        )

        /**
         * numpy's default percentiles of [pixels]: linear between the two nearest ranks. Every pixel is a whole
         * level from 0 to 255, since loadGrayscale rounds them, so a count of each level stands in for the sort.
         */
        internal fun percentiles(pixels: FloatArray, qs: List<Double>): List<Double> {
            val counts = IntArray(256)
            for (pixel in pixels) {
                val level = pixel.toInt()
                check(level.toFloat() == pixel && level in counts.indices) { "$pixel is not a whole level from 0 to 255" }
                counts[level]++
            }
            // The pixel at a rank of the sorted pixels: the first level whose running count passes the rank.
            fun sorted(rank: Int): Float {
                var seen = 0
                for (level in counts.indices) {
                    seen += counts[level]
                    if (rank < seen) return level.toFloat()
                }
                throw IndexOutOfBoundsException("rank $rank of ${pixels.size} pixels")
            }
            return qs.map { q ->
                val rank = q / 100 * (pixels.size - 1)
                val low = rank.toInt()
                val high = minOf(low + 1, pixels.size - 1)
                sorted(low) + (sorted(high) - sorted(low)) * (rank - low)
            }
        }

        /** 1 at distance 0 or less, 0 at RAMP or more, linear in between. */
        private fun fade(distance: Double): Double = (1.0 - distance / RAMP).coerceIn(0.0, 1.0)
    }
}
