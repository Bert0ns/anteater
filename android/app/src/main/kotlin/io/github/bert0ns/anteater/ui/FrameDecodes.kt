package io.github.bert0ns.anteater.ui

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.tracing.trace
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameIndex
import coil3.video.videoFrameMicros
import coil3.video.videoFrameOption
import coil3.video.videoFramePercent
import io.github.bert0ns.anteater.tracing.traceSuspend
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException

/**
 * How many video frames decode at once, in the whole app (#478). Each decode is a `MediaMetadataRetriever` with a hardware
 * decoder of its own, and Coil's [VideoFrameDecoder] has no limit: a grid opened up to 13 at once folded and 28 unfolded,
 * beside the 2 loops (#446). At 2 the phone keeps 150 to 400 MB more free and the first screen of frames comes in sooner
 * (#499). Android still kills background apps as the grid opens, for the loops' decoders.
 */
internal const val FRAME_DECODES = 2

/**
 * The video frame decodes that wait for one of [permits], in two queues: the still frames (the tiles', the full size view's and
 * its strip's) and the slideshows' frames. A permit that comes free goes to the still frame that waited longest, and to a
 * slideshow frame only when no still frame waits, so a screen of tiles shows its frames before the slideshows take the
 * decoders. Each queue keeps the order the frames were asked in. A decode can also run [alone][withPermit], holding every
 * permit.
 */
internal class FrameQueue(private val permits: Int) {
    private class Waiter(val weight: Int, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private val stills = ArrayDeque<Waiter>()
    private val slides = ArrayDeque<Waiter>()
    private var held = 0

    /** The permits no decode holds. */
    val free: Int get() = synchronized(lock) { permits - held }

    /** Waits for a permit, or for every permit when [alone], and returns how many it holds, to [release] once the decode ends. */
    suspend fun acquire(slide: Boolean, alone: Boolean = false): Int {
        val weight = if (alone) permits else 1
        val queue = if (slide) slides else stills
        suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(weight, continuation)
            // One cancelled after grant let it in gets its permits back from the resume's onCancellation, in grant.
            continuation.invokeOnCancellation { if (synchronized(lock) { queue.remove(waiter) }) grant() }
            synchronized(lock) { queue.addLast(waiter) }
            grant()
        }
        return weight
    }

    fun release(weight: Int) {
        synchronized(lock) { held -= weight }
        grant()
    }

    /** Runs [block] holding a permit, or every permit when [alone], and gives them back when it ends, fails or is cancelled. */
    suspend fun <T> withPermit(slide: Boolean, alone: Boolean = false, block: suspend () -> T): T {
        val weight = acquire(slide, alone)
        try {
            return block()
        } finally {
            release(weight)
        }
    }

    /** Lets in the waiters at the head of the queues, the still frames first, while the permits last. */
    private fun grant() {
        val granted = mutableListOf<Waiter>()
        synchronized(lock) {
            while (true) {
                val queue = if (stills.isNotEmpty()) stills else slides
                val next = queue.firstOrNull()?.takeIf { held + it.weight <= permits } ?: break
                queue.removeFirst()
                held += next.weight
                granted += next
            }
        }
        for (waiter in granted) waiter.continuation.resume(Unit) { _, _, _ -> release(waiter.weight) }
    }
}

/** The decodes of every video frame the app shows. */
internal val frameQueue = FrameQueue(FRAME_DECODES)

/**
 * A video frame's decode, by a decoder from [create], under a permit of [queue], a [slide] one in the slideshows' queue. A
 * decode that fails is tried once more, alone, before the frame counts as failed: the retriever says no more than that the
 * frame did not decode, also when the phone had no decoder free, and a tile whose frame fails says the video cannot be read.
 */
internal class QueuedFrameDecoder(private val queue: FrameQueue, private val slide: Boolean, private val create: () -> Decoder) : Decoder {
    override suspend fun decode(): DecodeResult? = try {
        decode(alone = false)
    } catch (e: CancellationException) {
        throw e
    } catch (failed: Throwable) {
        decode(alone = true)
    }

    private suspend fun decode(alone: Boolean): DecodeResult? {
        val weight = traceSuspend("Frame.wait") { queue.acquire(slide, alone) }
        try {
            return traceSuspend(if (alone) "Frame.retry" else "Frame.decode") { create().decode() }
        } finally {
            queue.release(weight)
        }
    }
}

/** The thread that writes the frames a decode of the video gave to [TileCache.frames], one at a time. */
private val frameStores = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/**
 * The decoder of a video frame (docs/design.md §8, #481). A frame [TileCache.frames] holds decodes from its JPEG as a grid tile
 * does ([TileDecoder]), never in [queue], so it waits for no decode of another video. Any other frame decodes from the video, by
 * a decoder from [video], in [queue] as a [slide] or a still frame. A [cache miss][TileCache.Miss] stores what it decoded on
 * [stores], once the permit is back and the frame is on its way to the tile, so the encode and the write delay neither.
 */
internal class FrameDecoderFactory(
    private val queue: FrameQueue,
    private val slide: Boolean,
    private val stores: CoroutineScope = frameStores,
    private val video: (SourceFetchResult, Options) -> Decoder,
) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        if (result.source.metadata === TileCache.Hit) return TileDecoder.factory.create(result, options, imageLoader)
        val decoder = QueuedFrameDecoder(queue, slide) { video(result, options) }
        val miss = result.source.metadata as? TileCache.Miss ?: return decoder
        return Decoder { decoder.decode()?.also { store(it, miss) } }
    }

    private fun store(result: DecodeResult, miss: TileCache.Miss) {
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: return
        stores.launch { if (storable(bitmap)) trace("Frame.store") { miss.store(bitmap) } else trace("Frame.skip") {} }
    }
}

/**
 * Whether a JPEG gives [bitmap] back as it is: 8 bits a channel in [Bitmap.Config.ARGB_8888] or [Bitmap.Config.RGB_565], sRGB
 * or no colour space, and no pixel that is not opaque, which a JPEG would turn black. A hardware bitmap cannot be read without
 * a copy, and an HDR, BT.2020, extended or linear one would come back in other colours.
 */
internal fun storable(bitmap: Bitmap): Boolean {
    if (bitmap.config != Bitmap.Config.ARGB_8888 && bitmap.config != Bitmap.Config.RGB_565) return false
    val space = bitmap.colorSpace
    if (space != null && space != ColorSpace.get(ColorSpace.Named.SRGB)) return false
    if (!bitmap.hasAlpha()) return true
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { it ushr 24 != 0xFF }) return false
    }
    return true
}

/**
 * The parts of a video frame's [TileCache] key after the tile's: its time, percent and index, the retriever's option and the
 * bitmap config. [Bitmap.Config.HARDWARE] counts as [Bitmap.Config.ARGB_8888], since the retriever hands back the same
 * software frame for both, and Coil asks for one or the other by the thread it reads the request on.
 */
internal fun frameKey(options: Options): String {
    val config = options.bitmapConfig.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
    return ":frame:${options.videoFrameMicros}:${options.videoFramePercent}:${options.videoFrameIndex}:${options.videoFrameOption}:$config"
}
