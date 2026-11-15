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
                want.getString("video_codec"),
                want.getBoolean("hdr"),
                if (want.isNull("captured_at")) null else LocalDateTime.parse(want.getString("captured_at")),
            )
            assertEquals(name, wanted, readVideoMetadata(copy(name), zone))
        }
    }

    @Test
    fun aFileWithOnlyAudioHasNoVideoTrack() {
        val names = expected.getJSONArray("no_video_track")
        for (i in 0 until names.length()) assertThrows(names.getString(i), NoVideoTrack::class.java) { readVideoMetadata(copy(names.getString(i)), zone) }
    }

    @Test
    fun aHeaderThatDoesNotReadIsUnreadable() {
        val names = expected.getJSONArray("unreadable")
        for (i in 0 until names.length()) assertThrows(names.getString(i), UnreadableVideo::class.java) { readVideoMetadata(copy(names.getString(i)), zone) }
    }
}
