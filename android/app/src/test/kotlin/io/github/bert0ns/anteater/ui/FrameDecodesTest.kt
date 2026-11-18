package io.github.bert0ns.anteater.ui

import coil3.decode.DecodeResult
import coil3.decode.Decoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FrameDecodesTest {
    /** A decode that takes 10 ms and records how many decodes ran with it and in which order they started. */
    private class Decodes {
        var running = 0
        var most = 0
        val started = mutableListOf<String>()

        suspend fun run(name: String) {
            started += name
            running++
            most = maxOf(most, running)
            delay(10)
            running--
        }
    }

    @Test
    fun `no more frames decode at once than the limit, in the order they were asked for, and all of them finish`() = runTest {
        val queue = FrameQueue(2)
        val decodes = Decodes()
        List(10) { i -> async { queue.withPermit(slide = false) { decodes.run("still $i") } } }.awaitAll()
        assertEquals(2, decodes.most)
        assertEquals(List(10) { "still $it" }, decodes.started)
        assertEquals(2, queue.free)
    }

    @Test
    fun `a still frame that waits goes before the slideshow frames that waited longer`() = runTest {
        val queue = FrameQueue(1)
        val decodes = Decodes()
        val first = launch { queue.withPermit(slide = false) { decodes.run("still 0") } }
        val slides = List(3) { i -> launch { queue.withPermit(slide = true) { decodes.run("slide $i") } } }
        testScheduler.runCurrent()
        val still = launch { queue.withPermit(slide = false) { decodes.run("still 1") } }
        (slides + first + still).forEach { it.join() }
        assertEquals(listOf("still 0", "still 1", "slide 0", "slide 1", "slide 2"), decodes.started)
    }

    @Test
    fun `a frame that is no longer needed gives its place back, whether it waits or decodes`() = runTest {
        val queue = FrameQueue(1)
        val decoding = launch { queue.withPermit(slide = false) { awaitCancellation() } }
        val waiting = launch { queue.withPermit(slide = true) { awaitCancellation() } }
        testScheduler.runCurrent()
        assertEquals(0, queue.free)
        waiting.cancel()
        decoding.cancel()
        testScheduler.runCurrent()
        assertEquals(1, queue.free)
        assertEquals("done", queue.withPermit(slide = false) { "done" })
    }

    @Test
    fun `a decode that runs alone waits for the others to end, and nothing starts beside it`() = runTest {
        val queue = FrameQueue(2)
        val decodes = Decodes()
        val before = List(2) { i -> launch { queue.withPermit(slide = false) { decodes.run("still $i") } } }
        testScheduler.runCurrent()
        var aloneWith = -1
        val alone = launch {
            queue.withPermit(slide = false, alone = true) {
                aloneWith = decodes.running
                decodes.run("alone")
            }
        }
        val after = launch { queue.withPermit(slide = false) { decodes.run("still 2") } }
        (before + alone + after).forEach { it.join() }
        assertEquals(0, aloneWith)
        assertEquals(listOf("still 0", "still 1", "alone", "still 2"), decodes.started)
        assertEquals(2, queue.free)
    }

    /** Decoders whose decodes give [results] in turn, a throwable being a failed decode, and say how many others ran beside each. */
    private class FakeDecoders(private val decodes: Decodes, vararg results: Any?) {
        private val left = ArrayDeque(results.toList())
        var made = 0
        val beside = mutableListOf<Int>()

        fun create(): Decoder {
            made++
            return object : Decoder {
                override suspend fun decode(): DecodeResult? {
                    beside += decodes.running
                    val result = left.removeFirst()
                    if (result is Throwable) throw result
                    return result as DecodeResult?
                }
            }
        }
    }

    @Test
    fun `a frame that fails to decode, as when the phone has no codec free, is decoded again alone before it counts as failed`() = runTest {
        val queue = FrameQueue(2)
        val decodes = Decodes()
        val decoders = FakeDecoders(decodes, IllegalStateException("Failed to decode frame at 0 microseconds."), null)
        val other = launch { queue.withPermit(slide = false) { decodes.run("other") } }
        testScheduler.runCurrent()
        assertEquals(null, QueuedFrameDecoder(queue, slide = false, decoders::create).decode())
        other.join()
        assertEquals(2, decoders.made)
        // The first try ran beside the other decode, and the second only once it had ended.
        assertEquals(listOf(1, 0), decoders.beside)
        assertEquals(2, queue.free)
    }

    @Test
    fun `a frame that fails twice is a failed frame, and gives every permit back`() = runTest {
        val queue = FrameQueue(2)
        val decoders = FakeDecoders(Decodes(), IllegalStateException("first"), IllegalStateException("second"))
        val failure = runCatching { QueuedFrameDecoder(queue, slide = true, decoders::create).decode() }.exceptionOrNull()
        assertEquals("second", failure?.message)
        assertEquals(2, decoders.made)
        assertEquals(2, queue.free)
    }

    @Test
    fun `a frame that decodes is decoded once`() = runTest {
        val decoders = FakeDecoders(Decodes(), null)
        QueuedFrameDecoder(FrameQueue(2), slide = false, decoders::create).decode()
        assertEquals(1, decoders.made)
    }

    @Test
    fun `a frame that is no longer needed while it decodes is not decoded again`() = runTest {
        val queue = FrameQueue(2)
        var made = 0
        val frame = launch {
            QueuedFrameDecoder(queue, slide = false) {
                made++
                object : Decoder {
                    override suspend fun decode(): DecodeResult? = awaitCancellation()
                }
            }.decode()
        }
        testScheduler.runCurrent()
        frame.cancel()
        frame.join()
        assertEquals(1, made)
        assertEquals(2, queue.free)
    }
}
