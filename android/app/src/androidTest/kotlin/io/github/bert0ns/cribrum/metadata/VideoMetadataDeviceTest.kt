package io.github.bert0ns.cribrum.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The golden videos of fixtures/videos/, read by the phone's own parsers: the values the lab reads with PyAV.
 * Robolectric has no media parser, so only a phone can run this.
 */
class VideoMetadataDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val expected = JSONObject(assets.open("expected.json").bufferedReader().readText())
    private val zone = ZoneId.of(expected.getString("zone"))
    private val mayNotRead = expected.getJSONArray("phone_may_not_read").let { names -> List(names.length(), names::getString) }
    private val frameRateMayDiffer = expected.getJSONArray("frame_rate_may_differ").let { names -> List(names.length(), names::getString) }

    private fun copy(name: String): File = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, name).apply {
        assets.open(name).use { input -> outputStream().use(input::copyTo) }
    }

    @Test
    fun readsTheGoldenValues() {
        val videos = expected.getJSONObject("videos")
        for (name in videos.keys()) {
            val want = videos.getJSONObject(name)
            val wanted = VideoMetadata(
                want.getInt("width"),
                want.getInt("height"),
                want.getLong("duration_ms"),
                want.getDouble("frame_rate"),
                want.getLong("bitrate"),
                want.getBoolean("has_audio"),
                if (want.isNull("video_codec")) null else want.getString("video_codec"),
                want.getBoolean("hdr"),
                if (want.isNull("captured_at")) null else LocalDateTime.parse(want.getString("captured_at")),
                if (want.isNull("captured_instant")) null else want.getLong("captured_instant"),
            )
            if (name in mayNotRead) {
                // A codec the phone's extractor may not know: it reads the file or marks it unreadable, never drops it.
                // When it reads it, the size and length must match; the lab names no codec for it, so the rest may differ.
                val read = try {
                    readVideoMetadata(copy(name), zone)
                } catch (_: UnreadableVideo) {
                    continue
                }
                assertEquals(name, listOf(wanted.width, wanted.height, wanted.durationMs), listOf(read.width, read.height, read.durationMs))
            } else {
                val read = readVideoMetadata(copy(name), zone)
                // A container that counts no frames: the phone gives the rate it declares, or none (docs/design.md §4).
                assertEquals(name, wanted, if (name in frameRateMayDiffer) read.copy(frameRate = wanted.frameRate) else read)
            }
        }
    }

    /** The lab drops these, and the phone cannot tell one from a track it does not know, so it keeps them unreadable. */
    @Test
    fun aFileWithOnlyAudioIsUnreadable() {
        val names = expected.getJSONArray("no_video_track")
        for (i in 0 until names.length()) assertThrows(names.getString(i), UnreadableVideo::class.java) { readVideoMetadata(copy(names.getString(i)), zone) }
    }

    @Test
    fun aHeaderThatDoesNotReadIsUnreadable() {
        val names = expected.getJSONArray("unreadable")
        for (i in 0 until names.length()) assertThrows(names.getString(i), UnreadableVideo::class.java) { readVideoMetadata(copy(names.getString(i)), zone) }
    }
}
