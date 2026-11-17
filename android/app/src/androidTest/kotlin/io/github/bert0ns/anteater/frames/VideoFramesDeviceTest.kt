package io.github.bert0ns.anteater.frames

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bert0ns.anteater.imaging.Argb
import io.github.bert0ns.anteater.metadata.UnreadableVideo
import io.github.bert0ns.anteater.metadata.readVideoMetadata
import io.github.bert0ns.anteater.millis
import io.github.bert0ns.anteater.mobileclip.MobileClip
import io.github.bert0ns.anteater.mobileclip.VISION_MODEL_ASSET
import io.github.bert0ns.anteater.mobileclip.modelInput
import io.github.bert0ns.anteater.runtime.OnnxRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.system.measureNanoTime

/**
 * The frames of the golden videos of fixtures/videos/, taken on the phone and compared with the lab's in frames.json, and what
 * one video of the phone's own costs (docs/design.md §5). Robolectric decodes no video, so only a phone can run this.
 * Everything goes to logcat under the tag "anteater-frames".
 *
 * Each video must keep the frames at the times the lab's do: all of them for SDR, one a keyframe for HDR. An SDR frame must
 * match the lab's embedding within the cosine floor and its mean colour within a few levels, which a wrong frame or a wrong
 * color matrix misses. An HDR frame is only reported, with its brightness and clipped share on both sides: the phone tone
 * maps these small fixtures another way than its camera's clips, which `-e video` compares (§5).
 *
 * It refuses to run in the app Anteater is installed as, so build it under another id and let that one read shared storage:
 *
 *     ./gradlew -PapplicationIdSuffix=.framecheck assembleDebug assembleDebugAndroidTest
 *     adb install -t app/build/outputs/apk/debug/app-debug.apk
 *     adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell appops set io.github.bert0ns.anteater.framecheck MANAGE_EXTERNAL_STORAGE allow
 *     adb logcat -c
 *     adb shell am instrument -w -e class io.github.bert0ns.anteater.frames.VideoFramesDeviceTest \
 *         -e video /sdcard/DCIM/Camera/<a 4K 60 fps HLG clip>.mp4 \
 *         io.github.bert0ns.anteater.framecheck.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -d -s anteater-frames:I > ../lab/data/frames-log.txt
 *     adb pull /sdcard/DCIM/Camera/<the same clip>.mp4 ../lab/data/
 *     adb uninstall io.github.bert0ns.anteater.framecheck.test
 *     adb uninstall io.github.bert0ns.anteater.framecheck
 *
 * Then, from lab/, `uv run python scripts/compare_frames.py data/<the clip>.mp4 data/frames-log.txt` compares the clip's
 * frames with the lab's. Do not use connectedDebugAndroidTest: it uninstalls the app when it ends.
 *
 * A debug build runs the Kotlin preprocessing about three times slower than a release one (§5), so the log keeps the
 * decode, which is native, apart from the preprocessing.
 */
class VideoFramesDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val model by lazy { MobileClip { OnnxRuntime(context.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }) } }

    @Before
    fun refuseTheInstalledApp() {
        check(context.packageName != INSTALLED_APP) { "build with -PapplicationIdSuffix=.framecheck: this test must not replace or uninstall Anteater" }
        Log.i(TAG, "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun copy(name: String): File = File(context.cacheDir, name).apply { assets.open(name).use { input -> outputStream().use(input::copyTo) } }

    private fun vector(base64: String): FloatArray = ByteBuffer.wrap(Base64.decode(base64, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { FloatArray(it.remaining()).also(it::get) }

    private fun JSONArray.doubles() = List(length()) { getDouble(it) }

    @Test
    fun takesTheFramesOfTheGoldenVideosAsTheLabDoes() {
        val frames = JSONObject(assets.open("frames.json").bufferedReader().readText())
        val expected = JSONObject(assets.open("expected.json").bufferedReader().readText())
        val mayNotRead = listOf("phone_may_not_read", "phone_frames_may_not_read").flatMap { key -> expected.getJSONArray(key).let { List(it.length(), it::getString) } }
        val tolerance = frames.getJSONObject("tolerance")
        val lowered = tolerance.getJSONObject("lowered_embedding_cosine")
        val meanTolerance = tolerance.getDouble("mean_rgb")
        val off = mutableListOf<String>()
        val videos = frames.getJSONObject("videos")
        for (name in videos.keys()) {
            val video = videos.getJSONObject(name)
            val hdr = video.getBoolean("hdr")
            val floor = lowered.optDouble(name, tolerance.getDouble("embedding_cosine"))
            val times = video.getJSONArray("times_ms").let { List(it.length()) { i -> it.getLong(i) } }
            val taken = try {
                readFrames(copy(name), frameTimes(expected.getJSONObject("videos").getJSONObject(name).getLong("duration_ms"))) { Brightness.of(it) to modelInput(it) }
            } catch (error: UnreadableVideo) {
                if (name in mayNotRead) continue else throw error
            }
            if (taken.keys.toList() != times) {
                Log.i(TAG, "$name: frames at ${taken.keys} ms against the lab's at $times")
                off += "$name at ${taken.keys} ms"
                continue
            }
            val embeddings = model.embedPixels(taken.values.map { it.second })
            for ((i, time) in times.withIndex()) {
                val want = vector(video.getJSONArray("embeddings").getString(i))
                val cosine = embeddings[i].indices.sumOf { embeddings[i][it].toDouble() * want[it] }
                val labMean = video.getJSONArray("mean_rgb").getJSONArray(i).doubles()
                val labClipped = video.getJSONArray("clipped").getDouble(i)
                val phone = taken.getValue(time).first
                Log.i(TAG, "$name $time ms${if (hdr) " (HDR)" else ""}: cosine ${cosine.fixed(5)}, mean RGB ${phone.meanText()} against ${labMean.joinToString(" ") { it.fixed(1) }}, clipped ${phone.clipped.fixed(4)} against ${labClipped.fixed(4)}")
                if (!hdr && (cosine < floor || phone.mean.zip(labMean).any { (a, b) -> abs(a - b) > meanTolerance })) off += "$name $time ms"
            }
        }
        assertTrue("SDR frames off the lab's: $off", off.isEmpty())
    }

    /** The seconds one video costs, and its frames for compare_frames.py, from an instrumentation argument: `-e video <path>`. */
    @Test
    fun reportsWhatOneVideoCosts() {
        val path = InstrumentationRegistry.getArguments().getString("video")
        assumeTrue("pass -e video <path of a video on the phone>", path != null)
        val file = File(path!!)
        val meta = readVideoMetadata(file)
        val times = frameTimes(meta.durationMs)
        Log.i(TAG, "${file.name}: ${meta.width}x${meta.height}, ${meta.frameRate} fps, ${meta.videoCodec}, hdr ${meta.hdr}, ${meta.durationMs} ms, ${file.length() / 1_000_000} MB, frames at $times")
        model.load()
        lateinit var taken: Map<Long, Pair<Brightness, FloatArray>>
        repeat(2) { round ->
            // The first round warms the decoder, the runtime and the model up.
            val total = measureNanoTime {
                taken = readFrames(file, times) { Brightness.of(it) to modelInput(it) }
                model.embedPixels(taken.values.map { it.second })
            }
            Log.i(TAG, "round ${round + 1}: ${millis(total, 1)} ms for the video (${millis(total, taken.size)} ms a frame, ${taken.size} frames at ${taken.keys}), as the scan does it")
        }
        for ((time, frame) in taken.keys.zip(model.embedPixels(taken.values.map { it.second }).zip(taken.values.map { it.first }))) {
            val (embedding, brightness) = frame
            val bytes = ByteBuffer.allocate(4 * embedding.size).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(embedding) }.array()
            Log.i(TAG, "frame $time mean ${brightness.meanText()} clipped ${brightness.clipped.fixed(4)} embedding ${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
        }
        for ((label, option) in listOf("exact" to MediaMetadataRetriever.OPTION_CLOSEST, "sync" to MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                for (time in times) {
                    var bitmap: Bitmap? = null
                    val decode = measureNanoTime { bitmap = retriever.getFrameAtTime(time * 1000, option) }
                    val frame = bitmap
                    if (frame == null) {
                        // What OPTION_CLOSEST gives for an HDR frame (#424).
                        Log.i(TAG, "$label $time ms: no frame after ${millis(decode, 1)} ms")
                        continue
                    }
                    var argb: Argb? = null
                    val copy = measureNanoTime { argb = Argb(frame.width, frame.height, IntArray(frame.width * frame.height).also { frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height) }) }
                    val preprocess = measureNanoTime { modelInput(argb!!) }
                    Log.i(
                        TAG,
                        "$label $time ms: decode ${millis(decode, 1)} ms, pixels ${millis(copy, 1)} ms, preprocess ${millis(preprocess, 1)} ms, " +
                            "${frame.width}x${frame.height} ${frame.config} ${frame.colorSpace?.name}, mean RGB ${Brightness.of(argb!!).meanText()}",
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

    /** As brightness() in the lab's frames.py: the mean red, green and blue, and the share of pixels with a luma of 250 or more. */
    private class Brightness(val mean: List<Double>, val clipped: Double) {
        fun meanText() = mean.joinToString(" ") { it.fixed(1) }

        companion object {
            fun of(frame: Argb): Brightness {
                val sums = LongArray(3)
                var clipped = 0
                for (pixel in frame.pixels) {
                    val r = pixel shr 16 and 0xff
                    val g = pixel shr 8 and 0xff
                    val b = pixel and 0xff
                    sums[0] += r.toLong()
                    sums[1] += g.toLong()
                    sums[2] += b.toLong()
                    if (0.299 * r + 0.587 * g + 0.114 * b >= 250) clipped++
                }
                return Brightness(sums.map { it.toDouble() / frame.pixels.size }, clipped.toDouble() / frame.pixels.size)
            }
        }
    }

    private companion object {
        /** With a dot whatever the phone's language, so compare_frames.py reads the log. */
        fun Double.fixed(digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", this)

        const val TAG = "anteater-frames"
        const val INSTALLED_APP = "io.github.bert0ns.anteater"
    }
}
