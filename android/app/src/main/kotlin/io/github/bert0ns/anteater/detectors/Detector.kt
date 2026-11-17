// Detector interfaces. Every analysis technique implements one of them. Mirrors
// lab/src/anteater_lab/detectors/base.py and docs/design.md §7.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.imaging.Pixels
import io.github.bert0ns.anteater.metadata.ImageMetadata
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.metadata.readMetadata
import io.github.bert0ns.anteater.metadata.readVideoMetadata
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.Signal
import java.io.File
import java.time.LocalDateTime
import io.github.bert0ns.anteater.hashing.contentHash as hashFile
import io.github.bert0ns.anteater.hashing.perceptualHash as hashPerceptual

/**
 * Shared, read-only information available to detectors.
 *
 * Per-image lookups compute their value from the file by default; the pipeline replaces them
 * with lookups backed by the index, so each value is computed once. Grows as detectors need it.
 */
data class AnalysisContext(
    val contentHash: suspend (MediaRef) -> String = { hashFile(it) },
    val perceptualHash: suspend (MediaRef) -> ULong = { hashPerceptual(it) },
    /** L2-normalized MobileCLIP2-S0 embedding, 512 floats. Throws [MissingEmbedding] when the image has none. */
    val embedding: suspend (MediaRef) -> FloatArray = { throw MissingEmbedding(it) },
    /** Throws [io.github.bert0ns.anteater.metadata.UnreadableImage] when the header can't be decoded. */
    val metadata: suspend (MediaRef) -> ImageMetadata = { readMetadata(File(it.path)) },
    /** The BLURRY measure of the image, the Laplacian variance of its sharpest region: 0 when it can't be decoded. */
    val sharpness: suspend (MediaRef) -> Double = { BlurDetector.peakVariance(it) },
    /**
     * The image's decoded pixels. The pipeline gives the detectors of one image the same [Pixels], so they
     * share its decodes, and drops it with the image. By default each call decodes anew.
     */
    val pixels: (MediaRef) -> Pixels = { Pixels(File(it.path)) },
    /** Throws [io.github.bert0ns.anteater.metadata.UnreadableVideo] when the header can't be read. */
    val videoMetadata: suspend (MediaRef) -> VideoMetadata = { readVideoMetadata(File(it.path)) },
    /** The L2-normalized embeddings of the video's sampled frames, in the order of their times: none when it has no frames stored. */
    val frames: suspend (MediaRef) -> List<FloatArray> = { emptyList() },
    /** What [io.github.bert0ns.anteater.frames.measureFrame] kept of the video's sampled frames, in the order of their times: none when it has no frames stored. */
    val frameMeasures: suspend (MediaRef) -> List<FrameMeasures> = { emptyList() },
    /**
     * The camera photos (a photo with a capture time in a camera folder, screenshots left out) taken within the given milliseconds of any of
     * the times, with their embeddings when the run has a model: none when it has none to give. It loads only those, not every photo.
     */
    val photos: suspend (List<LocalDateTime>, Int) -> List<Photo> = { _, _ -> emptyList() },
    /** Counts of the frame measures and the camera photos a group detector that reads them sees, which change when they do. Cheap queries: a detector that is up to date loads nothing. */
    val storedInputs: suspend () -> String = { "" },
    /** Known screen resolutions as (short side, long side). */
    val screenSizes: Set<Pair<Int, Int>> = SCREEN_SIZES,
)

/** A camera photo, as a video detector that looks for a photo beside a clip sees it. */
data class Photo(
    /** Local time with no zone, as [VideoMetadata.capturedAt] is, so the two compare as wall clocks. */
    val capturedAt: LocalDateTime,
    /** L2-normalized MobileCLIP embedding, null when the photo has none stored. */
    val embedding: FloatArray?,
)

/**
 * The run has no embedding for this image: no model, or it could not decode the image. A
 * detector that needs one lets it propagate, and the pipeline runs it again on a later run.
 */
class MissingEmbedding(image: MediaRef) : NoSuchElementException(image.path)

/**
 * Analyzes one image and emits zero or more signals.
 *
 * Bump [version] whenever the output for the same input can change, so stored signals from older
 * versions get recomputed. Keep it equal to the lab's version of the same detector.
 */
interface Detector {
    val id: String
    val version: Int

    /** The category its signals are about, so the app can tell which categories it looks for. */
    val category: Category? get() = null

    /** True when [analyze] reads the embedding, so a new embedding means its stored result is stale. */
    val usesEmbeddings: Boolean get() = false

    /**
     * The draft side [analyze] decodes the image at through [AnalysisContext.pixels], or null when it decodes
     * nothing. The pipeline runs an image's detectors from the largest side down, so the ones whose sides give
     * one sample size run one after the other and share a decode.
     */
    val decodeSide: Int? get() = null

    /**
     * True when [analyze] reads a video's frames. The pipeline runs such a detector after the frames step and only on a video with
     * measures stored, and a new sampling of the frames makes its stored result stale.
     */
    val usesFrames: Boolean get() = false

    /** The media types the pipeline gives it, images only unless it says more. Keep it equal to the lab's `media_types`. */
    val mediaTypes: Set<MediaType> get() = IMAGES_ONLY

    suspend fun analyze(image: MediaRef, context: AnalysisContext): List<Signal>
}

val IMAGES_ONLY: Set<MediaType> = setOf(MediaType.IMAGE)

/**
 * Analyzes all images at once and emits signals for groups of them (duplicates, bursts).
 *
 * Every member of a group gets a signal, so the review screen can show the whole group. Its
 * evidence holds the group id under `GROUP_KEY` and the member's role under `ROLE_KEY`: the copy
 * to keep is `Role.BEST` with score 0, so no threshold ever suggests it; the others are
 * `Role.INFERIOR`. A group of takes has no copy to keep, and its members are `Role.TAKE` or
 * `Role.SHORT_TAKE`. Images in no group are left out of the result.
 */
interface GroupDetector {
    val id: String
    val version: Int

    /** As in [Detector]. */
    val category: Category? get() = null

    /** As in [Detector]. */
    val mediaTypes: Set<MediaType> get() = IMAGES_ONLY

    /**
     * What it reads besides the files it is given, such as the results of other detectors: the pipeline adds it to the detector's own
     * fingerprint, so only this detector runs again when it changes.
     */
    suspend fun inputsKey(context: AnalysisContext): String = ""

    suspend fun analyze(images: List<MediaRef>, context: AnalysisContext): Map<MediaRef, List<Signal>>
}

/** The median, the mean of the middle two for an even count, as Python's `statistics.median` takes it. */
internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
