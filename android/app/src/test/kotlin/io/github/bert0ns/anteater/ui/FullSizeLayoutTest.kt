package io.github.bert0ns.anteater.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import io.github.bert0ns.anteater.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The close and share buttons at the top left and the checkbox at the top right are separate overlays: on a narrow screen they must not cover each other. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w340dp-h640dp-xhdpi")
class FullSizeLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Robolectric measures text at about one pixel a character, so the label is long enough to fill the screen here, as the Italian
     * one does on a phone at this width and font scale. The checkbox has to give way, not cover the buttons.
     */
    private fun topControls(label: String): Triple<Rect, Rect, Rect> {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                FullSizeViewer(listOf("/sdcard/a.jpg"), "/sdcard/a.jpg", onClose = {}, image = {}) {
                    FullSizeToggle(true, label, Color.Red, Color.White) {}
                }
            }
        }
        val close = compose.onNodeWithContentDescription(compose.activity.getString(R.string.close)).fetchSemanticsNode().boundsInRoot
        val share = compose.onNodeWithContentDescription(compose.activity.getString(R.string.share)).fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNode(isToggleable()).fetchSemanticsNode().boundsInRoot
        return Triple(close, share, toggle)
    }

    @Test
    fun `at 340 dp and font scale 1_3 a long checkbox label gives way to the close and share buttons`() {
        val (close, share, toggle) = topControls("Marked for the trash ".repeat(100))

        assertTrue("close $close, share $share, toggle $toggle", close.right <= share.left && share.right <= toggle.left)
    }

    @Test
    fun `a checkbox label that fits is not cut`() {
        val (_, share, toggle) = topControls("Selected")

        assertTrue("share $share, toggle $toggle", share.right <= toggle.left)
        assertTrue("the checkbox is a pill of its own, not the width of the screen: $toggle", toggle.width < compose.activity.resources.displayMetrics.widthPixels / 2)
    }
}
