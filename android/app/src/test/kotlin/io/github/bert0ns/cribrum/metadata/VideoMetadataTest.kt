package io.github.bert0ns.cribrum.metadata

import android.media.MediaFormat
import android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER
import android.media.MediaMetadataRetriever.METADATA_KEY_DATE
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
import android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private val ROME = ZoneId.of("Europe/Rome")

/**
 * The mapping from what MediaMetadataRetriever and MediaExtractor report to [VideoMetadata]. Robolectric has no media
 * parser, so the shadows give the values; VideoMetadataDeviceTest reads the golden files on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class VideoMetadataTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun reset() {
        ShadowMediaMetadataRetriever.reset()
        ShadowMediaExtractor.reset()
    }

    private fun video(keys: Map<Int, String>, format: MediaFormat? = null, size: Int = 125_000): File {
        val file = folder.newFile().apply { writeBytes(ByteArray(size)) }
        val source = DataSource.toDataSource(file.path)
        keys.forEach { (key, value) -> ShadowMediaMetadataRetriever.addMetadata(source, key, value) }
        format?.let { ShadowMediaExtractor.addTrack(source, it, ByteArray(0)) }
        return file
    }

    private val clip = mapOf(
        METADATA_KEY_HAS_VIDEO to "yes",
        METADATA_KEY_VIDEO_WIDTH to "1920",
        METADATA_KEY_VIDEO_HEIGHT to "1080",
        METADATA_KEY_DURATION to "10000",
    )

    /** An MP4's track: its frame rate rounded to an integer, as MPEG4Extractor gives it, and the track's duration. */
    private fun hevc(frameRate: Int = 30, durationUs: Long? = null) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        durationUs?.let { setLong(MediaFormat.KEY_DURATION, it) }
    }

    @Test
    fun `reads every field of a clip`() {
        val file = video(
            clip + mapOf(
                METADATA_KEY_HAS_AUDIO to "yes",
                METADATA_KEY_DATE to "20240501T102030.000Z",
                METADATA_KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_HLG.toString(),
            ),
            hevc(),
        )

        val meta = readVideoMetadata(file, ROME)

        // 125,000 bytes over 10 s is 100,000 bits per second.
        val expected = VideoMetadata(1920, 1080, 10_000, 30.0, 100_000, true, "video/hevc", true, LocalDateTime.of(2024, 5, 1, 12, 20, 30))
        assertEquals(expected, meta)
    }

    @Test
    fun `a rotation of a quarter turn swaps the width and the height`() {
        for (rotation in listOf("90", "270")) {
            val meta = readVideoMetadata(video(clip + (METADATA_KEY_VIDEO_ROTATION to rotation)), ROME)

            assertEquals(rotation, 1080 to 1920, meta.width to meta.height)
        }
        val upsideDown = readVideoMetadata(video(clip + (METADATA_KEY_VIDEO_ROTATION to "180")), ROME)
        assertEquals(1920 to 1080, upsideDown.width to upsideDown.height)
    }

    @Test
    fun `the UTC creation time becomes the local time of the zone at that instant`() {
        val file = video(clip + (METADATA_KEY_DATE to "20240101T102030.000Z"))

        // Winter time in Rome, and summer time would give 12:20:30.
        assertEquals(LocalDateTime.of(2024, 1, 1, 11, 20, 30), readVideoMetadata(file, ROME).capturedAt)
        assertEquals(LocalDateTime.of(2024, 1, 1, 5, 20, 30), readVideoMetadata(file, ZoneId.of("America/New_York")).capturedAt)
    }

    @Test
    fun `a zero creation time or one that does not parse is no capture time`() {
        for (date in listOf("19040101T000000.000Z", "19700101T000000.000Z", "not a date")) {
            assertNull(date, readVideoMetadata(video(clip + (METADATA_KEY_DATE to date)), ROME).capturedAt)
        }
    }

    @Test
    fun `the frame rate is the frames over the track's duration, not the rate rounded to an integer`() {
        // NTSC: 30 frames over 1.001 s, which MPEG4Extractor's own rate calls 30.
        val ntsc = video(clip + (METADATA_KEY_VIDEO_FRAME_COUNT to "30"), hevc(frameRate = 30, durationUs = 1_001_000))

        assertEquals(29.97, readVideoMetadata(ntsc, ROME).frameRate)
    }

    @Test
    fun `without a frame count the frame rate is the one the container declares`() {
        assertEquals(25.0, readVideoMetadata(video(clip, hevc(frameRate = 25, durationUs = 1_000_000)), ROME).frameRate)
    }

    @Test
    fun `an SDR clip with no audio, no extractor track and no duration has none of those`() {
        val meta = readVideoMetadata(video(clip - METADATA_KEY_DURATION + (METADATA_KEY_COLOR_TRANSFER to "3")), ROME)

        assertEquals(VideoMetadata(1920, 1080, null, null, null, false, null, false), meta)
    }

    @Test
    fun `a file whose video track the retriever does not see is unreadable, not dropped`() {
        // An audio-only MP4, or a ProRes MOV whose codec the extractor does not know: the retriever says the same of both.
        val unseen = video(mapOf(METADATA_KEY_HAS_AUDIO to "yes"))

        assertThrows(UnreadableVideo::class.java) { readVideoMetadata(unseen, ROME) }
    }

    @Test
    fun `a header that does not read or gives no size is unreadable`() {
        val broken = folder.newFile()
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(broken.path), IllegalArgumentException("setDataSource failed"))
        val sizeless = video(clip - METADATA_KEY_VIDEO_WIDTH)

        for (file in listOf(broken, sizeless)) assertThrows(UnreadableVideo::class.java) { readVideoMetadata(file, ROME) }
    }
}
