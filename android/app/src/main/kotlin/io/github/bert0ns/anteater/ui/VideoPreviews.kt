// The small preview clips that the grid tiles of videos larger than 1080p loop in place of the file (docs/design.md §8, #482).
package io.github.bert0ns.anteater.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import coil3.disk.DiskCache
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.tracing.traceSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The most pixels a tile loops straight from the video file: 1080p. Two 1080p decoders cost about a quarter of two 4K ones,
 * and a hardware decoder cannot decode at a lower resolution, so a larger video loops a preview (#482).
 */
internal const val FILE_LOOP_PIXELS = 1920L * 1080

/** A preview's short side: about a tile, 540 px in 2 columns on a folded Fold and 440 px in 4 unfolded. */
internal const val PREVIEW_SHORT_SIDE = 540

/** A preview's video bitrate: about 1.9 MB for the 10 seconds most tiles loop. */
internal const val PREVIEW_BITRATE = 1_500_000

/** About 65 previews of 10 seconds. */
internal const val PREVIEWS_MAX_BYTES = 128L * 1024 * 1024

/** Part of every preview's key, so a change in how they are made makes them again. */
private const val PREVIEW_VERSION = 1

/**
 * A grid tile that moves (docs/design.md §8): its [video], its size in pixels as the index read it, null until the video
 * steps have, its length when known, and how much of it the tile loops: its first [endMs], or all of it when that is null.
 */
internal data class Mover(val video: MediaRef, val pixels: Long?, val durationMs: Long?, val endMs: Long?) {
    /** The end of the loop, or null for the whole clip, which a clip no longer than [endMs] loops too: both share one preview. */
    val loopEndMs: Long? get() = endMs?.takeIf { durationMs == null || durationMs > it }

    /** Whether the index knows the video is no larger than 1080p, so the tile loops the file itself. */
    val playsFile: Boolean get() = pixels != null && pixels <= FILE_LOOP_PIXELS
}

/** Where the loop of a moving tile comes from: the video file, or its preview. */
internal interface Clips {
    /** What [mover]'s tile can loop right away: the video up to 1080p, or its preview once it is made. Null while it has none. */
    suspend fun find(mover: Mover): File?

    /**
     * Makes what [find] lacked: reads the size of a video the index has not read, and makes the preview of one larger than
     * 1080p. The file to loop, or null when the header does not read or the preview could not be made: the tile then shows
     * the slideshow, and the video is not marked unreadable.
     */
    suspend fun make(mover: Mover): File?
}

/**
 * The previews on [disk], a capped folder of the app's cache that evicts the least recently used, so an evicted preview is
 * made again the next time it is wanted. A preview is made by [transcode] (the input, the end of the loop or null, the file
 * to write), one at a time in the whole app, under one of the frame decode permits of [queue] in the slideshows' queue
 * (`FRAME_DECODES`): a transcode runs a decoder and an encoder, and must not add to the grid's frame decodes. A video of unknown size
 * is read by [header], which opens no decoder.
 */
internal class VideoPreviews(
    private val disk: DiskCache,
    private val queue: FrameQueue = frameQueue,
    private val header: (File) -> Long? = ::headerPixels,
    private val transcode: suspend (input: File, endMs: Long?, output: File) -> Unit,
) : Clips {
    private val making = Mutex()

    override suspend fun find(mover: Mover): File? {
        val file = File(mover.video.path)
        if (mover.playsFile) return file
        if (mover.pixels == null) return null
        return withContext(Dispatchers.IO) { previewKey(file, mover.loopEndMs)?.let(::cached) }
    }

    override suspend fun make(mover: Mover): File? {
        val file = File(mover.video.path)
        val pixels = mover.pixels ?: withContext(Dispatchers.IO) { header(file) } ?: return null
        if (pixels <= FILE_LOOP_PIXELS) return file
        return making.withLock {
            withContext(Dispatchers.IO) {
                val key = previewKey(file, mover.loopEndMs) ?: return@withContext null
                cached(key) ?: queue.withPermit(slide = true) { traceSuspend("Preview.make") { write(file, key, mover.loopEndMs) } }
            }
        }
    }

    /** The preview the cache holds under [key], or null. A cache that cannot be read is a miss. */
    private fun cached(key: String): File? = try {
        disk.openSnapshot(key)?.use { it.data.toFile() }
    } catch (_: IOException) {
        null
    }

    /**
     * Transcodes [file] into the cache under [key]. A transcode that fails, or a file that changed meanwhile, stores
     * nothing, and a cancelled one stores nothing either.
     */
    private suspend fun write(file: File, key: String, endMs: Long?): File? {
        val length = file.length()
        val modified = file.lastModified()
        val editor = disk.openEditor(key) ?: return null
        try {
            transcode(file, endMs, editor.data.toFile())
        } catch (e: CancellationException) {
            editor.abort()
            throw e
        } catch (_: Exception) {
            editor.abort()
            return null
        }
        if (file.length() != length || file.lastModified() != modified) {
            editor.abort()
            return null
        }
        return editor.commitAndOpenSnapshot()?.use { it.data.toFile() }
    }

    companion object {
        @Volatile
        private var instance: VideoPreviews? = null

        /** The previews of the process, in `previews` in the app's cache directory: "Clear cache" empties it too. */
        fun get(context: Context): VideoPreviews = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val disk = DiskCache.Builder().directory(File(app.cacheDir, "previews").toOkioPath()).maxSizeBytes(PREVIEWS_MAX_BYTES).build()
                VideoPreviews(disk) { input, endMs, output -> transcode(app, input, endMs, output) }
            }.also { instance = it }
        }
    }
}

/**
 * The key of [file]'s preview: the path, the file's length and modification time, so an edited or trimmed video gets a new
 * one, and the end of the loop, or `whole`. Coil hashes it into the file name, so no path is written to disk. Null for a
 * file that is gone.
 */
internal fun previewKey(file: File, endMs: Long?): String? {
    val length = file.length().takeIf { it > 0 } ?: return null
    return "preview:$PREVIEW_VERSION:${file.path}:$length:${file.lastModified()}:${endMs ?: "whole"}"
}

/** The video's width times its height from its header, which opens no decoder, or null when it does not read or reads 0. */
internal fun headerPixels(file: File): Long? = runCatching {
    MediaMetadataRetriever().use {
        it.setDataSource(file.path)
        val width = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull()
        val height = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull()
        if (width == null || height == null) null else (width * height).takeIf { it > 0 }
    }
}.getOrNull()

/** The thread every transcode is built, started and told about on: Media3's Transformer needs a looper. */
private val previewThread: Looper by lazy { HandlerThread("VideoPreviews").apply { start() }.looper }

/**
 * Writes the first [endMs] of [input], or all of it, to [output] as H.264 of [PREVIEW_SHORT_SIDE] px on the short side,
 * with no audio, and an HDR video tone mapped to SDR, which is what a TextureView shows anyway. Throws when the transcode
 * fails, as when the phone has no H.264 encoder free. A cancelled one returns only once the Transformer has stopped, so its
 * muxer writes nothing more and its codecs are let go before the cache entry is aborted and the decode permit given back.
 */
@OptIn(UnstableApi::class)
private suspend fun transcode(context: Context, input: File, endMs: Long?, output: File) {
    val handler = Handler(previewThread)
    var transformer: Transformer? = null
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            handler.post {
                if (!continuation.isActive) return@post
                try {
                    val encoder = DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(PREVIEW_BITRATE).build()).build()
                    transformer = Transformer.Builder(context)
                        .setLooper(previewThread)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) = continuation.resume(Unit)

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) = continuation.resumeWithException(exportException)
                        })
                        .build()
                        .apply { start(previewComposition(input, endMs), output.path) }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    } catch (e: CancellationException) {
        // Transformer.cancel() returns once the export's threads have let go of the codecs and the muxer.
        withContext(NonCancellable) {
            suspendCoroutine { done ->
                handler.post {
                    runCatching { transformer?.cancel() }
                    done.resume(Unit)
                }
            }
        }
        throw e
    }
}

@OptIn(UnstableApi::class)
private fun previewComposition(input: File, endMs: Long?): Composition {
    val clip = MediaItem.Builder().setUri(Uri.fromFile(input))
    if (endMs != null) clip.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(endMs).build())
    val item = EditedMediaItem.Builder(clip.build())
        .setRemoveAudio(true)
        .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(PREVIEW_SHORT_SIDE))))
        .build()
    return Composition.Builder(EditedMediaItemSequence.Builder(item).build()).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
}
