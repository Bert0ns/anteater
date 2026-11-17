// Turns signals into candidates and a review order. Mirrors lab/src/cribrum_lab/ranker.py and
// docs/design.md §6.
package io.github.bert0ns.cribrum.ranker

import io.github.bert0ns.cribrum.detectors.pathDigest
import io.github.bert0ns.cribrum.folders.isExcluded
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.IndexedMedia
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import java.nio.ByteBuffer

/**
 * Score a signal needs to make an image a candidate. Picked by the maintainer from the lab
 * evaluation report's sweep, for recall. Keep them equal to the lab's.
 */
val THRESHOLDS: Map<Category, Float> = mapOf(
    Category.EXACT_DUPLICATE to 0.5f,
    Category.NEAR_DUPLICATE to 0.5f,
    Category.BURST to 0.5f,
    Category.BLURRY to 0.4f,
    Category.BAD_EXPOSURE to 0.4f,
    Category.SCREENSHOT to 0.5f,
    Category.DOCUMENT to 0.2f,
    Category.FORWARDED to 0.5f,
    Category.STICKER to 0.5f,
    Category.TAKES to 0.5f,
)

/**
 * The threshold of a category for videos, where its video score is graded: a video is not held to the photos' slider, which could hide
 * it without a word (docs/design.md §6.1 and §8). A category not listed here has one threshold for both. Picked from the sweep of the
 * video labels (docs/design.md §5). Keep them equal to the lab's.
 */
val VIDEO_THRESHOLDS: Map<Category, Float> = mapOf(
    Category.BLURRY to 0.15f,
    Category.BAD_EXPOSURE to 0.4f,
    Category.SHORT_CLIPS to 0.3f,
)

/** How much a protection signal at score 1 lowers an image's likelihood. */
val PROTECTION_WEIGHTS: Map<Protection, Float> =
    mapOf(Protection.FACES to 0.5f, Protection.FAVORITE to 0.8f, Protection.ALBUM to 0.6f)

/** Lowers images in no duplicate, burst or takes group: nothing else keeps the same memory. */
const val UNIQUE_WEIGHT = 0.2f

val GROUP_CATEGORIES = setOf(Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE, Category.BURST, Category.TAKES)

data class Candidate(
    val image: IndexedMedia,
    /**
     * Signals past their threshold, strongest first. Empty when the image is listed only for
     * "review everything".
     */
    val reasons: List<Signal>,
    /** How likely the image is deletable, comparable across categories, after protection. */
    val likelihood: Float,
    /** Factor protection signals applied to the likelihood: 1 when nothing protects it. */
    val protection: Float = 1f,
) {
    val isCandidate: Boolean get() = reasons.isNotEmpty()
}

class Ranker(private val thresholds: Map<Category, Float> = THRESHOLDS, videoThresholds: Map<Category, Float> = VIDEO_THRESHOLDS) {
    /** What a video is held to: its own threshold where its category has one, the shared one elsewhere. */
    private val limitsOfVideos = thresholds + videoThresholds

    /**
     * The "review everything" order: every unreviewed image, most likely deletable first.
     *
     * Candidates always come before the other images. Protection signals lower an image within
     * its part of the order but never move a candidate behind a non-candidate. A kept image is
     * listed again only for a signal it did not have when the user kept it, such as a new
     * duplicate group or a new role in its group. A newer detector version or a higher score is
     * not new. The index counts a detector's first result after the keep as seen, so it is not new either. Trashed images are never listed, nor are those in an excluded folder.
     */
    fun rank(images: List<IndexedMedia>, excludedFolders: Collection<String> = emptyList()): List<Candidate> {
        val ranked = mutableListOf<Candidate>()
        for (image in images) {
            if (image.reviewState == ReviewState.TRASHED || isExcluded(image.ref.path, excludedFolders)) continue
            val limits = if (image.ref.mediaType == MediaType.VIDEO) limitsOfVideos else thresholds
            var reasons = image.signals.filter { passes(it, limits) }
            if (image.reviewState == ReviewState.KEPT) {
                reasons = reasons.filter { it.identity !in image.seenWhenKept }
                if (reasons.isEmpty()) continue
            }
            val protection = protection(image.signals)
            val likelihood = protection * (image.signals.maxOfOrNull { likelihood(it, limits) } ?: 0f)
            ranked += Candidate(image, reasons.sortedByDescending { likelihood(it, limits) }, likelihood, protection)
        }
        // Ties go by a hash of the path: the path itself holds the date in many file names, and
        // photo age is never a signal (docs/design.md, D5).
        val tieBreak = ranked.associate { it.image.ref.path to pathHash(it.image.ref.path) }
        return ranked.sortedWith(
            compareBy<Candidate> { !it.isCandidate }
                .thenByDescending { it.likelihood }
                .thenBy { tieBreak.getValue(it.image.ref.path) },
        )
    }

    /** Protection signals have no threshold, so they never pass. */
    private fun passes(signal: Signal, limits: Map<Category, Float>): Boolean {
        val threshold = limits[signal.kind] ?: return false
        return signal.score >= threshold
    }

    /** Score / threshold below the threshold, 1 + the margin over it above, 0 for protection signals. */
    private fun likelihood(signal: Signal, limits: Map<Category, Float>): Float {
        val threshold = limits[signal.kind] ?: return 0f
        return when {
            signal.score < threshold -> signal.score / threshold
            threshold == 1f -> 1f
            else -> 1f + (signal.score - threshold) / (1f - threshold)
        }
    }

    companion object {
        /**
         * Candidates under each category they have a reason for, highest score first, each score
         * lowered by the candidate's protection. A video's score is also multiplied by its size, so
         * of two equal videos the larger comes first, and an image's size plays no part
         * (docs/design.md §6.6). A list's order holds only within one media type, since a video's
         * key is in bytes and an image's is not. A sticker is listed under STICKER only.
         */
        fun byCategory(ranked: List<Candidate>): Map<Category, List<Candidate>> {
            val groups = mutableMapOf<Category, MutableList<Candidate>>()
            for (candidate in ranked) {
                var categories = candidate.reasons.mapNotNull { it.kind as? Category }.toSet()
                if (Category.STICKER in categories) categories = setOf(Category.STICKER) // they would crowd every other category
                for (category in categories) {
                    groups.getOrPut(category) { mutableListOf() } += candidate
                }
            }
            return groups.mapValues { (category, candidates) ->
                candidates.sortedByDescending { c ->
                    c.protection * c.reasons.filter { it.kind == category }.maxOf { it.score } * sizeFactor(c.image.ref)
                }
            }
        }
    }
}

/**
 * The bytes a video frees: a 900 MB video is worth more of a review than a 5 MB clip. At least 1, so an
 * empty video keeps its score and protection order. Double, as a Float would round sizes.
 */
private fun sizeFactor(ref: MediaRef): Double = if (ref.mediaType == MediaType.VIDEO) ref.size.coerceAtLeast(1).toDouble() else 1.0

/**
 * xxh3 of the path as an unsigned number, which orders the same as the lab's `_tie_break` hex
 * text: `getLong` reads the 8 bytes most significant first, the order the fixed-length hex writes them.
 */
private fun pathHash(path: String): ULong = ByteBuffer.wrap(pathDigest(path)).getLong().toULong()

private fun protection(signals: List<Signal>): Float {
    var factor = 1f
    for (signal in signals) {
        val kind = signal.kind
        if (kind is Protection) factor *= 1f - (PROTECTION_WEIGHTS[kind] ?: 0f) * signal.score
    }
    if (signals.none { it.kind in GROUP_CATEGORIES }) factor *= 1f - UNIQUE_WEIGHT // members always get one
    return factor
}
