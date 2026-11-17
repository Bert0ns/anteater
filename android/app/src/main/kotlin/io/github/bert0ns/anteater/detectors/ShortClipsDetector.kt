// SHORT_CLIPS: short videos recorded by mistake or beside a photo (docs/design.md §5). Mirrors
// lab/src/anteater_lab/detectors/short_clips.py.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.folders.isMessagingFolder
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ROLE_KEY
import io.github.bert0ns.anteater.model.Role
import io.github.bert0ns.anteater.model.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.min

/** A video this long or longer gets no signal. On the 49 video labels of #390, 23 of the 26 labeled videos up to 10 s were deleted, and 7 of the 16 between 10 s and 30 s. */
const val SHORT_CLIP_MAX_MS = 15000

/** The length term is at its full weight up to here, and fades to 0 at [SHORT_CLIP_MAX_MS]. */
const val FULL_DURATION_MS = 5000

/** A clip up to [FULL_DURATION_MS] scores this alone, above the default threshold, so a short clip is found with no other evidence. The slider can go past it to ask for some. */
const val DURATION_WEIGHT = 0.4f

/** A camera photo this close in time to the clip, either side, reads as the photo the clip was meant to be. */
const val PHOTO_WINDOW_MS = 60000

/** The closest frame of the clip and the photo must be this alike, when both have an embedding. The 99th percentile of the cosine between a frame and a photo that are not related is 0.62. */
const val PHOTO_MIN_COSINE = 0.6f
const val PHOTO_WEIGHT = 0.5f

/** A failed take: `TAKES` found a longer take of the same moment after it. */
const val TAKE_WEIGHT = 0.6f
const val MISTAKE_WEIGHT = 0.8f

/** Dark, bright, flat or blurred on most frames, from `BAD_EXPOSURE` and `BLURRY` on a video. Under this score the frames say nothing: a camera clip's median blur score is about 0.1. */
const val MISTAKE_MIN_SCORE = 0.15f
const val GIF_WEIGHT = 0.6f

/** The short side of a GIF-style clip at most. */
const val GIF_MAX_SIDE = 480

/**
 * A video shorter than [SHORT_CLIP_MAX_MS] gets a graded score from independent pieces of evidence, combined as chances (noisy
 * OR) like SCREENSHOT: its length, a camera photo taken beside it, a failed take, a dark or blurred video and a GIF-style clip.
 * Past the longest length it gets no signal, whatever else it has. Faces and favourites protect it in the ranker, so they are not
 * read here.
 */
class ShortClipsDetector : GroupDetector {
    override val id = "short_clips"

    override val version = 1
    override val category = Category.SHORT_CLIPS
    override val mediaTypes = setOf(MediaType.VIDEO)

    /**
     * What it reads besides its videos: the versions of TAKES, BLURRY and BAD_EXPOSURE, whose results on videos it takes, and the counts of
     * the measures and camera photos.
     */
    override suspend fun inputsKey(context: AnalysisContext): String = "${TakesDetector().version}:${BlurDetector().version}:${ExposureDetector().version}:${context.storedInputs()}"

    override suspend fun analyze(images: List<MediaRef>, context: AnalysisContext): Map<MediaRef, List<Signal>> {
        val failed = TakesDetector().analyze(images, context).filterValues { it.single().evidence[ROLE_KEY] == Role.SHORT_TAKE.value }.keys
        val short = LinkedHashMap<MediaRef, VideoMetadata>()
        for (video in images) {
            val meta = metadata(video, context) ?: continue
            val duration = meta.durationMs ?: continue
            if (duration in 1 until SHORT_CLIP_MAX_MS) short[video] = meta
        }
        val photos = context.photos(short.values.mapNotNull { it.capturedAt }, PHOTO_WINDOW_MS).sortedBy { it.capturedAt }
        val stamps = photos.map { it.capturedAt }
        val result = LinkedHashMap<MediaRef, List<Signal>>()
        for ((video, meta) in short) {
            currentCoroutineContext().ensureActive()
            val duration = meta.durationMs ?: continue
            val chances = linkedMapOf("duration" to DURATION_WEIGHT * fade(duration))
            val evidence = linkedMapOf("duration_ms" to duration.toString())
            if (photoBeside(video, meta, photos, stamps, context)) chances["photo"] = PHOTO_WEIGHT.toDouble()
            if (video in failed) chances["failed_take"] = TAKE_WEIGHT.toDouble()
            mistake(video, context)?.let { (name, score) ->
                chances["mistake"] = MISTAKE_WEIGHT * score
                evidence["mistake"] = name
            }
            if (isGif(video, meta)) chances["gif"] = GIF_WEIGHT.toDouble()
            var miss = 1.0
            for (chance in chances.values) miss *= 1.0 - chance
            // What each piece added, which the review's reason line reads.
            for ((name, chance) in chances) evidence["${name}_chance"] = "%.3f".format(Locale.ROOT, chance)
            result[video] = listOf(Signal(id, version, Category.SHORT_CLIPS, (1.0 - miss).toFloat(), evidence))
        }
        return result
    }

    /** 1 up to [FULL_DURATION_MS], 0 at [SHORT_CLIP_MAX_MS], linear in between. */
    private fun fade(durationMs: Long): Double = min(1.0, (SHORT_CLIP_MAX_MS - durationMs).toDouble() / (SHORT_CLIP_MAX_MS - FULL_DURATION_MS))

    /** Recall first: without an embedding of the photo or frames of the clip, time alone decides. */
    private suspend fun photoBeside(video: MediaRef, meta: VideoMetadata, photos: List<Photo>, stamps: List<LocalDateTime>, context: AnalysisContext): Boolean {
        val capturedAt = meta.capturedAt ?: return false
        val window = Duration.ofMillis(PHOTO_WINDOW_MS.toLong())
        val frames = context.frames(video)
        // The photos are sorted by time, so the window is found by bisection.
        return photos.subList(firstAtOrAfter(stamps, capturedAt - window), firstAfter(stamps, capturedAt + window)).any { photo ->
            photo.embedding == null || frames.isEmpty() || frames.maxOf { dot(photo.embedding, it) } >= PHOTO_MIN_COSINE
        }
    }

    /** What is wrong with most of the frames, and how much, from the video's own BAD_EXPOSURE and BLURRY signals, which read the median frame. */
    private suspend fun mistake(video: MediaRef, context: AnalysisContext): Pair<String, Double>? {
        val found = (ExposureDetector().analyze(video, context) + BlurDetector().analyze(video, context)).map { (it.evidence["case"] ?: "blurry") to it.score.toDouble() }
        val best = found.maxByOrNull { it.second } ?: return null
        return if (best.second >= MISTAKE_MIN_SCORE) best else null
    }

    private fun isGif(video: MediaRef, meta: VideoMetadata): Boolean = !meta.hasAudio && min(meta.width, meta.height) <= GIF_MAX_SIDE && isMessagingFolder(video.path)

    private suspend fun metadata(video: MediaRef, context: AnalysisContext): VideoMetadata? = try {
        context.videoMetadata(video)
    } catch (error: Exception) { // unreadable header: no length to judge
        if (error is CancellationException) throw error
        null
    }
}
