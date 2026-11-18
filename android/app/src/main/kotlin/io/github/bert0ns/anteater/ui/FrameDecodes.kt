package io.github.bert0ns.anteater.ui

import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.video.VideoFrameDecoder
import io.github.bert0ns.anteater.tracing.traceSuspend
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException

/**
 * How many video frames decode at once, in the whole app (#478). Each decode is a `MediaMetadataRetriever` with a hardware
 * decoder of its own, and Coil's [VideoFrameDecoder] has no limit: a grid opened up to 13 at once folded and 28 unfolded,
 * beside the 2 loops, and Android killed up to 20 background apps for the memory. At 2 it killed none, and the first screen
 * of frames was no slower (#446).
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
