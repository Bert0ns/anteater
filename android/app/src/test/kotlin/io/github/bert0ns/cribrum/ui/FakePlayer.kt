package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A player that decodes nothing, which Robolectric cannot do: it keeps what it was asked and plays as far as the state goes,
 * so the tests see which video is loaded, where it is and what was released.
 */
internal class FakePlayer(private val lengthMs: Long = 61_000) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var items = emptyList<MediaItem>()
    private var wanted = false
    private var state = Player.STATE_IDLE
    private var position = 0L
    private var level = 1f
    private var failure: PlaybackException? = null

    var released = false
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

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(state)
        .setPlaylist(items.map { MediaItemData.Builder(it).setDurationUs(lengthMs * 1000).build() })
        .setContentPositionMs(position)
        .setVolume(level)
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
        return Futures.immediateVoidFuture()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        level = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val STATE_IDLE = Player.STATE_IDLE
        const val STATE_READY = Player.STATE_READY
    }
}

/** The players a screen under test made, as [LocalPlayerFactory] gives them out. */
internal class Players {
    val made = mutableListOf<FakePlayer>()
    val factory: (Context) -> Player = { FakePlayer().also(made::add) }
}
