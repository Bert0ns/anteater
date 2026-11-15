// The offer to free the space of a large trashing at once (docs/design.md §9): the trash frees nothing until its retention ends.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.trash.TrashedImage

/** "1 GB" as the app shows sizes: Android's formatter counts in powers of 1000. */
private const val FREE_NOW_BYTES = 1_000_000_000L

/** What [decision] trashed, in bytes, when it is more than 1 GB, or null when the snackbar offers only Undo. */
internal fun freeNowBytes(decision: Decision): Long? = decision.trashed.sumOf { it.size }.takeIf { it > FREE_NOW_BYTES }

/** The snackbar of a page that trashed more than 1 GB: Undo, and [freeNow], which asks before deleting those files for good. */
internal class FreeNowVisuals(
    val decision: Decision,
    override val message: String,
    val undo: String,
    val freeNow: String,
    val onFreeNow: () -> Unit,
) : SnackbarVisuals {
    override val actionLabel get() = undo
    override val withDismissAction get() = true
    override val duration get() = SnackbarDuration.Long
}

/** The app's snackbars: Material's own, and the two actions of [FreeNowVisuals]. Free leaves Undo on screen while its dialog asks. */
@Composable
internal fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val visuals = data.visuals as? FreeNowVisuals
        if (visuals == null) {
            Snackbar(data)
            return@SnackbarHost
        }
        val colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor)
        Snackbar(
            action = {
                Row {
                    TextButton(visuals.onFreeNow, colors = colors) { Text(visuals.freeNow) }
                    TextButton(data::performAction, colors = colors) { Text(visuals.undo) }
                }
            },
            dismissAction = { IconButton(data::dismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close)) } },
            actionOnNewLine = true,
        ) { Text(visuals.message) }
    }
}

/** Asks before deleting for good [items], what the last page trashed and is still in the trash (D6), and says how many and that they cannot come back. */
@Composable
internal fun FreeNowDialog(items: List<TrashedImage>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = items.size
    val size = Formatter.formatShortFileSize(LocalContext.current, items.sumOf { it.original.size })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.delete_files_title, count, count)) },
        text = { Text(pluralStringResource(R.plurals.free_now_body, count, count, size)) },
        confirmButton = { TextButton(onConfirm, colors = destructive()) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
