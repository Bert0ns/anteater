package io.github.bert0ns.cribrum.pipeline

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file that failed at [step], by name only: the settings screen shows no paths. */
data class FailedFile(val step: String, val name: String)

/**
 * What the last finished scan left, for the settings screen (docs/design.md §8). [failures] counts by step.
 * [durationMs] is null and [phaseMs] empty in a report saved before the times were kept.
 * [images] and [videos] say which steps it ran: the normal scan's, the video scan's, or both in a night pass.
 * [imagesAt] and [videosAt] are when the last scan that ran each of them finished, this one or an earlier one, for the
 * line beside each tab's button. [videosFound] is null in a report saved before the scan counted the videos.
 */
data class ScanReport(
    val finishedAt: Long,
    val unreadable: Int,
    val failures: Map<String, Int>,
    val files: List<FailedFile>,
    val unreadableFiles: List<String> = emptyList(),
    val durationMs: Long? = null,
    val phaseMs: Map<String, Long> = emptyMap(),
    val images: Boolean = true,
    val videos: Boolean = false,
    val imagesAt: Long? = finishedAt.takeIf { images },
    val videosAt: Long? = finishedAt.takeIf { videos },
    val videosFound: Int? = null,
)

/** The report of the last scan that finished, kept with the other settings. A scan that stops leaves the previous report. */
class LastScan(private val preferences: SharedPreferences) {
    val report: ScanReport?
        get() = preferences.getString(KEY, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    val failed: Boolean
        get() = preferences.getBoolean(KEY_FAILED, false)

    fun recordFailure() {
        preferences.edit { putBoolean(KEY_FAILED, true) }
    }

    /**
     * Runs [scan] and keeps its report, with how long it took on the monotonic [elapsed] clock, and logs the
     * times, the image counts and the group detectors that ran or skipped under [TIME_TAG]. A scan that throws or is cancelled leaves the previous one,
     * or the report of its image steps if it called the function it is given once they ended: a night pass stopped in its video steps
     * still scanned the photos.
     */
    suspend fun run(
        images: Boolean = true,
        videos: Boolean = false,
        now: () -> Long = System::currentTimeMillis,
        elapsed: () -> Long = SystemClock::elapsedRealtime,
        scan: suspend (imagesDone: suspend (Stats) -> Unit) -> Stats,
    ): Stats {
        val start = elapsed()
        val stats = scan { record(it, now(), elapsed() - start, images = true, videos = false) }
        val durationMs = elapsed() - start
        record(stats, now(), durationMs, images, videos)
        Log.i(TIME_TAG, "images ${stats.images}")
        Log.i(TIME_TAG, "videos ${stats.videos}")
        Log.i(TIME_TAG, "new or changed ${stats.newOrChanged}")
        stats.counts.forEach { (name, count) -> Log.i(TIME_TAG, "$name $count") }
        stats.phaseMs.forEach { (phase, ms) -> Log.i(TIME_TAG, "$phase $ms ms") }
        stats.thermal.forEach { (phase, status) -> Log.i(TIME_TAG, "thermal $phase $status") }
        fun ids(list: List<String>) = list.ifEmpty { listOf("none") }.joinToString(", ")
        Log.i(TIME_TAG, "group detectors ran: ${ids(stats.ranGroupDetectors)}; skipped: ${ids(stats.skippedGroupDetectors)}")
        Log.i(TIME_TAG, "total $durationMs ms")
        return stats
    }

    fun record(stats: Stats, finishedAt: Long, durationMs: Long? = null, images: Boolean = true, videos: Boolean = false) {
        val previous = report
        val json = JSONObject()
            .put("finished_at", finishedAt)
            .put("unreadable", stats.unreadable)
            .put("failures", JSONObject(stats.failures.groupingBy { it.step }.eachCount()))
            .put("files", JSONArray(stats.failures.take(MAX_FILES).map { JSONArray(listOf(it.step, File(it.path).name)) }))
            .put("unreadable_files", JSONArray(stats.unreadablePaths.take(MAX_FILES).map { File(it).name }))
            .put("phase_ms", JSONObject(stats.phaseMs))
            .put("images", images)
            .put("videos", videos)
            .put("videos_found", stats.videos)
        if (durationMs != null) json.put("duration_ms", durationMs)
        // A video scan does not change when the photos were last scanned, nor the other way round.
        (if (images) finishedAt else previous?.imagesAt)?.let { json.put("images_at", it) }
        (if (videos) finishedAt else previous?.videosAt)?.let { json.put("videos_at", it) }
        val hasFailed = stats.failures.any { it.path.isEmpty() || it.step == "trash" || it.step in GROUP_DETECTOR_IDS }
        preferences.edit {
            putString(KEY, json.toString())
            putBoolean(KEY_FAILED, hasFailed)
        }
    }

    private fun parse(json: JSONObject): ScanReport {
        val failures = json.getJSONObject("failures")
        val files = json.getJSONArray("files")
        val unreadableFiles = json.optJSONArray("unreadable_files") // absent in a report saved before the names were kept
        val phases = json.optJSONObject("phase_ms") // absent, with duration_ms, in a report saved before the times were kept
        // Absent in a report saved before the video scan: that one was the normal scan.
        val images = json.optBoolean("images", true)
        val videos = json.optBoolean("videos", false)
        val finishedAt = json.getLong("finished_at")
        fun time(key: String, ran: Boolean) = if (json.has(key)) json.getLong(key) else finishedAt.takeIf { ran }
        return ScanReport(
            finishedAt,
            json.getInt("unreadable"),
            failures.keys().asSequence().associateWith(failures::getInt),
            List(files.length()) { files.getJSONArray(it).let { file -> FailedFile(file.getString(0), file.getString(1)) } },
            unreadableFiles?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            if (json.has("duration_ms")) json.getLong("duration_ms") else null,
            phases?.let { p -> p.keys().asSequence().associateWith(p::getLong) }.orEmpty(),
            images,
            videos,
            time("images_at", images),
            time("videos_at", videos),
            if (json.has("videos_found")) json.getInt("videos_found") else null,
        )
    }

    companion object {
        /** More names than this would not be read, and the report lives in the settings file. */
        const val MAX_FILES = 100
        private const val KEY = "last_scan"
        private const val KEY_FAILED = "last_scan_failed"

        /** The log tag of the scan times, for `adb logcat -s`. */
        const val TIME_TAG = "scan-time"
    }
}
