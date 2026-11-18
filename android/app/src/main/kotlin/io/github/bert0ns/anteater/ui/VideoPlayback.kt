// Playing a video in the full size view (docs/design.md §8): the one Media3 player of the view, and the controls that drive it.
package io.github.bert0ns.anteater.ui

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.bert0ns.anteater.R
import kotlinx.coroutines.delay
import java.io.File

/** How many frames the strip under the controls shows. */
internal const val STRIP_FRAMES = 5

/**
 * The players of a full size view and of the loops in a grid (docs/design.md §8), made on [Looper], which every call on
 * them must then come from. A test hands in others.
 */
internal val LocalPlayerFactory = staticCompositionLocalOf<(Context, Looper) -> Player> { ::exoPlayer }

/** Whether the page that reads it is the one the pager has come to rest on, so not one that is only being swiped past. */
internal val LocalPageSettled = compositionLocalOf { false }

// setLooper is marked unstable, though Media3 documents it as the way to play off the main thread.
@OptIn(UnstableApi::class)
private fun exoPlayer(context: Context, looper: Looper): Player = ExoPlayer.Builder(context).setLooper(looper).build()

private val MOVIE_SOUND = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

/**
 * The times of the strip's frames: the middle of each of [count] equal parts of the video, so the first is not the black
 * frame many videos start with and the last is not the end card.
 */
internal fun stripTimes(durationMs: Long, count: Int = STRIP_FRAMES): List<Long> = List(count) { (durationMs * (2 * it + 1)) / (2 * count) }

/**
 * The one player of a full size view, and what the controls show of it. It makes the player when a video first plays, plays
 * one video at a time, and [release]s it when the view closes, so no player outlives the view and there is never a second.
 * A view starts muted ([startMuted], #440): its video autoplays, and the user's music keeps playing until they unmute, which
 * turns the sound on and only then takes audio focus. The mute state is the view's, so the videos after an unmute play with
 * sound, and a view that opens again starts muted again.
 * Everything runs on the main thread, where the player is made: one player, made once and released when the view closes,
 * costs the view little, unlike the loops of a grid (`ClipLoop`).
 */
internal class VideoPlayback(startMuted: Boolean = false, private val create: () -> Player) {
    private var player: Player? = null

    /** The path of the video the player holds, playing or paused, or null when it holds none. */
    var loaded by mutableStateOf<String?>(null)
        private set

    /** Whether the video is playing, or would be once it has buffered. */
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0)
        private set

    /** The length the player reads, 0 until it has. */
    var durationMs by mutableLongStateOf(0)
        private set
    var videoSize by mutableStateOf<VideoSize?>(null)
        private set
    var muted by mutableStateOf(startMuted)
        private set

    /** The video whose last play failed. A video that does not play is a line of text, never a crash. */
    var failed by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()

        override fun onPlayerError(error: PlaybackException) {
            val path = loaded
            stop()
            failed = path
        }
    }

    /** Plays [file] from [atMs], or moves to it when the player already holds it. A video that is not loaded replaces the one that is. */
    fun play(file: File, atMs: Long = 0) {
        val player = player ?: create().also {
            it.addListener(listener)
            it.sound()
            (it as? ExoPlayer)?.setHandleAudioBecomingNoisy(true)
            player = it
        }
        failed = null
        if (loaded == file.path) {
            player.seekTo(atMs)
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), atMs)
            player.prepare()
            loaded = file.path
            videoSize = null
        }
        player.playWhenReady = true
        sync()
    }

    /** Pauses the video that plays, plays the one that is paused or ended again, and starts [file] when it is not the one loaded. */
    fun toggle(file: File) {
        val player = player
        if (player == null || loaded != file.path) return play(file)
        if (playing) {
            player.playWhenReady = false
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = true
        }
        sync()
    }

    /** Moves [file] to [ms], and starts it there when it is not the video loaded. */
    fun seekTo(file: File, ms: Long) {
        if (loaded != file.path) return play(file, ms)
        player?.seekTo(ms)
        sync()
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun toggleMute() {
        muted = !muted
        player?.sound()
    }

    /**
     * Silent while muted, and then without audio focus too, so a muted video leaves the user's music playing (#418).
     * Unmuting asks for focus again, as a video with sound should.
     */
    private fun Player.sound() {
        volume = if (muted) 0f else 1f
        setAudioAttributes(MOVIE_SOUND, !muted)
    }

    /** Lets go of the video and keeps the player for the next one. */
    fun stop() {
        player?.run {
            stop()
            clearMediaItems()
        }
        loaded = null
        playing = false
        positionMs = 0
        durationMs = 0
        videoSize = null
    }

    /** Lets go of the player itself. The next [play] makes another. */
    fun release() {
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        stop()
    }

    fun attach(view: SurfaceView) {
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        player?.clearVideoSurfaceView(view)
    }

    /** Reads the player's state into what the screen shows. */
    fun sync() {
        val player = player ?: return
        playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        positionMs = player.currentPosition.coerceAtLeast(0)
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        videoSize = player.videoSize.takeIf { it.width > 0 && it.height > 0 }
    }
}

/** The [VideoPlayback] of the full size view that calls this: it is released when the view leaves, and pauses when the app leaves the screen. */
@Composable
internal fun rememberVideoPlayback(startMuted: Boolean = true): VideoPlayback {
    val context = LocalContext.current.applicationContext
    val factory = LocalPlayerFactory.current
    val playback = remember(context, factory, startMuted) { VideoPlayback(startMuted) { factory(context, Looper.getMainLooper()) } }
    DisposableEffect(playback) { onDispose { playback.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.pause() }
    return playback
}

/**
 * The page of a video in the full size view: its frame, which starts the video once the pager comes to rest on the page
 * ([LocalPageSettled]), and a play button over it. A page that is swiped past starts nothing. A tap pauses the video and
 * plays it again, and the close button closes the view. A vertical swipe shows or hides the bar ([swipeBar]), as on a photo. A video that is [unreadable] is the placeholder, which a tap
 * closes, and never plays. [autoplay] is false while the page cannot yet tell whether the video is readable. A video that fails to play keeps its frame, and the play button tries it again.
 */
@Composable
internal fun VideoPage(file: File, section: String, playback: VideoPlayback, unreadable: Boolean, onClose: () -> Unit, autoplay: Boolean = true) {
    val showing = playback.loaded == file.path
    val settled = LocalPageSettled.current
    // Only when the video is not loaded, so a pause stays a pause, and not after a failure, so a drag does not retry it: the play button does.
    LaunchedEffect(settled, unreadable, autoplay) {
        if (settled && autoplay && !unreadable && playback.loaded != file.path && playback.failed != file.path) playback.play(file)
    }
    val tap = stringResource(
        if (unreadable) {
            R.string.close
        } else if (showing && playback.playing) {
            R.string.video_pause
        } else {
            R.string.video_play
        },
    )
    Box(Modifier.fillMaxSize().swipeBar(LocalFullSizeBar.current).clickable(onClickLabel = tap, onClick = if (unreadable) onClose else ({ playback.toggle(file) }))) {
        VideoFrame(file, section, Modifier.fillMaxSize(), ContentScale.Fit, unreadable)
        if (showing) VideoSurface(playback)
        if (!unreadable && !(showing && playback.playing)) {
            IconButton(
                { playback.toggle(file) },
                Modifier.align(Alignment.Center).size(72.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.video_play), Modifier.size(40.dp), tint = Color.White) }
        }
    }
}

/** The surface Media3 draws on, as large as the video's shape lets it be in the page. */
@Composable
private fun VideoSurface(playback: VideoPlayback) {
    val size = playback.videoSize
    val shape = size?.let { Modifier.aspectRatio(it.width * it.pixelWidthHeightRatio / it.height) } ?: Modifier.size(1.dp)
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        AndroidView(
            factory = { SurfaceView(it) },
            modifier = shape,
            onRelease = playback::detach,
            update = {
                playback.attach(it)
                // The screen stays on while the video plays, not while it is paused or has ended, and the surface is the only thing that can say so from a dialog.
                it.keepScreenOn = playback.playing
            },
        )
    }
}

/**
 * What drives the video of the full size view's bar: play or pause, a seek bar with the position and the length, the sound,
 * and a strip of [STRIP_FRAMES] frames across the length that a tap jumps to. [durationMs] is the length the index or the
 * file gave. The strip uses it whenever it is known and takes the player's only when it is not, since a time is part of a
 * frame's request, and a few ms between two parsers would otherwise redecode all five frames as the video starts. The seek
 * bar uses the player's length once it has read it. A video that is [unreadable] has none of it.
 */
@Composable
internal fun VideoControls(playback: VideoPlayback, file: File, section: String, durationMs: Long?, unreadable: Boolean) {
    if (unreadable) return
    val here = playback.loaded == file.path
    val total = (if (here && playback.durationMs > 0) playback.durationMs else durationMs) ?: 0
    val stripLength = durationMs?.takeIf { it > 0 } ?: total
    val position = if (here) playback.positionMs.coerceAtMost(total) else 0
    LaunchedEffect(playback.playing, here) {
        while (here && playback.playing) {
            playback.sync()
            delay(POSITION_INTERVAL_MS)
        }
    }
    Column {
        if (playback.failed == file.path) {
            Text(stringResource(R.string.video_play_failed), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val playing = here && playback.playing
            IconButton({ playback.toggle(file) }) {
                Icon(if (playing) Pause else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.video_pause else R.string.video_play), tint = Color.White)
            }
            if (total > 0) {
                var dragging by remember(file) { mutableStateOf<Float?>(null) }
                val seek = stringResource(R.string.video_seek)
                Slider(
                    dragging ?: (position.toFloat() / total),
                    { dragging = it },
                    Modifier.weight(1f).semantics { contentDescription = seek },
                    onValueChangeFinished = {
                        dragging?.let { playback.seekTo(file, (it * total).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Text("${formatLength(position)} / ${formatLength(total)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            } else {
                Box(Modifier.weight(1f))
            }
            IconButton(playback::toggleMute) {
                Icon(if (playback.muted) SoundOff else SoundOn, stringResource(if (playback.muted) R.string.video_unmute else R.string.video_mute), tint = Color.White)
            }
        }
        if (stripLength > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (ms in stripTimes(stripLength)) {
                    val label = stringResource(R.string.video_go_to, formatLength(ms))
                    VideoFrame(
                        file,
                        "$section.strip",
                        Modifier.weight(1f).aspectRatio(STRIP_ASPECT).clip(MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button) { playback.seekTo(file, ms) }
                            .semantics { contentDescription = label },
                        atMillis = ms,
                        small = true,
                    )
                }
            }
        }
    }
}

private const val POSITION_INTERVAL_MS = 250L
private const val STRIP_ASPECT = 16f / 9f

// Material's Pause, Volume up and Volume off, which material-icons-extended holds and the app does not depend on.
private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

private val Pause by lazy { icon("Filled.Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
private val SoundOn by lazy {
    icon("Filled.VolumeUp", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z")
}
private val SoundOff by lazy {
    icon(
        "Filled.VolumeOff",
        "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
    )
}
