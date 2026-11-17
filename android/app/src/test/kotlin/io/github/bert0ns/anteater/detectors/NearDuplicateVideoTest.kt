// The copies of fixtures/videos/near_duplicates.json, which the lab's tests/test_fixtures.py checks too.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.metadata.UnreadableVideo
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ROLE_KEY
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NearDuplicateVideoTest {
    private val fixture = JSONObject(File("../../fixtures/videos/near_duplicates.json").readText())

    @Test
    fun `the copies the lab links among its made-up clips match exactly`() = runBlocking {
        val clips = fixture.getJSONArray("clips").let { array -> (0 until array.length()).map(array::getJSONObject) }
        val byVideo = clips.associateBy { MediaRef(it.getString("path"), 0, 0, MediaType.VIDEO) }
        val context = AnalysisContext(
            videoMetadata = { video ->
                val clip = byVideo.getValue(video)
                fun long(key: String) = if (clip.isNull(key)) null else clip.getLong(key)
                VideoMetadata(clip.getInt("width"), clip.getInt("height"), long("duration_ms"), null, long("bitrate"), false, null, false, null, long("captured_instant"))
            },
            frames = { video ->
                val frames = byVideo.getValue(video).optJSONArray("frames") ?: JSONArray()
                (0 until frames.length()).map { i -> frames.getJSONArray(i).let { f -> FloatArray(f.length()) { f.getDouble(it).toFloat() } } }
            },
        )

        val found = NearDuplicateDetector().analyze(byVideo.keys.toList(), context)

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
    fun `a video whose header does not read is linked to none`() = runBlocking {
        val video = MediaRef("DCIM/Camera/a.mp4", 0, 0, MediaType.VIDEO)
        val context = AnalysisContext(videoMetadata = { throw UnreadableVideo(File(it.path)) }, frames = { listOf(floatArrayOf(1f, 0f)) })

        assertEquals(emptyMap<MediaRef, Any>(), NearDuplicateDetector().analyze(listOf(video, video.copy(path = "DCIM/Camera/b.mp4")), context))
    }

    @Test
    fun `trashing a copy keeps the group and role of the others`() = runBlocking {
        val original = MediaRef("DCIM/Camera/a.mp4", 0, 0, MediaType.VIDEO)
        val first = MediaRef("WhatsApp/Media/WhatsApp Video/Sent/b.mp4", 0, 0, MediaType.VIDEO)
        val second = first.copy(path = "WhatsApp/Media/WhatsApp Video/Sent/c.mp4")
        val context = AnalysisContext(
            videoMetadata = { VideoMetadata(if (it == original) 1080 else 480, 1920, 10_000, null, null, false, null, false) },
            frames = { listOf(floatArrayOf(1f, 0f)) },
        )
        val before = NearDuplicateDetector().analyze(listOf(original, first, second), context).getValue(second)

        assertEquals(before, NearDuplicateDetector().analyze(listOf(original, second), context).getValue(second))
    }

    @Test
    fun `camera videos captured at different instants are takes and not copies, and one with the same instant or none is a copy`() = runBlocking {
        fun camera(name: String) = MediaRef("DCIM/Camera/$name.mp4", 0, 0, MediaType.VIDEO)
        val instants = mapOf("first" to 1_714_550_400_000L, "second" to 1_714_550_460_000L, "export" to 1_714_550_400_000L, "no-time" to null)
        val context = AnalysisContext(
            videoMetadata = { VideoMetadata(if (it.path.contains("first")) 1080 else 480, 1920, 10_000, null, null, false, null, false, null, instants.getValue(File(it.path).nameWithoutExtension)) },
            frames = { listOf(floatArrayOf(1f, 0f)) },
        )

        assertEquals(emptyMap<MediaRef, Any>(), NearDuplicateDetector().analyze(listOf(camera("first"), camera("second")), context))
        val roles = NearDuplicateDetector().analyze(listOf(camera("first"), camera("export"), camera("no-time")), context)
            .mapKeys { File(it.key.path).nameWithoutExtension }.mapValues { it.value.single().evidence.getValue(ROLE_KEY) }
        assertEquals(mapOf("first" to "best", "export" to "inferior", "no-time" to "inferior"), roles)
    }
}
