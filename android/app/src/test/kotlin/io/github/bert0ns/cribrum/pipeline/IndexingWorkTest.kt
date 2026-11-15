package io.github.bert0ns.cribrum.pipeline

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import io.github.bert0ns.cribrum.metadata.UnreadableVideo
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toMediaRef
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.ExpiredPurge
import io.github.bert0ns.cribrum.ui.TilePreparing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Stands in for the scan itself: counts the scans that start, reports progress and waits for [gate]. */
class FakeScan(
    context: Context,
    parameters: WorkerParameters,
    private val scans: AtomicInteger,
    private val gate: CompletableDeferred<Unit>,
    private val steps: List<Triple<String, Int, Int>> = emptyList(),
    private val leftMs: Long? = null,
) : IndexWorker(context, parameters) {
    init {
        isForeground = true
    }

    override suspend fun scan(): Result {
        scans.incrementAndGet()
        steps.forEach { (step, done, total) -> report(step, done, total) }
        setProgress(workDataOf(Indexing.STEP to "detectors", Indexing.DONE to 1, Indexing.TOTAL to 2, Indexing.LEFT_MS to (leftMs ?: -1L)))
        gate.await()
        return Result.success()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Same as the other Robolectric tests: SDK 37 needs more than the default setup
class IndexingWorkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Rome")
    private val defaultZone = TimeZone.getDefault()
    private var now = at(21, 12)
    private val clock = Clock { now.toInstant().toEpochMilli() }
    private val scans = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()
    private val steps = mutableListOf<Triple<String, Int, Int>>()
    private var workerFactory: (Context, WorkerParameters) -> ListenableWorker = { ctx, params ->
        FakeScan(ctx, params, scans, gate, steps)
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val configuration = Configuration.Builder()
            .setClock(clock)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = workerFactory(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
    }

    /** A scan left waiting would keep the process-wide lock for the next test. */
    @After
    fun tearDown() {
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { while (scheduled().any { it.state == WorkInfo.State.RUNNING }) kotlinx.coroutines.yield() } }
        TimeZone.setDefault(defaultZone)
    }

    private fun scheduled() = WorkManager.getInstance(app).getWorkInfosByTag(Indexing.TAG).get()

    /** The tile preparing job a finished scan enqueues. Its battery constraint keeps it waiting in the test WorkManager. */
    private fun preparing() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(TilePreparing.WORK).get().filterNot { it.state.isFinished }

    private fun nightly() = scheduled().single { it.tags.contains(Indexing.NIGHTLY_TAG) }

    private fun millis(time: ZonedDateTime) = time.toInstant().toEpochMilli()

    /** Lets the nightly request start at [time], as if charging and the delay were met then. */
    private fun startNightlyAt(time: ZonedDateTime) {
        now = time
        val id = nightly().id
        WorkManagerTestInitHelper.getTestDriver(app)!!.apply {
            setAllConstraintsMet(id)
            setInitialDelayMet(id)
        }
    }

    @Test
    fun `opening the app again keeps the first night, however late it is`() {
        Indexing.schedulePeriodic(app, now)
        now = at(21, 20)
        Indexing.schedulePeriodic(app, now)

        assertEquals(millis(at(22, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `the stored scan costs of an older version are removed`() {
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("scan_costs", "{}").putBoolean("other", true).commit()

        Indexing.schedulePeriodic(app, now)

        assertFalse(settings.contains("scan_costs"))
        assertTrue(settings.getBoolean("other", false))
    }

    @Test
    fun `the old daily request is cancelled`() {
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniquePeriodicWork("index-daily", ExistingPeriodicWorkPolicy.KEEP, Indexing.dailyRequest(now))

        Indexing.schedulePeriodic(app, now)

        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork("index-daily").get().single().state)
        assertEquals(1, scheduled().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `no scan is shown while none runs`() {
        Indexing.schedulePeriodic(app, now)

        assertNull(runBlocking { withTimeout(10_000) { Indexing.progress(app).first() } })
    }

    @Test
    fun `the progress of the nightly scan shows`() {
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2), progress)
    }

    @Test
    fun `the progress of a scan started with Scan now shows`() {
        Indexing.scanNow(app)

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2), progress)
    }

    @Test
    fun `the progress carries the time left`() {
        workerFactory = { ctx, params -> FakeScan(ctx, params, scans, gate, leftMs = 720_000) }
        Indexing.scanNow(app)

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2, leftMs = 720_000), progress)
    }

    @Test
    fun `a run in the night scans and pins the next one to the following night`() {
        Indexing.schedulePeriodic(app, now)
        gate.complete(Unit)

        startNightlyAt(at(22, 2, 30))

        assertEquals(1, scans.get())
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `a run in the daytime skips the scan and pins the next one to the coming night`() {
        Indexing.schedulePeriodic(app, now)
        gate.complete(Unit)

        startNightlyAt(at(22, 14))

        assertEquals(0, scans.get())
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `stopping a scan frees the lock for a waiting batch and keeps the previous report`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        val before = appInstance.lastScan.report
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }
        assertTrue(IndexWorker.running.isLocked)

        WorkManager.getInstance(app).cancelWorkById(scheduled().single().id)

        runBlocking { withTimeout(10_000) { while (IndexWorker.running.isLocked) yield() } }
        assertEquals(before, appInstance.lastScan.report)
        assertFalse(appInstance.lastScan.failed)
    }

    @Test
    fun `stopping the nightly scan brings its request back`() {
        Indexing.schedulePeriodic(app, now)
        val stopped = nightly().id
        startNightlyAt(at(22, 2, 30))
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        WorkManager.getInstance(app).cancelWorkById(stopped)

        runBlocking {
            withTimeout(10_000) {
                while (scheduled().none { Indexing.NIGHTLY_TAG in it.tags && it.id != stopped && it.state == WorkInfo.State.ENQUEUED }) yield()
            }
        }
    }

    @Test
    fun `the app's stop cancels the nightly scan, as the notification's does, and the night comes back`() {
        Indexing.schedulePeriodic(app, now)
        val stopped = nightly().id
        startNightlyAt(at(22, 2, 30))
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        runBlocking { Indexing.stop(app) }

        // A new nightly request, which the stopped run adds back only when its own was cancelled.
        runBlocking {
            withTimeout(10_000) {
                while (scheduled().none { Indexing.NIGHTLY_TAG in it.tags && it.id != stopped && it.state == WorkInfo.State.ENQUEUED }) yield()
                while (IndexWorker.running.isLocked) yield()
            }
        }
    }

    @Test
    fun `the app's stop cancels a Scan now and leaves the nightly request as it was`() {
        Indexing.schedulePeriodic(app, now)
        val night = nightly().id
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        runBlocking { Indexing.stop(app) }

        runBlocking {
            withTimeout(10_000) {
                Indexing.progress(app).first { it == null }
                while (IndexWorker.running.isLocked) yield()
            }
        }
        assertEquals(WorkInfo.State.ENQUEUED, scheduled().single { it.id == night }.state)
        assertEquals(1, scheduled().count { Indexing.NIGHTLY_TAG in it.tags })
    }

    @Test
    fun `Scan now waits for a scan that still holds the lock, and then scans`() {
        runBlocking { IndexWorker.running.lock() }
        Indexing.scanNow(app)
        Thread.sleep(300)
        assertEquals(0, scans.get())

        IndexWorker.running.unlock()

        runBlocking { withTimeout(10_000) { while (scans.get() == 0) yield() } }
    }

    @Test
    fun `the nightly run does not start a second scan on top of one started with Scan now`() {
        Indexing.scanNow(app)
        Indexing.schedulePeriodic(app, now)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        startNightlyAt(at(22, 2, 30))

        assertEquals(1, scans.get())
    }

    @Test
    fun `Scan videos asked for while Scan now runs adds nothing, and the progress says it is the photo scan`() {
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        Indexing.scanVideos(app)

        assertEquals(1, scans.get())
        assertEquals(1, scheduled().size)
        assertFalse(Indexing.VIDEO_TAG in scheduled().single().tags)
        assertEquals(ScanKind.IMAGES, runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }.kind)
        assertFalse((app as CribrumApp).videoScans.ran)
    }

    @Test
    fun `Scan now asked for while Scan videos runs adds nothing, and the progress says it is the video scan`() {
        Indexing.scanVideos(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        Indexing.scanNow(app)

        assertEquals(1, scans.get())
        assertTrue(Indexing.VIDEO_TAG in scheduled().single().tags)
        assertEquals(ScanKind.VIDEOS, runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }.kind)
    }

    @Test
    fun `the nightly run does not start a second scan on top of Scan videos`() {
        Indexing.scanVideos(app)
        Indexing.schedulePeriodic(app, now)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        startNightlyAt(at(22, 2, 30))

        assertEquals(1, scans.get())
    }

    @Test
    fun `stopping Scan videos frees the lock, keeps the previous report, and the night still takes the videos`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        val before = appInstance.lastScan.report
        Indexing.scanVideos(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        runBlocking { Indexing.stop(app) }

        runBlocking {
            withTimeout(10_000) {
                Indexing.progress(app).first { it == null }
                while (IndexWorker.running.isLocked) yield()
            }
        }
        assertEquals(before, appInstance.lastScan.report)
        assertTrue(appInstance.videoScans.nightly)
    }

    /** The real scan, with a pipeline whose readers say which of an image and a video it read. */
    private class ReadingScan(
        context: Context,
        parameters: WorkerParameters,
        private val reads: MutableCollection<String>,
        private val done: CompletableDeferred<Unit>,
        private val readingVideo: () -> Unit = {},
    ) : IndexWorker(context, parameters) {
        override fun hasStorageAccess() = true

        override suspend fun setForegroundInfo(info: ForegroundInfo) {}

        override suspend fun purgeExpired(app: CribrumApp) = ExpiredPurge.NONE

        override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(
            app.index,
            detectors = emptyList(),
            groupDetectors = emptyList(),
            metadataReader = {
                reads += "image"
                throw UnreadableImage(it)
            },
            videoMetadataReader = {
                reads += "video"
                readingVideo()
                throw UnreadableVideo(it)
            },
        )

        override suspend fun scan(): Result {
            try {
                return super.scan()
            } finally {
                done.complete(Unit)
            }
        }
    }

    /** Runs as a video is read. */
    private var readingVideo: () -> Unit = {}

    /** What a scan that [start] asks for reads, of an image and a video the index holds without their metadata. */
    private fun reads(start: () -> Unit): List<String> {
        val image = File(folder.root, "a.jpg").apply { writeText("a") }.toMediaRef()
        val video = File(folder.root, "v.mp4").apply { writeText("v") }.toMediaRef(MediaType.VIDEO)
        runBlocking { (app as CribrumApp).index.recordAll(listOf(image, video)) }
        val reads = ConcurrentLinkedQueue<String>()
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params -> ReadingScan(ctx, params, reads, done, readingVideo) }
        start()
        runBlocking { withTimeout(10_000) { done.await() } }
        return reads.toList()
    }

    private fun nightPass() {
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
    }

    @Test
    fun `the night pass reads no video before Scan videos has run`() {
        assertEquals(listOf("image"), reads(::nightPass))
    }

    @Test
    fun `once Scan videos has run, the night pass reads the videos after the images`() {
        (app as CribrumApp).videoScans.ran = true

        assertEquals(listOf("image", "video"), reads(::nightPass))
    }

    @Test
    fun `a night pass stopped in its video part records that the photos were scanned, and keeps the time of the videos`() {
        val appInstance = app as CribrumApp
        appInstance.videoScans.ran = true
        // A "Scan videos" ran before, and no photo scan has finished yet.
        appInstance.lastScan.record(Stats(), finishedAt = 100, images = false, videos = true)
        assertNull(appInstance.lastScan.report!!.imagesAt)

        // The stop comes while the video is read, and the read ends after it, so the pipeline sees the cancel.
        readingVideo = { runBlocking { Indexing.stop(app) } }

        assertEquals(listOf("image", "video"), reads(::nightPass))

        runBlocking { withTimeout(10_000) { while (IndexWorker.running.isLocked) yield() } }
        val report = appInstance.lastScan.report!!
        assertTrue(report.imagesAt!! > 100)
        assertEquals(100L, report.videosAt)
        assertFalse(report.videos)
    }

    @Test
    fun `the switch in Settings keeps the videos out of the night pass`() {
        (app as CribrumApp).videoScans.apply {
            ran = true
            atNight = false
        }

        assertEquals(listOf("image"), reads(::nightPass))
    }

    @Test
    fun `Scan videos reads only the videos, and turns the night's videos on`() {
        assertEquals(listOf("video"), reads { Indexing.scanVideos(app) })
        assertTrue((app as CribrumApp).videoScans.nightly)
    }

    @Test
    fun `Scan now never reads a video, even once Scan videos has run`() {
        (app as CribrumApp).videoScans.ran = true

        assertEquals(listOf("image"), reads { Indexing.scanNow(app) })
    }

    private fun notificationTitle() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().extras.getString("android.title")

    private fun notificationSubText() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().extras.getString("android.subText")

    private fun notificationText() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().extras.getString("android.text")

    @Test
    fun `the notification shows no total while the scan does not know one`() {
        steps += Triple("scan", 300, 0)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("300 so far", notificationText())
    }

    @Test
    fun `the scan's notification offers to stop it, which cancels the scan's work`() {
        steps += Triple("scan", 300, 0)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        val action = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().actions.single()
        assertEquals("Stop scanning", action.title)
        val running = WorkManager.getInstance(app).getWorkInfosByTag(Indexing.TAG).get().single()
        // WorkManager's own cancel intent, as the compression batch uses: it names the scan's work.
        assertTrue(shadowOf(action.actionIntent).savedIntent.toUri(0).contains(running.id.toString()))
    }

    @Test
    fun `the notification shows the total once the phase knows it`() {
        steps += Triple("embedded_detectors", 3, 10)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("3 of 10", notificationText())
    }

    @Test
    fun `each phase of the pipeline has a title of its own`() {
        assertEquals(R.string.scan_phase_scan, Indexing.phaseTitle("scan"))
        assertEquals(R.string.scan_phase_metadata, Indexing.phaseTitle("metadata"))
        assertEquals(R.string.scan_phase_analysis, Indexing.phaseTitle("detectors"))
        assertEquals(R.string.scan_phase_classification, Indexing.phaseTitle("embedded_detectors"))
        assertEquals(R.string.scan_phase_groups, Indexing.phaseTitle("near_duplicate"))
    }

    @Test
    fun `each phase has its number out of five, and every group detector is the last`() {
        val steps = listOf("scan", "metadata", "detectors", "embedded_detectors", "near_duplicate")
        assertEquals(listOf(1, 2, 3, 4, 5), steps.map(Indexing::phaseNumber))
        assertEquals(5, Indexing.PHASES)
        assertEquals(5, Indexing.phaseNumber("burst"))
        assertEquals(5, Indexing.phaseNumber("exact_duplicate"))
    }

    @Test
    fun `a scan with no step yet is in the first phase`() {
        assertEquals(1, Indexing.phaseNumber(""))
        assertEquals(R.string.scan_phase_scan, Indexing.phaseTitle(""))
    }

    @Test
    fun `the notification title follows the phase, even right after the last update`() {
        steps += Triple("scan", 100, 0)
        steps += Triple("embedded_detectors", 1, 10)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("Phase 4 of 5 · " + app.getString(R.string.scan_phase_classification), notificationTitle())
    }

    @Test
    fun `the video scan numbers its own phases from 1`() {
        assertEquals(listOf(1, 1, 2), listOf("", "scan", VIDEO_METADATA_STEP).map { Indexing.phaseNumber(it, ScanKind.VIDEOS) })
        assertEquals(2, Indexing.phases(ScanKind.VIDEOS))
        assertEquals(R.string.scan_phase_video_scan, Indexing.phaseTitle("scan", ScanKind.VIDEOS))
        assertEquals(R.string.scan_phase_video_metadata, Indexing.phaseTitle(VIDEO_METADATA_STEP, ScanKind.VIDEOS))
    }

    @Test
    fun `the notification of Scan videos shows its own phases and says it is the video scan`() {
        steps += Triple("scan", 300, 0)
        Indexing.scanVideos(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("Phase 1 of 2 · Looking for videos", notificationTitle())
        assertEquals("Video scan", notificationSubText())
    }

    @Test
    fun `a pass that does both shows the video phases after the image ones, as the video scan`() {
        steps += Triple("near_duplicate", 1, 1)
        steps += Triple(VIDEO_METADATA_STEP, 1, 10)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("Phase 2 of 2 · Reading the videos", notificationTitle())
        assertEquals("Video scan", notificationSubText())
    }

    private fun waitForScan(done: CompletableDeferred<Unit>, expectedNextNight: ZonedDateTime = at(23, 2)) = runBlocking {
        withTimeout(10_000) {
            done.await()
            while (nightly().nextScheduleTimeMillis != millis(expectedNextNight)) yield()
        }
    }

    @Test
    fun `a crashing scan records failure and pins the next night`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        assertFalse(appInstance.lastScan.failed)
        val done = CompletableDeferred<Unit>()

        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override suspend fun scan(): Result {
                    try {
                        scans.incrementAndGet()
                        throw IllegalStateException("crash in scan")
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertEquals(1, scans.get())
        assertTrue(appInstance.lastScan.failed)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
        assertFalse(IndexWorker.running.isLocked)
    }

    @Test
    fun `missing storage permission fails the scan, records failure and pins the next night`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        assertFalse(appInstance.lastScan.failed)
        val done = CompletableDeferred<Unit>()

        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = false
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(appInstance.lastScan.failed)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `foreground service start refusal does not abort the scan and pins the next night`() {
        var completedScan = false
        var foregroundAttempted = false
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {
                    foregroundAttempted = true
                    throw IllegalStateException("ForegroundServiceStartNotAllowedException")
                }
                override suspend fun purgeExpired(app: CribrumApp) = ExpiredPurge.NONE
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        completedScan = true
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(foregroundAttempted)
        assertTrue(completedScan)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(IndexWorker.NOTIFICATION_ID))
    }

    @Test
    fun `purgeExpired failure is recorded in failures and scan continues to completion`() {
        val appInstance = app as CribrumApp
        var completedScan = false
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {}
                override suspend fun purgeExpired(app: CribrumApp): ExpiredPurge = throw IllegalStateException("disk error during purge")
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        completedScan = true
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(completedScan)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
        assertTrue(appInstance.lastScan.failed)
        assertEquals(1, appInstance.lastScan.report?.failures?.get("trash"))
    }

    @Test
    fun `the scan forgets the rows of what the real trash purged, and keeps the row of an item it could not delete`() {
        val appInstance = app as CribrumApp
        val volume = folder.newFolder("storage")
        val trash = AppTrash({ listOf(volume) }, retention = { Duration.ZERO })
        val purged = File(volume, "purged.jpg").apply { writeText("purged") }.toMediaRef()
        val stuck = File(volume, "stuck.jpg").apply { writeText("stuck") }.toMediaRef()
        runBlocking {
            listOf(purged, stuck).forEach {
                appInstance.index.record(it)
                appInstance.index.setReviewState(it.path, ReviewState.TRASHED)
            }
            trash.moveToTrash(listOf(purged, stuck))
        }
        val stuckFolder = runBlocking { trash.trashed() }.single { it.original == stuck }.file.parentFile!!
        assumeTrue("the folder is still deletable (running as root?)", stuckFolder.setWritable(false) && !stuckFolder.canWrite())
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {}
                override suspend fun purgeExpired(app: CribrumApp) = trash.purgeExpired()
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        try {
            Indexing.schedulePeriodic(app, now)
            startNightlyAt(at(22, 2, 30))
            waitForScan(done)
        } finally {
            stuckFolder.setWritable(true)
        }

        assertEquals(listOf(stuck), runBlocking { appInstance.index.images() })
    }

    /** A model that counts how often it is closed. */
    private class CountingRuntime : ModelRuntime {
        var closed = 0

        override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> = emptyMap()

        override fun close() {
            closed++
        }
    }

    /** The real [IndexWorker.scan] with its outside calls replaced: they are counted and nothing touches the device. */
    private class CountingScan(context: Context, parameters: WorkerParameters, private val runtime: CountingRuntime, private val done: CompletableDeferred<Unit>, private val loadModel: Boolean, private val stopWith: GroupDetector? = null, private val fail: Boolean = false) : IndexWorker(context, parameters) {
        var foreground = 0
        var purged = 0
        var models = 0

        override fun hasStorageAccess() = true

        override suspend fun setForegroundInfo(info: ForegroundInfo) {
            foreground++
        }

        override suspend fun purgeExpired(app: CribrumApp): ExpiredPurge {
            purged++
            return ExpiredPurge.NONE
        }

        override fun loadVision(): ModelRuntime {
            models++
            return runtime
        }

        override fun pipeline(app: CribrumApp, embedder: MobileClip): Pipeline {
            if (loadModel) embedder.load()
            check(!fail) { "pipeline exploded" }
            return Pipeline(app.index, detectors = emptyList(), groupDetectors = listOfNotNull(stopWith), embedder = embedder)
        }

        override suspend fun scan(): Result {
            try {
                return super.scan()
            } finally {
                done.complete(Unit)
            }
        }
    }

    private fun runCountingScan(loadModel: Boolean, stopWith: GroupDetector? = null, fail: Boolean = false): Pair<CountingScan, CountingRuntime> {
        val runtime = CountingRuntime()
        val done = CompletableDeferred<Unit>()
        lateinit var worker: CountingScan
        workerFactory = { ctx, params -> CountingScan(ctx, params, runtime, done, loadModel, stopWith, fail).also { worker = it } }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)
        return worker to runtime
    }

    @Test
    fun `the real scan goes foreground, purges the trash and closes the model it loaded`() {
        val (worker, runtime) = runCountingScan(loadModel = true)

        assertEquals(1, worker.foreground)
        assertEquals(1, worker.purged)
        assertEquals(1, worker.models)
        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
        assertEquals(1, preparing().size)
    }

    @Test
    fun `the real scan loads no model when it has nothing to embed`() {
        val (worker, runtime) = runCountingScan(loadModel = false)

        assertEquals(1, worker.purged)
        assertEquals(0, worker.models)
        assertEquals(0, runtime.closed)
    }

    @Test
    fun `the real scan closes the model when the pipeline throws`() {
        val (_, runtime) = runCountingScan(loadModel = true, fail = true)

        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
        assertEquals(0, preparing().size)
    }

    @Test
    fun `the real scan closes the model when it is stopped`() {
        // The device has no images here, so the group detector runs on an empty list and stops the scan.
        val stop = object : GroupDetector {
            override val id = "stop"
            override val version = 1

            override suspend fun analyze(images: List<MediaRef>, context: AnalysisContext): Map<MediaRef, List<Signal>> = throw CancellationException("stopped")
        }

        val (_, runtime) = runCountingScan(loadModel = true, stopWith = stop)

        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
        assertEquals(0, preparing().size)
    }
}
