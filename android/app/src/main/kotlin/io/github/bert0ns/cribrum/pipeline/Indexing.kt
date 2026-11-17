// Background indexing with WorkManager (docs/design.md §4): a scan when the user asks for one, "Scan now" or
// "Scan videos", and an incremental one every night while the phone charges.
package io.github.bert0ns.cribrum.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.compress.CompressBatch
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.mobileclip.VISION_MODEL_ASSET
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.runtime.FallbackRuntime
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.sources.FilesystemMediaSource
import io.github.bert0ns.cribrum.sources.MediaSource
import io.github.bert0ns.cribrum.sources.MediaStoreSource
import io.github.bert0ns.cribrum.sources.MergedMediaSource
import io.github.bert0ns.cribrum.sources.NotImages
import io.github.bert0ns.cribrum.tracing.traceSuspend
import io.github.bert0ns.cribrum.trash.ExpiredPurge
import io.github.bert0ns.cribrum.ui.TilePreparing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

object Indexing {
    const val WORK = "index"

    /** On every scan request, so the progress covers the nightly one too. */
    const val TAG = "index-scan"
    private const val NIGHTLY_WORK = "index-nightly"
    private const val LEGACY_DAILY_WORK = "index-daily"
    private const val LEGACY_COSTS_KEY = "scan_costs"
    const val STEP = "step"
    const val DONE = "done"
    const val TOTAL = "total"

    /** The time left of the current phase in ms, absent while there is no estimate. */
    const val LEFT_MS = "left_ms"

    /** Only on the nightly request: its worker skips the scan outside the night. */
    const val NIGHTLY_TAG = "index-nightly-scan"

    /** Only on a "Scan videos" request: its worker runs the walk and then only the video steps. */
    const val VIDEO_TAG = "index-videos"

    /** The [ScanKind] the running scan reports, which a night pass that does both changes as it goes. */
    const val KIND = "kind"

    /** The time the nightly scan is aimed at. */
    private val NIGHT_START = LocalTime.of(2, 0)

    /**
     * The nightly worker scans only before this. WorkManager runs a periodic request when charging
     * starts, which can be 14:00 at a desk after a night without charging. A scan then is not a night
     * scan, so the worker skips it and waits for the next night.
     */
    private val NIGHT_END = LocalTime.of(6, 0)

    /** Starts a scan now, unless one is running. */
    fun scanNow(context: Context) = enqueue(context, OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG))

    /** Starts "Scan videos" now, unless a scan is running: the walk, then only the video steps. */
    fun scanVideos(context: Context) = enqueue(context, OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).addTag(VIDEO_TAG))

    /**
     * Both buttons share one unique work, so a tap on one while the other's request waits to start adds nothing: the button
     * of the other tab is only hidden once the scan runs, and two requests would run one after the other.
     */
    private fun enqueue(context: Context, request: OneTimeWorkRequest.Builder) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request.build())
    }

    /**
     * Stops the running scan with the cancel the notification's "Stop scanning" sends: that request's own, by its id.
     * A stopped nightly run adds its request back as it ends, and the nightly request of a stopped "Scan now" is untouched.
     */
    suspend fun stop(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.getWorkInfosByTagFlow(TAG).first().filter { it.state == WorkInfo.State.RUNNING || it.waits() }.forEach { manager.cancelWorkById(it.id) }
    }

    /**
     * A request of "Scan now" or "Scan videos" that waits to start, like one WorkManager put back after its process was killed.
     * The nightly request waits for the night, and is no scan until it runs.
     */
    private fun WorkInfo.waits() = !state.isFinished && state != WorkInfo.State.RUNNING && NIGHTLY_TAG !in tags

    /**
     * Later passes only process new or changed files, so a daily one while charging is cheap.
     * KEEP, so the delay to the night is computed once: UPDATE would measure a new delay from the
     * original enqueue time. The first request had no delay and a name of its own, which is cancelled.
     * KEEP leaves the request as it is, but [pinNextNight] rewrites its constraints, interval and tags from
     * the current code when each nightly run ends, so a change to those reaches installs after their next
     * nightly run. Only the initial delay stays fixed. Changing it needs a new unique name, as this one
     * replaced the first, and drops the pinned night of the installs that have the old name.
     */
    fun schedulePeriodic(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_DAILY_WORK)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { remove(LEGACY_COSTS_KEY) }
        manager.enqueueUniquePeriodicWork(NIGHTLY_WORK, ExistingPeriodicWorkPolicy.KEEP, dailyRequest(now))
    }

    /**
     * WorkManager has no time of day, so the first run waits for [NIGHT_START]. WorkManager measures each
     * later run from the end of the previous one, so the worker pins the next run to the following
     * [NIGHT_START] when it ends ([pinNextNight]). Charging and a battery that is not low decide when a run starts.
     */
    fun dailyRequest(now: ZonedDateTime = ZonedDateTime.now()): PeriodicWorkRequest = nightlyBuilder().setInitialDelay(Duration.between(now, nextNight(now))).build()

    private fun nightlyBuilder(): PeriodicWorkRequest.Builder {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        return PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS).setConstraints(constraints).addTag(TAG).addTag(NIGHTLY_TAG)
    }

    /** The next [NIGHT_START] after [now]. */
    private fun nextNight(now: ZonedDateTime): ZonedDateTime {
        val tonight = now.toLocalDate().atTime(NIGHT_START).atZone(now.zone)
        return if (tonight.isAfter(now)) tonight else now.toLocalDate().plusDays(1).atTime(NIGHT_START).atZone(now.zone)
    }

    /** WorkManager's clock, so a test can move it. */
    internal fun now(context: Context): ZonedDateTime = Instant.ofEpochMilli(WorkManager.getInstance(context).configuration.clock.currentTimeMillis()).atZone(ZoneId.systemDefault())

    internal fun isNight(now: ZonedDateTime) = now.toLocalTime().isBefore(NIGHT_END)

    /** Called by the nightly worker as it ends: the next run is the following [NIGHT_START], whatever time this one ran. */
    internal fun pinNextNight(context: Context, id: UUID, now: ZonedDateTime = now(context)) {
        val next = nextNight(now).toInstant().toEpochMilli()
        WorkManager.getInstance(context).updateWork(nightlyBuilder().setId(id).setNextScheduleTimeOverride(next).build())
    }

    /** How many phases the normal scan has. A rescan may skip one, so the number a user sees can jump. */
    internal const val PHASES = 5

    /** How many phases the video scan has, numbered on their own (docs/design.md §4). */
    internal const val VIDEO_PHASES = 4

    /**
     * Every step of the video part after the walk, with its number and title in the video scan's list. A night pass that does
     * both tells its video steps from the image steps by these names, so a new video step goes here (a test checks it).
     */
    private val VIDEO_STEP_PHASES = mapOf(
        VIDEO_METADATA_STEP to (2 to R.string.scan_phase_video_metadata),
        VIDEO_FRAMES_STEP to (3 to R.string.scan_phase_video_frames),
        VIDEO_GROUPS_STEP to (4 to R.string.scan_phase_video_groups),
    )

    internal val VIDEO_STEPS: Set<String> = VIDEO_STEP_PHASES.keys

    /** The scan a step belongs to: every step of "Scan videos", and the video steps of a night pass that does both. */
    internal fun kindOf(step: String, videoScan: Boolean) = if (videoScan || step in VIDEO_STEPS) ScanKind.VIDEOS else ScanKind.IMAGES

    internal fun phases(kind: ScanKind) = if (kind == ScanKind.VIDEOS) VIDEO_PHASES else PHASES

    /**
     * The number and the title of a [Pipeline] step in the scan [kind]. The step of a group detector is named after
     * the detector, and the step is empty until the walk sends its first progress.
     */
    private fun phase(step: String, kind: ScanKind) = when {
        // The walk, and a scan that has reported nothing yet.
        kind == ScanKind.VIDEOS -> VIDEO_STEP_PHASES[step] ?: (1 to R.string.scan_phase_video_scan)
        else -> when (step) {
            "", "scan" -> 1 to R.string.scan_phase_scan
            "metadata", JPEG_STEP -> 2 to R.string.scan_phase_metadata
            "detectors" -> 3 to R.string.scan_phase_analysis
            CLASSIFY_STEP -> 4 to R.string.scan_phase_classification
            else -> 5 to R.string.scan_phase_groups
        }
    }

    @StringRes
    internal fun phaseTitle(step: String, kind: ScanKind = ScanKind.IMAGES) = phase(step, kind).second

    internal fun phaseNumber(step: String, kind: ScanKind = ScanKind.IMAGES) = phase(step, kind).first

    /** "Phase 3 of 5". */
    internal fun phaseText(resources: Resources, step: String, kind: ScanKind = ScanKind.IMAGES) = resources.getString(R.string.scan_phase_number, phaseNumber(step, kind), phases(kind))

    /** "Photo scan" or "Video scan", which the progress and the notification show. */
    @StringRes
    internal fun scanName(kind: ScanKind) = if (kind == ScanKind.VIDEOS) R.string.scan_kind_videos else R.string.scan_kind_images

    /** The time left, rounded to the minute: "About 12 min left in this step", or "Less than a minute left in this step". */
    internal fun timeLeft(resources: Resources, ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return when {
            ms < 60_000 -> resources.getString(R.string.scan_left_under_a_minute)
            minutes < 60 -> resources.getString(R.string.scan_left_minutes, minutes)
            else -> resources.getString(R.string.scan_left_hours, minutes / 60, minutes % 60)
        }
    }

    /**
     * The running scan's progress, from "Scan now", "Scan videos" or the nightly request, then a request of the first two that
     * waits to start, or null when none does. A tap on either button while one waits adds nothing, so both tabs show it.
     */
    fun progress(context: Context): Flow<ScanProgress?> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
        fun kindOf(info: WorkInfo) = if (VIDEO_TAG in info.tags) ScanKind.VIDEOS else ScanKind.IMAGES
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { info ->
            val data = info.progress
            // Until the worker reports, its request says which scan it is.
            val kind = data.getString(KIND)?.let(ScanKind::valueOf) ?: kindOf(info)
            ScanProgress(data.getString(STEP).orEmpty(), data.getInt(DONE, 0), data.getInt(TOTAL, 0), data.getLong(LEFT_MS, -1).takeIf { it >= 0 }, kind)
        } ?: infos.firstOrNull { it.waits() }?.let { ScanProgress("", 0, 0, kind = kindOf(it), waiting = true) }
    }

    /** Every storage volume walked in full, then what MediaStore knows on top. [indexed] is what the index already has. */
    fun deviceSource(context: Context, indexed: suspend () -> Collection<MediaRef>, notImages: NotImages): MediaSource {
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        return MergedMediaSource(volumes.map { FilesystemMediaSource(it, notImages = notImages, indexed = indexed) } + MediaStoreSource(context.contentResolver, volumes, notImages))
    }
}

/** The normal scan's steps, or the video scan's, each numbered on their own (docs/design.md §4). */
enum class ScanKind { IMAGES, VIDEOS }

/**
 * [leftMs] is the time left of the current phase, null until the estimate is stable (docs/design.md §4). [waiting] is a
 * scan whose request waits to start, which has no step yet.
 */
data class ScanProgress(val step: String, val done: Int, val total: Int, val leftMs: Long? = null, val kind: ScanKind = ScanKind.IMAGES, val waiting: Boolean = false)

/**
 * The night rule (docs/design.md §4): the night pass analyses videos only once the user has run "Scan videos", and the switch
 * in Settings turns that off.
 */
class VideoScanSetting(private val preferences: SharedPreferences) {
    /** "Scan videos" has started once. A stopped or killed one counts: the user asked for videos, and the night goes on with them. */
    var ran: Boolean
        get() = preferences.getBoolean(KEY_RAN, false)
        set(value) = preferences.edit { putBoolean(KEY_RAN, value) }

    /** The switch in Settings, on until the user turns it off. */
    var atNight: Boolean
        get() = preferences.getBoolean(KEY_AT_NIGHT, true)
        set(value) = preferences.edit { putBoolean(KEY_AT_NIGHT, value) }

    /** Whether the night pass takes the videos, after the images. */
    val nightly: Boolean get() = ran && atNight

    private companion object {
        const val KEY_RAN = "video_scan_ran"
        const val KEY_AT_NIGHT = "video_scan_at_night"
    }
}

/** The switch in Settings that turns the image part of the night pass off (docs/design.md §4). On until the user turns it off. */
class ImageScanSetting(private val preferences: SharedPreferences) {
    var atNight: Boolean
        get() = preferences.getBoolean(KEY_AT_NIGHT, true)
        set(value) = preferences.edit { putBoolean(KEY_AT_NIGHT, value) }

    private companion object {
        const val KEY_AT_NIGHT = "image_scan_at_night"
    }
}

/**
 * Purges the expired trash, then runs the pipeline over the whole device. It runs in the
 * foreground, with a notification, since a first scan of thousands of images outlasts the few
 * minutes Android gives background work. The index keeps what each run did, so a stopped run
 * resumes where it was.
 */
open class IndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val nightly get() = Indexing.NIGHTLY_TAG in tags

    /** A "Scan videos" request: the walk, then only the video steps. */
    private val videoScan get() = Indexing.VIDEO_TAG in tags

    /** Whether this run takes the image steps: not "Scan videos", nor a night pass with the images switch off. */
    private val images by lazy { !videoScan && (!nightly || (applicationContext as CribrumApp).imageScans.atNight) }

    override suspend fun doWork(): Result {
        try {
            if (nightly && !Indexing.isNight(Indexing.now(applicationContext))) return Result.success()
            // Two scans would share the index and the model. A nightly run gives way to one already running. "Scan now"
            // waits instead: right after "Stop scanning" the stopped scan can still hold the lock, and a tap must not be
            // lost. It still gives way to a compression batch, which holds the lock for the whole batch (docs/design.md §9).
            val locked = when {
                nightly -> running.tryLock()
                CompressBatch.busy -> false
                else -> {
                    running.lock()
                    true
                }
            }
            return when {
                !locked -> Result.success()
                else -> try {
                    // A compression batch asked for or running goes first, and the scan after it reads its copies (docs/design.md §9).
                    if (CompressBatch.busy) {
                        Result.success()
                    } else {
                        // The user asked for videos, so the night takes them from now on, whether this scan ends or not.
                        if (videoScan) (applicationContext as CribrumApp).videoScans.ran = true
                        scan()
                    }
                } finally {
                    running.unlock()
                    // Once the lock is free, so the job does not take the ending scan for a new one (docs/design.md §8).
                    if (scanned) TilePreparing.enqueue(applicationContext)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(Indexing.TAG, "Scan failed", error)
            (applicationContext as? CribrumApp)?.lastScan?.recordFailure()
            return Result.failure()
        } finally {
            if (nightly) Indexing.pinNextNight(applicationContext, id)
            // "Stop scanning" cancels the nightly request itself. KEEP adds it back only then: a run stopped because the
            // charger was unplugged leaves the request enqueued, and this does nothing.
            if (nightly && isStopped) Indexing.schedulePeriodic(applicationContext, Indexing.now(applicationContext))
        }
    }

    protected var isForeground = false

    /** The pipeline ran to its end, so the Compress list may have changed. */
    private var scanned = false

    /** The scan itself, open so a test can tell when one starts. */
    protected open suspend fun scan(): Result {
        val app = applicationContext as CribrumApp
        if (!hasStorageAccess()) {
            app.lastScan.recordFailure()
            return Result.failure()
        }
        // A night pass takes the videos after the images once "Scan videos" has run (docs/design.md §4).
        val videos = videoScan || nightly && app.videoScans.nightly
        // Both switches off: the night only empties the trash, as the retention promises, and needs no notification.
        if (!images && !videos) {
            purgeTrash(app)
            return Result.success()
        }
        try {
            setForegroundInfo(getForegroundInfo())
            isForeground = true
        } catch (e: IllegalStateException) {
            Log.w(Indexing.TAG, "Foreground service start refused, continuing in background", e)
        }
        try {
            val trashFailures = purgeTrash(app)
            // The model loads when the first batch needs it, and the session is closed when the scan ends.
            MobileClip(::loadVision).use { embedder ->
                app.lastScan.run(images = images, videos = videos) { imagesDone ->
                    // Once, though a night pass hands the same stats to the report of its image steps and to the final one.
                    fun withTrash(stats: Stats) = stats.apply { if (!failures.containsAll(trashFailures)) failures += trashFailures }
                    val source = Indexing.deviceSource(applicationContext, app.index::images, app.index)
                    withTrash(pipeline(app, embedder).run(source, images = images, videos = videos, progress = ::report, imagesDone = { imagesDone(withTrash(it)) }))
                }
            }
            scanned = true
            return Result.success()
        } finally {
            applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    /** Purges the expired trash and forgets the paths it no longer holds. Returns the failure the scan's report keeps. */
    private suspend fun purgeTrash(app: CribrumApp): List<Failure> = try {
        // The purge has just read the whole trash, so what it left tells which paths are still held.
        val purge = traceSuspend("Trash.purge") { purgeExpired(app) }
        app.index.forgetTrashed(traceSuspend("Trash.unheld") { purge.unheldPaths })
        emptyList()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w(Indexing.TAG, "Failed to purge expired trash", error)
        listOf(Failure("", "trash", failureMessage(error)))
    }

    // The CPU fallback reads the model again only if the GPU fails, so the bytes are not held through the scan.
    protected open fun loadVision(): ModelRuntime = FallbackRuntime(OnnxRuntime(visionModel(), gpu = true, name = "mobileclip")) { OnnxRuntime(visionModel(), name = "mobileclip") }

    private fun visionModel() = applicationContext.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }

    protected open fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    protected open suspend fun setForegroundInfo(info: ForegroundInfo) = setForeground(info)

    protected open suspend fun purgeExpired(app: CribrumApp): ExpiredPurge = app.trash.purgeExpired()

    protected open fun pipeline(app: CribrumApp, embedder: MobileClip): Pipeline {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        return Pipeline(app.index, embedder = embedder, trash = app.trash, thermalStatus = { runCatching { power?.currentThermalStatus }.getOrNull() })
    }

    private var title = 0
    private var reported = 0L
    private var notified = 0L

    private val estimate = ScanEstimate(SystemClock::elapsedRealtime)

    /**
     * Sends the progress out, at most five times a second to the app and once a second to the
     * notification, but at once when the phase and so the title change.
     */
    protected suspend fun report(step: String, done: Int, total: Int) {
        estimate.update(step, done, total)
        val now = System.currentTimeMillis()
        val kind = Indexing.kindOf(step, !images)
        val phaseTitle = Indexing.phaseTitle(step, kind)
        val phaseChanged = phaseTitle != title
        if (now - reported < PROGRESS_INTERVAL_MS && done != total && !phaseChanged) return
        reported = now
        title = phaseTitle
        val left = estimate.millisLeft()
        setProgress(workDataOf(Indexing.STEP to step, Indexing.DONE to done, Indexing.TOTAL to total, Indexing.LEFT_MS to (left ?: -1L), Indexing.KIND to kind.name))
        if (isForeground && (phaseChanged || done == total || now - notified >= NOTIFICATION_INTERVAL_MS)) {
            notified = now
            // A stop that came meanwhile has had the notification removed: posting now would bring it back.
            currentCoroutineContext().ensureActive()
            // The service is in the foreground since scan() began, so the notification is updated in place.
            val count = if (total == 0) applicationContext.getString(R.string.scan_progress_open, done) else applicationContext.getString(R.string.scan_progress, done, total)
            val text = left?.let { applicationContext.getString(R.string.scan_joined, count, Indexing.timeLeft(applicationContext.resources, it)) } ?: count
            val info = foregroundInfo(step, kind, text)
            applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, info.notification)
        }
    }

    override suspend fun getForegroundInfo() = foregroundInfo("scan", Indexing.kindOf("scan", !images), applicationContext.getString(R.string.scan_starting))

    private fun foregroundInfo(step: String, kind: ScanKind, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW))
        val title = applicationContext.getString(R.string.scan_joined, Indexing.phaseText(applicationContext.resources, step, kind), applicationContext.getString(Indexing.phaseTitle(step, kind)))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // Which scan this is, in the header beside the app's name.
            .setSubText(applicationContext.getString(Indexing.scanName(kind)))
            .setOngoing(true)
            .setSilent(true)
            // As the compression batch can: a scan takes minutes and heats the phone. The index keeps what is done,
            // so the next scan resumes from there.
            .addAction(0, applicationContext.getString(R.string.scan_stop), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    internal companion object {
        /** One scan at a time in the process, whichever request started it, and no compression batch with it (docs/design.md §9). */
        val running = Mutex()
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_INTERVAL_MS = 200L
        const val NOTIFICATION_INTERVAL_MS = 1000L
    }
}
