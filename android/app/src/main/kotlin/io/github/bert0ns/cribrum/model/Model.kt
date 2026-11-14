// Core types shared by every component. Mirrors lab/src/cribrum_lab/model.py and docs/design.md §7.
package io.github.bert0ns.cribrum.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Evidence key holding the id of the group an image belongs to. */
const val GROUP_KEY = "group"

/** Evidence key holding the image's [Role] within its group. */
const val ROLE_KEY = "role"

/** What a signal is about: a deletable [Category] or a [Protection]. [value] is the stored name. */
sealed interface Kind {
    val value: String

    companion object {
        private val byValue: Map<String, Kind> =
            (Category.entries + Protection.entries).associateBy(Kind::value)

        fun of(value: String): Kind = requireNotNull(byValue[value]) { "unknown kind: $value" }
    }
}

enum class Category(override val value: String) : Kind {
    EXACT_DUPLICATE("exact_duplicate"),
    NEAR_DUPLICATE("near_duplicate"),
    BURST("burst"),
    BLURRY("blurry"),
    BAD_EXPOSURE("bad_exposure"),
    SCREENSHOT("screenshot"),
    DOCUMENT("document"),
    FORWARDED("forwarded"),
    STICKER("sticker"),
}

/** Hints that an image is a memory. They lower it in the review order, never remove it. */
enum class Protection(override val value: String) : Kind {
    FACES("faces"),
    FAVORITE("favorite"),
    ALBUM("album"),
}

enum class ReviewState(val value: String) {
    UNREVIEWED("unreviewed"),
    KEPT("kept"),
    TRASHED("trashed"),
    ;

    companion object {
        fun of(value: String): ReviewState = entries.first { it.value == value }
    }
}

/** A group member's role, stored in `evidence[ROLE_KEY]`. */
enum class Role(val value: String) {
    BEST("best"),
    INFERIOR("inferior"),
}

/** What a file holds, as the index's `media_type` column stores it. */
enum class MediaType(val value: String) {
    IMAGE("image"),
    VIDEO("video"),
    ;

    companion object {
        fun of(value: String): MediaType = entries.first { it.value == value }
    }
}

/** Identifies an image or video file. Size and mtime detect changes between scans. */
data class MediaRef(val path: String, val size: Long, val mtimeNs: Long, val mediaType: MediaType = MediaType.IMAGE)

/** The file as it is now, not a symlink's target. Every component builds its [MediaRef]s with it. */
fun File.toMediaRef(mediaType: MediaType = MediaType.IMAGE): MediaRef = toMediaRef(Files.readAttributes(toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS), mediaType)

/** [toMediaRef] for a caller that has read the attributes already, with `NOFOLLOW_LINKS`. */
fun File.toMediaRef(attributes: BasicFileAttributes, mediaType: MediaType = MediaType.IMAGE): MediaRef = MediaRef(path, attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), mediaType)

/**
 * A detector's evidence that an image may belong to a deletable category, or, with a
 * [Protection], that it may be a memory. Signals never decide on their own: the ranker
 * combines them.
 */
data class Signal(
    val detectorId: String,
    val detectorVersion: Int,
    val kind: Kind,
    val score: Float,
    val evidence: Map<String, String> = emptyMap(),
) {
    init {
        require(score in 0f..1f) { "score must be in [0, 1], got $score" }
    }

    /**
     * What the signal says, whatever the detector version and score: kind, group and role.
     * The ranker uses it to tell a new signal from one the user already saw.
     */
    val identity: SignalIdentity
        get() = SignalIdentity(kind, evidence[GROUP_KEY].orEmpty(), evidence[ROLE_KEY].orEmpty())
}

data class SignalIdentity(val kind: Kind, val group: String, val role: String)

/** An image as the ranker sees it. */
data class IndexedMedia(
    val ref: MediaRef,
    val reviewState: ReviewState = ReviewState.UNREVIEWED,
    val signals: List<Signal> = emptyList(),
    /** Identities of the signals the image had when the user kept it. */
    val seenWhenKept: Set<SignalIdentity> = emptySet(),
)
