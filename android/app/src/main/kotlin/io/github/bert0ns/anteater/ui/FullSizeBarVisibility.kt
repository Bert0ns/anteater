package io.github.bert0ns.anteater.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Whether the bar of the full size view is out of the way, so the photo or the video shows whole (#473). A vertical swipe sets
 * it on any page ([swipeBar]), and [BarAutoHide] sets it while a video plays. The view makes one, resets it on every page, and
 * [FullSizeBar] reads it.
 */
internal class FullSizeBarState {
    var hidden by mutableStateOf(false)

    /** Whether a touch exploration service such as TalkBack is on, which keeps the bar on screen: the view tells it. */
    var screenReader by mutableStateOf(false)

    /** Whether the bar shows: it always does with a screen reader on, whose user could not reach its controls. */
    val shown get() = !hidden || screenReader

    /** Whether the video of the page plays, as [BarAutoHide] saw it last. */
    var playing = false

    /** A swipe up brought the bar back while a video plays, so it stays until the next pause. */
    var pinned = false

    /** Whether a finger is on the seek bar, which the bar must not fade out from under. */
    var holding by mutableStateOf(false)

    /** Counts the touches of the controls that start the wait for [BarAutoHide] again. */
    var touches by mutableIntStateOf(0)
        private set

    fun touched() {
        touches++
    }

    /**
     * A swipe up shows the bar, and a swipe up on a bar the video hid keeps it. A swipe down hides a bar that shows, and closes the
     * view once it is hidden, so it takes two (#523). Returns whether the view should close. Does nothing with a screen reader on.
     */
    fun swipe(hide: Boolean): Boolean {
        if (screenReader) return false
        if (!hide) {
            if (hidden && playing) pinned = true
            hidden = false
            return false
        }
        if (hidden) return true
        pinned = false
        hidden = true
        return false
    }
}

internal val LocalFullSizeBar = staticCompositionLocalOf { FullSizeBarState() }

/**
 * Hides the bar of the full size view [BAR_HIDE_DELAY_MS] after the video starts to play, and shows it again when the video
 * pauses, ends or gives way to another, so a tap, which pauses, brings the controls back. A touch of the controls starts the wait
 * again, and a finger on the seek bar holds the bar. A swipe up while it plays keeps the bar until the next pause. It never hides
 * the bar on its own with a screen reader on. A photo never hides it on its own.
 */
@Composable
internal fun BarAutoHide(playback: VideoPlayback) {
    val bar = LocalFullSizeBar.current
    LaunchedEffect(playback.playing, playback.loaded, bar.screenReader, bar.touches, bar.holding) {
        bar.playing = playback.playing
        if (!playback.playing || bar.screenReader) {
            bar.pinned = false
            bar.hidden = false
        } else if (!bar.pinned) {
            bar.hidden = false
            if (!bar.holding) {
                delay(BAR_HIDE_DELAY_MS)
                bar.hidden = true
            }
        }
    }
}

/** Whether TalkBack or another touch exploration service is on, kept up to date while it changes. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * A vertical swipe: up shows the bar, down hides it and, with the bar already hidden, calls [onClose] (#523), wherever it ends. It claims a finger only when the first move past the touch
 * slop is more vertical than horizontal, so a flick that is mostly sideways, even a little askew, stays the pager's. It goes
 * before the zoom gestures of a photo on the same node, which see a drag first and consume it while the photo is zoomed, where
 * it pans, and a consumed finger is not one of these. A second finger leaves it to the pinch.
 */
internal fun Modifier.swipeBar(bar: FullSizeBarState, onClose: () -> Unit = {}) = pointerInput(bar, onClose) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        var dy = 0f
        var claimed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || event.changes.size > 1 || change.isConsumed && !claimed) return@awaitEachGesture
            val move = change.positionChange()
            dx += move.x
            dy += move.y
            if (!claimed && hypot(dx, dy) >= viewConfiguration.touchSlop) {
                if (abs(dy) <= abs(dx)) return@awaitEachGesture
                claimed = true
            }
            if (claimed) change.consume()
            if (!change.pressed) {
                if (claimed && dy != 0f && bar.swipe(hide = dy > 0)) onClose()
                return@awaitEachGesture
            }
        }
    }
}

private const val BAR_HIDE_DELAY_MS = 1_000L
