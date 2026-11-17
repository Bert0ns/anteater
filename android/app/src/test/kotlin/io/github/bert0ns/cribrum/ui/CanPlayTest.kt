package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class CanPlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))
    private var canPlay = true

    private fun show(watching: Boolean = true) {
        compose.setContent { canPlay = rememberCanPlay(watching) }
        compose.waitForIdle()
    }

    private fun powerSave(on: Boolean) {
        power.setIsPowerSaveMode(on)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        compose.waitForIdle()
    }

    private fun thermal(status: Int) {
        compose.runOnUiThread { power.setCurrentThermalStatus(status) }
        compose.waitForIdle()
    }

    @Test
    fun `battery saver stops the loops, and turning it off lets them play again`() {
        show()
        assertTrue(canPlay)

        powerSave(true)
        assertFalse(canPlay)

        powerSave(false)
        assertTrue(canPlay)
    }

    @Test
    fun `a phone that is moderately hot or worse stops the loops, and a lightly warm one does not`() {
        show()

        thermal(PowerManager.THERMAL_STATUS_LIGHT)
        assertTrue(canPlay)

        thermal(PowerManager.THERMAL_STATUS_MODERATE)
        assertFalse(canPlay)

        thermal(PowerManager.THERMAL_STATUS_SEVERE)
        assertFalse(canPlay)

        thermal(PowerManager.THERMAL_STATUS_NONE)
        assertTrue(canPlay)
    }

    @Test
    fun `a screen that shows no videos reads neither`() {
        var watching by mutableStateOf(false)
        compose.setContent { canPlay = rememberCanPlay(watching) }
        power.setIsPowerSaveMode(true)
        compose.waitForIdle()
        assertTrue(canPlay)

        watching = true
        compose.waitForIdle()
        assertFalse(canPlay) // read when the screen starts watching, with no broadcast
    }
}
