// The speed of a video in the full size view (#527, #538, docs/design.md §8): a hold that opens a slider next to the finger, a
// swipe sideways that locks it and a swipe back that unlocks it, the badge of a locked speed, and the screen reader's buttons.
package io.github.bert0ns.anteater.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bert0ns.anteater.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** A hold on a playing video, while its finger is down: where the finger went down, and whether the speed is locked. */
internal class SpeedHold(val origin: Offset) {
    var locked by mutableStateOf(false)

    /** How far the finger went sideways since it went down or last unlocked, as a share of the swipe that locks, from 0 to 1. */
    var sideways by mutableFloatStateOf(0f)
}

/**
 * Holding a finger still on a playing video for the long press timeout opens the speed slider ([onHold] gets a [SpeedHold],
 * and null when the finger lifts). Up is faster and down slower, one [SPEED_TICK] every [SPEED_STEP_DRAG], and a swipe
 * sideways locks the speed. While the finger is still down, bringing it back by [SPEED_UNLOCK_SWIPE] unlocks the speed,
 * and up and down move it again from there. Lifting the finger keeps a locked speed and goes back to the one before the hold
 * otherwise.
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
        haptics.performHapticFeedback(Haptic.GRAB.type)
        val before = playback.speed
        val hold = SpeedHold(down.position)
        onHold(hold)
        try {
            val step = SPEED_STEP_DRAG.toPx()
            val lock = SPEED_LOCK_SWIPE.toPx()
            val unlock = SPEED_UNLOCK_SWIPE.toPx()
            var from = before
            var up = 0f
            // Sideways from where the finger went down, or from where it last unlocked; and while locked, the farthest it went.
            var dx = 0f
            var farthest = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                val move = change?.positionChange()
                event.changes.forEach { it.consume() }
                if (change == null || move == null || !change.pressed) break
                dx += move.x
                if (hold.locked) {
                    farthest = maxOf(farthest, abs(dx))
                    hold.sideways = 1f
                    // Any way back toward where it came from unlocks, however far the swipe that locked went.
                    if (farthest - abs(dx) >= unlock) {
                        hold.locked = false
                        from = playback.speed
                        up = 0f
                        dx = 0f
                        hold.sideways = 0f
                        haptics.performHapticFeedback(Haptic.GRAB.type)
                    }
                    continue
                }
                hold.sideways = (abs(dx) / lock).coerceAtMost(1f)
                if (abs(dx) >= lock) {
                    hold.locked = true
                    farthest = abs(dx)
                    haptics.performHapticFeedback(Haptic.KEEP.type)
                    continue
                }
                // Past either end the finger moves nothing, so it turns back from the end and not from where it went.
                up = (up - move.y).coerceIn((MIN_SPEED - from) / SPEED_TICK * step, (MAX_SPEED - from) / SPEED_TICK * step)
                val speed = heldSpeed(from, up, step)
                if (speed != playback.speed) {
                    playback.setSpeed(speed)
                    haptics.performHapticFeedback(Haptic.STEP.type)
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

/**
 * The slider a hold opens next to the finger, on the side of the page with more room (#538): a column of ticks, one per
 * [SPEED_TICK], that scrolls past the finger's height, where the chosen tick is the longest and the others shrink to dots and
 * fade toward the ends. Beside it, one number whose digits roll to the next speed, and an open padlock that brightens as the
 * finger goes sideways and snaps shut once the speed is locked, when the other ticks fade out. No box and no hint, so it covers little of
 * the video.
 */
@Composable
internal fun BoxScope.SpeedDial(speed: Float, hold: SpeedHold) {
    val position by animateFloatAsState(speed / SPEED_TICK - 1, spring(stiffness = Spring.StiffnessMediumLow), label = "speed")
    val lock by animateFloatAsState(if (hold.locked) 1f else hold.sideways * 0.75f, label = "lock")
    val shut by animateFloatAsState(if (hold.locked) 1f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "shut")
    val faded by animateFloatAsState(if (hold.locked) 1f else hold.sideways * 0.6f, label = "faded")
    val chosen = (speed / SPEED_TICK).roundToInt() - 1
    val rollHeight = with(LocalDensity.current) { 32.sp.toDp() }
    BoxWithConstraints(Modifier.matchParentSize().testTag(SPEED_DIAL_TAG)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val side = if (hold.origin.x > width / 2) -1 else 1
        val margin = with(LocalDensity.current) { DIAL_MARGIN.toPx() }
        val middle = hold.origin.y.coerceIn(margin, (height - margin).coerceAtLeast(margin))
        Canvas(Modifier.matchParentSize()) {
            val anchor = hold.origin.x + side * DIAL_GAP.toPx()
            for (i in 0 until TICKS) {
                val distance = i - position
                if (abs(distance) > VISIBLE_TICKS) continue
                val near = exp(-distance * distance / 1.3f)
                val alpha = (1 - abs(distance) / VISIBLE_TICKS).pow(1.2f) * (0.5f + 0.5f * near) * if (i == chosen) 1f else 1 - 0.85f * faded
                val y = middle - distance * TICK_SPACING.toPx()
                val start = Offset(anchor, y)
                val end = Offset(anchor + side * TICK_LENGTH.toPx() * near * near, y)
                val stroke = (3.2f + 1f * near).dp.toPx()
                // A dark edge under each tick keeps it visible on a white sky.
                drawLine(Color.Black.copy(alpha = 0.3f * alpha), start, end, stroke + 2.5f.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White.copy(alpha = alpha), start, end, stroke, StrokeCap.Round)
            }
        }
        Column(
            Modifier.layout { measurable, outer ->
                val column = measurable.measure(outer.copy(minWidth = 0, minHeight = 0))
                val x = hold.origin.x + side * NUMBER_GAP.toPx()
                val y = middle - (LOCK_ICON + LOCK_ICON_GAP).toPx() - rollHeight.toPx() / 2
                layout(outer.maxWidth, outer.maxHeight) { column.place((if (side > 0) x else x - column.width).roundToInt(), y.roundToInt()) }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val locked = stringResource(R.string.video_speed_locked)
            Padlock(
                { shut },
                Modifier.size(LOCK_ICON).graphicsLayer { alpha = 0.5f + 0.5f * lock }.semantics { if (hold.locked) contentDescription = locked },
            )
            Spacer(Modifier.height(LOCK_ICON_GAP))
            Box(Modifier.height(rollHeight).clipToBounds(), if (side > 0) Alignment.CenterStart else Alignment.CenterEnd) {
                // Between two ticks the lower number rolls down and out as the higher one rolls in from above.
                val lower = floor(position).toInt().coerceIn(0, TICKS - 1)
                val rise = (position - lower).coerceIn(0f, 1f)
                DialNumber(lower, Modifier.offset { IntOffset(0, (rise * rollHeight.toPx()).roundToInt()) }.graphicsLayer { alpha = 1 - 0.7f * rise })
                if (rise > 0.01f && lower + 1 < TICKS) {
                    DialNumber(lower + 1, Modifier.offset { IntOffset(0, (-(1 - rise) * rollHeight.toPx()).roundToInt()) }.graphicsLayer { alpha = 0.3f + 0.7f * rise })
                }
            }
        }
    }
}

/** A padlock whose shackle is lifted and swung open at [shut] 0, and drops into the body at 1. */
@Composable
private fun Padlock(shut: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val open = 1 - shut()
        val leg = Offset(w * 0.3f, w * 0.52f)
        val shackle = Path().apply {
            moveTo(leg.x, leg.y)
            lineTo(leg.x, w * 0.34f)
            arcTo(Rect(w * 0.3f, w * 0.14f, w * 0.7f, w * 0.54f), 180f, 180f, false)
            lineTo(w * 0.7f, leg.y)
        }
        translate(top = -w * 0.14f * open) {
            rotate(-28f * open, leg) {
                drawPath(shackle, Color.Black.copy(alpha = 0.3f), style = Stroke(w * 0.17f, cap = StrokeCap.Round))
                drawPath(shackle, Color.White, style = Stroke(w * 0.11f, cap = StrokeCap.Round))
            }
        }
        val body = Offset(w * 0.14f, w * 0.48f)
        val bodySize = Size(w * 0.72f, w * 0.48f)
        drawRoundRect(Color.Black.copy(alpha = 0.3f), body - Offset(w * 0.03f, w * 0.03f), Size(bodySize.width + w * 0.06f, bodySize.height + w * 0.06f), CornerRadius(w * 0.12f))
        drawRoundRect(Color.White, body, bodySize, CornerRadius(w * 0.09f))
    }
}

@Composable
private fun DialNumber(tick: Int, modifier: Modifier) {
    Text(speedText((tick + 1) * SPEED_TICK), modifier, style = DIAL_NUMBER)
}

private val DIAL_NUMBER = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f))

internal const val SPEED_DIAL_TAG = "speedDial"

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

/** How far a held finger goes sideways to lock the speed: a deliberate swipe, not the drift of a drag up or down. */
private val SPEED_LOCK_SWIPE = 56.dp

/** How far a locked finger comes back from the farthest it went to unlock the speed. */
private val SPEED_UNLOCK_SWIPE = 24.dp

/** Below the top row of the full size view: its 8 dp padding, a 48 dp button and 16 dp of space. */
internal val SPEED_BADGE_TOP = 72.dp

/** How far a finger goes up or down for each step of [SPEED_TICK]: 1x to 4x takes 12 of them. */
private val SPEED_STEP_DRAG = 18.dp

private const val TICKS = 16

/** How many ticks either side of the chosen one show, fading out toward the last. */
private const val VISIBLE_TICKS = 4.2f
private val TICK_SPACING = 20.dp
private val TICK_LENGTH = 30.dp

/** The ticks' distance from the finger, so the finger does not cover them. */
private val DIAL_GAP = 52.dp

/** The number's distance from the finger: past the longest tick. */
private val NUMBER_GAP = 94.dp

/** How close the dial's middle comes to the top or bottom of the page, so its ticks stay on it. */
private val DIAL_MARGIN = 96.dp
private val LOCK_ICON = 22.dp
private val LOCK_ICON_GAP = 6.dp

// Material's Remove, which material-icons-extended holds and the app does not depend on.
private val Slower: ImageVector by lazy {
    ImageVector.Builder("Filled.Remove", 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes("M19,13H5v-2h14v2z"), fill = SolidColor(Color.Black)).build()
}
