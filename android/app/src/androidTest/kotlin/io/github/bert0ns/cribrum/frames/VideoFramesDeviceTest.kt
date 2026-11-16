package io.github.bert0ns.cribrum.frames

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import io.github.bert0ns.cribrum.metadata.readVideoMetadata
import io.github.bert0ns.cribrum.millis
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.mobileclip.VISION_MODEL_ASSET
import io.github.bert0ns.cribrum.mobileclip.modelInput
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/**
 * The frames of the golden videos of fixtures/videos/, embedded on the phone and compared with the lab's in frames.json, and
 * what one video of the phone's own costs (docs/design.md §5). Robolectric decodes no video, so only a phone can run this.
 * Everything goes to logcat under the tag "cribrum-frames".
 *
 * The app under test has the same id as an installed Cribrum, so build it under another id, as HeicRotationDeviceTest
 * says, and let it read shared storage for the timing:
 *
 *     ./gradlew -PapplicationIdSuffix=.framecheck assembleDebug assembleDebugAndroidTest
 *     adb install -t app/build/outputs/apk/debug/app-debug.apk
 *     adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell appops set io.github.bert0ns.cribrum.framecheck MANAGE_EXTERNAL_STORAGE allow
 *     adb shell am instrument -w -e class io.github.bert0ns.cribrum.frames.VideoFramesDeviceTest \
 *         -e video /sdcard/DCIM/Camera/<a 4K 60 fps HDR clip>.mp4 \
 *         io.github.bert0ns.cribrum.framecheck.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -d -s cribrum-frames:I
 *     adb uninstall io.github.bert0ns.cribrum.framecheck.test
 *     adb uninstall io.github.bert0ns.cribrum.framecheck
 *
 * A debug build runs the Kotlin preprocessing about three times slower than a release one (§5), so the log keeps the
 * decode, which is native, apart from the preprocessing.
 */
class VideoFramesDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val model by lazy { MobileClip { OnnxRuntime(context.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }) } }

    private fun copy(name: String): File = File(context.cacheDir, name).apply { assets.open(name).use { input -> outputStream().use(input::copyTo) } }

    private fun vector(base64: String): FloatArray = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { FloatArray(it.remaining()).also(it::get) }

    @Test
    fun embedsTheFramesOfTheGoldenVideosAsTheLabDoes() {
        val frames = JSONObject(assets.open("frames.json").bufferedReader().readText())
        val mayNotRead = JSONObject(assets.open("expected.json").bufferedReader().readText()).getJSONArray("phone_may_not_read").let { List(it.length(), it::getString) }
        val tolerance = frames.getJSONObject("tolerance")
        val videos = frames.getJSONObject("videos")
        val low = mutableListOf<String>()
        for (name in videos.keys()) {
            val video = videos.getJSONObject(name)
            val times = video.getJSONArray("times_ms").let { List(it.length()) { i -> it.getLong(i) } }
            val wanted = video.getJSONArray("embeddings").let { List(it.length()) { i -> vector(it.getString(i)) } }
            val pixels = try {
                readFrames(copy(name), times) { modelInput(it) }
            } catch (error: UnreadableVideo) {
                if (name in mayNotRead) continue else throw error
            }
            val cosines = model.embedPixels(pixels).zip(wanted) { got, want -> got.indices.sumOf { got[it].toDouble() * want[it] } }
            val floor = tolerance.getDouble(if (video.getBoolean("hdr")) "hdr_embedding_cosine" else "embedding_cosine")
            Log.i(TAG, "$name: cosine with the lab ${cosines.joinToString { "%.5f".format(it) }} (floor $floor)")
            if (cosines.any { it < floor }) low += name
        }
        assertTrue("under the floor: $low", low.isEmpty())
    }

    /** The seconds one video costs, from an instrumentation argument: `-e video <path>`. Skipped without one. */
    @Test
    fun reportsWhatOneVideoCosts() {
        val path = InstrumentationRegistry.getArguments().getString("video")
        assumeTrue("pass -e video <path of a video on the phone>", path != null)
        val file = File(path!!)
        val meta = readVideoMetadata(file)
        val times = frameTimes(meta.durationMs)
        Log.i(TAG, "${file.name}: ${meta.width}x${meta.height}, ${meta.frameRate} fps, ${meta.videoCodec}, hdr ${meta.hdr}, ${meta.durationMs} ms, ${file.length() / 1_000_000} MB, frames at $times")
        model.load()
        repeat(2) { round ->
            // The first round warms the decoder, the runtime and the model up.
            var pixels = emptyList<FloatArray>()
            val total = measureNanoTime {
                pixels = readFrames(file, times) { modelInput(it) }
                model.embedPixels(pixels)
            }
            Log.i(TAG, "round ${round + 1}: ${millis(total, 1)} ms for the video (${millis(total, times.size)} ms a frame), as the scan does it")
        }
        for ((label, option) in listOf("exact" to MediaMetadataRetriever.OPTION_CLOSEST, "sync" to MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                for (time in times) {
                    var bitmap: Bitmap? = null
                    val decode = measureNanoTime { bitmap = retriever.getFrameAtTime(time * 1000, option) }
                    val frame = bitmap ?: error("no frame at $time ms")
                    var argb: Argb? = null
                    val copy = measureNanoTime { argb = Argb(frame.width, frame.height, IntArray(frame.width * frame.height).also { frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height) }) }
                    val preprocess = measureNanoTime { modelInput(argb!!) }
                    Log.i(
                        TAG,
                        "$label $time ms: decode ${millis(decode, 1)} ms, pixels ${millis(copy, 1)} ms, preprocess ${millis(preprocess, 1)} ms, " +
                            "${frame.width}x${frame.height} ${frame.config} ${frame.colorSpace?.name}, mean green ${"%.1f".format(argb!!.pixels.average { it shr 8 and 0xff })}",
                    )
                    frame.recycle()
                }
            } finally {
                retriever.release()
            }
        }
        val embed = measureNanoTime { model.embedPixels(List(times.size) { FloatArray(3 * 256 * 256) }) }
        Log.i(TAG, "model: ${millis(embed, times.size)} ms a frame, batch of ${times.size}")
    }

    private fun IntArray.average(channel: (Int) -> Int): Double = sumOf { channel(it).toLong() }.toDouble() / size

    private companion object {
        const val TAG = "cribrum-frames"
    }
}
