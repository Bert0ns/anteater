// The lab's tests/test_forwarded_video.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MEDIA = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"

// A video's signal reads no frame and no model: the default context would fail on both.
private fun analyze(path: String): List<Signal> = runBlocking { ForwardedDetector().analyze(MediaRef(path, 0, 0, MediaType.VIDEO), AnalysisContext()) }

class ForwardedDetectorVideoTest {
    @Test
    fun `the detector accepts videos`() {
        assertEquals(setOf(MediaType.IMAGE, MediaType.VIDEO), ForwardedDetector().mediaTypes)
    }

    @Test
    fun `a video in a sent folder is a stronger signal than a received one`() {
        val sent = analyze("$MEDIA/WhatsApp Video/Sent/VID-20260501-WA0001.mp4").single()
        val received = analyze("$MEDIA/WhatsApp Video/VID-20260501-WA0002.mp4").single()

        assertEquals(Category.FORWARDED, sent.kind)
        assertEquals(Category.FORWARDED, received.kind)
        assertEquals(0.8f, sent.score, 1e-6f)
        assertEquals(0.6f, received.score, 1e-6f)
        assertEquals(mapOf("messaging_folder" to "yes", "sent_folder" to "yes"), sent.evidence)
        assertEquals(mapOf("messaging_folder" to "yes", "sent_folder" to "no"), received.evidence)
    }

    @Test
    fun `every video in a messaging folder is a candidate`() {
        for (path in listOf(
            "$MEDIA/WhatsApp Video/Sent/a.mp4",
            "$MEDIA/WhatsApp Video/a.mp4",
            "$MEDIA/WhatsApp Animated Gifs/a.mp4",
            "$MEDIA/WhatsApp Animated Gifs/Sent/a.mp4",
            "$MEDIA/WhatsApp Video Notes/202618/a.mp4",
            "/sdcard/Telegram/Telegram Video/a.mp4",
        )) {
            assertTrue(path, analyze(path).single().score >= THRESHOLDS.getValue(Category.FORWARDED))
        }
    }

    @Test
    fun `a video outside a messaging folder gets no signal`() {
        for (path in listOf(
            "/sdcard/DCIM/Camera/20260501_101010.mp4",
            "/sdcard/Movies/Sent/holiday.mp4", // a folder named Sent says nothing outside an app's
        )) {
            assertEquals(path, emptyList<Signal>(), analyze(path))
        }
    }
}
