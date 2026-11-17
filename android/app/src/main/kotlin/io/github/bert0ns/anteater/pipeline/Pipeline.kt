// Analysis pipeline: scan → metadata → detectors that read no embedding → embeddings, with the detectors
// that read one beside them → group detectors, then the video steps when a run asks for them: the video metadata, then the frames.
// Mirrors lab/src/anteater_lab/pipeline.py, which runs the embeddings and those detectors one after the other.
package io.github.bert0ns.anteater.pipeline

import android.os.SystemClock
import android.util.Log
import androidx.tracing.trace
import com.appmattus.crypto.Algorithm
import io.github.bert0ns.anteater.compress.JpegInfo
import io.github.bert0ns.anteater.compress.readJpeg
import io.github.bert0ns.anteater.detectors.AnalysisContext
import io.github.bert0ns.anteater.detectors.BlurDetector
import io.github.bert0ns.anteater.detectors.BurstDetector
import io.github.bert0ns.anteater.detectors.Detector
import io.github.bert0ns.anteater.detectors.DocumentDetector
import io.github.bert0ns.anteater.detectors.ExactDuplicateDetector
import io.github.bert0ns.anteater.detectors.ExposureDetector
import io.github.bert0ns.anteater.detectors.FaceDetector
import io.github.bert0ns.anteater.detectors.ForwardedDetector
import io.github.bert0ns.anteater.detectors.GroupDetector
import io.github.bert0ns.anteater.detectors.MissingEmbedding
import io.github.bert0ns.anteater.detectors.NearDuplicateDetector
import io.github.bert0ns.anteater.detectors.Photo
import io.github.bert0ns.anteater.detectors.ScreenshotDetector
import io.github.bert0ns.anteater.detectors.ShortClipsDetector
import io.github.bert0ns.anteater.detectors.StickerDetector
import io.github.bert0ns.anteater.detectors.TakesDetector
import io.github.bert0ns.anteater.detectors.firstAfter
import io.github.bert0ns.anteater.detectors.firstAtOrAfter
import io.github.bert0ns.anteater.folders.isStickerFolder
import io.github.bert0ns.anteater.frames.FRAMES_VERSION
import io.github.bert0ns.anteater.frames.SampledFrame
import io.github.bert0ns.anteater.frames.frameTimes
import io.github.bert0ns.anteater.frames.measureFrame
import io.github.bert0ns.anteater.frames.readFrames
import io.github.bert0ns.anteater.hashing.PHASH_DECODE_SIDE
import io.github.bert0ns.anteater.hashing.contentHash
import io.github.bert0ns.anteater.hashing.perceptualHash
import io.github.bert0ns.anteater.hashing.toHex
import io.github.bert0ns.anteater.imaging.Pixels
import io.github.bert0ns.anteater.index.ImageIndex
import io.github.bert0ns.anteater.metadata.ImageMetadata
import io.github.bert0ns.anteater.metadata.UnreadableImage
import io.github.bert0ns.anteater.metadata.UnreadableVideo
import io.github.bert0ns.anteater.metadata.VideoMetadata
import io.github.bert0ns.anteater.metadata.readMetadata
import io.github.bert0ns.anteater.metadata.readVideoMetadata
import io.github.bert0ns.anteater.mobileclip.modelInput
import io.github.bert0ns.anteater.mobileclip.preprocess
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ReviewState
import io.github.bert0ns.anteater.model.Signal
import io.github.bert0ns.anteater.sources.MediaSource
import io.github.bert0ns.anteater.tracing.traceSuspend
import io.github.bert0ns.anteater.trash.Trash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.LocalDateTime

val DETECTORS: List<Detector> = listOf(
    BlurDetector(),
    ExposureDetector(),
    ScreenshotDetector(),
    DocumentDetector(),
    ForwardedDetector(),
    FaceDetector(),
    StickerDetector(),
)

/**
 * The detectors that read no embedding and still skip a sticker. A sticker is listed under STICKER only and its signal there already gives
 * the highest likelihood, so blur and exposure cannot change its place, and faces were not shown to order stickers (docs/design.md §5).
 */
val SKIPPED_ON_STICKERS: Set<String> = setOf("blur", "exposure", "faces")

/** Whether a sticker runs [detector]: not one that reads the embedding, which a sticker never gets, nor one in [SKIPPED_ON_STICKERS]. */
fun runsOnStickers(detector: Detector): Boolean = !detector.usesEmbeddings && detector.id !in SKIPPED_ON_STICKERS

val GROUP_DETECTORS: List<GroupDetector> = listOf(ExactDuplicateDetector(), NearDuplicateDetector(), BurstDetector(), TakesDetector(), ShortClipsDetector())
val GROUP_DETECTOR_IDS: Set<String> = GROUP_DETECTORS.map { it.id }.toSet()
val DETECTOR_CATEGORIES: Set<Category> =
    (DETECTORS.mapNotNull { it.category } + GROUP_DETECTORS.mapNotNull { it.category }).toSet()

/** The categories whose detectors take only videos, such as the takes of one moment: the Photos tab leaves them out. */
val VIDEO_ONLY_CATEGORIES: Set<Category> =
    GROUP_DETECTORS.filter { MediaType.IMAGE !in it.mediaTypes }.mapNotNull { it.category }.toSet() -
        (DETECTORS.filter { MediaType.IMAGE in it.mediaTypes }.mapNotNull { it.category } + GROUP_DETECTORS.filter { MediaType.IMAGE in it.mediaTypes }.mapNotNull { it.category }).toSet()

/** The categories that have a detector for videos: the ones the Videos tab lists (docs/design.md §8). */
val VIDEO_DETECTOR_CATEGORIES: Set<Category> =
    (DETECTORS.filter { MediaType.VIDEO in it.mediaTypes }.mapNotNull { it.category } + GROUP_DETECTORS.filter { MediaType.VIDEO in it.mediaTypes }.mapNotNull { it.category }).toSet()

/** Images embedded at once: bounds the memory held by decoded pixels. */
const val EMBEDDING_BATCH = 16

/** The progress step of the JPEG header read, part of the metadata phase. */
const val JPEG_STEP = "jpeg_headers"

/** The log tag of the JPEG status counts each scan writes, for the maintainer's check of the rules on a real library. */
const val JPEG_TAG = "jpeg-status"

/** The progress step of the video metadata, which runs only when the scan is asked to analyse videos. */
const val VIDEO_METADATA_STEP = "video_metadata"

/** The progress step of the group detectors on videos: one for them all, since the video scan numbers its phases and not its detectors. */
const val VIDEO_GROUPS_STEP = "video_groups"

/** The progress step of a video's frames, sampled and embedded after the video metadata, and the [Failure.step] of a video whose frames fail. */
const val VIDEO_FRAMES_STEP = "video_frames"

/** The progress step of the embeddings and the detectors that read them, which run at once (docs/design.md §4). */
const val CLASSIFY_STEP = "embedded_detectors"

/** Workers the detectors that read embeddings leave to the encoder's decodes while it runs. */
private const val EMBEDDING_DECODERS = 2

/** Images the walk records in one transaction. */
private const val RECORD_BATCH = 300

/** Images whose metadata, JPEG headers or detector results are stored in one transaction. A stopped scan loses at most one batch. */
private const val STORE_BATCH = 32

/** Files whose metadata or JPEG headers are read at once: mostly waiting on storage, so more than the cores of a small phone, and few enough that the writer keeps up. */
private const val MIN_READERS = 4
private const val MAX_READERS = 8

/** Embeds images preprocessed by [preprocess], as [io.github.bert0ns.anteater.mobileclip.MobileClip] does. */
interface Embedder {
    val id: String
    val version: Int

    /** Loads the model, so a model that cannot load fails once, here, and not once per image. Does nothing when it is loaded. */
    fun load() {}

    fun embedPixels(pixels: List<FloatArray>): List<FloatArray>
}

/** Called with a step name, the items done and the items in total (0 while unknown). */
typealias Progress = suspend (step: String, done: Int, total: Int) -> Unit

/** An image that failed at [step]: "metadata", "embedding", "perceptual hash" or a detector id. */
data class Failure(val path: String, val step: String, val error: String) {
    override fun toString() = "$path: $step: $error"
}

/** Format a Throwable as "ClassName: message" for [Failure]. */
internal fun failureMessage(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

data class Stats(
    var images: Int = 0,
    /** The videos the walk found. */
    var videos: Int = 0,
    var newOrChanged: Int = 0,
    var forgotten: Int = 0,
    var unreadable: Int = 0,
    /** The unreadable files among the ones the scan found. */
    var unreadablePaths: List<String> = emptyList(),
    /** Per-image analyses, perceptual hashes and embeddings computed, plus group detector runs. */
    var detectorRuns: Int = 0,
    /** Per-image analyses left for a run with embeddings. */
    var skipped: Int = 0,
    /** Analyses that threw. They are not stored, so the next run retries them. */
    val failures: MutableList<Failure> = mutableListOf(),
    /** Ids of the group runs, a failed one included, and of those whose fingerprint let them skip: the detector id, and `<id>:video` for the videos' run. */
    val ranGroupDetectors: MutableList<String> = mutableListOf(),
    val skippedGroupDetectors: MutableList<String> = mutableListOf(),
    /** Milliseconds each phase took, in the order they ran. */
    val phaseMs: MutableMap<String, Long> = linkedMapOf(),
    /** What the scan counted, for the scan-time log: the walk's counts, the trash items, the tasks of each phase and the stickers. */
    val counts: MutableMap<String, Int> = linkedMapOf(),
    /** The thermal status ([android.os.PowerManager.currentThermalStatus]) at the start of each phase. */
    val thermal: MutableMap<String, Int> = linkedMapOf(),
)

/**
 * Every step stores its results as it goes and skips what the index already holds for the same
 * detector version, so an interrupted run resumes where it stopped and a second run on unchanged
 * data does no detector work.
 */
class Pipeline(
    private val index: ImageIndex,
    private val detectors: List<Detector> = DETECTORS,
    private val groupDetectors: List<GroupDetector> = GROUP_DETECTORS,
    /** Without one no embeddings are computed, and detectors that need one wait for a run with it. */
    private val embedder: Embedder? = null,
    /** Images analyzed at once: decoding is the slow part, and it runs on every core. */
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    private val preprocessing: (File) -> FloatArray = ::preprocess,
    /** Lists what the trash holds, to reconcile TRASHED rows with it. Without one, TRASHED rows are left alone. */
    private val trash: Trash? = null,
    private val metadataReader: (File) -> ImageMetadata = ::readMetadata,
    private val jpegReader: (File) -> JpegInfo = ::readJpeg,
    private val videoMetadataReader: (File) -> VideoMetadata = { readVideoMetadata(it) },
    /** A video's frames at these times in milliseconds, by time, each preprocessed as [preprocessing] does an image, with its measures. */
    private val frameReader: (File, List<Long>) -> Map<Long, SampledFrame> = { file, times -> readFrames(file, times) { SampledFrame(modelInput(it), measureFrame(it)) } },
    /** Hashes an image from its task's [Pixels], so it reads the grayscale exposure already computed. */
    private val perceptualHasher: (MediaRef, Pixels) -> ULong = { _, pixels -> perceptualHash(pixels.gray(PHASH_DECODE_SIDE)) },
    private val contentHasher: (MediaRef) -> String = ::contentHash,
    /** Monotonic milliseconds, for [Stats.phaseMs]. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** The phone's thermal status, read once at the start of each phase. Null when unknown. */
    private val thermalStatus: () -> Int? = { null },
    /** Images the walk records in one transaction; a test sets it low to cross it with a few files. */
    internal val recordBatch: Int = RECORD_BATCH,
    /** Results stored in one transaction; a test sets it low to cross it with a few files. */
    internal val storeBatch: Int = STORE_BATCH,
    /** Files whose metadata and JPEG headers are read at once. */
    internal val readers: Int = parallelism.coerceIn(MIN_READERS, MAX_READERS),
) {
    /**
     * Analyzes every image the source finds. Images it no longer finds leave the index, except
     * the trashed ones the trash still holds, and the rows of an original a compression left in the
     * trash halfway: restoring them must find their review state and signals again (docs/design.md §9). A trashed row whose file is back on disk with no item in
     * the trash becomes unreviewed, and one that is neither on disk nor in the trash leaves the index.
     * Only images under a root the source walked can be gone: an absent SD card or an unreadable
     * folder is not a deletion.
     *
     * Every run walks and records images and videos. [images] and [videos] pick the steps after the walk (docs/design.md §4):
     * the image steps alone for "Scan now", the video steps alone for "Scan videos", and both, videos after every image
     * step, for a night pass once "Scan videos" has run. A run that does both calls [imagesDone] with the stats so far once
     * the image steps end, so a stop in the video steps does not lose that the photos were scanned.
     */
    suspend fun run(
        source: MediaSource,
        images: Boolean = true,
        videos: Boolean = false,
        imagesDone: suspend (Stats) -> Unit = {},
        progress: Progress = { _, _, _ -> },
    ): Stats = withContext(Dispatchers.IO) {
        val stats = Stats()
        val found = LinkedHashSet<String>()
        val before = index.reviewStates()
        phase("walk", stats) {
            val batch = ArrayList<MediaRef>(recordBatch)
            suspend fun flush() {
                stats.newOrChanged += index.recordAll(batch)
                batch.clear()
            }
            source.scan().collect { image ->
                found += image.path
                batch += image
                if (batch.size == recordBatch) flush()
                if (found.size % 100 == 0) progress("scan", found.size, 0)
            }
            flush()
        }
        source.counts.forEach { (name, count) -> stats.counts["walk $name"] = count }
        // A trash that cannot be listed is a failure of this step. No missing file leaves the index until a scan can read it.
        val items = try {
            trash?.trashed()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            stats.failures += Failure("", "trash", failureMessage(error))
            null
        }
        items?.let { stats.counts["trash items"] = it.size }
        val inTrash = items?.mapTo(HashSet()) { it.original.path }
        // A compression killed between its two moves leaves the original in the trash, its path empty, and its row
        // not TRASHED and still the original's. A finished swap's row is the copy's, so a copy deleted elsewhere goes.
        val halfSwaps = items?.filter { it.compressedCopy != null }?.mapTo(HashSet()) { it.original }.orEmpty()
        val rows = if (halfSwaps.isEmpty()) emptyMap() else index.images().associateBy { it.path }
        val states = index.reviewStates()
        val walked = source.walkedRoots
        // A review action may have run during the walk: only a row in the state the walk began with is judged by it.
        val judged = states.filter { (path, state) -> before[path] == state }
        val gone = judged.filter { (path, state) ->
            path !in found &&
                walked.any { root -> File(path).startsWith(root) } &&
                !File(path).exists() &&
                // a file that went and came back while the walk was in its folder is not gone
                // With no listing of the trash, any row could be a half swap.
                if (state == ReviewState.TRASHED) inTrash != null && path !in inTrash else (trash == null || inTrash != null) && rows[path] !in halfSwaps
        }.keys
        index.forget(gone)
        if (inTrash != null) {
            // The file is back on disk and the trash does not hold it: someone put it back by hand.
            judged.filter { (path, state) -> state == ReviewState.TRASHED && path in found && path !in inTrash }
                .keys.forEach { index.restoreReviewState(it, ReviewState.UNREVIEWED, emptySet(), emptySet()) }
        }
        stats.forgotten = gone.size

        if (images) imageSteps(found, stats, progress)
        if (images && videos) imagesDone(counted(found, stats))
        if (videos) videoSteps(found, stats, progress)
        counted(found, stats)
    }

    /** Fills in the counts of what the walk [found], and drops the failures the user can do nothing about. */
    private suspend fun counted(found: Set<String>, stats: Stats): Stats {
        val unreadable = index.unreadable().toSet()
        stats.unreadablePaths = found.filter { it in unreadable }
        stats.unreadable = stats.unreadablePaths.size
        val media = index.images().filter { it.path in found }
        stats.images = media.count { it.mediaType == MediaType.IMAGE }
        stats.videos = media.size - stats.images
        // An image forgotten, trashed or moved since the walk failed because of that, and the user can do nothing about it.
        val statesNow = index.reviewStates()
        stats.failures.removeAll { it.path.isNotEmpty() && (!File(it.path).isFile || statesNow[it.path].let { state -> state == null || state == ReviewState.TRASHED }) }
        return stats
    }

    /** Every step after the walk for the images found, the group detectors last. */
    private suspend fun imageSteps(found: Set<String>, stats: Stats, progress: Progress) {
        // The metadata read from an image header, and every step after it but the group detectors, take images only:
        // a video is recorded and nothing more (docs/design.md §4).
        val pending = stickersLast(index.withoutMetadata().filter { it.mediaType == MediaType.IMAGE })
        phase("metadata", stats) {
            readAndStore(pending, "metadata", progress, read = { attempt { metadataReader(it) } }) { batch -> storeMetadata(batch, stats) }
        }
        // Android only: what the compress page needs, from the JPEG headers (docs/design.md §9).
        val jpegs = index.jpegsToRead().filter { it.path in found }
        phase("jpegHeaders", stats) {
            readAndStore(jpegs, JPEG_STEP, progress, read = { attempt { jpegReader(it) } }) { batch -> storeJpegs(batch, stats) }
        }
        index.jpegCounts().forEach { Log.i(JPEG_TAG, "${it.status} gain_map=${it.gainMap} ${it.count}") }
        val unreadable = index.unreadable().toSet()
        val media = index.images().filter { it.path in found }
        val readable = media.filter { it.mediaType == MediaType.IMAGE && it.path !in unreadable } // group detectors still see the unreadable files
        // The home screen lists candidates as soon as these are stored; the embeddings come after.
        phase("detectors", stats) {
            analyzeEach(stickersLast(readable), detectors.filter { !it.usesEmbeddings }, "detectors", stats, progress)
        }
        // A sticker is listed under STICKER only (docs/design.md §8), so neither MobileCLIP nor text coverage runs on it.
        val (stickers, embeddable) = readable.partition { isStickerFolder(it.path) }
        stats.counts["stickers"] = stickers.size
        // A sticker scanned before those rules keeps results they no longer produce: drop them so a library gives the same result either way.
        index.forgetAnalyses(stickers.map { it.path }, detectors.filterNot(::runsOnStickers).map { it.id })
        val embeddings = embedAndClassify(embeddable, stats, progress)

        // Embeddings change what group detectors can see: a run with them is a different run.
        val model = embedder?.let { "${it.id}:${it.version}:${embeddings.size}" }.orEmpty()
        groupSteps(MediaType.IMAGE, found, embeddings, model, detectors.firstOrNull { it.category == Category.BLURRY }, stats, progress)
    }

    /**
     * Runs the group detectors that accept [mediaType] on the files of that type, so a group never mixes images and videos.
     * The image run and the video run each keep a fingerprint of their own, so neither runs the other's detectors again.
     * [frames] holds the videos' stored frames, in the order of their times.
     */
    private suspend fun groupSteps(mediaType: MediaType, found: Set<String>, embeddings: Map<String, FloatArray>, model: String, blur: Detector?, stats: Stats, progress: Progress, frames: Map<String, List<FloatArray>> = emptyMap()) {
        val media = index.images().filter { it.path in found && it.mediaType == mediaType }
        val video = mediaType == MediaType.VIDEO
        // The exact detector skips an image whose content hash fails: the run stores those paths with its fingerprint.
        val hashFailed = mutableSetOf<String>()
        // Read once: a query per image took seconds on a 10,000-image library.
        val perceptualHashes = index.perceptualHashes()
        val metadata = index.allMetadata()
        val videoMetadata = if (video) index.allVideoMetadata() else emptyMap()
        val stored = StoredForClips()
        val context = AnalysisContext(
            contentHash = {
                try {
                    cachedContentHash(it)
                } catch (error: IOException) {
                    hashFailed += it.path
                    throw error
                }
            },
            perceptualHash = { perceptualHashes[it.path] ?: throw NoSuchElementException(it.path) },
            embedding = { embeddings[it.path] ?: throw MissingEmbedding(it) },
            metadata = { metadata[it.path] ?: throw UnreadableImage(File(it.path)) },
            videoMetadata = { videoMetadata[it.path] ?: throw UnreadableVideo(File(it.path)) },
            frames = { frames[it.path].orEmpty() },
            frameMeasures = { stored.measures(it) },
            photos = { times, windowMs -> stored.photosNear(times, windowMs) },
            storedInputs = { stored.inputs() },
            // Burst ranks its shots by the measure blur stored, and decodes a shot again only when blur left none.
            sharpness = { storedSharpness(blur, it) ?: BlurDetector.peakVariance(it) },
        )
        // A hash that failed on an earlier run is a result a group detector lacked. An image whose metadata failed has none either (it gets no per-image
        // analysis), so when the next scan reads the metadata and the hash appears, this changes the fingerprint and is what puts the image back in its burst.
        val hashed = index.hashedPaths()
        // Burst reads blur's stored measure: a new blur version is new input, though no file changed.
        val blurInput = blur?.let { "${it.id}:${it.version}" }.orEmpty()
        phase(if (video) "videoGroups" else "groupDetectors", stats) {
            for (detector in groupDetectors) {
                if (mediaType !in detector.mediaTypes) continue
                val inputs = media
                // The images keep the run they had, which a video run must not overwrite.
                val runId = if (video) "${detector.id}:${mediaType.value}" else detector.id
                val step = if (video) VIDEO_GROUPS_STEP else detector.id
                // A capture time read again, as by a migration, is new input to the takes though no file changed.
                val captured = if (video) ":${videoMetadata.values.count { it.capturedInstant != null }}" else ""
                // A detector that reads more than its files, such as the photos beside a clip, adds its own inputs: a new photo runs it again and no other group detector.
                val own = detector.inputsKey(context).let { if (it.isEmpty()) "" else ":$it" }
                val fingerprint = fingerprint(inputs, "$model:${inputs.count { it.path in hashed }}:$blurInput$captured$own")
                if (upToDate(detector, runId, fingerprint, inputs)) {
                    stats.skippedGroupDetectors += runId
                    continue
                }
                stats.ranGroupDetectors += runId
                progress(step, 0, 1)
                hashFailed.clear()
                try {
                    val signals = traceSuspend("Group.${detector.id}.analyze") { detector.analyze(inputs, context) }
                    traceSuspend("Group.${detector.id}.store") {
                        // An image in two groups is rewritten by each of their detectors at every run, to keep its signals' order.
                        stats.counts["$runId rewritten"] = index.setGroupSignals(detector.id, detector.version, inputs.associate { it.path to signals[it].orEmpty() })
                        index.setGroupFingerprint(runId, detector.version, fingerprint, hashFailed.sorted())
                    }
                    stats.detectorRuns++
                    progress(step, 1, 1)
                } catch (error: Exception) {
                    if (error is CancellationException || error is MissingEmbedding) throw error
                    stats.failures += Failure("", detector.id, failureMessage(error))
                }
            }
        }
    }

    /**
     * What SHORT_CLIPS reads from the index besides the videos. Each part loads when a detector asks for it, not before: a run that finds
     * the detector up to date reads none of it.
     */
    private inner class StoredForClips {
        private var measures: Map<String, List<FrameMeasures>>? = null
        private var captures: List<Pair<String, LocalDateTime>>? = null

        suspend fun measures(video: MediaRef): List<FrameMeasures> = (measures ?: index.frameMeasures(FRAMES_VERSION).also { measures = it })[video.path].orEmpty()

        /** The camera photos within [windowMs] of any of [times], with their embeddings. */
        suspend fun photosNear(times: List<LocalDateTime>, windowMs: Int): List<Photo> {
            val ordered = captures ?: index.cameraCaptures().toList().sortedBy { it.second }.also { captures = it }
            val stamps = ordered.map { it.second }
            val window = Duration.ofMillis(windowMs.toLong())
            val near = LinkedHashMap<String, LocalDateTime>()
            for (time in times) {
                val start = firstAtOrAfter(stamps, time - window)
                val end = firstAfter(stamps, time + window)
                for (i in start until end) near[ordered[i].first] = ordered[i].second
            }
            val vectors = embedder?.let { index.embeddingsOf(it.id, it.version, near.keys) }.orEmpty()
            return near.map { (path, at) -> Photo(at, vectors[path]) }
        }

        suspend fun inputs(): String = index.shortClipInputs(embedder?.id.orEmpty(), embedder?.version ?: 0, FRAMES_VERSION)
    }

    /**
     * The video steps, which only a run asked for videos takes: the metadata, the per-video detectors that read no frame, then the
     * frames, the detectors that read them and the group detectors that accept videos, which may read them too. The detectors that
     * read no frame go before the frames, the longest step, so a scan stopped there still has their signals. The ones that read them
     * come after it, from the measures it stored: they take a moment, and they need no frame decoded again.
     */
    private suspend fun videoSteps(found: Set<String>, stats: Stats, progress: Progress) {
        val pending = index.withoutMetadata().filter { it.mediaType == MediaType.VIDEO }
        phase("videoMetadata", stats) {
            readAndStore(pending, VIDEO_METADATA_STEP, progress, read = { attempt { videoMetadataReader(it) } }) { batch -> storeVideoMetadata(batch, stats) }
        }
        analyzeVideos(found, stats, usesFrames = false)
        var frames = emptyMap<String, List<FloatArray>>()
        var model = ""
        if (embedder != null) {
            phase("videoFrames", stats) { embedFrames(embedder, stats, progress) }
            frames = index.frameEmbeddings(embedder.id, embedder.version, FRAMES_VERSION).mapValues { it.value.values.toList() }
            // A video's frames change what its group detectors can see, as embeddings do for images.
            if (frames.isNotEmpty()) model = "${embedder.id}:${embedder.version}:$FRAMES_VERSION:${frames.size}"
        }
        analyzeVideos(found, stats, usesFrames = true)
        groupSteps(MediaType.VIDEO, found, emptyMap(), model, null, stats, progress, frames)
    }

    /**
     * Runs the detectors that accept videos, and read frames or not as [usesFrames] says, on each video that lacks them. The ones
     * that read none are given no embedding, so one that asks for it fails and is counted. The ones that read frames take only a video
     * whose measures are stored: without them a video has nothing to analyze yet, and no empty result is stored. A video whose header
     * did not read is left out, as an unreadable image is.
     */
    private suspend fun analyzeVideos(found: Set<String>, stats: Stats, usesFrames: Boolean) {
        val selected = detectors.filter { MediaType.VIDEO in it.mediaTypes && it.usesFrames == usesFrames }
        if (selected.isEmpty()) return
        val analyzed = index.analyzedPaths(selected.associate { it.id to it.version })
        val unreadable = index.unreadable().toSet()
        val measures = if (usesFrames) index.frameMeasures(FRAMES_VERSION) else emptyMap()
        val context = AnalysisContext(frameMeasures = { measures[it.path].orEmpty() })
        val results = index.images().filter { it.mediaType == MediaType.VIDEO && it.path in found && it.path !in unreadable && (!usesFrames || it.path in measures) }.flatMap { video ->
            selected.filter { video.path !in analyzed.getValue(it.id) }.map { detector -> Triple(video.path, detector, attempt { detector.analyze(video, context) }) }
        }
        for (batch in results.chunked(STORE_BATCH)) {
            index.batch(batch.map { it.first }) { recorded ->
                for ((path, detector, result) in batch) {
                    if (path !in recorded) continue // the row went while the scan ran
                    result.fold(
                        {
                            index.setSignals(path, detector.id, detector.version, it)
                            stats.detectorRuns++
                        },
                        { stats.failures += Failure(path, detector.id, failureMessage(it)) },
                    )
                }
            }
        }
    }

    /**
     * Embeds the frames of each readable video with no frames at [FRAMES_VERSION], or with one at a time [frameTimes] does not
     * give now (docs/design.md §4). An HDR video stores one frame a keyframe, so some of the times may be missing. A rule
     * that adds times must bump [FRAMES_VERSION], or the videos sampled before it keep their frames. One video at a time: its frames
     * decode one after the other, the model takes them as one batch and they are stored together, so a stop loses at most
     * the video it was on. A video whose frames do not read is marked, as an unreadable image is, and not decoded again
     * until the file or [FRAMES_VERSION] changes. Any other error is a failure, and the next scan tries it again, as an
     * image whose embedding fails.
     */
    private suspend fun embedFrames(embedder: Embedder, stats: Stats, progress: Progress) {
        val stored = index.sampledTimes(embedder.id, embedder.version, FRAMES_VERSION)
        val todo = index.readVideos(FRAMES_VERSION).mapNotNull { video ->
            frameTimes(video.durationMs).takeUnless { times -> stored[video.ref.path]?.let { times.containsAll(it) } == true }?.let { video.ref to it }
        }
        stats.counts["$VIDEO_FRAMES_STEP tasks"] = todo.size
        progress(VIDEO_FRAMES_STEP, 0, todo.size)
        if (todo.isEmpty()) return
        withContext(Dispatchers.Default) { attempt { embedder.load() } }.exceptionOrNull()?.let { error ->
            stats.failures += Failure("", EMBEDDING_STEP, failureMessage(error))
            return
        }
        var frames = 0
        var unreadable = 0
        for ((done, task) in todo.withIndex()) {
            val (video, times) = task
            attempt { frameReader(File(video.path), times).let { taken -> taken to taken.keys.zip(embedder.embedPixels(taken.values.map { it.input })).toMap() } }.fold(
                onSuccess = { (taken, vectors) ->
                    // A video gone from the index since the walk keeps nothing. A detector's result belongs to the frames it read: new ones mean analyzing again.
                    index.batch(listOf(video.path)) { recorded ->
                        if (video.path in recorded) {
                            index.forgetAnalyses(listOf(video.path), detectors.filter { it.usesFrames }.map { it.id })
                            index.setFrameEmbeddings(video.path, embedder.id, embedder.version, FRAMES_VERSION, vectors, taken.mapValues { it.value.measures })
                        }
                    }
                    frames += vectors.size
                    stats.detectorRuns += vectors.size
                },
                onFailure = { error ->
                    // A file moved since the walk, such as one the user trashed, is not the video's fault.
                    if (error is UnreadableVideo && File(video.path).isFile) {
                        index.batch(listOf(video.path)) { recorded -> if (video.path in recorded) index.setFramesUnreadable(video.path, FRAMES_VERSION) }
                        unreadable++
                    } else {
                        stats.failures += Failure(video.path, VIDEO_FRAMES_STEP, failureMessage(error))
                    }
                },
            )
            progress(VIDEO_FRAMES_STEP, done + 1, todo.size)
        }
        stats.counts["$VIDEO_FRAMES_STEP frames"] = frames
        stats.counts["$VIDEO_FRAMES_STEP unreadable"] = unreadable
    }

    /** The real images first: a sticker only delays them, since the home screen lists candidates as they are stored. */
    private fun stickersLast(images: List<MediaRef>): List<MediaRef> = images.partition { !isStickerFolder(it.path) }.let { (real, stickers) -> real + stickers }

    /** Runs one phase of [run] in a trace section, and keeps how long it took. */
    private suspend fun <T> phase(name: String, stats: Stats, startedAt: Long? = null, block: suspend () -> T): T {
        thermalStatus()?.let { stats.thermal[name] = it }
        val start = startedAt ?: clock()
        return traceSuspend("Pipeline.$name", block).also { stats.phaseMs[name] = clock() - start }
    }

    private class AnalysisTask(
        val image: MediaRef,
        val metadata: ImageMetadata,
        val embedding: FloatArray?,
        val detectors: List<Detector>,
        val needsPhash: Boolean,
        val beforeEmbeddings: Boolean,
    )

    private class AnalysisOutcome(
        val task: AnalysisTask,
        val detectorResults: List<Pair<Detector, Result<List<Signal>?>>>,
        val phash: Result<ULong>?,
    )

    /**
     * The embeddings, with the detectors that read them running beside them (docs/design.md §4, §7). The images whose
     * embedding is stored already go to the detectors first, and each batch's images as soon as its embeddings are stored,
     * after the results they replace are forgotten. While the model runs, [EMBEDDING_DECODERS] workers are left to its
     * decodes. The two steps' own work runs on one thread, so they never update the stats or report progress at once.
     * Returns the embeddings of [images], for the group detectors.
     */
    private suspend fun embedAndClassify(images: List<MediaRef>, stats: Stats, progress: Progress): Map<String, FloatArray> = withContext(Dispatchers.IO.limitedParallelism(1)) {
        // The phase's time starts here, so it holds the reads and the tasks of the images embedded already.
        val start = clock()
        val selected = detectors.filter { it.usesEmbeddings }
        val kept = images.mapTo(HashSet()) { it.path }
        val embeddings = HashMap(embedder?.let { index.embeddings(it.id, it.version).filterKeys { path -> path in kept } }.orEmpty())
        val todo = if (embedder == null) emptyList() else images.filter { it.path !in embeddings }
        val analyzed = index.analyzedPaths(selected.associate { it.id to it.version })
        var tasks = 0
        var stickerTasks = 0

        // As in analyzeEach: only an image with work reads its metadata, and one without metadata waits for a restore.
        suspend fun task(image: MediaRef, todo: List<Detector>): AnalysisTask? {
            if (todo.isEmpty()) return null
            val task = index.metadata(image.path)?.let { AnalysisTask(image, it, embeddings[image.path], todo, needsPhash = false, beforeEmbeddings = false) } ?: return null
            tasks++
            if (isStickerFolder(image.path)) stickerTasks++
            return task
        }
        fun missing(image: MediaRef) = selected.filter { image.path !in analyzed.getValue(it.id) }
        val waiting = todo.mapTo(HashSet()) { it.path }
        val ready = traceSuspend("Pipeline.tasks") { images.filter { it.path !in waiting }.mapNotNull { task(it, missing(it)) } }

        // An image is done once its results are stored, or once it turns out to need none.
        val total = ready.size + todo.size
        var done = 0
        progress(CLASSIFY_STEP, 0, total)
        val queue = Channel<AnalysisTask>(Channel.UNLIMITED)
        ready.forEach(queue::trySend)
        val permits = Semaphore(parallelism)
        val held = if (todo.isEmpty()) 0 else EMBEDDING_DECODERS.coerceAtMost(parallelism - 1)
        repeat(held) { permits.acquire() }
        val classify = launch {
            runTasks(queue, stats, permits) { count ->
                done += count
                progress(CLASSIFY_STEP, done, total)
            }
        }

        // A decode's retry runs alone, so the detectors wait for it too.
        suspend fun alone(block: () -> Unit) {
            repeat(parallelism - held) { permits.acquire() }
            try {
                block()
            } finally {
                repeat(parallelism - held) { permits.release() }
            }
        }
        phase("embeddings", stats, start) {
            if (embedder != null) {
                embed(embedder, todo, stats, ::alone) { batch, vectors ->
                    embeddings += vectors
                    traceSuspend("Pipeline.tasks") {
                        for (image in batch) {
                            // A new embedding has had the results of the old one forgotten, so every detector runs again.
                            val task = task(image, if (image.path in vectors) selected else missing(image))
                            if (task == null) progress(CLASSIFY_STEP, ++done, total) else queue.send(task)
                        }
                    }
                }
            }
        }
        repeat(held) { permits.release() }
        queue.close()
        phase("embeddedDetectors", stats) { classify.join() }
        stats.counts["$CLASSIFY_STEP tasks"] = tasks
        stats.counts["$CLASSIFY_STEP sticker tasks"] = stickerTasks
        embeddings
    }

    /**
     * Decodes on every core left free and runs the model on a batch at a time, decoding the next batch while
     * the model runs on this one. An image that fails is a failure of that image only: it gets no
     * embedding and the run goes on. A decode's retry runs inside [alone]. Calls [stored] once for every image of
     * [todo], with its batch and the embeddings stored for it, none if the model did not load.
     */
    private suspend fun embed(
        embedder: Embedder,
        todo: List<MediaRef>,
        stats: Stats,
        alone: suspend (() -> Unit) -> Unit,
        stored: suspend (batch: List<MediaRef>, vectors: Map<String, FloatArray>) -> Unit,
    ) {
        stats.counts["embeddings tasks"] = todo.size
        if (todo.isEmpty()) return
        val loaded = withContext(Dispatchers.Default) { attempt { embedder.load() } }
        loaded.exceptionOrNull()?.let { error ->
            // The same model fails the same way for every image: one failure for the step, and no embedding this run.
            stats.failures += Failure("", EMBEDDING_STEP, failureMessage(error))
            stored(todo, emptyMap())
            return
        }
        fun fail(image: MediaRef, error: Throwable) {
            stats.failures += Failure(image.path, EMBEDDING_STEP, failureMessage(error))
        }
        val batches = todo.chunked(EMBEDDING_BATCH)
        coroutineScope {
            fun decode(batch: List<MediaRef>) = batch.map { image -> async(Dispatchers.Default) { image to attempt { preprocessing(File(image.path)) } } }
            var next = decode(batches.first())
            for ((i, batch) in batches.withIndex()) {
                val pixels = mutableListOf<Pair<MediaRef, FloatArray>>()
                val outOfMemory = mutableListOf<MediaRef>()
                for ((image, outcome) in next.awaitAll()) {
                    val error = outcome.exceptionOrNull()
                    when {
                        error == null -> pixels += image to outcome.getOrThrow()
                        error is OutOfMemoryError -> outOfMemory += image
                        else -> fail(image, error)
                    }
                }
                // Other decodes may have taken the memory this one needed, so it gets one more go, alone, before the next batch starts.
                if (outOfMemory.isNotEmpty()) {
                    alone {
                        for (image in outOfMemory) {
                            attempt { preprocessing(File(image.path)) }.fold({ pixels += image to it }, { fail(image, it) })
                        }
                    }
                }
                // One batch ahead at most, so memory holds two batches of pixels.
                batches.getOrNull(i + 1)?.let { next = decode(it) }
                // Not on Dispatchers.Default, where the model would wait behind the decodes queued ahead. Its failures are
                // counted back here, beside the detectors' own.
                val failed = mutableListOf<Pair<MediaRef, Throwable>>()
                val vectors = withContext(Dispatchers.IO) { embedBatch(embedder, pixels) { image, error -> failed += image to error } }
                failed.forEach { (image, error) -> fail(image, error) }
                if (vectors.isNotEmpty()) {
                    // A detector's result belongs to the embedding it read: a new one means analyzing again.
                    index.forgetAnalyses(vectors.keys.toList(), detectors.filter { it.usesEmbeddings }.map { it.id })
                    index.setEmbeddings(embedder.id, embedder.version, vectors)
                    stats.detectorRuns += vectors.size
                }
                stored(batch, vectors)
            }
        }
    }

    /** One call for the batch. If it throws, one call per image finds which images it throws for. */
    private fun embedBatch(embedder: Embedder, pixels: List<Pair<MediaRef, FloatArray>>, fail: (MediaRef, Throwable) -> Unit): Map<String, FloatArray> {
        if (pixels.isEmpty()) return emptyMap()
        attempt { embedder.embedPixels(pixels.map { it.second }) }.onSuccess { return pixels.map { it.first.path }.zip(it).toMap() }
        return pixels.mapNotNull { (image, plane) ->
            val vector = attempt { embedder.embedPixels(listOf(plane)).single() }
            vector.exceptionOrNull()?.let { fail(image, it) }
            vector.getOrNull()?.let { image.path to it }
        }.toMap()
    }

    /** An image's failure, [OutOfMemoryError] included, comes back as a value, so it never stops the run. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    } catch (error: OutOfMemoryError) {
        Result.failure(error)
    }

    /**
     * Runs the [selected] per-image detectors that read no embedding on [parallelism] images at a time, and
     * computes the perceptual hashes. A sticker runs only the ones that [runsOnStickers]. A detector here that asks for an
     * embedding fails: it should have said `usesEmbeddings`.
     */
    private suspend fun analyzeEach(images: List<MediaRef>, selected: List<Detector>, step: String, stats: Stats, progress: Progress) {
        val analyzed = index.analyzedPaths(selected.associate { it.id to it.version })
        val hashed = index.hashedPaths()
        val tasks = traceSuspend("Pipeline.tasks") {
            images.mapNotNull { image ->
                val sticker = isStickerFolder(image.path)
                val todo = selected.filter { image.mediaType in it.mediaTypes && image.path !in analyzed.getValue(it.id) && (!sticker || runsOnStickers(it)) }
                val needsPhash = image.path !in hashed
                // Only an image with work reads its metadata: a query each for the whole library took most of a scan with few changes.
                // No metadata: the image was trashed before its metadata was read, and a restore brings it back to the next run.
                if (todo.isEmpty() && !needsPhash) return@mapNotNull null
                index.metadata(image.path)?.let { AnalysisTask(image, it, null, todo, needsPhash, beforeEmbeddings = true) }
            }
        }
        stats.counts["$step tasks"] = tasks.size
        stats.counts["$step sticker tasks"] = tasks.count { isStickerFolder(it.image.path) }
        progress(step, 0, tasks.size)
        val queue = Channel<AnalysisTask>(Channel.UNLIMITED)
        tasks.forEach(queue::trySend)
        queue.close()
        var done = 0
        runTasks(queue, stats, permits = null) { count ->
            done += count
            progress(step, done, tasks.size)
        }
    }

    /**
     * Runs the tasks [queue] brings on [parallelism] workers, no more at once than [permits] lets, and stores the
     * outcomes here, in order of completion, [storeBatch] in one transaction, calling [stored] with the count of each.
     * What ran out of memory meanwhile may have lost to another worker, so it runs again alone once the queue is closed
     * and empty.
     */
    private suspend fun runTasks(queue: ReceiveChannel<AnalysisTask>, stats: Stats, permits: Semaphore?, stored: suspend (count: Int) -> Unit) = coroutineScope {
        val results = Channel<AnalysisOutcome>(parallelism)
        val workers = List(parallelism) {
            launch(Dispatchers.Default) {
                for (task in queue) results.send(if (permits == null) analyze(task) else permits.withPermit { analyze(task) })
            }
        }
        launch {
            workers.forEach { it.join() }
            results.close()
        }
        val again = mutableListOf<AnalysisTask>()
        val batch = ArrayList<AnalysisOutcome>(storeBatch)
        suspend fun flush() {
            again += store(batch, stats, final = false)
            val count = batch.size
            batch.clear()
            stored(count)
        }
        for (outcome in results) {
            batch += outcome
            if (batch.size == storeBatch) flush()
        }
        if (batch.isNotEmpty()) flush()
        for (task in again) store(listOf(withContext(Dispatchers.Default) { analyze(task) }), stats, final = true)
    }

    private suspend fun analyze(task: AnalysisTask): AnalysisOutcome {
        // The task's decodes, shared by its detectors and the perceptual hash, and dropped with it (docs/design.md §7).
        val pixels = Pixels(File(task.image.path))
        val context = AnalysisContext(
            metadata = { task.metadata },
            embedding = {
                check(!task.beforeEmbeddings) { "${task.image.path}: reads an embedding without usesEmbeddings" }
                task.embedding ?: throw MissingEmbedding(it)
            },
            pixels = { if (it.path == task.image.path) pixels else Pixels(File(it.path)) },
        )
        val kind = if (isStickerFolder(task.image.path)) "sticker" else "image" // the sections tell the two apart, never the path
        // From the largest draft side down, so the sides that give one sample size come one after the other. The hash comes last, at 256.
        val ran = task.detectors.indices.sortedByDescending { task.detectors[it].decodeSide ?: 0 }
            .associateWith { analyze(task.detectors[it], task.image, context, kind) }
        // Stored in the detectors' own order, so the index lists an image's signals and failures as it did before the shared decode.
        val results = task.detectors.mapIndexed { i, detector -> detector to ran.getValue(i) }
        return AnalysisOutcome(task, results, if (task.needsPhash) computePhash(task.image, pixels, kind) else null)
    }

    /**
     * Stores what [outcomes] have, in one transaction. Unless [final], what ran out of memory is not stored but returned
     * as tasks to run again. An image whose row went while the scan ran is gone, and nothing of it is stored.
     */
    private suspend fun store(outcomes: List<AnalysisOutcome>, stats: Stats, final: Boolean): List<AnalysisTask> = traceSuspend("Pipeline.store") {
        // The transaction runs on Room's own thread, so its counts go into [stats] after it, on the caller's thread.
        val tally = Stats()
        index.batch(outcomes.map { it.task.image.path }) { recorded ->
            outcomes.filter { it.task.image.path in recorded }.mapNotNull { storeOutcome(it, tally, final) }
        }.also {
            stats.failures += tally.failures
            stats.skipped += tally.skipped
            stats.detectorRuns += tally.detectorRuns
        }
    }

    private suspend fun storeOutcome(outcome: AnalysisOutcome, stats: Stats, final: Boolean): AnalysisTask? {
        val path = outcome.task.image.path
        fun again(result: Result<*>) = !final && result.exceptionOrNull() is OutOfMemoryError
        for ((detector, result) in outcome.detectorResults) {
            val error = result.exceptionOrNull()
            val signals = result.getOrNull()
            when {
                again(result) -> Unit
                error != null -> stats.failures += Failure(path, detector.id, failureMessage(error))
                signals == null -> stats.skipped++ // the embedding it needs is missing
                else -> {
                    index.setSignals(path, detector.id, detector.version, signals)
                    stats.detectorRuns++
                }
            }
        }
        val phash = outcome.phash
        if (phash != null && !again(phash)) {
            phash.fold(
                onSuccess = {
                    index.setPerceptualHash(path, it)
                    stats.detectorRuns++
                },
                onFailure = { stats.failures += Failure(path, "perceptual hash", failureMessage(it)) },
            )
        }
        val detectorsAgain = outcome.detectorResults.filter { again(it.second) }.map { it.first }
        val phashAgain = phash != null && again(phash)
        return if (detectorsAgain.isEmpty() && !phashAgain) {
            null
        } else {
            AnalysisTask(outcome.task.image, outcome.task.metadata, outcome.task.embedding, detectorsAgain, phashAgain, outcome.task.beforeEmbeddings)
        }
    }

    /**
     * One broken file must not stop a run over thousands: errors come back as values. A detector
     * that needs the missing embedding comes back with null signals and no error.
     */
    private suspend fun analyze(detector: Detector, image: MediaRef, context: AnalysisContext, kind: String): Result<List<Signal>?> {
        val result = attempt<List<Signal>?> { traceSuspend("Detector.${detector.id}.$kind") { detector.analyze(image, context) } }
        return if (result.exceptionOrNull() is MissingEmbedding) Result.success(null) else result
    }

    private fun computePhash(image: MediaRef, pixels: Pixels, kind: String): Result<ULong> = attempt { trace("Pipeline.phash.$kind") { perceptualHasher(image, pixels) } }

    /**
     * The detector last ran on [images] as they are, and every content hash that failed in that run fails again.
     * Hashing only those again is what groups a file that has become readable, without a run on every scan.
     */
    private suspend fun upToDate(detector: GroupDetector, runId: String, fingerprint: String, images: List<MediaRef>): Boolean {
        if (index.groupFingerprint(runId, detector.version) != fingerprint) return false
        val failed = index.groupHashFailed(runId, detector.version).toSet()
        return images.filter { it.path in failed }.none { image ->
            try {
                cachedContentHash(image)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private suspend fun storedSharpness(blur: Detector?, image: MediaRef): Double? = blur?.let { detector ->
        index.signals(image.path).firstOrNull { it.detectorId == detector.id && it.detectorVersion == detector.version }
            ?.evidence?.get(BlurDetector.PEAK_VARIANCE_KEY)?.toDoubleOrNull()
    }

    private suspend fun cachedContentHash(image: MediaRef): String = index.contentHash(image.path) ?: contentHasher(image).also { index.setContentHash(image.path, it) }

    /**
     * Reads [images] on [readers] threads and stores them [storeBatch] at a time, one transaction each, in the order of
     * [images]: the real images still come before the stickers. The reads run at most about three batches ahead of the
     * store (two in [reads], the one being filled), so a first scan does not hold the whole library. A file that is gone, like one trashed since the walk, is not
     * read, and it gets its metadata when it is back.
     */
    private suspend fun <T> readAndStore(
        images: List<MediaRef>,
        step: String,
        progress: Progress,
        read: (File) -> Result<T>,
        store: suspend (List<Pair<String, Result<T>>>) -> Unit,
    ) = coroutineScope {
        val reads = Channel<Deferred<Pair<String, Result<T>?>>>(2 * storeBatch)
        val pool = Dispatchers.IO.limitedParallelism(readers)
        launch {
            for (image in images) reads.send(async(pool) { image.path to File(image.path).takeIf { it.isFile }?.let(read) })
            reads.close()
        }
        var done = 0
        val batch = ArrayList<Pair<String, Result<T>>>(storeBatch)
        suspend fun flush() {
            if (batch.isNotEmpty()) store(batch)
            batch.clear()
            progress(step, done, images.size)
        }
        for (pending in reads) {
            val (path, result) = pending.await()
            result?.let { batch += path to it }
            if (++done % storeBatch == 0) flush()
        }
        if (done % storeBatch != 0) flush()
    }

    /**
     * Only [UnreadableImage] marks a file unreadable. Any other error, [OutOfMemoryError] included, may pass, so it is
     * a failure and the next scan reads the file again.
     */
    private suspend fun storeMetadata(batch: List<Pair<String, Result<ImageMetadata>>>, stats: Stats) = traceSuspend("Pipeline.storeMetadata") {
        for ((path, result) in batch) {
            result.exceptionOrNull()?.takeIf { it !is UnreadableImage }?.let { stats.failures += Failure(path, "metadata", failureMessage(it)) }
        }
        index.batch(batch.map { it.first }) { recorded ->
            for ((path, result) in batch) {
                if (path !in recorded) continue // the row went while the scan ran
                result.fold({ index.setMetadata(path, it) }, { if (it is UnreadableImage) index.setUnreadable(path) })
            }
        }
    }

    /** As [storeMetadata], with [UnreadableVideo]: a video the phone cannot read keeps its row and its size. */
    private suspend fun storeVideoMetadata(batch: List<Pair<String, Result<VideoMetadata>>>, stats: Stats) = traceSuspend("Pipeline.storeVideoMetadata") {
        for ((path, result) in batch) {
            result.exceptionOrNull()?.takeIf { it !is UnreadableVideo }?.let { stats.failures += Failure(path, "metadata", failureMessage(it)) }
        }
        index.batch(batch.map { it.first }) { recorded ->
            for ((path, result) in batch) {
                if (path !in recorded) continue // the row went while the scan ran
                result.fold({ index.setVideoMetadata(path, it) }, { if (it is UnreadableVideo) index.setUnreadable(path) })
            }
        }
    }

    /** A JPEG whose headers fail to read is a failure of the metadata step, so the next scan reads it again. */
    private suspend fun storeJpegs(batch: List<Pair<String, Result<JpegInfo>>>, stats: Stats) = traceSuspend("Pipeline.storeJpegs") {
        for ((path, result) in batch) result.exceptionOrNull()?.let { stats.failures += Failure(path, "metadata", failureMessage(it)) }
        index.batch(batch.map { it.first }) { recorded ->
            for ((path, result) in batch) if (path in recorded) result.onSuccess { index.setJpeg(path, it) }
        }
    }

    /** As the lab's: the same image set and inputs (embedding model, hashes) give the same text. */
    private fun fingerprint(images: List<MediaRef>, inputs: String): String {
        val digest = Algorithm.XXH3_64().createDigest()
        digest.update(inputs.toByteArray())
        for (image in images.sortedBy { it.path }) digest.update("${image.path}\u0000${image.size}\u0000${image.mtimeNs}\n".toByteArray())
        return digest.digest().toHex()
    }
}
