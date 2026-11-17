// The takes of fixtures/videos/takes.json, which the lab's tests/test_fixtures.py checks too.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import io.github.bert0ns.cribrum.metadata.VideoMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TakesDetectorTest {
    private val fixture = JSONObject(File("../../fixtures/videos/takes.json").readText())

    @Test
    fun `the takes of the lab's made-up clips match exactly`() = runBlocking {
        val clips = fixture.getJSONArray("clips").let { array -> (0 until array.length()).map(array::getJSONObject) }
        val byVideo = clips.associateBy { MediaRef(it.getString("path"), 0, 0, MediaType.VIDEO) }
        val context = AnalysisContext(
            videoMetadata = { video ->
                val clip = byVideo.getValue(video)
                fun long(key: String) = if (clip.isNull(key)) null else clip.getLong(key)
                VideoMetadata(0, 0, long("duration_ms"), null, null, false, null, false, null, long("captured_instant"))
            },
            frames = { video ->
                val frames = byVideo.getValue(video).optJSONArray("frames") ?: JSONArray()
                (0 until frames.length()).map { i -> frames.getJSONArray(i).let { f -> FloatArray(f.length()) { f.getDouble(it).toFloat() } } }
            },
        )

        val found = TakesDetector().analyze(byVideo.keys.toList(), context)

        val expected = fixture.getJSONObject("signals").let { signals ->
            signals.keys().asSequence().associateWith { path ->
                signals.getJSONArray(path).getJSONObject(0).let { s ->
                    val evidence = s.getJSONObject("evidence")
                    listOf(s.getString("detector"), s.getInt("version"), s.getString("category"), s.getDouble("score").toFloat(), evidence.keys().asSequence().associateWith(evidence::getString))
                }
            }
        }
        val actual = found.entries.associate { (video, signals) ->
            val s = signals.single()
            video.path to listOf(s.detectorId, s.detectorVersion, (s.kind as Category).value, s.score, s.evidence)
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `a video whose header does not read is no take`() = runBlocking {
        val video = MediaRef("DCIM/Camera/a.mp4", 0, 0, MediaType.VIDEO)
        val context = AnalysisContext(videoMetadata = { throw UnreadableVideo(File(it.path)) })

        assertEquals(emptyMap<MediaRef, Any>(), TakesDetector().analyze(listOf(video, video.copy(path = "DCIM/Camera/b.mp4")), context))
    }
}
