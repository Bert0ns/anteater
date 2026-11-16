package io.github.bert0ns.cribrum.ui

import android.media.MediaMetadataRetriever
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class VideoTilesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a length read for one video and then another shows each one's own length when it comes back`() {
        // Files of their own: the lengths read are kept for the process.
        val long = File("/sdcard/DCIM/tiles-test/long.mp4")
        val short = File("/sdcard/DCIM/tiles-test/short.mp4")
        ShadowMediaMetadataRetriever.addMetadata(long.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "122000")
        ShadowMediaMetadataRetriever.addMetadata(short.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "35000")
        var file by mutableStateOf(long)
        // The full size view's bar stays in place while the pages under it change, as this does.
        compose.setContent { Text(rememberDuration(file) ?: "none") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("02:02").fetchSemanticsNodes().isNotEmpty() }

        file = short
        compose.waitUntil(5_000) { compose.onAllNodesWithText("00:35").fetchSemanticsNodes().isNotEmpty() }

        // Back to a video whose length is already read: its own, not the one shown last.
        file = long
        compose.waitForIdle()
        compose.onNodeWithText("02:02").assertExists()
    }
}
