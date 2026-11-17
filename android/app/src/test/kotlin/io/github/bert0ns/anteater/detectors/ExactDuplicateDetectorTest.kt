// The lab's tests/test_exact_duplicate.py, translated.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.GROUP_KEY
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ROLE_KEY
import io.github.bert0ns.anteater.model.Role
import io.github.bert0ns.anteater.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExactDuplicateDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(name: String, content: String, mediaType: MediaType = MediaType.IMAGE): MediaRef {
        val file = File(folder.root, name).apply {
            parentFile!!.mkdirs()
            writeText(content)
        }
        return MediaRef(file.path, content.length.toLong(), 0, mediaType)
    }

    private fun run(images: List<MediaRef>, context: AnalysisContext = AnalysisContext()) = runBlocking { ExactDuplicateDetector().analyze(images, context) }

    private fun Map<MediaRef, List<Signal>>.evidence(image: MediaRef, key: String) = getValue(image).single().evidence[key]

    @Test
    fun `identical files form a group with one best copy`() {
        val camera = write("DCIM/Camera/a.jpg", "photo")
        val forwarded = write("WhatsApp/Media/WhatsApp Images/b.jpg", "photo")
        val downloaded = write("Download/c.jpg", "photo")

        val signals = run(listOf(forwarded, downloaded, camera))

        assertEquals(
            mapOf(camera to "best", forwarded to "inferior", downloaded to "inferior"),
            signals.keys.associateWith { signals.evidence(it, ROLE_KEY) },
        )
        assertEquals(1, signals.keys.map { signals.evidence(it, GROUP_KEY) }.toSet().size)
        assertEquals(0f, signals.getValue(camera).single().score)
        assertEquals(1f, signals.getValue(forwarded).single().score)
        assertTrue(signals.values.all { it.single().kind == Category.EXACT_DUPLICATE })
    }

    @Test
    fun `messaging copy loses to any other folder`() {
        val received = write("Android/media/com.whatsapp/WhatsApp/Media/a.jpg", "meme")
        val telegram = write("Telegram/Telegram Images/b.jpg", "meme")
        val saved = write("Pictures/c.jpg", "meme")

        assertEquals(Role.BEST.value, run(listOf(received, telegram, saved)).evidence(saved, ROLE_KEY))
    }

    @Test
    fun `same size with different content is not a duplicate`() {
        assertEquals(emptyMap<MediaRef, List<Signal>>(), run(listOf(write("a.jpg", "aaaa"), write("b.jpg", "bbbb"))))
    }

    @Test
    fun `unique sizes are never hashed`() {
        val hashed = mutableListOf<MediaRef>()
        val context = AnalysisContext(contentHash = {
            hashed += it
            "same"
        })

        assertEquals(emptyMap<MediaRef, List<Signal>>(), run(listOf(write("a.jpg", "a"), write("b.jpg", "bb")), context))
        assertEquals(emptyList<MediaRef>(), hashed)
    }

    @Test
    fun `separate groups get separate ids`() {
        val images = listOf(write("a1.jpg", "aaaa"), write("a2.jpg", "aaaa"), write("b1.jpg", "bbbb"), write("b2.jpg", "bbbb"))

        val signals = run(images)

        val groups = images.map { signals.evidence(it, GROUP_KEY) }
        assertEquals(groups[0], groups[1])
        assertNotEquals(groups[1], groups[2])
        assertEquals(groups[2], groups[3])
    }

    @Test
    fun `file gone since the scan is skipped`() {
        val a = write("a.jpg", "photo")
        val b = write("b.jpg", "photo")
        val gone = write("c.jpg", "photo")
        File(gone.path).delete()

        assertEquals(setOf(a, b), run(listOf(a, b, gone)).keys)
    }

    @Test
    fun `it accepts images and videos`() {
        assertEquals(setOf(MediaType.IMAGE, MediaType.VIDEO), ExactDuplicateDetector().mediaTypes)
    }

    @Test
    fun `identical videos form a group with the camera copy as the best`() {
        val camera = write("DCIM/Camera/a.mp4", "clip", MediaType.VIDEO)
        val sent = write("WhatsApp/Media/WhatsApp Video/Sent/b.mp4", "clip", MediaType.VIDEO)

        val signals = run(listOf(sent, camera))

        assertEquals(Role.BEST.value, signals.evidence(camera, ROLE_KEY))
        assertEquals(Role.INFERIOR.value, signals.evidence(sent, ROLE_KEY))
        assertEquals(signals.evidence(camera, GROUP_KEY), signals.evidence(sent, GROUP_KEY))
    }

    @Test
    fun `two videos of one size with different content are not duplicates`() {
        assertEquals(emptyMap<MediaRef, List<Signal>>(), run(listOf(write("a.mp4", "aaaa", MediaType.VIDEO), write("b.mp4", "bbbb", MediaType.VIDEO))))
    }

    @Test
    fun `a video is never grouped with an image, and neither is read when alone in its type and size`() {
        val hashed = mutableListOf<MediaRef>()
        val context = AnalysisContext(contentHash = {
            hashed += it
            "same"
        })

        val signals = run(listOf(write("a.jpg", "same bytes"), write("b.mp4", "same bytes", MediaType.VIDEO)), context)

        assertEquals(emptyMap<MediaRef, List<Signal>>(), signals)
        assertEquals(emptyList<MediaRef>(), hashed)
    }

    @Test
    fun `a video beside a pair of images of its size stays out of their group`() {
        val one = write("a.jpg", "same bytes")
        val two = write("b.jpg", "same bytes")
        val clip = write("c.mp4", "same bytes", MediaType.VIDEO)

        assertEquals(setOf(one, two), run(listOf(one, two, clip)).keys)
    }
}
