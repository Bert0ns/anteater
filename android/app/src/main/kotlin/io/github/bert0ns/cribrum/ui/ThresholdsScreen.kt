package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.pipeline.DETECTOR_CATEGORIES
import io.github.bert0ns.cribrum.pipeline.VIDEO_ONLY_CATEGORIES
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.ranker.VIDEO_THRESHOLDS
import io.github.bert0ns.cribrum.review.Thresholds
import kotlin.math.roundToInt

/**
 * The score a signal needs to make an image a candidate, per category (docs/design.md §6.1), in the two sections Clean has. A
 * slider shows under "Videos" only for a category whose video score is graded, which has a threshold of its own, so the photos'
 * slider never hides a video without a word (§8).
 */
@Composable
fun ThresholdsScreen(
    thresholds: Map<Category, Float>,
    counts: Map<Category, Int>,
    defaultCounts: Map<Category, Int>,
    onChange: (Category, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    videoThresholds: Map<Category, Float> = VIDEO_THRESHOLDS,
    videoCounts: Map<Category, Int> = emptyMap(),
    videoDefaultCounts: Map<Category, Int> = emptyMap(),
    onChangeVideo: (Category, Float) -> Unit = { _, _ -> },
) {
    val haptics = LocalHapticFeedback.current
    LazyColumn(modifier.fillMaxSize()) {
        item {
            // The direction, which the sliders do not show, in one line. The counts under each slider show the effect.
            Text(stringResource(R.string.thresholds_direction), Modifier.padding(16.dp))
        }
        item { SectionHeader(stringResource(R.string.tab_photos)) }
        items(Category.entries.filter { it in DETECTOR_CATEGORIES && it !in Thresholds.FIXED && it !in VIDEO_ONLY_CATEGORIES }, key = { it }) { category ->
            ThresholdRow(category, thresholds[category] ?: THRESHOLDS.getValue(category), counts[category] ?: 0, defaultCounts[category], onChange, haptics)
        }
        item { SectionHeader(stringResource(R.string.tab_videos)) }
        items(Category.entries.filter { it in VIDEO_THRESHOLDS }, key = { "video-$it" }) { category ->
            ThresholdRow(category, videoThresholds[category] ?: VIDEO_THRESHOLDS.getValue(category), videoCounts[category] ?: 0, videoDefaultCounts[category], onChangeVideo, haptics, MediaType.VIDEO)
        }
        item {
            OutlinedButton({
                haptics.performHapticFeedback(Haptic.KEEP.type)
                onReset()
            }, Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.thresholds_reset)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ThresholdRow(category: Category, value: Float, count: Int, defaultCount: Int?, onChange: (Category, Float) -> Unit, haptics: HapticFeedback, mediaType: MediaType = MediaType.IMAGE) {
    val change = count - (defaultCount ?: count)
    var shown by remember(value) { mutableFloatStateOf(value) }
    val name = categoryName(category, mediaType)
    // Two sliders of one category stand under the two sections: a screen reader tells them apart.
    val description = if (mediaType == MediaType.VIDEO) stringResource(R.string.thresholds_slider_videos, name) else name
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Column {
                Slider(
                    value = shown,
                    onValueChange = {
                        val step = (it * 20).roundToInt() / 20f
                        if (step != shown) haptics.performHapticFeedback(Haptic.TICK.type)
                        shown = step
                    },
                    onValueChangeFinished = { onChange(category, shown) },
                    valueRange = Thresholds.RANGE,
                    steps = 18,
                    // The steps stay, the tick dots go: they only crowded the track.
                    colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                    modifier = Modifier.semantics { contentDescription = description },
                )
                val videos = mediaType == MediaType.VIDEO
                Text(pluralStringResource(if (videos) R.plurals.candidates_videos else R.plurals.candidates, count, count))
                when {
                    change > 0 -> Text(pluralStringResource(if (videos) R.plurals.thresholds_more_videos else R.plurals.thresholds_more, change, change))
                    change < 0 -> Text(pluralStringResource(if (videos) R.plurals.thresholds_fewer_videos else R.plurals.thresholds_fewer, -change, -change))
                }
            }
        },
    )
}
