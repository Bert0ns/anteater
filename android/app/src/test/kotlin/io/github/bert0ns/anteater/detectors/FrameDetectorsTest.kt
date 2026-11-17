// BLURRY, BAD_EXPOSURE and FACES of fixtures/videos/frame_detectors.json, which the lab's tests/test_fixtures.py checks too.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FrameDetectorsTest {
    private val fixture = JSONObject(File("../../fixtures/videos/frame_detectors.json").readText())

    private fun measures(clip: org.json.JSONArray) = (0 until clip.length()).map { i ->
        clip.getJSONObject(i).let {
            FrameMeasures(
                it.getDouble("peak_variance"),
                it.getDouble("p1"),
                it.getDouble("p5"),
                it.getDouble("p95"),
                it.getDouble("p99"),
                it.optInt("faces", 0),
                it.optDouble("face_confidence", 0.0),
                it.optDouble("face_area", 0.0),
            )
        }
    }

    @Test
    fun `the signals of the lab's made-up clips match`() = runBlocking {
        val clips = fixture.getJSONObject("clips")
        val signals = fixture.getJSONObject("signals")
        val detectors = listOf(BlurDetector(), ExposureDetector(), FaceDetector())
        for (name in clips.keys()) {
            val video = MediaRef(name, 0, 0, MediaType.VIDEO)
            val context = AnalysisContext(frameMeasures = { measures(clips.getJSONArray(name)) })
            val actual = detectors.flatMap { it.analyze(video, context) }
            val expected = signals.getJSONArray(name).let { array -> (0 until array.length()).map(array::getJSONObject) }

            assertEquals(name, expected.map { it.getString("detector") }, actual.map { it.detectorId })
            for ((want, got) in expected.zip(actual)) {
                assertEquals(name, want.getInt("version"), got.detectorVersion)
                assertEquals(name, want.getDouble("score"), got.score.toDouble(), 1e-6)
                val evidence = want.getJSONObject("evidence")
                assertEquals(name, evidence.keys().asSequence().associateWith(evidence::getString), got.evidence)
            }
        }
    }

    @Test
    fun `a video of one frame scores as that frame, and is pinned by the fixture`() = runBlocking {
        val clips = fixture.getJSONObject("clips")
        assertEquals(1, clips.getJSONArray("one-frame.mp4").length())
        val video = MediaRef("one-frame.mp4", 0, 0, MediaType.VIDEO)
        val context = AnalysisContext(frameMeasures = { measures(clips.getJSONArray("one-frame.mp4")) })

        val signal = BlurDetector().analyze(video, context).single()

        assertEquals(0.8, signal.score.toDouble(), 1e-6)
        assertEquals("1", signal.evidence["frames"])
    }
}
