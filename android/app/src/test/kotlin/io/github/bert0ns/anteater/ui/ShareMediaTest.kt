package io.github.bert0ns.anteater.ui

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A MediaStore that knows the images in [rows] (path to row id and MIME type) and nothing else. */
class ShareMedia : ContentProvider() {
    companion object {
        var rows: Map<String, Pair<Long, String>> = emptyMap()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
        if ("images" in uri.pathSegments) args?.firstOrNull()?.let { rows[it] }?.let { cursor.addRow(arrayOf(it.first)) }
        return cursor
    }
    override fun getType(uri: Uri): String? = rows.values.firstOrNull { it.first == ContentUris.parseId(uri) }?.second
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class ShareMediaTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // An activity starts the sheet, as in the app: an application context would need a new task flag.
    private val activity get() = compose.activity
    private val photo = "/sdcard/DCIM/Camera/holiday.jpg"

    @Before
    fun store() {
        ShareMedia.rows = mapOf(photo to (42L to "image/jpeg"))
        Robolectric.setupContentProvider(ShareMedia::class.java, MediaStore.AUTHORITY)
    }

    private fun sent(): Intent? = shadowOf(activity).nextStartedActivity

    @Test
    fun `the share sheet sends the MediaStore uri of the item with its type and a read grant, and copies nothing`() {
        assertTrue(runBlocking { shareMedia(activity, photo) })

        val chooser = sent()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 42), uri)
        assertEquals("content", uri.scheme)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `a file MediaStore does not index is not shared, and no sheet opens`() {
        assertFalse(runBlocking { shareMedia(activity, "/sdcard/DCIM/.hidden/none.jpg") })

        assertNull(sent())
    }

    @Test
    fun `the full size view has a share button, and it opens the sheet for the page it is on`() {
        compose.setContent { FullSizeViewer(listOf(photo), photo, onClose = {}, image = {}) {} }

        compose.onNodeWithContentDescription("Share").performClick()
        compose.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(activity).peekNextStartedActivity() != null
        }

        assertNotNull(sent())
    }

    @Test
    fun `the share button says so when the file cannot be shared`() {
        val none = "/sdcard/DCIM/.hidden/none.jpg"
        compose.setContent { FullSizeViewer(listOf(none), none, onClose = {}, image = {}) {} }

        compose.onNodeWithContentDescription("Share").performClick()
        compose.waitUntil {
            shadowOf(Looper.getMainLooper()).idle() // the toast is made on the main thread, which this test thread is
            ShadowToast.getLatestToast() != null
        }

        assertEquals("Can't share this file", ShadowToast.getTextOfLatestToast())
        assertNull(sent())
    }

    @Test
    fun `a view that is not shareable has no share button`() {
        compose.setContent { FullSizeViewer(listOf(photo), photo, onClose = {}, image = {}, shareable = false) {} }

        compose.onAllNodesWithContentDescription("Share").assertCountEquals(0)
    }
}
