package io.github.bert0ns.anteater.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.MediaMetadataRetriever
import androidx.core.graphics.drawable.toDrawable
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.size.Scale
import coil3.video.videoFrameMillis
import coil3.video.videoFrameOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The video frames' disk cache (docs/design.md §8, #481). Robolectric decodes no video, so a fake stands in for Coil's
 * `VideoFrameDecoder` and hands back the bitmap the retriever would have, at the size Coil computes for the request.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FrameCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val disk by lazy { DiskCache.Builder().directory(folder.newFolder("frames").toOkioPath()).maxSizeBytes(TileCache.FRAMES_MAX_BYTES).build() }
    private val cache by lazy { TileCache(disk, context.getSharedPreferences("frames-test", Context.MODE_PRIVATE), minBytes = 0) }
    private val loader by lazy { ImageLoader.Builder(context).build() }
    private val stores = StandardTestDispatcher()
    private var decodes = 0

    /** A video file the fake never reads: only its path, length and time matter to the cache. */
    private fun video(name: String = "a.mp4"): File = File(folder.root, name).apply { writeBytes(Random(1).nextBytes(4096)) }

    /** A frame with some texture, opaque but in a bitmap that says it has alpha, as a retriever's ARGB_8888 frame does. */
    private fun frame(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val pixels = IntArray(width * height) { Color.rgb(it % width * 255 / width, it / width * 255 / height, 128) }
        val argb = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return if (config == Bitmap.Config.ARGB_8888) argb else argb.copy(config, false)
    }

    /** What `VideoFrameDecoder` returns, counted: a [BitmapDrawable][android.graphics.drawable.BitmapDrawable] of [bitmap]. */
    private fun decoder(bitmap: () -> Bitmap, queue: FrameQueue = FrameQueue(FRAME_DECODES), slide: Boolean = false) = FrameDecoderFactory(queue, slide, CoroutineScope(stores)) { _, _ ->
        Decoder {
            decodes++
            DecodeResult(bitmap().toDrawable(context.resources).asImage(), isSampled = true)
        }
    }

    private fun request(file: File, factory: Decoder.Factory) = ImageRequest.Builder(context).data(file)
        .fetcherFactory(TileCache.Fetcher.Factory(frames = true) { cache }, Uri::class).decoderFactory(factory)
        .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)

    /** A grid tile's still frame: 540 px square, cropped, as AsyncImage asks for it. */
    private fun still(file: File, factory: Decoder.Factory, millis: Long? = null) = request(file, factory)
        .size(540, 540).scale(Scale.FILL).precision(Precision.INEXACT).apply { if (millis != null) videoFrameMillis(millis) }

    /** A slideshow frame, as `slideFrameRequest` asks for it. */
    private fun slide(file: File, factory: Decoder.Factory, millis: Long) = request(file, factory)
        .videoFrameMillis(millis).videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        .size(SLIDE_FRAME_PX).scale(Scale.FILL).bitmapConfig(Bitmap.Config.RGB_565)

    private fun load(builder: ImageRequest.Builder): ImageResult = runBlocking { loader.execute(builder.build()) }.also { stores.scheduler.advanceUntilIdle() }

    private val ImageResult.bitmap get() = ((this as SuccessResult).image as BitmapImage).bitmap

    private fun meanColor(bitmap: Bitmap): Triple<Double, Double, Double> {
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(software.width * software.height).also { software.getPixels(it, 0, software.width, 0, 0, software.width, software.height) }
        return Triple(pixels.map(Color::red).average(), pixels.map(Color::green).average(), pixels.map(Color::blue).average())
    }

    private fun assertLooksTheSame(decoded: Bitmap, cached: Bitmap) {
        assertEquals(decoded.width, cached.width)
        assertEquals(decoded.height, cached.height)
        assertEquals(decoded.config, cached.config)
        val (r1, g1, b1) = meanColor(decoded)
        val (r2, g2, b2) = meanColor(cached)
        assertTrue("mean colour ($r1, $g1, $b1) against ($r2, $g2, $b2)", abs(r1 - r2) < 3 && abs(g1 - g2) < 3 && abs(b1 - b2) < 3)
    }

    @Test
    fun `a still frame comes back from the cache at the same size and colour, without decoding the video again`() {
        val file = video()
        // A 4K video in a 540 px tile: Coil's VideoFrameDecoder gives 960 x 540.
        val factory = decoder({ frame(960, 540) })
        val decoded = load(still(file, factory)).bitmap
        val cached = load(still(file, factory)).bitmap
        assertEquals(1, decodes)
        assertEquals(960, cached.width)
        assertLooksTheSame(decoded, cached)
    }

    @Test
    fun `a slideshow frame comes back in RGB_565 at its size, so it takes no more memory`() {
        val file = video()
        // A 4K video at 320 px, exact: 569 x 320.
        val factory = decoder({ frame(569, 320, Bitmap.Config.RGB_565) }, slide = true)
        val decoded = load(slide(file, factory, 1_000)).bitmap
        val cached = load(slide(file, factory, 1_000)).bitmap
        assertEquals(1, decodes)
        assertEquals(Bitmap.Config.RGB_565, cached.config)
        assertEquals(569, cached.width)
        assertEquals(320, cached.height)
        assertLooksTheSame(decoded, cached)
    }

    @Test
    fun `a strip frame comes back at its size`() {
        val file = video()
        // A strip frame of about 200 by 113 px, cropped: 201 x 113 for a 4K video.
        val factory = decoder({ frame(201, 113) })
        val strip = { request(file, factory).size(200, 113).scale(Scale.FILL).precision(Precision.INEXACT).videoFrameMillis(4_500) }
        val decoded = load(strip()).bitmap
        val cached = load(strip()).bitmap
        assertEquals(1, decodes)
        assertLooksTheSame(decoded, cached)
    }

    @Test
    fun `another frame time, another retriever option, another size or a changed file each decode the video`() {
        val file = video()
        val factory = decoder({ frame(960, 540) })
        load(still(file, factory, millis = 1_000))
        load(still(file, factory, millis = 2_000))
        load(still(file, factory, millis = 2_000).videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST))
        load(request(file, factory).size(440, 440).scale(Scale.FILL).precision(Precision.INEXACT).videoFrameMillis(2_000))
        assertEquals(4, decodes)

        val modified = file.lastModified()
        file.appendBytes(ByteArray(16))
        file.setLastModified(modified)
        load(still(file, factory, millis = 1_000))
        assertEquals(5, decodes)
        load(still(file, factory, millis = 1_000))
        assertEquals(5, decodes)
    }

    @Test
    fun `a cached frame does not wait for the frame decodes`() {
        val file = video()
        val queue = FrameQueue(1)
        val factory = decoder({ frame(960, 540) }, queue)
        load(still(file, factory))
        // Another video's decode holds the only permit.
        runBlocking { queue.acquire(slide = false) }
        val cached = runBlocking { withTimeout(5_000) { loader.execute(still(file, factory).build()) } }
        assertTrue(cached is SuccessResult)
        assertEquals(1, decodes)
    }

    @Test
    fun `the frame is written after the tile has it and the permit is back`() {
        val file = video()
        val queue = FrameQueue(1)
        val result = runBlocking { loader.execute(still(file, decoder({ frame(960, 540) }, queue)).build()) }
        assertTrue(result is SuccessResult)
        assertEquals(1, queue.free)
        assertEquals(0L, disk.size)
        stores.scheduler.advanceUntilIdle()
        assertTrue(disk.size > 0)
    }

    @Test
    fun `a frame a JPEG would not give back as it was is not stored`() {
        val transparent = { frame(960, 540).copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(10, 10, Color.TRANSPARENT) } }
        val inSpace = { config: Bitmap.Config, space: ColorSpace.Named -> { Bitmap.createBitmap(960, 540, config, false, ColorSpace.get(space)).apply { eraseColor(Color.GRAY) } } }
        val frames = listOf(
            transparent,
            inSpace(Bitmap.Config.ARGB_8888, ColorSpace.Named.BT2020),
            inSpace(Bitmap.Config.ARGB_8888, ColorSpace.Named.DISPLAY_P3),
            inSpace(Bitmap.Config.RGBA_1010102, ColorSpace.Named.BT2020_HLG),
            inSpace(Bitmap.Config.RGBA_1010102, ColorSpace.Named.BT2020_PQ),
            inSpace(Bitmap.Config.RGBA_F16, ColorSpace.Named.EXTENDED_SRGB),
            inSpace(Bitmap.Config.RGBA_F16, ColorSpace.Named.LINEAR_EXTENDED_SRGB),
        )
        for ((i, bitmap) in frames.withIndex()) {
            val file = video("$i.mp4")
            val factory = decoder(bitmap)
            val result = load(still(file, factory))
            assertTrue("frame $i: ${(result as? ErrorResult)?.throwable}", result is SuccessResult)
            assertEquals("frame $i", 0L, disk.size)
        }
        load(still(video("0.mp4"), decoder(transparent)))
        assertEquals(frames.size + 1, decodes)
    }

    @Test
    fun `a frame that does not decode stores nothing and still fails`() {
        val factory = FrameDecoderFactory(FrameQueue(FRAME_DECODES), slide = false, CoroutineScope(stores)) { _, _ ->
            Decoder {
                decodes++
                error("no frame")
            }
        }
        assertTrue(load(still(video(), factory)) is ErrorResult)
        assertEquals(0L, disk.size)
    }
}
