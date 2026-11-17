package io.github.bert0ns.anteater

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bert0ns.anteater.index.ImageIndex
import io.github.bert0ns.anteater.pipeline.ImageScanSetting
import io.github.bert0ns.anteater.pipeline.LastScan
import io.github.bert0ns.anteater.pipeline.VideoScanSetting
import io.github.bert0ns.anteater.review.ReviewOrderSetting
import io.github.bert0ns.anteater.review.Thresholds
import io.github.bert0ns.anteater.trash.AppTrash
import io.github.bert0ns.anteater.trash.TrashRetention
import io.github.bert0ns.anteater.ui.CleanTabSetting
import io.github.bert0ns.anteater.ui.HapticsSetting

/** Remembers whether the app has already asked for the scan progress notification permission. */
class NotificationPreferences(private val preferences: SharedPreferences) {
    var permissionRequested: Boolean
        get() = preferences.getBoolean(KEY, false)
        set(value) = preferences.edit { putBoolean(KEY, value) }

    private companion object {
        const val KEY = "notification_permission_requested"
    }
}

/** Holds the one index and trash the app and its background work share. */
class AnteaterApp : Application() {
    val index: ImageIndex by lazy { ImageIndex.open(this) }
    val trashRetention: TrashRetention by lazy { TrashRetention(getSharedPreferences("settings", MODE_PRIVATE)) }
    val thresholds: Thresholds by lazy { Thresholds(getSharedPreferences("settings", MODE_PRIVATE)) }
    val haptics: HapticsSetting by lazy { HapticsSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val cleanTab: CleanTabSetting by lazy { CleanTabSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val reviewOrder: ReviewOrderSetting by lazy { ReviewOrderSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val lastScan: LastScan by lazy { LastScan(getSharedPreferences("settings", MODE_PRIVATE)) }
    val imageScans: ImageScanSetting by lazy { ImageScanSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val videoScans: VideoScanSetting by lazy { VideoScanSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val notificationPreferences: NotificationPreferences by lazy {
        NotificationPreferences(getSharedPreferences("settings", MODE_PRIVATE))
    }
    val trash: AppTrash by lazy { AppTrash.forDevice(this, retention = { trashRetention.duration }) }
}
