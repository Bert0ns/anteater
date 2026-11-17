// What a video's tile shows, on the trash screen, on "Largest videos" and in the review (docs/design.md §8, §9): a frame, the length and the size, and a short clip playing.
package io.github.bert0ns.cribrum.ui

import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import coil3.decode.Decoder
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.tracing.traced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Decodes a frame of the file whatever its name says: the screen that draws it knows it holds a video. */
private val videoFrames = Decoder.Factory { result, options, _ -> VideoFrameDecoder(result.source, options) }

/**
 * A frame of the video, the first one or the one at [atMillis]. A file that is [unreadable] or whose frame does not decode
 * draws [UnreadableVideo] instead of an empty tile (docs/design.md §8, decision 17 of #382), or, for a [small] frame that has no
 * room for its note, a plain block.
 */
@Composable
internal fun VideoFrame(file: File, section: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop, unreadable: Boolean = false, atMillis: Long? = null, small: Boolean = false) {
    val context = LocalContext.current
    var failed by remember(file) { mutableStateOf(false) }
    if (unreadable || failed) {
        if (small) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) else UnreadableVideo(modifier)
    } else {
        val request = remember(file, section, context, atMillis) {
            ImageRequest.Builder(context).data(file).decoderFactory(videoFrames).apply { if (atMillis != null) videoFrameMillis(atMillis) }.traced(section).build()
        }
        AsyncImage(request, null, modifier, contentScale = contentScale, onError = { failed = true })
    }
}

/** How many tiles of the short clips grid play at once: a decoder each. A guess until the phone shows how many it runs while the grid scrolls (#398). */
internal const val LOOPING_CLIPS = 2

/**
 * A short clip playing muted and in a loop over its tile's frame, cropped to the tile as the frame is once the player knows
 * the video's size. Its view is empty, and so transparent, until the first frame arrives, and goes once the clip fails to
 * play, so the frame under it shows through. A clip that fails releases its player and calls [onFailed], so the grid can
 * give its place to another tile. Its player is released when the tile leaves the composition or the app leaves the screen,
 * and the clip starts again when the app comes back.
 */
@Composable
internal fun LoopingClip(file: File, modifier: Modifier, onFailed: () -> Unit) {
    val playback = rememberVideoPlayback(loop = true)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { playback.play(file) }
    val failed = playback.failed == file.path
    LaunchedEffect(failed) {
        if (failed) {
            playback.release()
            onFailed()
        }
    }
    if (failed) return
    val size = playback.videoSize
    // A TextureView, not a SurfaceView: it scrolls with the grid and is clipped to the tile like any view.
    AndroidView(
        factory = { TextureView(it) },
        modifier = modifier.clipToBounds().testTag("clipLoop").layout { measurable, constraints ->
            val width = size?.let { it.width * it.pixelWidthHeightRatio } ?: constraints.maxWidth.toFloat()
            val height = size?.height?.toFloat() ?: constraints.maxHeight.toFloat()
            val scale = maxOf(constraints.maxWidth / width, constraints.maxHeight / height)
            val placeable = measurable.measure(Constraints.fixed((width * scale).roundToInt(), (height * scale).roundToInt()))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2) }
        },
        onRelease = playback::detach,
        // Reads loaded, so the view is attached again once the player exists: the first update runs before the first play.
        update = { if (playback.loaded != null) playback.attach(it) },
    )
}

/** The tile of a video that cannot be read: a warning and a note, in place of a frame. */
@Composable
internal fun UnreadableVideo(modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Warning, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.video_unreadable), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/**
 * Video lengths in ms by key, [NO_LENGTH] for one that cannot be read, so a tile that scrolls back in opens no new
 * retriever. The key says which file it was: a file in the trash never changes, and a library video's key holds its size and mtime.
 */
private val lengths = LruCache<String, Long>(500)

internal const val NO_LENGTH = -1L

/** The video's length as a player shows it ("01:42"), or null until it is read or when it cannot be. */
@Composable
internal fun rememberDuration(file: File, key: String = file.path): String? = rememberLengthMillis(file, key)?.let(::formatLength)

/** The video's length in ms, or null until it is read or when it cannot be. */
@Composable
internal fun rememberLengthMillis(file: File, key: String = file.path): Long? = rememberLengthRead(file, key)?.takeIf { it != NO_LENGTH }

/** What reading the video's length gave: null until it is read, [NO_LENGTH] when it cannot be, else the length in ms. */
@Composable
internal fun rememberLengthRead(file: File, key: String = file.path): Long? {
    // Keyed on the video, so a caller that stays in place while the video changes, such as the full size view's bar, never
    // shows the last video's length, not even for a frame.
    val read = remember(file, key) { mutableStateOf(lengths[key]) }
    LaunchedEffect(file, key) {
        if (read.value != null) return@LaunchedEffect
        val millis = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use {
                    it.setDataSource(file.path)
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()
        } ?: NO_LENGTH
        lengths.put(key, millis)
        read.value = millis
    }
    return read.value
}

/** A length in ms as a player shows it ("01:42"). */
internal fun formatLength(millis: Long): String = DateUtils.formatElapsedTime(millis / 1000)

/** What a screen reader says of a video: "Video, 01:42, 412 MB", with no length when it is not known. */
@Composable
internal fun videoDetails(size: Long, duration: String?): String = stringResource(R.string.video_details, lengthAndSize(size, duration))

/** A video's length and size, "01:42, 412 MB", with no length when it is not known. */
@Composable
internal fun lengthAndSize(size: Long, duration: String?): String = listOfNotNull(duration, Formatter.formatShortFileSize(LocalContext.current, size)).joinToString(", ")
