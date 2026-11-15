package io.github.bert0ns.cribrum.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class FreeNowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun decision(vararg sizes: Long) = Decision(sizes.mapIndexed { i, size -> MediaRef("/sdcard/DCIM/$i.mp4", size, 0) }, emptyList(), emptyList())

    @Test
    fun `only a page that trashed more than 1 GB offers to free it now`() {
        assertNull(freeNowBytes(decision(600_000_000, 400_000_000)))
        assertEquals(1_000_000_001L, freeNowBytes(decision(600_000_000, 400_000_001)))
    }

    @Test
    fun `the snackbar offers to free the space now next to Undo, and Free keeps the Undo on screen`() {
        val state = SnackbarHostState()
        var freed = false
        var result: SnackbarResult? = null
        compose.setContent {
            AppSnackbarHost(state)
            LaunchedEffect(Unit) { result = state.showSnackbar(FreeNowVisuals(decision(2_300_000_000), "1 image moved to the trash, 2.3 GB", "Undo", "Free 2.3 GB now") { freed = true }) }
        }

        compose.onNodeWithText("Free 2.3 GB now").performClick()
        assertTrue(freed)
        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil { result != null }
        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    @Test
    fun `the dialog says how many files go and that they cannot be restored, and deletes only on confirm`() {
        var confirmed = false
        var dismissed = false
        val items = decision(2_000_000_000, 300_000_000).trashed.map { TrashedImage(it, Instant.EPOCH, File("/trash/${it.path}"), Instant.EPOCH) }
        compose.setContent { FreeNowDialog(items, onConfirm = { confirmed = true }, onDismiss = { dismissed = true }) }

        compose.onNodeWithText("Delete 2 files for good?").assertExists()
        compose.onNodeWithText("This frees 2.3 GB. Only the 2 files you just trashed are deleted, and they cannot be restored.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertFalse(confirmed)
        compose.onNodeWithText("Delete").performClick()

        assertTrue(confirmed)
    }
}
