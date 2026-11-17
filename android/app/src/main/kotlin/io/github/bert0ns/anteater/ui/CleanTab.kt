package io.github.bert0ns.anteater.ui

import android.content.SharedPreferences
import androidx.core.content.edit

/** The two tabs of Clean (docs/design.md §8). */
enum class CleanTab { PHOTOS, VIDEOS }

/** The tab Clean opens on: the one the user used last. */
class CleanTabSetting(private val preferences: SharedPreferences) {
    var tab: CleanTab
        get() = CleanTab.entries.firstOrNull { it.name == preferences.getString(KEY, null) } ?: CleanTab.PHOTOS
        set(value) = preferences.edit { putString(KEY, value.name) }

    private companion object {
        const val KEY = "clean_tab"
    }
}
