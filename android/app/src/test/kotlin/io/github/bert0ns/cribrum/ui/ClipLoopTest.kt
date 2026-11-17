package io.github.bert0ns.cribrum.ui

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How a loop hands the tile's surface to its player, which Robolectric never does on its own: it draws no TextureView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipLoopTest {
    private val players = Players()
    private val loop = ClipLoop { players.factory(ApplicationProvider.getApplicationContext(), it) }
    private val clip = File("/sdcard/DCIM/Camera/clip.mp4")

    /** Runs what the loops' thread holds, then what it posted back to the main thread. */
    private fun flush() {
        val done = CountDownLatch(1)
        Handler(clipLoops).post { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the player draws on the tile's surface, and lets go of it before the surface is released`() {
        loop.play(clip, null)
        val texture = SurfaceTexture(false)
        loop.onSurfaceTextureAvailable(texture, 1, 1)
        flush()
        val player = players.made.single()
        val surface = player.output as Surface
        assertTrue(surface.isValid)

        assertFalse(loop.onSurfaceTextureDestroyed(texture)) // the loop releases the texture itself, once the player let go
        flush()

        assertNull(player.output)
        assertEquals(true, player.clearedValid)
        assertFalse(surface.isValid)
    }

    @Test
    fun `a loop released before its view goes still releases the surface`() {
        loop.play(clip, null)
        val texture = SurfaceTexture(false)
        loop.onSurfaceTextureAvailable(texture, 1, 1)
        flush()
        val player = players.made.single()
        val surface = player.output as Surface

        loop.release()
        flush()
        assertTrue(player.released)

        assertFalse(loop.onSurfaceTextureDestroyed(texture))
        flush()
        assertNull(player.clearedValid) // a released player is told nothing
        assertFalse(surface.isValid)
    }
}
