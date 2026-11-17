// TAKES: camera videos of one moment, filmed one after the other (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/takes.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isCameraFolder
import io.github.bert0ns.cribrum.metadata.VideoMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.CancellationException
import java.io.File

/** Five minutes, from the end of a take to the start of the next. On the maintainer's phone it groups 95 camera videos in 34 groups, against 53 in 18 at one minute (#172). */
const val TAKES_MAX_GAP_MS = 300000

/**
 * Two takes whose closest frames are below this are different subjects. Recall first: the halves of one clip, the closest
 * thing to two takes of a moment, were all above it on the labeled videos.
 */
const val TAKES_MIN_COSINE = 0.50f

/** A take at most this share of the next take's length reads as a failed start. */
const val SHORT_TAKE_RATIO = 0.5f

/**
 * A video joins the takes of the one before it when it starts at most [TAKES_MAX_GAP_MS] after that one ends, by
 * `capturedInstant`, as long as the
 * two look alike when both have frames. A group of takes has no best copy: which take to keep is not obvious, so every
 * take is a candidate and the review preselects none.
 */
class TakesDetector : GroupDetector {
    override val id = "takes"
    override val version = 1
    override val category = Category.TAKES
    override val mediaTypes = setOf(MediaType.VIDEO)

    /** Start as epoch milliseconds, length in milliseconds when known, video. */
    private data class Take(val start: Long, val lengthMs: Long?, val video: MediaRef)

    override suspend fun analyze(images: List<MediaRef>, context: AnalysisContext): Map<MediaRef, List<Signal>> {
        val takes = mutableListOf<Take>()
        for (video in images) {
            if (!isCameraFolder(video.path) || isScreenRecording(video.path)) continue // a sent or saved copy is no take, whatever its time, nor a recording
            val meta = metadata(video, context) ?: continue
            takes += Take(meta.capturedInstant ?: continue, meta.durationMs, video)
        }
        takes.sortWith(compareBy({ it.start }, { it.video.path }))
        val result = LinkedHashMap<MediaRef, List<Signal>>()
        for (moment in moments(takes, context)) result.putAll(signals(moment))
        return result
    }

    private fun signals(moment: List<Take>): Map<MediaRef, List<Signal>> {
        val groupId = groupId(moment[0].video.path)
        return moment.withIndex().associate { (i, take) ->
            val length = take.lengthMs
            val following = moment.getOrNull(i + 1)?.lengthMs
            val short = length != null && following != null && length <= following * SHORT_TAKE_RATIO
            val evidence = mapOf(
                GROUP_KEY to groupId,
                ROLE_KEY to (if (short) Role.SHORT_TAKE else Role.TAKE).value,
                "takes" to moment.size.toString(),
            )
            take.video to listOf(Signal(id, version, Category.TAKES, 1f, evidence))
        }
    }

    private suspend fun moments(takes: List<Take>, context: AnalysisContext): List<List<Take>> {
        val moments = mutableListOf<List<Take>>()
        var current = mutableListOf<Take>()
        for (take in takes) {
            if (current.isNotEmpty() && follows(current.last(), take, context)) {
                current += take
            } else {
                moments += current
                current = mutableListOf(take)
            }
        }
        moments += current
        return moments.filter { it.size > 1 }
    }

    private suspend fun follows(previous: Take, take: Take, context: AnalysisContext): Boolean {
        val end = previous.start + (previous.lengthMs ?: 0)
        return take.start - end <= TAKES_MAX_GAP_MS && alike(previous.video, take.video, context)
    }

    /** Recall first: without the frames of both, time alone decides. */
    private suspend fun alike(a: MediaRef, b: MediaRef, context: AnalysisContext): Boolean {
        val framesA = context.frames(a)
        val framesB = context.frames(b)
        if (framesA.isEmpty() || framesB.isEmpty()) return true
        return framesA.maxOf { x -> framesB.maxOf { y -> dot(x, y) } } >= TAKES_MIN_COSINE
    }

    /** A Galaxy saves screen recordings to DCIM/Screen recordings. */
    private fun isScreenRecording(path: String): Boolean = generateSequence(File(path).parentFile) { it.parentFile }.any { it.name.equals("screen recordings", ignoreCase = true) }

    private suspend fun metadata(video: MediaRef, context: AnalysisContext): VideoMetadata? = try {
        context.videoMetadata(video)
    } catch (error: Exception) { // unreadable header: no capture time to chain on
        if (error is CancellationException) throw error
        null
    }
}
