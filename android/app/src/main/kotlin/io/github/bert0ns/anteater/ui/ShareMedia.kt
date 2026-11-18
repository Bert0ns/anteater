// The share button of the full size views (docs/design.md §8): the system share sheet, over the item's MediaStore row.
package io.github.bert0ns.anteater.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bert0ns.anteater.R
import io.github.bert0ns.anteater.sources.mediaUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The share sheet's intent for the MediaStore item at [uri]. The file is not copied: the receiving app reads it through the grant. */
internal fun shareIntent(uri: Uri, mimeType: String?): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser reads the grant and the preview from this clip.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Opens the share sheet for the image or video at [path]. False when MediaStore has no row for it, or the sheet could not open. */
internal suspend fun shareMedia(context: Context, path: String): Boolean {
    val resolver = context.contentResolver
    val (uri, type) = withContext(Dispatchers.IO) {
        mediaUri(resolver, path)?.let { it to resolver.getType(it) }
    } ?: return false
    return try {
        context.startActivity(shareIntent(uri, type))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("share", "Could not open the share sheet", e)
        false
    }
}

/** The button that shares the photo or video at [path]. A toast says so when it has no MediaStore row to share. */
@Composable
internal fun ShareButton(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.share)
    val failed = stringResource(R.string.share_failed)
    IconButton(
        { scope.launch { if (!shareMedia(context, path)) withContext(Dispatchers.Main) { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() } } },
        modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape),
    ) { Icon(ShareIcon, label, tint = Color.White) }
}

// Material's Filled.Share, which material-icons-extended holds and the app does not depend on.
private val ShareIcon: ImageVector by lazy {
    ImageVector.Builder("Filled.Share", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92 1.61,0 2.92,-1.31 2.92,-2.92s-1.31,-2.92 -2.92,-2.92z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
