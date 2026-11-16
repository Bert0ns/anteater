// Frames sampled from a video, for the detectors images have (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/frames.py, which decodes the same times with FFmpeg.
package io.github.bert0ns.cribrum.frames

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.metadata.HDR_TRANSFERS
import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import java.io.File
import java.io.IOException

const val FRAMES = 5

/** Bump it when the frames a video gives change, such as their times or their decoding: the frames stored at another version are taken again. */
const val FRAMES_VERSION = 2

/** Kept off each end, where a clip fades in, starts black or catches the hand on the button. */
const val MARGIN_MS = 500

/**
 * The times to sample, in milliseconds: the middle of each of [FRAMES] equal slices of the video, [MARGIN_MS] off each
 * end, a quarter of the clip off each end when it is shorter than two margins. A clip too short for distinct times gets
 * fewer, and one with no duration its start.
 */
fun frameTimes(durationMs: Long?): List<Long> {
    val duration = durationMs ?: 0L
    val margin = minOf(MARGIN_MS.toLong(), duration / 4)
    val span = duration - 2 * margin
    return (0 until FRAMES).map { margin + span * (2 * it + 1) / (2 * FRAMES) }.distinct().sorted()
}

/**
 * The frame closest to each time ([MediaMetadataRetriever.OPTION_CLOSEST]) by its time, upright, an HDR frame tone mapped by
 * the platform. An HDR video takes the keyframe closest to each time instead ([MediaMetadataRetriever.OPTION_CLOSEST_SYNC]),
 * since the phone gives no exact HDR frame: times that land on one keyframe give it once, under the first of them. Each
 * frame's pixels go to [block] and are dropped after it, so memory holds one frame: a 4K one is 33 MB as a bitmap, dropped
 * once its pixels are copied. A time with no frame, or a blank one, fails the video, which the next scan tries again.
 */
internal fun <T> readFrames(file: File, timesMs: List<Long>, block: (Argb) -> T): Map<Long, T> {
    val retriever = MediaMetadataRetriever()
    try {
        try {
            retriever.setDataSource(file.path)
        } catch (error: IllegalArgumentException) {
            throw UnreadableVideo(file, error)
        }
        val hdr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() in HDR_TRANSFERS
        val option = if (hdr) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
        // The time in microseconds each frame is asked at: a keyframe's own, so one taken twice shows by its time.
        val asked = if (hdr) timesMs.zip(keyframeTimesUs(file, timesMs)).distinctBy { it.second } else timesMs.map { it to it * 1000 }
        val taken = LinkedHashMap<Long, T>()
        for ((time, timeUs) in asked) {
            val bitmap = retriever.getFrameAtTime(timeUs, option) ?: throw UnreadableVideo(file)
            val pixels = try {
                // getPixels gives sRGB whatever the bitmap's config and color space.
                Argb(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) })
            } finally {
                bitmap.recycle()
            }
            if (pixels.isBlank()) throw UnreadableVideo(file)
            taken[time] = block(pixels)
        }
        return taken
    } finally {
        retriever.release()
    }
}

/**
 * The time of the keyframe nearest each time, in microseconds, read from the extractor without decoding. Its
 * [MediaExtractor.SEEK_TO_CLOSEST_SYNC] seeks as the retriever's [MediaMetadataRetriever.OPTION_CLOSEST_SYNC] does, through
 * the same extractor: to the frame nearest the time, then to the keyframe nearest that frame, a tie going to the later.
 */
private fun keyframeTimesUs(file: File, timesMs: List<Long>): List<Long> {
    val extractor = MediaExtractor()
    try {
        try {
            extractor.setDataSource(file.path)
        } catch (error: IOException) {
            throw UnreadableVideo(file, error)
        }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: throw UnreadableVideo(file)
        extractor.selectTrack(track)
        return timesMs.map { time ->
            extractor.seekTo(time * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            extractor.sampleTime.takeIf { it >= 0 } ?: throw UnreadableVideo(file)
        }
    } finally {
        extractor.release()
    }
}

/**
 * What a decoder that output nothing hands over: one flat color that is transparent, a buffer of zeros, or the green YUV of
 * zeros turns into, with no red or blue and a green of 77 to 136 through BT.601, BT.709 or BT.2020, in either range (0/135/0
 * for clip.webm's keyframe on the phone). Black, and a brighter green, are frames.
 */
private fun Argb.isBlank(): Boolean {
    val first = pixels.firstOrNull() ?: return true
    if (pixels.any { it != first }) return false
    return Color.alpha(first) == 0 || (Color.red(first) == 0 && Color.blue(first) == 0 && Color.green(first) in 70..140)
}
