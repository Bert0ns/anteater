// "Largest videos" and the Videos tab of Clean (docs/design.md §6.5, §8): every video not reviewed, largest first, as the Compress page lists photos.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.VideoItem
import io.github.bert0ns.cribrum.trash.TrashRetention
import java.io.File

/** The two tabs of Clean: Photos, the list of categories, and Videos. */
@Composable
fun CleanTabs(selected: CleanTab, onSelect: (CleanTab) -> Unit, modifier: Modifier = Modifier) {
    PrimaryTabRow(selected.ordinal, modifier) {
        for (tab in CleanTab.entries) {
            Tab(tab == selected, { onSelect(tab) }, text = { Text(stringResource(if (tab == CleanTab.PHOTOS) R.string.tab_photos else R.string.tab_videos)) })
        }
    }
}

/**
 * The Videos tab, under "Scan videos": how many videos wait for that scan, then "Largest videos" with its count and the space
 * it can free, which opens the list. Until a scan has found a video, it says they appear after the next scan, or that the
 * phone has none once a scan counted them ([noVideos]).
 */
@Composable
fun VideosTab(
    videos: List<VideoItem>,
    indexed: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    waiting: Int = 0,
    noVideos: Boolean = false,
    /** The categories with a video detector, listed after "Largest videos". */
    categories: List<CategorySummary> = emptyList(),
    onOpenCategory: (Category) -> Unit = {},
    /** "Scan videos" has started once: until then a category with nothing in it says it needs that scan. */
    scanRan: Boolean = true,
) {
    LazyColumn(modifier.fillMaxSize()) {
        if (!indexed) {
            item { Text(stringResource(if (noVideos) R.string.videos_none_found else R.string.videos_after_scan), Modifier.padding(16.dp)) }
            return@LazyColumn
        }
        if (waiting > 0) {
            item { Text(pluralStringResource(R.plurals.videos_waiting, waiting, waiting), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        val bytes = videos.sumOf { it.video.size }
        if (bytes > 0) {
            item {
                Text(
                    stringResource(R.string.freeable_total, Formatter.formatShortFileSize(LocalContext.current, bytes)),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        item {
            val canOpen = videos.isNotEmpty()
            ListItem(
                headlineContent = { Text(stringResource(R.string.videos_largest)) },
                supportingContent = {
                    val count = pluralStringResource(R.plurals.videos_count, videos.size, videos.size)
                    Text(if (canOpen) stringResource(R.string.scan_joined, count, Formatter.formatShortFileSize(LocalContext.current, bytes)) else count)
                },
                // Greyed out like a category with nothing to open.
                modifier = Modifier.clickable(enabled = canOpen, onClick = onOpen).alpha(if (canOpen) 1f else 0.5f),
            )
            HorizontalDivider()
        }
        items(categories, key = { it.category }) { summary ->
            val canOpen = summary.count > 0
            ListItem(
                headlineContent = { Text(categoryName(summary.category, MediaType.VIDEO)) },
                supportingContent = {
                    val count = pluralStringResource(R.plurals.videos_count, summary.count, summary.count)
                    Text(
                        when {
                            canOpen -> stringResource(R.string.scan_joined, count, Formatter.formatShortFileSize(LocalContext.current, summary.bytes))
                            !scanRan -> stringResource(R.string.videos_category_needs_scan)
                            else -> count
                        },
                    )
                },
                // Greyed out like a category with nothing to open.
                modifier = Modifier.clickable(enabled = canOpen) { onOpenCategory(summary.category) }.alpha(if (canOpen) 1f else 0.5f),
            )
            HorizontalDivider()
        }
    }
}

/**
 * "Largest videos": a grid of tiles, largest first. A tap selects, a long press opens the video full size, and nothing is
 * selected for the user, since these are videos they keep. The main button trashes the selection and says what it holds;
 * "Keep" marks it kept. A favourite among the selection asks first (D6, §6.2).
 */
@Composable
fun VideosScreen(
    videos: List<VideoItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    /** Gets the selection the user saw, so an apply is of what they confirmed: trashed, or kept. */
    onApply: (paths: Set<String>, trash: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown right above the buttons, which a snackbar at the bottom of the screen would cover. */
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    retentionDays: Int = TrashRetention.DEFAULT_DAYS,
    /** A change is running: the list on screen is about to be replaced, so confirming it is off. */
    loading: Boolean = false,
    /** Makes the video a gallery favourite, or takes the mark off, from the full size view. */
    onFavorite: (MediaRef, Boolean) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    var askFavorites by remember { mutableStateOf(false) }
    var fullSize by remember { mutableStateOf<VideoItem?>(null) }
    val chosen = videos.filter { it.video.path in selected }
    val favorites = chosen.count { it.isFavorite }
    val size = Formatter.formatShortFileSize(LocalContext.current, chosen.sumOf { it.video.size }).replace(' ', ' ')
    val paths = chosen.map { it.video.path }.toSet()
    val trash = {
        haptics.performHapticFeedback(Haptic.TRASH.type)
        onApply(paths, true)
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (videos.isEmpty()) {
                Text(stringResource(R.string.videos_none_left), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.scan_joined, pluralStringResource(R.plurals.videos_count, videos.size, videos.size), Formatter.formatShortFileSize(LocalContext.current, videos.sumOf { it.video.size })),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.videos_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
            items(videos, key = { it.video.path }) { item ->
                VideoTile(item, item.video.path in selected, onHold = { fullSize = item }) {
                    haptics.performHapticFeedback(Haptic.TICK.type)
                    onToggle(item.video.path)
                }
            }
        }
        AppSnackbarHost(snackbar)
        if (chosen.isNotEmpty()) {
            Buttons(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = { if (favorites > 0) askFavorites = true else trash() }, enabled = !loading) {
                    Text(pluralStringResource(R.plurals.videos_trash, chosen.size, chosen.size, size))
                }
                OutlinedButton(onClick = {
                    haptics.performHapticFeedback(Haptic.KEEP.type)
                    onApply(paths, false)
                }, enabled = !loading) { Text(stringResource(R.string.videos_keep)) }
            }
        }
    }
    // Trashing a favourite is never a surprise: it was selected on purpose, and the dialog says so once more.
    if (askFavorites && favorites > 0) {
        AlertDialog(
            onDismissRequest = { askFavorites = false },
            icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            title = { Text(pluralStringResource(R.plurals.videos_summary_title, chosen.size, chosen.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.videos_summary_body, retentionDays, size, retentionDays))
                    Text(pluralStringResource(R.plurals.videos_summary_favorites, favorites, favorites), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton({
                    askFavorites = false
                    trash()
                }, enabled = !loading, colors = destructive()) { Text(stringResource(R.string.summary_confirm_favorites)) }
            },
            dismissButton = { TextButton({ askFavorites = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A trash, a keep or a scan can take the video off the list while it is open.
    LaunchedEffect(videos, fullSize) {
        if (fullSize != null && videos.none { it.video.path == fullSize?.video?.path }) fullSize = null
    }
    fullSize?.let { start ->
        FullSize(videos, start, selected, onToggle, onFavorite) { fullSize = null }
    }
}

/** The length the index read, or else the one the tile reads from the file, as the trash does: a video scan is not needed to show it. */
@Composable
private fun VideoItem.lengthText(): String? = durationMs?.let(::formatLength) ?: if (unreadable) null else rememberDuration(File(video.path), "${video.path}|${video.size}|${video.mtimeNs}")

/** A frame with its length and size. A tap selects it and a long press opens it full size. A video that cannot be read draws a placeholder and says so. */
@Composable
private fun VideoTile(item: VideoItem, selected: Boolean, onHold: () -> Unit, onToggle: () -> Unit) {
    val path = item.video.path
    val size = Formatter.formatShortFileSize(LocalContext.current, item.video.size)
    val length = item.lengthText()
    val parts = buildList {
        add(stringResource(R.string.tile_description, File(path).name, videoDetails(item.video.size, length)))
        if (item.unreadable) add(stringResource(R.string.video_unreadable))
        add(stringResource(if (selected) R.string.video_tile_selected else R.string.video_tile_not_selected))
        if (item.isFavorite) add(stringResource(R.string.favorite_video))
    }
    val toggle = stringResource(if (selected) R.string.action_deselect else R.string.action_select)
    val open = stringResource(R.string.action_open_full_size)
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .combinedClickable(onClick = onToggle, onClickLabel = toggle, onLongClick = onHold, onLongClickLabel = open)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
                contentDescription = parts.joinToString(". ")
                customActions = listOf(
                    CustomAccessibilityAction(toggle) {
                        onToggle()
                        true
                    },
                    CustomAccessibilityAction(open) {
                        onHold()
                        true
                    },
                )
            },
    ) {
        VideoFrame(File(path), "Videos.tile", Modifier.fillMaxSize(), unreadable = item.unreadable)
        // The description says all of this, so the visible texts stay out of the screen reader's way.
        Row(Modifier.align(Alignment.TopStart).clearAndSetSemantics {}.background(Color.Black.copy(alpha = 0.6f)).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp), tint = Color.White)
            if (length != null) Text(length, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text(size, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {}, color = Color.White, style = MaterialTheme.typography.labelSmall)
        // As in the Compress page: the heart sits at the bottom right, and at the top right once selected, beside the check.
        if (selected) {
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectedCheck()
                if (item.isFavorite) FavoriteHeart(Modifier.clearAndSetSemantics {})
            }
        } else if (item.isFavorite) {
            FavoriteHeart(Modifier.align(Alignment.BottomEnd).padding(4.dp).clearAndSetSemantics {})
        }
    }
}

/**
 * The videos of the list at full size, over the viewer the review and Compress share: a tap on a video plays it, with the controls
 * and the strip of frames in the bar, a swipe to another video stops it, and a heart makes the video a gallery favourite or takes that off (§8).
 */
@Composable
private fun FullSize(videos: List<VideoItem>, start: VideoItem, selected: Set<String>, onToggle: (String) -> Unit, onFavorite: (MediaRef, Boolean) -> Unit, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val playback = rememberVideoPlayback()
    // A video that leaves the list while it plays leaves the pager's page where it was, so no swipe stops it.
    val paths = videos.map { it.video.path }
    LaunchedEffect(paths) { if (playback.loaded != null && playback.loaded !in paths) playback.stop() }
    val image: @Composable (Int) -> Unit = { page ->
        videos.getOrNull(page)?.let { VideoPage(File(it.video.path), "Videos.fullSize", playback, it.unreadable, onClose) }
    }
    FullSizeViewer(videos.map { it.video.path }, start.video.path, onClose, image, onPage = { playback.stop() }) { page ->
        val item = videos.getOrNull(page) ?: return@FullSizeViewer // the list emptied under the dialog; the screen closes it next
        FullSizeToggle(item.video.path in selected, stringResource(R.string.video_tile_selected), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) { onToggle(item.video.path) }
        // A selected video is one the user means to trash, and a favourite and a trash contradict each other, as in the review.
        val heart: @Composable () -> Unit = {
            FavoriteToggle(item.isFavorite, enabled = item.video.path !in selected, video = true) {
                haptics.performHapticFeedback(Haptic.TICK.type)
                onFavorite(item.video, it)
            }
        }
        FullSizeBar(item.video.path, item.isFavorite, heart = heart) {
            val file = File(item.video.path)
            val length = item.durationMs ?: if (item.unreadable) null else rememberLengthMillis(file, "${item.video.path}|${item.video.size}|${item.video.mtimeNs}")
            VideoControls(playback, file, "Videos.fullSize", length, item.unreadable)
            Text(videoDetails(item.video.size, item.lengthText()), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
