// The review screens (docs/design.md §8): categories, a preselected grid per category, and the
// summary before trashing.
package io.github.bert0ns.cribrum.ui

import android.provider.Settings
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.window.core.layout.WindowSizeClass
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.TrashRetention
import kotlinx.coroutines.flow.drop
import java.io.File

/** The category's name, which agrees with [mediaType] where a language needs it: Italian says "Inoltrate" of photos and "Inoltrati" of videos. */
@Composable
fun categoryName(category: Category, mediaType: MediaType = MediaType.IMAGE): String = stringResource(
    when (category) {
        Category.EXACT_DUPLICATE -> R.string.category_exact_duplicate
        Category.NEAR_DUPLICATE -> R.string.category_near_duplicate
        Category.BURST -> R.string.category_burst
        Category.BLURRY -> R.string.category_blurry
        Category.BAD_EXPOSURE -> R.string.category_bad_exposure
        Category.SCREENSHOT -> R.string.category_screenshot
        Category.DOCUMENT -> R.string.category_document
        Category.FORWARDED -> if (mediaType == MediaType.VIDEO) R.string.category_forwarded_videos else R.string.category_forwarded
        Category.STICKER -> R.string.category_sticker
        Category.TAKES -> R.string.category_takes
        Category.SHORT_CLIPS -> R.string.category_short_clips
    },
)

@Composable
fun CategoryList(categories: List<CategorySummary>, freeableBytes: Long, onOpen: (Category) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        if (freeableBytes > 0) {
            item {
                Text(
                    stringResource(R.string.freeable_total, Formatter.formatShortFileSize(LocalContext.current, freeableBytes)),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        items(categories, key = { it.category }) { summary ->
            val canOpen = summary.hasDetector && summary.count > 0
            ListItem(
                headlineContent = { Text(categoryName(summary.category)) },
                supportingContent = {
                    Text(
                        if (!summary.hasDetector) {
                            stringResource(R.string.category_coming_soon)
                        } else {
                            // The header says once what the numbers are: images to review and the space they can free.
                            val count = pluralStringResource(R.plurals.category_images, summary.count, summary.count)
                            if (summary.count == 0) count else stringResource(R.string.scan_joined, count, Formatter.formatShortFileSize(LocalContext.current, summary.bytes))
                        },
                    )
                },
                // Greyed out like a disabled button: there is nothing to open.
                modifier = Modifier.clickable(enabled = canOpen) { onOpen(summary.category) }.alpha(if (canOpen) 1f else 0.5f),
            )
            HorizontalDivider()
        }
    }
}

/** The control of the category screen's top bar: one menu with the review order to choose. */
@Composable
fun ReviewOrderMenu(selected: ReviewOrder, options: List<ReviewOrder>, onSelect: (ReviewOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }) { Icon(SortIcon, stringResource(R.string.review_order)) }
    DropdownMenu(open, { open = false }) {
        for (grouping in options) {
            DropdownMenuItem(
                text = { Text(stringResource(grouping.title)) },
                leadingIcon = { if (grouping == selected) Icon(Icons.Default.Check, null) },
                onClick = {
                    open = false
                    onSelect(grouping)
                },
            )
        }
    }
}

/** Three lines of falling length. The core icon set has no sort icon and the extended one is large. */
private val SortIcon = ImageVector.Builder("Sort", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f)
        horizontalLineTo(21f)
        verticalLineTo(8f)
        horizontalLineTo(3f)
        close()
        moveTo(3f, 11f)
        horizontalLineTo(15f)
        verticalLineTo(13f)
        horizontalLineTo(3f)
        close()
        moveTo(3f, 16f)
        horizontalLineTo(9f)
        verticalLineTo(18f)
        horizontalLineTo(3f)
        close()
    }
}.build()

private val ReviewOrder.title
    get() = when (this) {
        ReviewOrder.OFF -> R.string.review_order_off
        ReviewOrder.DAY -> R.string.review_order_day
        ReviewOrder.WEEK -> R.string.review_order_week
        ReviewOrder.MONTH -> R.string.review_order_month
        ReviewOrder.SIZE -> R.string.review_order_size
    }

/**
 * A thumbnail that is marked for the trash or kept. Tap toggles, a long press shows it full size.
 * A screen reader hears why the image is here and how it is marked, and gets both as actions too.
 * A video tile that [loop]s plays its clip over its frame, to [loopEndMs] when that is set, and calls [onLoopFailed] when the
 * clip cannot be played. One that shows [slides] steps through the frames of its strip instead.
 */
@Composable
private fun Tile(item: ReviewItem, marked: Boolean, loop: Boolean, slides: Boolean, loopEndMs: Long?, onTap: () -> Unit, onHold: () -> Unit, onLoopFailed: () -> Unit) {
    val state = stringResource(
        when {
            marked -> R.string.selected_for_trash
            item.isBest -> R.string.kept_best_copy
            else -> R.string.kept
        },
    )
    val why = item.reasons.firstNotNullOfOrNull(::reasonOf)?.text() ?: File(item.image.path).name
    val video = item.image.mediaType == MediaType.VIDEO
    // A video's tile shows its length and size, so takes of one moment compare at a glance, and the description says them too.
    val details = lengthAndSize(item.image.size, item.durationMs?.let(::formatLength))
    val described = stringResource(R.string.tile_description, if (video) stringResource(R.string.tile_description, File(item.image.path).name, details) else why, state)
    // The tile's own description hides the heart's from a screen reader, so it says it too.
    val favorite = stringResource(if (video) R.string.favorite_video else R.string.favorite)
    val description = if (item.isFavorite) stringResource(R.string.tile_description, described, favorite) else described
    val toggle = stringResource(if (marked) R.string.action_keep else R.string.action_trash)
    val open = stringResource(R.string.action_open_full_size)
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (marked) 3.dp else 0.dp, if (marked) markColor else Color.Transparent)
            .combinedClickable(onClick = onTap, onClickLabel = toggle, onLongClick = onHold, onLongClickLabel = open)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(toggle) {
                        onTap()
                        true
                    },
                    CustomAccessibilityAction(open) {
                        onHold()
                        true
                    },
                )
            },
    ) {
        if (video) {
            val file = File(item.image.path)
            VideoFrame(file, "Review.tile", Modifier.fillMaxSize(), unreadable = item.unreadable)
            if (slides) {
                val length = item.durationMs ?: rememberLengthMillis(file, "${item.image.path}|${item.image.size}|${item.image.mtimeNs}")
                if (length != null && length > 0) Slideshow(file, length, Modifier.fillMaxSize())
            }
            if (loop) LoopingClip(file, loopEndMs, Modifier.fillMaxSize(), onLoopFailed)
            Text(details, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {}, color = Color.White, style = MaterialTheme.typography.labelSmall)
        } else {
            TracedAsyncImage(File(item.image.path), "Review.tile", null, Modifier.fillMaxSize(), tile = true)
        }
        // A kept tile is a plain photo, so a check means "selected" everywhere in the app. A favourite's heart takes
        // the top right corner when the image is marked, so the trash badge moves left of it.
        if (marked) {
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.background(markColor, MaterialTheme.shapes.small))
                if (item.isFavorite) FavoriteHeart(description = favorite)
            }
        } else if (item.isFavorite) {
            FavoriteHeart(Modifier.align(Alignment.BottomEnd).padding(4.dp), favorite)
        }
        if (item.isBest) {
            Text(
                stringResource(R.string.best_copy),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                // The description already says it is the best copy.
                // A video's size takes the bottom left corner.
                modifier = Modifier.align(if (video) Alignment.TopStart else Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {},
            )
        }
    }
}

/** The tiles on screen that play their clip, and the ones that show a slideshow, by key. */
private data class Motion(val loops: Set<Any>, val slides: Set<Any>)

/** How much of a copy a tile loops: its first 10 seconds, since copies can be long videos. A short clip or a take loops whole. */
private const val COPY_LOOP_MS = 10_000L

/**
 * The tiles of a video review that move (#457): every short clip, the best copy of each group of copies, and the longest
 * take of each moment, since the user compares the others against it. The other categories keep still frames, and an
 * unreadable video never moves.
 */
private fun movingTiles(category: Category, page: List<ReviewGroup>): Set<String> = when (category) {
    Category.SHORT_CLIPS -> page.flatMap { it.images }.filterNot { it.unreadable }
    Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE -> page.mapNotNull { group -> group.images.firstOrNull { it.isBest && !it.unreadable } }
    // The longest readable take, so a moment whose longest take cannot be read still moves.
    Category.TAKES -> page.mapNotNull { group -> group.images.filterNot { it.unreadable }.maxByOrNull { it.durationMs ?: 0 } }
    else -> emptyList()
}.map { it.image.path }.toSet()

/** The badge of a tile selected for an action, in Compress and in the trash. A check means "selected" everywhere. */
@Composable
internal fun SelectedCheck(modifier: Modifier = Modifier) {
    Icon(Icons.Filled.Check, null, modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small), tint = MaterialTheme.colorScheme.onPrimary)
}

/** Tells the image is a favourite in the gallery. The dark disc keeps the heart legible on a light photo, and the pink on a dark one. */
@Composable
internal fun FavoriteHeart(modifier: Modifier = Modifier, description: String = stringResource(R.string.favorite)) {
    Icon(
        Icons.Filled.Favorite,
        contentDescription = description,
        tint = Color(0xFFFF6B81),
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(3.dp).size(18.dp),
    )
}

@Composable
fun CategoryScreen(
    page: List<ReviewGroup>,
    remaining: Int,
    marked: Set<String>,
    grouped: Boolean,
    onToggle: (MediaRef) -> Unit,
    /** Gets the paths to trash and to keep that the user confirmed, so an apply is of what they saw. */
    onApply: (toTrash: Set<String>, toKeep: Set<String>) -> Unit,
    /** Gets the ids of the groups on screen, so a skip is of what the user saw. */
    onSkip: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown right above the button, which a snackbar at the bottom of the screen would cover. */
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onExcludeFolder: (MediaRef) -> Unit = {},
    /** Makes the image a gallery favourite, or takes the mark off, from the full size view. */
    onFavorite: (MediaRef, Boolean) -> Unit = { _, _ -> },
    reviewOrder: ReviewOrder = ReviewOrder.OFF,
    retentionDays: Int = TrashRetention.DEFAULT_DAYS,
    /** A change is running: the page on screen is about to be replaced, so confirming it is off. */
    loading: Boolean = false,
    /** What the page holds: the texts say "images" or "videos". */
    mediaType: MediaType = MediaType.IMAGE,
    /** The category on screen, which says which video tiles move (#457). */
    category: Category? = null,
    /** False while the phone is hot or in battery saver: the tiles that would play show the slideshow instead. */
    canPlay: Boolean = true,
) {
    val videos = mediaType == MediaType.VIDEO
    val locale = LocalConfiguration.current.locales[0]
    val haptics = LocalHapticFeedback.current
    var askFavorites by remember { mutableStateOf(false) }
    var fullSize by remember { mutableStateOf<MediaRef?>(null) }
    val images = page.flatMap { it.images }
    val toTrash = images.filter { it.image.path in marked }
    val favorites = toTrash.count { it.isFavorite }
    // Candidates only, as the header counts: a group's best copy is there for comparison, and nobody chose to keep it.
    val toKeepCount = if (grouped) images.count { it.isCandidate && it.image.path !in marked } else images.size - toTrash.size
    // The button with its counts, the 30 day trash and the Undo snackbar are the confirmation (D6). Only a favourite asks first.
    val apply = {
        haptics.performHapticFeedback((if (toTrash.isEmpty()) Haptic.KEEP else Haptic.TRASH).type)
        val trashPaths = toTrash.map { it.image.path }.toSet()
        onApply(trashPaths, images.map { it.image.path }.toSet() - trashPaths)
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (images.isNotEmpty()) {
                // Candidates only: a group shows its best copy too, for comparison.
                val shown = images.count { it.isCandidate }
                val position = if (remaining > shown) {
                    (if (videos) R.plurals.page_more_videos else R.plurals.page_more)
                } else if (videos) {
                    R.plurals.page_last_videos
                } else {
                    R.plurals.page_last
                }
                Text(pluralStringResource(position, remaining, shown, remaining), style = MaterialTheme.typography.titleSmall)
            }
            if (images.isEmpty()) {
                Text(stringResource(if (videos) R.string.review_done_videos else R.string.review_done), style = MaterialTheme.typography.bodySmall)
            } else if (images.none { it.suggestTrash && !it.isFavorite }) {
                // Takes, or a group whose best copy is in the trash or a sticker: a tap marks, so "tap to keep" would be wrong.
                Text(stringResource(if (videos) R.string.review_hint_unmarked_videos else R.string.review_hint_unmarked), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(if (videos) R.string.review_hint_videos else R.string.review_hint) + " " + stringResource(R.string.review_hint_gestures), style = MaterialTheme.typography.bodySmall)
            }
        }
        // A new grouping reorders the page: start at its top instead of in the middle of it.
        val grid = rememberLazyGridState()
        LaunchedEffect(reviewOrder) { grid.scrollToItem(0) }
        val context = LocalContext.current
        val animations = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
        val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
        // Nothing moves behind the full size view, so its player is the only one, nor in the background.
        val moves = animations && started && fullSize == null
        val movers = remember(page, category, videos) { if (videos && category != null) movingTiles(category, page) else emptySet() }
        // A clip the player could not play gives its player to the next tile, and shows the slideshow.
        var failedLoops by remember { mutableStateOf(emptySet<String>()) }
        // The tiles' keys are their paths. The first tiles on screen that move play, in grid order, and the others show the
        // slideshow. Nothing moves while the grid scrolls, so a fling builds and releases no player and decodes no frame for
        // each row it passes: the tiles on screen start once the grid settles.
        val motion by remember(grid, movers, moves, canPlay) {
            derivedStateOf(structuralEqualityPolicy()) {
                val onScreen = if (!moves || grid.isScrollInProgress) emptyList() else grid.layoutInfo.visibleItemsInfo.map { it.key }.filter { it in movers }
                val loops = if (canPlay) onScreen.filterNot { it in failedLoops }.take(LOOPING_CLIPS).toSet() else emptySet()
                Motion(loops, onScreen.toSet() - loops)
            }
        }
        val loopEndMs = if (category == Category.EXACT_DUPLICATE || category == Category.NEAR_DUPLICATE) COPY_LOOP_MS else null
        val columns = if (videos && category == Category.SHORT_CLIPS) {
            GridCells.Fixed(if (currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) 4 else 2)
        } else {
            GridCells.Adaptive(110.dp)
        }
        LazyVerticalGrid(columns, Modifier.weight(1f), grid) {
            for ((position, group) in page.withIndex()) {
                if (group.date != null && group.date != page.getOrNull(position - 1)?.date) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "date-${group.id}") {
                        Text(
                            reviewOrder.label(group.date, locale),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // At the text margin of the header above, not at the edge of the tiles.
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (grouped) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.id}") {
                        Column {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            if (group.hasExcludedCopy) {
                                Text(stringResource(R.string.excluded_copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                            if (group.hasTrashedBestCopy) {
                                Text(stringResource(R.string.trashed_best_copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
                items(group.images, key = { it.image.path }) { item ->
                    Tile(
                        item,
                        item.image.path in marked,
                        item.image.path in motion.loops,
                        item.image.path in motion.slides,
                        loopEndMs,
                        onTap = {
                            haptics.performHapticFeedback(Haptic.TICK.type)
                            onToggle(item.image)
                        },
                        onHold = { fullSize = item.image },
                        onLoopFailed = { failedLoops = failedLoops + item.image.path },
                    )
                }
            }
        }
        AppSnackbarHost(snackbar)
        if (images.isNotEmpty()) {
            Buttons(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = { if (favorites > 0) askFavorites = true else apply() }, enabled = !loading) {
                    Text(stringResource(R.string.apply, toTrash.size, toKeepCount))
                }
                OutlinedButton(onClick = {
                    haptics.performHapticFeedback(Haptic.TICK.type)
                    onSkip(page.map { it.id }.toSet())
                }) { Text(stringResource(R.string.skip)) }
            }
        }
    }
    // A favourite was marked on purpose, by a tap, but trashing one is never a surprise.
    if (askFavorites && favorites > 0) {
        val size = Formatter.formatShortFileSize(LocalContext.current, toTrash.sumOf { it.image.size })
        AlertDialog(
            onDismissRequest = { askFavorites = false },
            icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            title = { Text(pluralStringResource(if (videos) R.plurals.videos_summary_title else R.plurals.summary_title, toTrash.size, toTrash.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(if (videos) R.plurals.videos_summary_body else R.plurals.summary_body, retentionDays, size, retentionDays))
                    Text(pluralStringResource(if (videos) R.plurals.videos_summary_favorites else R.plurals.summary_favorites, favorites, favorites), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton({
                    askFavorites = false
                    apply()
                }, enabled = !loading, colors = destructive()) { Text(stringResource(R.string.summary_confirm_favorites)) }
            },
            dismissButton = { TextButton({ askFavorites = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A skip, an apply or an exclusion can take the image off the page while it is open.
    LaunchedEffect(images, fullSize) {
        if (fullSize != null && images.none { it.image == fullSize }) fullSize = null
    }
    fullSize?.let { start ->
        FullSize(images, start, marked, onToggle, onExcludeFolder = {
            fullSize = null
            haptics.performHapticFeedback(Haptic.KEEP.type)
            onExcludeFolder(it)
        }, onFavorite) { fullSize = null }
    }
}

/**
 * The images of the list at full size, to tell real blur from a small thumbnail. Swipe to page,
 * pinch to zoom, and drag to pan once zoomed. A checkbox says whether the grid marks the image
 * for the trash and toggles that mark, which writes nothing on its own, a heart makes the image a gallery
 * favourite or takes that off, and the view offers to never suggest the image's folder again.
 */
@Composable
private fun FullSize(
    items: List<ReviewItem>,
    start: MediaRef,
    marked: Set<String>,
    onToggle: (MediaRef) -> Unit,
    onExcludeFolder: (MediaRef) -> Unit,
    onFavorite: (MediaRef, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // A page holds one media type. The player is made when the first video plays, so an image page never makes one.
    val video = start.mediaType == MediaType.VIDEO
    val playback = rememberVideoPlayback()
    val paths = items.map { it.image.path }
    LaunchedEffect(paths) { if (playback.loaded != null && playback.loaded !in paths) playback.stop() }
    val page: @Composable (Int) -> Unit = { page ->
        items.getOrNull(page)?.let { VideoPage(File(it.image.path), "Review.fullSize", playback, it.unreadable, onClose) }
    }
    FullSizeViewer(
        paths,
        start.path,
        onClose,
        image = if (video) {
            page
        } else {
            { ZoomableImage(paths[it], onClose) }
        },
        onPage = { if (playback.loaded != paths.getOrNull(it)) playback.stop() },
    ) { page ->
        val current = items.getOrNull(page) ?: return@FullSizeViewer // the page emptied under the dialog; CategoryScreen closes it next
        FullSizeToggle(current.image.path in marked, stringResource(R.string.selected_for_trash), markColor, Color.White) {
            haptics.performHapticFeedback(Haptic.TICK.type)
            onToggle(current.image)
        }
        val marked = current.image.path in marked
        val heart: @Composable () -> Unit = {
            FavoriteToggle(current.isFavorite, enabled = !marked, offReason = if (video) R.string.favorite_off_marked_video else R.string.favorite_off_marked) {
                haptics.performHapticFeedback(Haptic.TICK.type)
                onFavorite(current.image, it)
            }
        }
        FullSizeBar(current.image.path, current.isFavorite, heart = heart) {
            if (video) {
                val file = File(current.image.path)
                val key = "${current.image.path}|${current.image.size}|${current.image.mtimeNs}"
                // The index's length when it has one, as in "Largest videos"; else the retriever's, unless the file cannot be read.
                val length = current.durationMs ?: if (current.unreadable) null else rememberLengthMillis(file, key)
                VideoControls(playback, file, "Review.fullSize", length, current.unreadable)
                Text(videoDetails(current.image.size, length?.let(::formatLength)), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            for (reason in current.reasons.mapNotNull(::reasonOf)) {
                Text(reason.text(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = { onExcludeFolder(current.image) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) { Text(stringResource(R.string.exclude_folder)) }
        }
    }
}

/**
 * The full size view both the review and the Compress page open: a black dialog that pages through
 * [paths] from [start], with [image] in each page, the review's zoomable image unless the screen
 * has its own. [overlay] draws over it what is specific to the screen, for the page the pager is on. [onPage] hears each page
 * the pager comes to rest on, so a video that plays can stop; a drag that comes back to the same page tells it nothing. A page learns from [LocalPageSettled] that the pager came to rest on it.
 */
@Composable
internal fun FullSizeViewer(
    paths: List<String>,
    start: String,
    onClose: () -> Unit,
    image: @Composable (page: Int) -> Unit = { ZoomableImage(paths[it], onClose) },
    onPage: (Int) -> Unit = {},
    overlay: @Composable BoxScope.(page: Int) -> Unit,
) {
    val pager = rememberPagerState(paths.indexOf(start).coerceAtLeast(0)) { paths.size }
    ClearFullSizeImagesOnExit()
    val pageChanged by rememberUpdatedState(onPage)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { pageChanged(it) } }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("fullSize"), key = { paths[it] }) { page ->
                CompositionLocalProvider(LocalPageSettled provides (page == pager.currentPage && !pager.isScrollInProgress)) { image(page) }
            }
            overlay(pager.currentPage)
            // A tap on an image closes the view too, but nothing showed that. A video's tap plays and pauses, so this is its only way out.
            IconButton(
                onClose,
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
        }
    }
}

/**
 * The checkbox at the top right of the full size view, with [label] saying what a tick means, as a tap on a tile
 * toggles it. A box and a label read as a control, where a colored chip read as a status.
 */
@Composable
internal fun BoxScope.FullSizeToggle(checked: Boolean, label: String, checkedColor: Color, checkmarkColor: Color, onToggle: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clip(CircleShape)
            .toggleable(checked, role = Role.Checkbox) { onToggle() }
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = checkedColor, uncheckedColor = Color.White, checkmarkColor = checkmarkColor))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The dark translucent bar at the bottom of the full size view, readable on a white image: [content], then the folder
 * of [path] and a favourite's [heart]. The folder's name is enough to place a photo, where its path took two lines.
 */
@Composable
internal fun BoxScope.FullSizeBar(path: String, favorite: Boolean, heart: @Composable () -> Unit = { if (favorite) FavoriteHeart() }, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(File(path).parentFile?.name.orEmpty(), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium)
            heart()
        }
        content()
    }
}

/**
 * The heart of the full size view of a photo or a video: filled for a favourite, an outline for any other, and a tap says
 * the other. It is off while the item is marked for the trash, and says why with [offReason] to a screen reader, which would
 * only hear "dimmed" from a plain disabled button.
 */
@Composable
internal fun FavoriteToggle(favorite: Boolean, enabled: Boolean, @StringRes offReason: Int = R.string.favorite_off_marked, onChange: (Boolean) -> Unit) {
    val label = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)
    val reason = stringResource(offReason)
    IconButton(
        { onChange(!favorite) },
        Modifier.semantics { if (!enabled) stateDescription = reason },
        enabled,
    ) {
        Icon(
            if (favorite) Icons.Filled.Favorite else FavoriteOutline,
            label,
            tint = (if (favorite) Color(0xFFFF6B81) else Color.White).copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

// Material's Outlined.FavoriteBorder, which material-icons-extended holds and the app does not depend on.
private val FavoriteOutline: ImageVector by lazy {
    materialIcon(name = "Outlined.FavoriteBorder") {
        materialPath {
            moveTo(16.5f, 3.0f)
            curveToRelative(-1.74f, 0.0f, -3.41f, 0.81f, -4.5f, 2.09f)
            curveTo(10.91f, 3.81f, 9.24f, 3.0f, 7.5f, 3.0f)
            curveTo(4.42f, 3.0f, 2.0f, 5.42f, 2.0f, 8.5f)
            curveToRelative(0.0f, 3.78f, 3.4f, 6.86f, 8.55f, 11.54f)
            lineTo(12.0f, 21.35f)
            lineToRelative(1.45f, -1.32f)
            curveTo(18.6f, 15.36f, 22.0f, 12.28f, 22.0f, 8.5f)
            curveTo(22.0f, 5.42f, 19.58f, 3.0f, 16.5f, 3.0f)
            close()
            moveTo(12.1f, 18.55f)
            lineToRelative(-0.1f, 0.1f)
            lineToRelative(-0.1f, -0.1f)
            curveTo(7.14f, 14.24f, 4.0f, 11.39f, 4.0f, 8.5f)
            curveTo(4.0f, 6.5f, 5.5f, 5.0f, 7.5f, 5.0f)
            curveToRelative(1.54f, 0.0f, 3.04f, 0.99f, 3.57f, 2.36f)
            horizontalLineToRelative(1.87f)
            curveTo(13.46f, 5.99f, 14.96f, 5.0f, 16.5f, 5.0f)
            curveToRelative(2.0f, 0.0f, 3.5f, 1.5f, 3.5f, 3.5f)
            curveToRelative(0.0f, 2.89f, -3.14f, 5.74f, -7.9f, 10.05f)
            close()
        }
    }
}

/** Pinch zooms. A drag pans only while zoomed, so at scale 1 it is left to the pager to change page. */
@Composable
internal fun ZoomableImage(path: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    TracedAsyncImage(
        File(path),
        "FullSize.image",
        contentDescription = File(path).name,
        fullSize = true,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .clickable(onClick = onClose)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
