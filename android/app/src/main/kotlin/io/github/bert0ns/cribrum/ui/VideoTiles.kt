// What a video's tile shows, on the trash screen, on "Largest videos" and in the review (docs/design.md §8, §9): a frame, the length and the size, and a clip playing or a slideshow of its frames.
package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.window.core.layout.WindowSizeClass
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.decode.Decoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Scale
import coil3.toBitmap
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import coil3.video.videoFrameOption
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.tracing.traced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val request = remember(file, section, context, atMillis) { videoFrameRequest(context, file, section, atMillis) }
        AsyncImage(request, null, modifier, contentScale = contentScale, onError = { failed = true })
    }
}

private fun videoFrameRequest(context: Context, file: File, section: String, atMillis: Long?) = ImageRequest.Builder(context).data(file).decoderFactory(videoFrames).apply { if (atMillis != null) videoFrameMillis(atMillis) }.traced(section).build()

/**
 * How many tiles of a video review play at once: a decoder each. The phone measured it (#438): at 4, Android already takes
 * decoders back from the app and the scroll stalls for 250 ms, and at 8 the decoders run out of memory.
 */
internal const val LOOPING_CLIPS = 2

/** How much of a video that can be long a tile loops: its first 10 seconds. A short clip or a take loops whole. */
internal const val LONG_LOOP_MS = 10_000L

/** The thread every loop's player lives on (#457): building, starting and releasing one blocks it, and not the main thread. */
internal val clipLoops: Looper by lazy { HandlerThread("ClipLoops").apply { start() }.looper }

/**
 * A clip playing muted and in a loop in a tile, on a player made by [create] on [clipLoops]. Every call on the player is
 * posted there, and what the tile shows of it comes back to the main thread. The player draws on a [Surface] of the tile's
 * [TextureView], handed over on that thread too: Media3's own TextureView listener would call the player from the main thread,
 * and wait there for the codec to let go of the surface. [positions] holds where each clip was when its player was
 * released, and only that thread touches it: a release and the next play of the same clip are queued there in order, so
 * the play always finds where the release left it.
 */
internal class ClipLoop(private val positions: MutableMap<String, Long> = HashMap(), private val create: (Looper) -> Player) : TextureView.SurfaceTextureListener {
    private val loops = Handler(clipLoops)
    private val main = Handler(Looper.getMainLooper())

    // Only touched on the loops' thread.
    private var player: Player? = null
    private var surface: Surface? = null
    private var path: String? = null

    var videoSize by mutableStateOf<VideoSize?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) main.post { videoSize = size }
        }

        override fun onPlayerError(error: PlaybackException) {
            main.post { failed = true }
        }
    }

    /**
     * Plays [file] again and again from where it was last released, only its first [endMs] when that is set. Muted from the
     * first frame, with its audio track off, so it takes no audio focus, no audio decoder and no AudioTrack.
     */
    fun play(file: File, endMs: Long?) {
        loops.post {
            path = file.path
            val player = player ?: create(clipLoops).also {
                it.addListener(listener)
                it.volume = 0f
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                player = it
            }
            val item = MediaItem.Builder().setUri(Uri.fromFile(file))
            if (endMs != null) item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(endMs).build())
            player.setMediaItem(item.build(), positions[file.path] ?: 0)
            surface?.let(player::setVideoSurface)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Lets go of the player, and keeps where in the clip it was. */
    fun release() {
        loops.post {
            player?.run {
                path?.let { positions[it] = currentPosition }
                removeListener(listener)
                release()
            }
            player = null
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val surface = Surface(texture)
        loops.post {
            this.surface = surface
            player?.setVideoSurface(surface)
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        loops.post {
            // Waits for the codec to let go of the surface, so only then are the surface and its texture released.
            surface?.let { player?.clearVideoSurface(it) }
            surface?.release()
            surface = null
            texture.release()
        }
        return false
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/**
 * A clip playing muted and in a loop over its tile's frame from where it was in [positions], its first [endMs] only when
 * that is set, cropped to the tile as the frame is once the player knows the video's size. Its view is empty, and so
 * transparent, until the first frame arrives, and goes once the clip fails to play, so the frame under it shows through. A
 * clip that fails releases its player and calls [onFailed], so the grid can give its place to another tile. Its player is
 * released when the tile leaves the composition, and [positions] keeps where the clip was.
 */
@Composable
internal fun LoopingClip(file: File, endMs: Long?, positions: MutableMap<String, Long>, modifier: Modifier, onFailed: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = LocalPlayerFactory.current
    val loop = remember(file, factory, positions) { ClipLoop(positions) { factory(context, it) } }
    DisposableEffect(loop) {
        loop.play(file, endMs)
        onDispose { loop.release() }
    }
    LaunchedEffect(loop.failed) {
        if (loop.failed) {
            loop.release()
            onFailed()
        }
    }
    if (loop.failed) return
    val size = loop.videoSize
    // A TextureView, not a SurfaceView: it scrolls with the grid and is clipped to the tile like any view. Keyed by the
    // loop, so a loop made again gets a view of its own, whose surface goes to it and not to the one before.
    key(loop) {
        AndroidView(
            factory = { TextureView(it).apply { surfaceTextureListener = loop } },
            modifier = modifier.clipToBounds().testTag("clipLoop").layout { measurable, constraints ->
                val width = size?.let { it.width * it.pixelWidthHeightRatio } ?: constraints.maxWidth.toFloat()
                val height = size?.height?.toFloat() ?: constraints.maxHeight.toFloat()
                val scale = maxOf(constraints.maxWidth / width, constraints.maxHeight / height)
                val placeable = measurable.measure(Constraints.fixed((width * scale).roundToInt(), (height * scale).roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2) }
            },
        )
    }
}

/**
 * The columns of a grid of videos laid out as "Short clips" is (#457): 2 on a compact width and 4 on a medium or expanded
 * one, by the window size class and not by fold detection, so a tablet, landscape and split screen behave the same.
 */
@Composable
internal fun videoColumns(): GridCells = GridCells.Fixed(if (currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) 4 else 2)

/**
 * Which video tiles on screen move (docs/design.md §8, #457), by key: the ones in [loops] play their clip and the ones in
 * [slides] show the slideshow. It also keeps, for the life of the screen, the clips that failed to play and where each clip
 * was when its player was released, so it plays on from there.
 */
@Stable
internal class TileMotion {
    var loops by mutableStateOf(emptySet<Any>())
        private set
    var slides by mutableStateOf(emptySet<Any>())
        private set
    var failed by mutableStateOf(emptySet<String>())
        private set

    /** Where each clip was when its player was released. Only the loops' thread touches it (see [ClipLoop]). */
    private val positions = HashMap<String, Long>()

    /**
     * Moves the [onScreen] tiles, in grid order. A tile that plays keeps its player for as long as it is on screen, scroll or
     * not, and the places left free go to the first tiles on screen only once the grid is [settled], so a fling builds no
     * player for the rows it passes. The others on screen show the slideshow.
     */
    fun update(onScreen: List<Any>, settled: Boolean, canPlay: Boolean) {
        val kept = onScreen.filter { it in loops && it !in failed }
        loops = when {
            !canPlay -> emptySet()
            !settled -> kept.toSet()
            else -> (kept + onScreen.filter { it !in kept && it !in failed }).take(LOOPING_CLIPS).toSet()
        }
        slides = onScreen.toSet() - loops
    }

    /** The clip that plays, or the slideshow, over a moving tile's frame of [video], whose length is [durationMs] when known. */
    @Composable
    fun Over(video: MediaRef, durationMs: Long?, endMs: Long?, modifier: Modifier) {
        val file = File(video.path)
        when (video.path) {
            in loops -> LoopingClip(file, endMs, positions, modifier, onFailed = { failed = failed + video.path })
            in slides -> {
                val length = durationMs ?: rememberLengthMillis(file, "${video.path}|${video.size}|${video.mtimeNs}")
                if (length != null && length > 0) Slideshow(file, length, modifier)
            }
        }
    }
}

/**
 * The [TileMotion] of a grid whose tiles in [movers] move, while they may: not while the system removes animations (read
 * when the screen opens), the full size view is [open] or the app is in the background. Unless [canPlay], they all show
 * the slideshow.
 */
@Composable
internal fun rememberTileMotion(grid: LazyGridState, movers: Set<String>, open: Boolean, canPlay: Boolean): TileMotion {
    val context = LocalContext.current
    val animations = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val moves = animations && started && !open
    val motion = remember { TileMotion() }
    LaunchedEffect(motion, grid, movers, moves, canPlay) {
        // The tiles' keys are their paths. This emits when a tile comes or goes, the grid settles, or a clip fails.
        snapshotFlow { Triple(grid.layoutInfo.visibleItemsInfo.map { it.key }.filter { it in movers }, grid.isScrollInProgress, motion.failed) }
            .collect { (onScreen, scrolling, _) -> motion.update(if (moves) onScreen else emptyList(), !scrolling, canPlay) }
    }
    return motion
}

/** How many frames a slideshow steps through, and how long it shows each: a cycle of 4 seconds. */
internal const val SLIDE_FRAMES = 8
internal const val SLIDE_MS = 500L

/**
 * The short side a slideshow's frames decode at: below the tile, about 440 px in 4 columns on an unfolded Fold and 540 px
 * in 2 folded, and still sharp there once the frame moves every half second.
 */
internal const val SLIDE_FRAME_PX = 320

/**
 * A tile that would move but has no player (#457): [SLIDE_FRAMES] frames from across the video, the middle of each eighth
 * of its length, one every [SLIDE_MS], over the tile's still frame, which shows until the first is decoded. Each frame is
 * decoded once, at the closest sync frame and [SLIDE_FRAME_PX] in RGB_565, and kept by the tile while it is on screen,
 * out of Coil's memory cache, so the slideshows never push the grid's still frames out of it: about 2.9 MB a tile for a
 * 16:9 video (8 × 569 × 320 × 2 bytes). A frame stays until the next one is decoded, so nothing flashes, and one that does
 * not decode is skipped. The first waits [SLIDE_MS] too, so a tile a fling only passes through decodes nothing, and a tile
 * that leaves cancels the decode it waits for.
 */
@Composable
internal fun Slideshow(file: File, durationMs: Long, modifier: Modifier) {
    val context = LocalContext.current
    val times = remember(durationMs) { stripTimes(durationMs, SLIDE_FRAMES) }
    var shown by remember(file, times) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, times) {
        val loader = SingletonImageLoader.get(context)
        // Null for a frame that did not decode, so it is not tried again.
        val frames = HashMap<Int, ImageBitmap?>()
        var step = 0
        while (true) {
            delay(SLIDE_MS)
            val frame = step++ % times.size
            if (frame !in frames) frames[frame] = (loader.execute(slideFrameRequest(context, file, times[frame])) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
            frames[frame]?.let { shown = it }
        }
    }
    Box(modifier.testTag("slideshow")) { shown?.let { Image(it, null, Modifier.fillMaxSize().testTag("slide"), contentScale = ContentScale.Crop) } }
}

private fun slideFrameRequest(context: Context, file: File, atMillis: Long) = ImageRequest.Builder(context).data(file).decoderFactory(videoFrames)
    .videoFrameMillis(atMillis).videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    .size(SLIDE_FRAME_PX).scale(Scale.FILL).bitmapConfig(Bitmap.Config.RGB_565).allowHardware(false)
    .memoryCachePolicy(CachePolicy.DISABLED).traced("Review.slideshow").build()

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
