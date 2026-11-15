// Media sources: where images and videos come from. Mirrors lab/src/cribrum_lab/sources.py and docs/design.md §4.
package io.github.bert0ns.cribrum.sources

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.tracing.trace
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.toMediaRef
import io.github.bert0ns.cribrum.trash.TRASH_FOLDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

interface MediaSource {
    fun scan(): Flow<MediaRef>

    /**
     * [scan], free to leave out a path in [known] before any work on it. [MergedMediaSource] passes the paths the
     * earlier sources yielded, whose refs it keeps over any later one.
     */
    fun scan(known: Set<String>): Flow<MediaRef> = scan()

    /**
     * The roots the last [scan] listed in full. An image under one of them that [scan] did not
     * yield is gone; under any other root the scan could not look, so it says nothing.
     */
    val walkedRoots: List<File>

    /**
     * What the last [scan] counted, for the scan-time log: folders listed, entries listed, files whose head was sniffed,
     * the ftyp and EBML files turned away by an unknown brand, in all and by brand, MediaStore rows, and files a later
     * source found that an earlier one already had. A source counts what it does.
     */
    val counts: Map<String, Int> get() = emptyMap()
}

/**
 * What the walk remembers of the files it sniffed and found to be neither images nor videos, so it does not read
 * their head again while their size and mtime stay the same.
 */
interface NotImages {
    suspend fun notImages(): Collection<MediaRef>

    /** Stores the files; one already stored with the same path is replaced. */
    suspend fun addNotImages(files: List<MediaRef>)

    suspend fun forgetNotImages(paths: Collection<String>)
}

/** Files the walk hands to [NotImages.addNotImages] at once. */
private const val NOT_IMAGES_BATCH = 300

/**
 * Walks a folder tree and yields every image and video file: the deep scan.
 *
 * Hidden folders and folders with a `.nomedia` file are included: that is where messaging apps
 * keep media the gallery never shows. Files are recognized by their content, not their
 * extension, and yielded with the media type their head gave, except a file that [indexed] has with the same path, size and mtime: the index
 * took it, from this walk or from MediaStore, so its head is not read again and it keeps the media type the index has. The
 * same goes for a file [notImages] has: an earlier walk found it to be neither an image nor a video. The walk
 * adds the non-images it sniffs, and drops the ones it no longer finds when it listed the whole root.
 * Symlinks are skipped so no file is yielded twice, and so are FIFOs and other special files,
 * which would block or fail on read. Left out on purpose: the app trash, files in the system
 * trash or still being written (`.trashed-*`, `.pending-*`), `Android/.Trash`, which is
 * Samsung Gallery's recycle bin, and `Android/data` and `Android/obb`, which Android 11 and
 * newer keep from every app.
 */
class FilesystemMediaSource(
    private val root: File,
    /** Read once per scan, before the walk. */
    private val notImages: NotImages? = null,
    /** Read once per scan, before the walk. Last, so a test can pass it as a trailing lambda. */
    private val indexed: suspend () -> Collection<MediaRef> = { emptyList() },
) : MediaSource {
    /** The root once the walk listed every folder; a folder that cannot be listed, or a missing root, leaves it out. */
    @Volatile // written on the IO thread of the flow, read by the collector
    override var walkedRoots: List<File> = emptyList()
        private set

    @Volatile
    override var counts: Map<String, Int> = emptyMap()
        private set

    override fun scan(): Flow<MediaRef> = flow {
        walkedRoots = emptyList()
        counts = emptyMap()
        var folderCount = 0
        var entryCount = 0
        var sniffCount = 0
        // The ftyp and EBML files turned away by a brand the list does not know: a video format it misses shows in the scan log.
        val unknownBrands = sortedMapOf<String, Int>()
        var listedAll = true
        // By path, so a file the index holds is yielded with the media type it was recorded with.
        val known = indexed().associateBy { it.path }
        val remembered = notImages?.notImages().orEmpty()
        val rememberedSet = remembered.toHashSet()
        val metNotImages = HashSet<String>() // paths of non-images this walk met, remembered or newly sniffed
        var sniffed = ArrayList<MediaRef>(NOT_IMAGES_BATCH)
        val folders = ArrayDeque(listOf(root))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val listing = trace("Walk.list") { folder.listFiles() }
            if (listing == null) listedAll = false
            folderCount++
            entryCount += listing?.size ?: 0
            // One stat per entry: it tells a folder from a file and gives the size and mtime. One section for the folder's entries.
            val entries = trace("Walk.stat") {
                listing.orEmpty().sortedBy { it.name }.mapNotNull { entry ->
                    try {
                        entry to Files.readAttributes(entry.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: IOException) {
                        null // unreadable or vanished during the scan
                    }
                }
            }
            val (dirs, files) = entries.partition { (_, attributes) -> attributes.isDirectory }
            for ((file, attributes) in files) {
                if (hasSkippedPrefix(file)) continue // the folders were decided when they were listed
                if (!attributes.isRegularFile) continue
                var image = file.toMediaRef(attributes)
                val indexedRef = known[image.path]
                if (indexedRef != null && indexedRef.size == image.size && indexedRef.mtimeNs == image.mtimeNs) {
                    image = indexedRef
                } else {
                    val again = image in rememberedSet
                    if (!again) sniffCount++
                    val type = if (again) {
                        null
                    } else {
                        val head = try {
                            head(file)
                        } catch (_: IOException) {
                            continue // the head cannot be read
                        }
                        mediaType(head).also { if (it == null) unknownBrand(head)?.let { brand -> unknownBrands.merge(brand, 1, Int::plus) } }
                    }
                    if (type == null) {
                        metNotImages += image.path
                        if (!again && notImages != null) {
                            sniffed.add(image)
                            if (sniffed.size == NOT_IMAGES_BATCH) {
                                notImages.addNotImages(sniffed)
                                sniffed = ArrayList(NOT_IMAGES_BATCH) // the store may still hold the list it was given
                            }
                        }
                        continue
                    }
                    image = image.copy(mediaType = type)
                }
                emit(image)
            }
            // Depth first, in name order, like the lab's Path.walk.
            folders.addAll(0, dirs.map { it.first }.filter { !isSkippedFolder(root, it) })
        }
        if (notImages != null) {
            if (sniffed.isNotEmpty()) notImages.addNotImages(sniffed)
            // Only a root listed in full can tell a file that is gone from one the walk could not see.
            if (listedAll) notImages.forgetNotImages(remembered.map { it.path }.filter { it !in metNotImages && File(it).startsWith(root) })
        }
        if (listedAll) walkedRoots = listOf(root)
        counts = mapOf("folders" to folderCount, "entries" to entryCount, "sniffed" to sniffCount, "unknownContainers" to unknownBrands.values.sum()) +
            unknownBrands.mapKeys { (brand, _) -> "unknownBrand '$brand'" }
    }.flowOn(Dispatchers.IO)
}

private val SKIPPED_PREFIXES = listOf(".trashed-", ".pending-")
private val SKIPPED_FOLDERS = setOf("Android/data", "Android/obb", "Android/.Trash")

private fun isSkippedFolder(root: File, folder: File) = folder.name == TRASH_FOLDER || folder.relativeTo(root).invariantSeparatorsPath in SKIPPED_FOLDERS

private fun hasSkippedPrefix(file: File) = SKIPPED_PREFIXES.any(file.name::startsWith)

/** Whether the deep scan leaves [file] out: a file the system is still writing or has trashed, or one below a skipped folder of its volume [root]. */
internal fun isSkipped(root: File, file: File): Boolean {
    if (hasSkippedPrefix(file)) return true
    return generateSequence(file.parentFile) { it.parentFile }.takeWhile { it != root }.any { isSkippedFolder(root, it) }
}

/**
 * The images and videos MediaStore indexes, on every volume, the SD card included. It misses `.nomedia`
 * folders, which the deep scan covers, and leaves out what the deep scan leaves out. A video is one only in a
 * container the deep scan takes, by the MIME type MediaStore gave it. It walks no folder, so it reports no
 * walked root. [volumes] tell which folder is the root of a path.
 */
class MediaStoreSource(private val resolver: ContentResolver, private val volumes: List<File>) : MediaSource {
    override val walkedRoots: List<File> = emptyList()

    @Volatile
    override var counts: Map<String, Int> = emptyMap()
        private set

    override fun scan(): Flow<MediaRef> = scan(emptySet())

    /** A path in [known] costs no stat: the walk already found it, and that is most of what MediaStore lists. */
    override fun scan(known: Set<String>): Flow<MediaRef> = flow {
        counts = emptyMap()
        var rows = 0
        var duplicates = 0
        // The query and the stats are one section; the emits wait for the collector, so they stay out of it. Without an emit
        // per row, each row checks for a stopped scan itself, so a stop does not wait for the stats of every row.
        val media = trace("Walk.mediaStore") {
            val paths = mutableListOf<Pair<String, MediaType>>()

            @Suppress("DEPRECATION") // DATA is the only way to the path, and All files access allows reading it
            suspend fun query(uri: Uri, type: MediaType) = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val path = cursor.getString(0) ?: continue
                    rows++
                    if (type == MediaType.IMAGE || cursor.getString(1) in VIDEO_MIME_TYPES) paths += path to type
                }
            }
            query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaType.IMAGE)
            query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaType.VIDEO)
            paths.sortedBy { it.first }.mapNotNull { (path, type) ->
                currentCoroutineContext().ensureActive()
                if (path in known) {
                    duplicates++
                    return@mapNotNull null
                }
                val file = File(path)
                if (isSkipped(volumes.firstOrNull(file::startsWith) ?: File("/"), file)) return@mapNotNull null
                try {
                    if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file.toMediaRef(type) else null
                } catch (_: IOException) {
                    null
                }
            }
        }
        counts = mapOf("mediaStoreRows" to rows, "duplicates" to duplicates)
        media.forEach { emit(it) }
    }.flowOn(Dispatchers.IO)
}

/**
 * Every image any source finds, once. Each source builds its refs with [toMediaRef], so the same
 * file has the same path, size and mtime whichever source finds it.
 */
class MergedMediaSource(private val sources: List<MediaSource>) : MediaSource {
    override val walkedRoots: List<File> get() = sources.flatMap { it.walkedRoots }

    @Volatile
    private var duplicates = 0

    /** Its own duplicates are added to those a source left out itself. */
    override val counts: Map<String, Int>
        get() = (sources.flatMap { it.counts.toList() } + ("duplicates" to duplicates)).groupingBy { it.first }.fold(0) { sum, entry -> sum + entry.second }

    override fun scan(): Flow<MediaRef> = flow {
        duplicates = 0
        val seen = HashSet<String>()
        for (source in sources) {
            // A copy: the source may still read it while this adds the paths it yields.
            source.scan(seen.toSet()).collect { if (seen.add(it.path)) emit(it) else duplicates++ }
        }
    }
}

/** The MIME types MediaStore gives the videos the deep scan takes by their head: MP4, MOV, 3GP, M4V, Matroska and WebM. */
private val VIDEO_MIME_TYPES = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2", "video/x-m4v", "video/x-matroska", "video/webm")

/** ISO base media brands of HEIF and AVIF images, which win wherever the ftyp box names them. */
private val HEIF_BRANDS = setOf(
    "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "mif2", "msf1", "miaf", "avif", "avis",
)

/**
 * Major brands of MP4, MOV, 3GP and M4V. Audio-only files (M4A, M4B) and raw photos such as Canon's CR3 name isom among
 * their compatible brands too, so only the major brand counts.
 */
private val VIDEO_BRANDS = setOf(
    "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "mmp4",
    "XAVC", "qt  ", "3gp4", "3gp5", "3gp6", "3gp7", "3g2a", "M4V ", "M4VH", "M4VP",
)

/** Enough for the magic bytes of every format and the brand list of a typical ftyp box. */
private const val HEAD_SIZE = 64

/** The first [HEAD_SIZE] bytes of [file], fewer for a shorter file. */
private fun head(file: File): ByteArray = file.inputStream().use { input ->
    val buffer = ByteArray(HEAD_SIZE)
    var read = 0
    while (read < HEAD_SIZE) read += input.read(buffer, read, HEAD_SIZE - read).takeIf { it > 0 } ?: break
    buffer.copyOf(read)
}

/** Major brands of what is known not to be a video: audio, and Canon's raw photos. The walk turns them away without counting them as an unknown brand. */
private val NOT_VIDEO_BRANDS = setOf("M4A ", "M4B ", "M4P ", "crx ")

/**
 * For a head [mediaType] turned away, the brand of its ftyp or EBML container when it is not one known to be something
 * other than a video: an ftyp major brand, or `ebml/` and the DocType. Null for a file that is no such container.
 */
internal fun unknownBrand(head: ByteArray): String? {
    val text = String(head, Charsets.ISO_8859_1)
    fun printable(value: String) = value.map { if (it.isISOControl() || it.code > 0x7e) '?' else it }.joinToString("")
    if (text.startsWith("ftyp", 4) && head.size >= 12) return text.substring(8, 12).takeIf { it !in NOT_VIDEO_BRANDS }?.let(::printable)
    if (!text.startsWith("\u001a\u0045\u00df\u00a3")) return null
    // The DocType element: its ID 0x4282, a one-byte size, then the text.
    val at = text.indexOf("\u0042\u0082")
    val size = head.getOrNull(at + 2)?.toInt()?.and(0xff) ?: 0
    val docType = if (at < 0 || size and 0x80 == 0) "?" else text.substring(at + 3, minOf(head.size, at + 3 + (size and 0x7f)))
    return "ebml/" + printable(docType)
}

/**
 * What the head of a file says it is, or null for neither an image nor a video.
 *
 * The walk remembers the files this refused, by size and mtime (docs/design.md §4). A change that lets
 * the head accept more, such as a new format or HEIF brand, must empty `not_images` in the same change:
 * a Room migration that runs `DELETE FROM not_images`. Otherwise those files are never sniffed again.
 */
internal fun mediaType(head: ByteArray): MediaType? {
    fun at(offset: Int, vararg bytes: Int) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    fun text(offset: Int, value: String) = at(offset, *value.map { it.code }.toIntArray())
    val ftyp = text(4, "ftyp")
    val image = at(0, 0xff, 0xd8, 0xff) ||
        // JPEG
        at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
        // PNG
        text(0, "GIF87a") ||
        text(0, "GIF89a") ||
        (text(0, "RIFF") && text(8, "WEBP")) ||
        (ftyp && ftypBrands(head).any { it in HEIF_BRANDS })
    // Matroska and WebM: the EBML header, with the DocType a few bytes after it.
    val ebml = at(0, 0x1a, 0x45, 0xdf, 0xa3) && String(head, Charsets.ISO_8859_1).let { "matroska" in it || "webm" in it }
    return when {
        image -> MediaType.IMAGE
        ebml || ftyp && ftypBrands(head).firstOrNull() in VIDEO_BRANDS -> MediaType.VIDEO
        else -> null
    }
}

/** Major brand plus compatible brands: HEIF files may declare their brand in either. */
private fun ftypBrands(head: ByteArray): List<String> {
    if (head.size < 12) return emptyList()
    val boxSize = head.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    val boxEnd = minOf(boxSize, head.size.toLong()).toInt()
    fun brand(offset: Int) = String(head, offset, 4, Charsets.ISO_8859_1)
    return listOf(brand(8)) + (16..boxEnd - 4 step 4).map(::brand)
}
