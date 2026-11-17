package io.github.bert0ns.anteater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.review.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppliedMessageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val images = (1..3).map { MediaRef("/sdcard/$it.jpg", 1_000_000, 0) }

    @Test
    fun `a trashed page says how many and their size, which does not break`() {
        val message = appliedMessage(context, Decision(trashed = images.take(2), kept = images.drop(2), failed = emptyList()))

        assertEquals("2 images moved to the trash, 2.0 MB", message)
    }

    @Test
    fun `a page with nothing marked was kept`() {
        assertEquals("3 images kept", appliedMessage(context, Decision(trashed = emptyList(), kept = images, failed = emptyList())))
    }

    @Test
    fun `a page whose moves all failed says so, and not that it kept nothing`() {
        val message = appliedMessage(context, Decision(trashed = emptyList(), kept = emptyList(), failed = images))

        assertEquals("3 images changed since the scan and stayed where they were", message)
    }

    @Test
    fun `a page with some failed moves says both`() {
        val message = appliedMessage(context, Decision(trashed = images.take(1), kept = emptyList(), failed = images.drop(1)))

        assertEquals("1 image moved to the trash, 1.0 MB. 2 images changed since the scan and stayed where they were", message)
    }

    private val clip = MediaRef("/sdcard/clip.mp4", 1_000_000, 0, MediaType.VIDEO)

    @Test
    fun `what stays in the trash is called an image while all of it is images`() {
        assertEquals("1 image stays in the trash: a file now has its name, or its folder is gone", stayedMessage(context, images.take(1), emptyList()))
    }

    @Test
    fun `what stays in the trash is called a file once a video is among it`() {
        assertEquals("2 files stay in the trash: a file now has their name, or their folder is gone", stayedMessage(context, listOf(images[0], clip), emptyList()))
    }

    @Test
    fun `a file refused because its folder is on another volume gets its own reason`() {
        assertEquals(
            "1 image stays in the trash: a file now has its name, or its folder is gone. " +
                "1 file stays in the trash: its folder is on another storage, and Cribrum does not copy files back",
            stayedMessage(context, listOf(images[0], clip), listOf(clip)),
        )
    }

    @Test
    fun `a page of videos says files, not images`() {
        assertEquals("1 file moved to the trash, 1.0\u00A0MB. 1 file changed since the scan and stayed where it was", appliedMessage(context, Decision(trashed = listOf(clip), kept = emptyList(), failed = listOf(clip.copy(path = "/sdcard/b.mp4")))))
        assertEquals("1 file kept", appliedMessage(context, Decision(trashed = emptyList(), kept = listOf(clip), failed = emptyList())))
    }
}
