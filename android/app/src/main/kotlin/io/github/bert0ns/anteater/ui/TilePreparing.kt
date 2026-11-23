// Fills the grid tiles' disk cache for the first two Compress pages after a scan, and makes the previews of the video tiles
// larger than 1080p that the first page of each video grid loops (docs/design.md §8).
package io.github.bert0ns.anteater.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.github.bert0ns.anteater.AnteaterApp
import io.github.bert0ns.anteater.compress.CompressBatch
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.pipeline.IndexWorker
import io.github.bert0ns.anteater.review.Review
import io.github.bert0ns.anteater.review.VIDEO_PAGE_SIZE
import io.github.bert0ns.anteater.sources.favoritePaths
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

object TilePreparing {
    const val WORK = "tile-prepare"

    /** How many photos it prepares: the first two Compress pages. */
    internal const val PHOTOS = 2 * COMPRESS_PAGE_SIZE

    /** How many previews it makes at most: about 90 MB of 10 second loops, so they fit in the cache with room for those made on view. */
    internal const val PREVIEWS = 48

    /** After a scan ends, with no lock held. REPLACE: the list of the newest scan is the one to prepare. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TilePrepareWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Decodes the tiles of the first two Compress pages into [TileCache], largest first, so the first Compress screen after a
 * scan decodes thumbnails and not originals of up to 200 MP. It decodes at the size of the last Compress tile, so its
 * thumbnails are the ones the page asks for, and skips the photos before the page has shown a tile. One photo at a time, on
 * one thread of background priority. Then it makes the previews of the moving video tiles larger than 1080p (#482), one at
 * a time, so a grid opened after a scan loops them at once and does not wait on its first view. Between two photos or two
 * previews it stops when a scan starts (the scan's end enqueues it again) or the phone is hot, and waits for a compression
 * batch to end, as WorkManager retries it. A preview that fails is left to the grid, which shows the slideshow.
 */
open class TilePrepareWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var headroom: Float? = null

    override suspend fun doWork(): Result {
        val size = tileSize()
        if (size != null) {
            val files = photos().take(TilePreparing.PHOTOS)
            Executors.newSingleThreadExecutor {
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    it.run()
                }, "tile-prepare")
            }
                .asCoroutineDispatcher()
                .use { thread ->
                    for (file in files) {
                        stop()?.let { return it }
                        prepare(file, size, thread)
                    }
                }
        }
        stop()?.let { return it }
        var previews = 0
        for (mover in movers().filter { !it.playsFile }) {
            if (previews == TilePreparing.PREVIEWS) break
            stop()?.let { return it }
            if (preview(mover)) previews++
        }
        return Result.success()
    }

    /** Why it stops before the next photo or preview: a compression batch, a scan or heat. Null when it goes on. */
    private fun stop(): Result? = when {
        CompressBatch.busy -> Result.retry()
        IndexWorker.running.isLocked -> Result.success()
        hot() -> Result.success()
        else -> null
    }

    protected open fun tileSize(): Size? = TileCache.get(applicationContext).compressTileSize

    /** The photos of the Compress list, largest first, as the page lists them. */
    protected open suspend fun photos(): List<File> = (applicationContext as AnteaterApp).index.compressible(Build.VERSION.SDK_INT)
        .filter { it.info.marker == null }
        .map { File(it.path) }

    /** Loads [file] as a Compress tile of [size] would, so a miss stores its thumbnail, and skips it when the cache holds it. */
    protected open suspend fun prepare(file: File, size: Size, thread: CoroutineContext) {
        val cache = TileCache.get(applicationContext)
        val key = cache.key(file, size, Scale.FILL, Precision.INEXACT) ?: return
        if (cache.contains(key)) return
        // The scale and precision of AsyncImage with ContentScale.Crop. No memory cache and no hardware bitmap: nothing shows it.
        val request = ImageRequest.Builder(applicationContext).data(file).tile("Tile.prepare")
            .size(size).scale(Scale.FILL).precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)
            .fetcherCoroutineContext(thread).decoderCoroutineContext(thread)
            .build()
        SingletonImageLoader.get(applicationContext).execute(request)
    }

    /**
     * The moving tiles of the first page of each video grid, in the order the screens show them: "Largest videos", then the
     * video reviews in the order of the videos' list. Those the index knows are up to 1080p loop their file and need nothing,
     * and when every video is one of them it ranks nothing.
     */
    internal open suspend fun movers(): List<Mover> {
        val app = applicationContext as AnteaterApp
        if (!app.index.hasVideosAbove(FILE_LOOP_PIXELS)) return emptyList()
        val review = Review(app.index, app.trash, thresholds = { app.thresholds.values }, videoThresholds = { app.thresholds.videoValues }, favorites = { favoritePaths(app.contentResolver) })
        val largest = review.largestVideos().take(VIDEO_PAGE_SIZE).filterNot { it.unreadable }.map { Mover(it.video, it.pixels, it.durationMs, LONG_LOOP_MS) }
        val reviews = review.overview(MediaType.VIDEO).categories.filter { it.count > 0 }.flatMap { summary ->
            movingTiles(summary.category, review.page(summary.category, grouping = app.reviewOrder[summary.category], mediaType = MediaType.VIDEO).groups)
        }
        return (largest + reviews).distinctBy { it.video.path to it.loopEndMs }
    }

    /**
     * Makes [mover]'s preview, unless the cache holds it. False when the video turned out to be up to 1080p, so it loops its
     * file and takes none of the [TilePreparing.PREVIEWS].
     */
    internal open suspend fun preview(mover: Mover): Boolean = VideoPreviews.get(applicationContext).make(mover)?.path != mover.video.path

    /**
     * Moderate heat or worse, as the scan's heat line reads it (docs/design.md §4). A phone with no thermal service, or a
     * read that throws, counts as not hot, as the scan's own read does.
     */
    protected open fun hot(): Boolean {
        val power = applicationContext.getSystemService(PowerManager::class.java) ?: return false
        // NaN when read again within 10 seconds: the last good reading stays.
        runCatching { power.getThermalHeadroom(0) }.getOrNull()?.takeIf { it > 0 }?.let { headroom = it }
        val status = runCatching { power.currentThermalStatus }.getOrNull() ?: return false
        val level = heatOf(status, headroom)?.level ?: return false
        return level >= HeatLevel.MODERATE
    }
}
