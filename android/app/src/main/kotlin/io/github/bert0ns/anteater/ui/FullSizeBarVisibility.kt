package io.github.bert0ns.anteater.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether the bar of the full size view is out of the way, so the photo or the video shows whole (#473). A vertical swipe sets
 * it on any page ([swipeBar]), and [BarAutoHide] sets it while a video plays. The view makes one, and [FullSizeBar] reads it.
 */
internal class FullSizeBarState {
    var hidden by mutableStateOf(false)
}

internal val LocalFullSizeBar = staticCompositionLocalOf { FullSizeBarState() }

/**
 * Hides the bar of the full size view [BAR_HIDE_DELAY_MS] after the video starts to play, and shows it again when the video
 * pauses, ends or gives way to another, so a tap, which pauses, brings the controls back. A swipe up while it plays
 * keeps the bar until the next pause. It never hides the bar on its own while a touch exploration service such as TalkBack runs
 * ([touchExploration]), whose user could not reach the controls. A photo never hides it on its own.
 */
@Composable
internal fun BarAutoHide(playback: VideoPlayback, touchExploration: Boolean) {
    val bar = LocalFullSizeBar.current
    LaunchedEffect(playback.playing, playback.loaded, touchExploration) {
        if (!playback.playing) {
            bar.hidden = false
        } else if (!touchExploration) {
            delay(BAR_HIDE_DELAY_MS)
            bar.hidden = true
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
 * A vertical drag: up shows the bar, down hides it, wherever it ends. A horizontal one is left to the pager. It goes before the
 * zoom gestures of a photo on the same node, which see a drag first and consume it while the photo is zoomed, where it pans, and
 * a consumed drag is not one of these.
 */
internal fun Modifier.swipeBar(bar: FullSizeBarState) = pointerInput(bar) {
    var dragged = 0f
    detectVerticalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = { if (dragged != 0f) bar.hidden = dragged > 0 },
    ) { _, dy -> dragged += dy }
}

private const val BAR_HIDE_DELAY_MS = 1_000L
