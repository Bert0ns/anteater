// The short clips of fixtures/videos/short_clips.json, which the lab's tests/test_fixtures.py checks too.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import io.github.bert0ns.cribrum.metadata.VideoMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.FrameMeasures
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ShortClipsDetectorTest {
    private val fixture = JSONObject(File("../../fixtures/videos/short_clips.json").readText())

    private fun list(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)

    private fun vector(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private fun clock(at: String) = LocalDateTime.parse("2024-05-01T$at")

    @Test
    fun `the short clips of the lab's made-up clips and photos match`() = runBlocking {
        val clips = list(fixture.getJSONArray("clips")).associateBy { MediaRef(it.getString("path"), 0, 0, MediaType.VIDEO) }
        val photos = list(fixture.getJSONArray("photos")).map { Photo(LocalDateTime.parse(it.getString("captured_at")), it.optJSONArray("embedding")?.let(::vector)) }
        val context = AnalysisContext(
            videoMetadata = { video ->
                val clip = clips.getValue(video)
                val at = clip.optString("at").takeIf { it.isNotEmpty() }
                VideoMetadata(
                    clip.optInt("width", 1080),
                    clip.optInt("height", 1920),
                    if (clip.isNull("duration_ms")) null else clip.getLong("duration_ms"),
                    null,
                    null,
                    clip.optBoolean("has_audio", true),
                    null,
                    false,
                    at?.let(::clock),
                    at?.let { clock(it).toInstant(ZoneOffset.ofHours(2)).toEpochMilli() },
                )
            },
            frames = { video -> clips.getValue(video).optJSONArray("frames")?.let { f -> list2(f).map(::vector) }.orEmpty() },
            frameMeasures = { video ->
                list(clips.getValue(video).optJSONArray("measures") ?: JSONArray()).map {
                    FrameMeasures(it.getDouble("peak_variance"), it.getDouble("p1"), it.getDouble("p5"), it.getDouble("p95"), it.getDouble("p99"))
                }
            },
            photos = { _, _ -> photos },
        )

        val found = ShortClipsDetector().analyze(clips.keys.toList(), context)

        val signals = fixture.getJSONObject("signals")
        assertEquals(signals.keys().asSequence().toSet(), found.keys.map { it.path }.toSet())
        for ((video, actual) in found) {
            val want = signals.getJSONArray(video.path).getJSONObject(0)
            val got = actual.single()
            assertEquals(video.path, want.getString("detector"), got.detectorId)
            assertEquals(video.path, want.getInt("version"), got.detectorVersion)
            assertEquals(video.path, want.getString("category"), (got.kind as Category).value)
            assertEquals(video.path, want.getDouble("score"), got.score.toDouble(), 1e-6)
            val evidence = want.getJSONObject("evidence")
            assertEquals(video.path, evidence.keys().asSequence().associateWith(evidence::getString), got.evidence)
        }
    }

    private fun list2(array: JSONArray) = (0 until array.length()).map(array::getJSONArray)

    @Test
    fun `a video whose header does not read gets no signal`() = runBlocking {
        val video = MediaRef("DCIM/Camera/a.mp4", 0, 0, MediaType.VIDEO)
        val context = AnalysisContext(videoMetadata = { throw UnreadableVideo(File(it.path)) })

        assertTrue(ShortClipsDetector().analyze(listOf(video), context).isEmpty())
    }
}
