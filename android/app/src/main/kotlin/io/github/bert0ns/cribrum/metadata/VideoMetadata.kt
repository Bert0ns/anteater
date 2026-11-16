// Reads a video's metadata from the file's header, without decoding a frame. Mirrors read_video_metadata in
// lab/src/cribrum_lab/metadata.py.
package io.github.bert0ns.cribrum.metadata

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/** [width] and [height] are as displayed, with the rotation already applied. */
data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val frameRate: Double?,
    /** Bits per second over the whole file: its size over its duration. */
    val bitrate: Long?,
    val hasAudio: Boolean,
    /** The MIME type MediaFormat gives the codec, such as `video/avc`. */
    val videoCodec: String?,
    /** The transfer function is PQ or HLG. */
    val hdr: Boolean,
    /** The MP4 creation time, which is UTC, turned into local time as an EXIF time is (docs/design.md §7). */
    val capturedAt: LocalDateTime? = null,
    /** The same moment as epoch milliseconds in UTC, which no zone moves (docs/design.md §7). */
    val capturedInstant: Long? = null,
)

/** The file looks like a video but its header can't be read. */
class UnreadableVideo(file: File, cause: Throwable? = null) : IOException(file.path, cause)

/** What MediaMetadataRetriever writes for a date: the MP4 creation time, in UTC. */
private val RETRIEVER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")

/** The transfers a video is HDR with: PQ and HLG. */
internal val HDR_TRANSFERS = setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)

/** The capture time goes to [zone], the phone's own by default (docs/design.md §7). */
fun readVideoMetadata(file: File, zone: ZoneId = ZoneId.systemDefault()): VideoMetadata {
    val retriever = MediaMetadataRetriever()
    try {
        try {
            retriever.setDataSource(file.path)
        } catch (error: IllegalArgumentException) {
            // What the native layer throws for a file it cannot parse. Another error, such as a media server that died, may
            // pass: it reaches the pipeline as a failure, and the next scan reads the file again.
            throw UnreadableVideo(file, error)
        }
        fun key(key: Int): String? = retriever.extractMetadata(key)
        // The extractor sees only the codecs it knows: a ProRes or MJPEG MOV looks like it has no video. Such a file stays
        // in the index, unreadable, where an audio-only MP4 shows as an unreadable video too (docs/design.md §4).
        if (key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") throw UnreadableVideo(file)
        val codedWidth = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }
        val codedHeight = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }
        if (codedWidth == null || codedHeight == null) throw UnreadableVideo(file)
        val rotated = (key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0
        val durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        val format = videoFormat(file)
        val created = creationTime(key(MediaMetadataRetriever.METADATA_KEY_DATE))
        return VideoMetadata(
            width = if (rotated) codedHeight else codedWidth,
            height = if (rotated) codedWidth else codedHeight,
            durationMs = durationMs,
            frameRate = frameRate(key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull(), format),
            bitrate = durationMs?.let { (file.length() * 8000.0 / it).roundToLong() },
            hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            videoCodec = format?.getString(MediaFormat.KEY_MIME),
            hdr = key(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() in HDR_TRANSFERS,
            capturedAt = created?.atZoneSameInstant(zone)?.toLocalDateTime(),
            capturedInstant = created?.toInstant()?.toEpochMilli(),
        )
    } finally {
        retriever.release()
    }
}

/**
 * Frames over the video track's duration, as the lab computes it, rounded to two decimals: MediaFormat's own rate of an MP4
 * is rounded to an integer, 30 for 29.97. A container that does not count its frames, such as Matroska, gives the rate
 * it declares instead.
 */
private fun frameRate(frames: Long?, format: MediaFormat?): Double? {
    val durationUs = format?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
    val rate = if (frames != null && frames > 0 && durationUs != null && durationUs > 0) {
        frames * 1_000_000.0 / durationUs
    } else {
        format?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }?.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: return null
    }
    return (rate * 100).roundToLong() / 100.0
}

/** The first video track's format, for the codec and the frame rate the retriever does not give. */
private fun videoFormat(file: File): MediaFormat? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
    } catch (_: IOException) {
        null // the retriever read the header, which is what counts
    } finally {
        extractor.release()
    }
}

private fun creationTime(utc: String?): OffsetDateTime? {
    val moment = try {
        utc?.let { LocalDateTime.parse(it, RETRIEVER_DATE) } ?: return null
    } catch (_: DateTimeParseException) {
        return null
    }
    if (moment.year < 1971) return null // a camera with no clock writes zero, which is 1904 or 1970
    return moment.atOffset(ZoneOffset.UTC)
}
