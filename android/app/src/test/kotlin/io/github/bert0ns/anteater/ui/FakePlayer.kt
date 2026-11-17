package io.github.bert0ns.anteater.ui

import android.content.Context
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player that decodes nothing, which Robolectric cannot do: it keeps what it was asked and plays as far as the state goes,
 * so the tests see which video is loaded, where it is and what was released.
 */
internal class FakePlayer(private val lengthMs: Long = 61_000, looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {
    /** The thread it was built on. A call from any thread but its looper's throws, as Media3's players do. */
    val builtOn: Thread = Thread.currentThread()

    private var items = emptyList<MediaItem>()
    private var wanted = false
    private var state = Player.STATE_IDLE
    private var position = 0L
    private var level = 1f
    private var repeat = Player.REPEAT_MODE_OFF
    private var tracks = TrackSelectionParameters.DEFAULT
    private var failure: PlaybackException? = null

    var released = false
        private set

    /** Whether it was last told to take audio focus, as ExoPlayer does with it. */
    var focus = false
        private set

    /** The files it holds, as paths. */
    val paths get() = items.map { it.localConfiguration!!.uri.path!! }

    /** Where it is, in ms, and whether it was told to play. */
    val positionMs get() = position
    val playing get() = wanted

    /** Fails as a file that no extractor reads does. */
    fun fail() {
        failure = PlaybackException("unreadable", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        state = STATE_IDLE
        invalidateState()
    }

    /** Plays to the end of the video, as it does when nobody stops it. */
    fun ended() {
        position = lengthMs
        state = STATE_ENDED
        invalidateState()
    }

    /** Muted, and looping its one video, read without the player's own getters, which only its looper may call. */
    val muted get() = level == 0f
    val repeating get() = repeat == Player.REPEAT_MODE_ONE
    val soundless get() = C.TRACK_TYPE_AUDIO in tracks.disabledTrackTypes

    /** Where the first item is clipped to end, in ms, or [C.TIME_END_OF_SOURCE] when it plays to its end. */
    val endMs get() = items.firstOrNull()?.clippingConfiguration?.endPositionMs ?: C.TIME_END_OF_SOURCE

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(state)
        .setPlaylist(items.map { MediaItemData.Builder(it).setDurationUs(lengthMs * 1000).build() })
        .setContentPositionMs(position)
        .setVolume(level)
        .setRepeatMode(repeat)
        .setTrackSelectionParameters(tracks)
        .setPlayerError(failure)
        .setVideoSize(if (state == STATE_READY) VideoSize(1920, 1080) else VideoSize.UNKNOWN)
        .build()

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems
        position = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        items = items.filterIndexed { index, _ -> index !in fromIndex until toIndex }
        position = 0
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        failure = null
        state = STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        failure = null
        wanted = false
        state = STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        position = positionMs
        if (state == STATE_ENDED) state = STATE_READY
        return Futures.immediateVoidFuture()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        level = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        tracks = trackSelectionParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleSetAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ListenableFuture<*> {
        focus = handleAudioFocus
        return Futures.immediateVoidFuture()
    }

    /** What it draws on, a surface or a view, or null when it draws on nothing. */
    var output: Any? = null
        private set

    /** Whether the surface it was last told to let go of was still valid then, or null when it was told to let go of none. */
    var clearedValid: Boolean? = null
        private set

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        output = videoOutput
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        clearedValid = (videoOutput as? Surface)?.isValid
        output = null
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val STATE_IDLE = Player.STATE_IDLE
        const val STATE_READY = Player.STATE_READY
        const val STATE_ENDED = Player.STATE_ENDED
    }
}

/** The players a screen under test made, as [LocalPlayerFactory] gives them out, on the looper it asks for. */
internal class Players(private val lengthMs: Long = 61_000) {
    /** Filled from the loops' thread as well as the main one. */
    val made: MutableList<FakePlayer> = java.util.concurrent.CopyOnWriteArrayList()
    val factory: (Context, Looper) -> Player = { _, looper -> FakePlayer(lengthMs, looper).also(made::add) }
}
