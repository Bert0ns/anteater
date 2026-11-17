package io.github.bert0ns.anteater.review

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.ranker.THRESHOLDS
import io.github.bert0ns.anteater.ranker.VIDEO_THRESHOLDS

/**
 * The thresholds the app ranks with: the user's overrides over the defaults in [THRESHOLDS]
 * (docs/design.md §6.1). A value equal to its default is not stored, so the defaults stay the
 * lab's. A threshold is at least [MIN]: a best copy of a duplicate or burst group has score 0, and
 * only a threshold above 0 keeps it from being suggested (§7). A category in [FIXED] always has its
 * default: a stored override from an older version is ignored. The categories of [VIDEO_THRESHOLDS] have a second threshold for
 * videos, stored under a key of its own, so the photos' slider never reaches a video (§8).
 */
class Thresholds(private val preferences: SharedPreferences) {
    val values: Map<Category, Float>
        get() = THRESHOLDS.mapValues { (category, default) ->
            if (category in FIXED) default else preferences.getFloat(key(category), default).takeIf { it in RANGE } ?: default
        }

    operator fun set(category: Category, value: Float) {
        require(category !in FIXED) { "$category has no threshold to set" }
        require(value in RANGE) { "not a threshold: $value" }
        preferences.edit {
            if (value == THRESHOLDS[category]) remove(key(category)) else putFloat(key(category), value)
        }
    }

    /** The thresholds of the videos, for the categories that have one. */
    val videoValues: Map<Category, Float>
        get() = VIDEO_THRESHOLDS.mapValues { (category, default) -> preferences.getFloat(videoKey(category), default).takeIf { it in RANGE } ?: default }

    fun setVideo(category: Category, value: Float) {
        require(category in VIDEO_THRESHOLDS) { "$category has no threshold for videos" }
        require(value in RANGE) { "not a threshold: $value" }
        preferences.edit {
            if (value == VIDEO_THRESHOLDS[category]) remove(videoKey(category)) else putFloat(videoKey(category), value)
        }
    }

    fun reset() = preferences.edit {
        THRESHOLDS.keys.forEach { remove(key(it)) }
        VIDEO_THRESHOLDS.keys.forEach { remove(videoKey(it)) }
    }

    private fun key(category: Category) = "threshold_${category.name}"

    private fun videoKey(category: Category) = "video_threshold_${category.name}"

    companion object {
        const val MIN = 0.05f
        val RANGE = MIN..1f

        /** Detectors that score only 0 or 1, so every threshold up to 1 gives the same candidates. */
        val FIXED = setOf(Category.STICKER, Category.EXACT_DUPLICATE, Category.TAKES)
    }
}
