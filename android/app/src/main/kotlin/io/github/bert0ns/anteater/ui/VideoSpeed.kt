// The speed of a video in the full size view (#527, docs/design.md §8): a hold that opens a slider, a swipe sideways that locks
// it, the badge of a locked speed, and the screen reader's buttons.
package io.github.bert0ns.anteater.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bert0ns.anteater.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2

/** A hold on a playing video, while its finger is down: whether a swipe sideways has locked the speed yet. */
internal class SpeedHold {
    var locked by mutableStateOf(false)
}

/**
 * Holding a finger still on a playing video for the long press timeout opens the speed slider ([onHold] gets a [SpeedHold],
 * and null when the finger lifts). Up is faster and down slower, on a log scale ([heldSpeed]), and a swipe sideways locks the
 * speed. Lifting the finger keeps a locked speed and goes back to the one before the hold otherwise.
 *
 * Until the timeout it consumes nothing and gives up on a lift, a second finger or a move past the touch slop, so a tap still
 * pauses, a vertical swipe still hides the bar or closes the view ([swipeBar]) and a horizontal one still pages. Once it holds,
 * it consumes every change to the end, the lift included. It must come after [swipeBar] and the tap on the same node, since
 * the main pass goes from the last modifier to the first, and [enabled] is read at each touch.
 */
internal fun Modifier.videoSpeedGesture(playback: VideoPlayback, haptics: HapticFeedback, enabled: () -> Boolean, onHold: (SpeedHold?) -> Unit) = pointerInput(playback, haptics) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled()) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        if (withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitLeave(down.id, slop) } != null) return@awaitEachGesture
        haptics.performHapticFeedback(Haptic.TICK.type)
        val before = playback.speed
        val hold = SpeedHold()
        onHold(hold)
        try {
            val octave = SPEED_OCTAVE.toPx()
            val lock = SPEED_LOCK_SWIPE.toPx()
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                val move = change?.positionChange()
                event.changes.forEach { it.consume() }
                if (change == null || move == null || !change.pressed) break
                if (hold.locked) continue
                dx += move.x
                dy += move.y
                playback.setSpeed(heldSpeed(before, -dy, octave))
                if (abs(dx) >= lock) {
                    hold.locked = true
                    haptics.performHapticFeedback(Haptic.KEEP.type)
                }
            }
            if (hold.locked) playback.lockSpeed() else playback.setSpeed(before)
        } finally {
            onHold(null)
        }
    }
}

/** Returns when the finger [id] lifts, is consumed, moves past [slop] or gets company. */
private suspend fun AwaitPointerEventScope.awaitLeave(id: PointerId, slop: Float) {
    var dx = 0f
    var dy = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id }
        if (change == null || event.changes.size > 1 || !change.pressed || change.isConsumed) return
        val move = change.positionChange()
        dx += move.x
        dy += move.y
        if (hypot(dx, dy) > slop) return
    }
}

/** "1.5x", "0.25x", "1x", with the decimal separator of the app's language. */
@Composable
internal fun speedText(speed: Float): String {
    val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0]))
    return stringResource(R.string.video_speed_value, format.format(speed))
}

/** The slider a hold opens in the middle of the video: the speed, where it sits from [MIN_SPEED] to [MAX_SPEED], and how to lock it. */
@Composable
internal fun BoxScope.SpeedPopup(speed: Float, locked: Boolean) {
    Column(
        Modifier.align(Alignment.Center).widthIn(max = 240.dp).background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.large).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(speedText(speed), color = Color.White, style = MaterialTheme.typography.headlineMedium)
        val share = (log2(speed / MIN_SPEED) / log2(MAX_SPEED / MIN_SPEED)).coerceIn(0f, 1f)
        Box(Modifier.width(THUMB).height(TRACK), Alignment.BottomCenter) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.3f), CircleShape))
            Box(Modifier.width(6.dp).height(THUMB + (TRACK - THUMB) * share).background(Color.White, CircleShape))
            Box(Modifier.offset(y = -(TRACK - THUMB) * share).size(THUMB).background(Color.White, CircleShape))
        }
        if (locked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Lock, null, tint = Color.White)
                Text(stringResource(R.string.video_speed_locked), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(stringResource(R.string.video_speed_hint), color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The locked speed, under the checkbox at the top right of the full size view, so it never covers the close, share or
 * checkbox buttons. A tap goes back to normal speed.
 */
@Composable
internal fun BoxScope.SpeedBadge(playback: VideoPlayback) {
    Row(
        Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = SPEED_BADGE_TOP, end = 8.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.video_speed_reset), role = Role.Button) { playback.resetSpeed() }
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Lock, stringResource(R.string.video_speed_locked), Modifier.size(18.dp), tint = Color.White)
        Text(speedText(playback.speed), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** With a screen reader on, which has no hold, the bar steps the speed through the usual ones, and each step is locked. */
@Composable
internal fun SpeedControls(playback: VideoPlayback) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ playback.stepSpeed(faster = false) }, enabled = playback.speed > MIN_SPEED) {
            Icon(Slower, stringResource(R.string.video_speed_slower), tint = Color.White)
        }
        Text(stringResource(R.string.video_speed_label, speedText(playback.speed)), Modifier.weight(1f, fill = false), color = Color.White, style = MaterialTheme.typography.labelMedium)
        IconButton({ playback.stepSpeed(faster = true) }, enabled = playback.speed < MAX_SPEED) {
            Icon(Icons.Filled.Add, stringResource(R.string.video_speed_faster), tint = Color.White)
        }
    }
}

/** How far a finger goes up to double the speed, so 1x to 4x takes two of them. */
private val SPEED_OCTAVE = 64.dp

/** How far a held finger goes sideways to lock the speed: a deliberate swipe, not the drift of a drag up or down. */
private val SPEED_LOCK_SWIPE = 56.dp

/** Below the top row of the full size view: its 8 dp padding, a 48 dp button and 16 dp of space. */
internal val SPEED_BADGE_TOP = 72.dp

private val TRACK = 160.dp
private val THUMB = 18.dp

// Material's Remove, which material-icons-extended holds and the app does not depend on.
private val Slower: ImageVector by lazy {
    ImageVector.Builder("Filled.Remove", 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes("M19,13H5v-2h14v2z"), fill = SolidColor(Color.Black)).build()
}
