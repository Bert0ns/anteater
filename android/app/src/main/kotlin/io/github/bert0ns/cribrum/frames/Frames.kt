// Frames sampled from a video, for the detectors images have (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/frames.py, which decodes the same times with FFmpeg.
package io.github.bert0ns.cribrum.frames

import android.media.MediaMetadataRetriever
import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import java.io.File

const val FRAMES = 5

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
 * The frame closest to each time ([MediaMetadataRetriever.OPTION_CLOSEST]), upright, an HDR frame tone mapped by the
 * platform. Each frame's pixels go to [block] and are dropped after it, so memory holds one frame: a 4K one is 33 MB
 * as a bitmap, dropped once its pixels are copied. A time with no frame fails the video, which the next scan tries again.
 */
internal fun <T> readFrames(file: File, timesMs: List<Long>, block: (Argb) -> T): List<T> {
    val retriever = MediaMetadataRetriever()
    try {
        try {
            retriever.setDataSource(file.path)
        } catch (error: IllegalArgumentException) {
            throw UnreadableVideo(file, error)
        }
        return timesMs.map { time ->
            val bitmap = retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: throw UnreadableVideo(file)
            val pixels = try {
                // getPixels gives sRGB whatever the bitmap's config and color space.
                Argb(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) })
            } finally {
                bitmap.recycle()
            }
            block(pixels)
        }
    } finally {
        retriever.release()
    }
}
