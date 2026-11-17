// The app-managed trash (docs/design.md §9): the only code allowed to delete files.
package io.github.bert0ns.anteater.trash

import android.content.Context
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.toMediaRef
import io.github.bert0ns.anteater.sources.FavoriteRequests
import io.github.bert0ns.anteater.sources.queryFavoritesAmong
import io.github.bert0ns.anteater.tracing.traceSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Folder at the root of each storage volume. Hidden, and with a `.nomedia` so galleries skip it. */
const val TRASH_FOLDER = ".anteater-trash"

/** Where to write a compressed copy of [original]: next to it, so moving it into place is a rename on the same volume. The scan skips `.pending-` files (§4). */
fun copyFileFor(original: File): File = File(original.parentFile, ".pending-anteater-${UUID.randomUUID()}.jpg")

/** The names [copyFileFor] gives. */
private val COPY_NAME = Regex("""^\.pending-anteater-[0-9a-f-]{36}\.jpg$""")

/** What [AppTrash.replaceWithCopy] did. The copy is discarded unless it is [Swapped]. */
sealed interface Swap {
    /** The copy, as it now is at the original's path. The original is in the trash. [favorite]: the gallery had it marked, and the copy is a new MediaStore row without the mark. */
    data class Swapped(val copy: MediaRef, val favorite: Boolean = false) : Swap

    /** Nothing changed: the original is where it was. */
    data object Untouched : Swap

    /** The copy never took the path and the original could not be put back: it waits in the trash. */
    data object LeftInTrash : Swap
}

data class TrashedImage(
    /** Where the image was, with the size and mtime it had: moving keeps both. */
    val original: MediaRef,
    val trashedAt: Instant,
    /** The file inside the trash. */
    val file: File,
    /** When the trash purges it, under the retention the user has chosen now. */
    val purgeAt: Instant,
    val seenAt: Instant = trashedAt,
    /** The compressed copy that took the original's path, as it was when it did. Null for an ordinary item. */
    val compressedCopy: MediaRef? = null,
    /** The gallery had it marked as a favourite when it went to the trash, so a restore asks for the mark again (docs/design.md §9). */
    val favorite: Boolean = false,
)

/**
 * The item's original path is not on the volume whose trash holds it, such as a trash folder copied by hand, so a
 * restore leaves it in the trash rather than copy it there (docs/design.md §9).
 */
val TrashedImage.awayFromItsVolume: Boolean
    get() {
        val volume = file.parentFile?.parentFile?.parentFile ?: return false
        // Canonical, as restore compares it: a path can reach its volume through a symlink.
        return !File(original.path).canonicalPath.startsWith(volume.canonicalPath + File.separator)
    }

interface Trash {
    /**
     * Moves images to the trash. Returns the item each moved image became: a file gone or changed since its scan stays
     * put. An item's [TrashedImage.file] is its exact name, since two items can hold equal originals.
     */
    suspend fun moveToTrash(images: List<MediaRef>): List<TrashedImage>

    /** Moves images back to their original path. Returns the ones restored: an occupied path is never overwritten. */
    suspend fun restore(images: List<MediaRef>): List<MediaRef>

    suspend fun trashed(): List<TrashedImage>

    /**
     * Deletes images from the trash for good, one item for each entry of [images], so two items that are the same image
     * go only when it is listed twice. Returns the ones deleted: a file the system would not delete is still in the trash.
     */
    suspend fun purge(images: List<MediaRef>): List<MediaRef>

    /**
     * Deletes for good the items whose files in the trash are [files], and no other, even one with an equal original.
     * Returns the originals it deleted: an item already gone, or a file that is no trash item, deletes nothing.
     */
    suspend fun purgeItems(files: Collection<File>): List<MediaRef>

    /** Deletes what has been in the trash for longer than the retention the user chose. Returns what it deleted. */
    suspend fun purgeExpired(): ExpiredPurge
}

/** What [Trash.purgeExpired] deleted, and the original paths of the items it read and left in the trash. */
data class ExpiredPurge(val purged: List<MediaRef>, val held: Set<String>) {
    /** [unheldPaths] without listing the trash again: what the purge read, minus what it deleted, is what is left. */
    val unheldPaths: List<String> get() = unheld(purged, held)

    companion object {
        val NONE = ExpiredPurge(emptyList(), emptySet())
    }
}

/**
 * The paths of [purged] that no item left in the trash holds, for the index to forget. A compressed original and
 * its copy share a path, so purging one must not drop the row the other still needs (docs/design.md §9).
 */
suspend fun Trash.unheldPaths(purged: List<MediaRef>): List<String> = unheld(purged, trashed().mapTo(HashSet()) { it.original.path })

private fun unheld(purged: List<MediaRef>, held: Set<String>): List<String> = purged.map { it.path }.filterNot { it in held }

/**
 * Each trashed file gets its own folder in the trash of its volume, holding the file under its
 * own name and an `origin.properties` with where it came from. The trash survives an uninstall
 * and needs no database to be restored. Moving is a rename, so it is instant and keeps the
 * file's mtime: a restored file is the same [MediaRef] as before.
 */
class AppTrash(
    /** The storage volume roots: each file goes to the trash on its own volume. */
    private val volumes: () -> List<File>,
    private val clock: Clock = Clock.systemUTC(),
    /** Read on every call, so a change applies to the images already in the trash. */
    private val retention: () -> Duration = { TrashRetention.DEFAULT },
    /** Which of the paths MediaStore flags as favourites now, read on the IO dispatcher. A throw counts as none. */
    private val favorites: (Collection<String>) -> Set<String> = { emptySet() },
    /** Told the paths of the restored images that were favourites, so the mark can be asked for again. Must return at once: a restore waits for it. */
    private val restoredFavorites: (List<String>) -> Unit = {},
    /** Told about every path that appeared or disappeared, so MediaStore can catch up. */
    private val changed: (List<String>) -> Unit = {},
) : Trash {

    internal var recordWriter: (File, MediaRef, Instant, Instant, MediaRef?, Boolean) -> Unit = ::writeOrigin

    /** Moves a file without replacing one already there. Tests swap it to fail a step. */
    internal var mover: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath()) }

    override suspend fun moveToTrash(images: List<MediaRef>): List<TrashedImage> = io {
        val flagged = flagged(images.map { it.path })
        val moved = images.mapNotNull { image ->
            val favorite = image.path in flagged
            val now = clock.instant()
            move(image, favorite = favorite, now = now)?.let { TrashedImage(image, now, File(it, File(image.path).name), now + retention(), now, favorite = favorite) }
        }
        changed(moved.map { it.original.path })
        moved
    }

    /**
     * Restoring an original whose path holds its compressed copy moves the copy to the trash first, so nothing is
     * deleted. A file at the path that is not that copy is never moved or overwritten, and the original stays.
     */
    override suspend fun restore(images: List<MediaRef>): List<MediaRef> {
        val (restored, favorites) = io { restoreFiles(images) }
        if (favorites.isNotEmpty()) restoredFavorites(favorites)
        return restored
    }

    /** The images put back, and the paths among them that were favourites. */
    private suspend fun restoreFiles(images: List<MediaRef>): Pair<List<MediaRef>, List<String>> {
        val byOriginal = items().associateBy { it.original }
        val restored = images.filter { image ->
            val item = byOriginal[image] ?: return@filter false
            val target = File(image.path)
            // Files.move copies across volumes, and a 900 MB video must come back by rename or not at all.
            if (rootOf(target)?.canonicalFile != item.file.parentFile?.parentFile?.canonicalFile) return@filter false
            val copy = item.compressedCopy
            val copyFolder = if (copy != null && target.exists()) move(copy, favorite = flagged(listOf(copy.path)).isNotEmpty()) ?: return@filter false else null
            target.parentFile?.mkdirs()
            val moved = runCatching { mover(item.file, target) }.isSuccess
            if (moved) {
                deleteItem(item.file.parentFile!!)
            } else if (copyFolder != null && runCatching { mover(File(copyFolder, target.name), target) }.isSuccess) {
                deleteItem(copyFolder)
            }
            moved
        }
        changed(restored.map { it.path })
        return restored to restored.filter { byOriginal.getValue(it).favorite }.map { it.path }
    }

    /**
     * Moves [original] to the trash and renames [copy], its compressed copy written by [copyFileFor], to its path.
     * The item records the copy, so restoring the original puts the copy in the trash in its place.
     */
    suspend fun replaceWithCopy(original: MediaRef, copy: File): Swap = io {
        val target = File(original.path)
        val copyRef = runCatching { copy.toMediaRef() }.getOrNull()?.let { MediaRef(original.path, it.size, it.mtimeNs) }
        val favorite = flagged(listOf(original.path)).isNotEmpty()
        val folder = copyRef?.let { runCatching { move(original, it, favorite) }.getOrNull() }
        if (folder == null || runCatching { mover(copy, target) }.isFailure) {
            discardCopy(copy)
            if (folder == null) return@io Swap.Untouched
            if (runCatching { mover(File(folder, target.name), target) }.isFailure) return@io Swap.LeftInTrash
            deleteItem(folder)
            return@io Swap.Untouched
        }
        changed(listOf(original.path))
        Swap.Swapped(runCatching { target.toMediaRef() }.getOrDefault(copyRef), favorite)
    }

    /**
     * Deletes a compressed copy that never took its original's place, and does nothing when it was never written.
     * Refuses anything but a file named by [copyFileFor] outside the trash.
     */
    internal fun discardCopy(copy: File) {
        check(COPY_NAME.matches(copy.name)) { "not a compressed copy: $copy" }
        if (!copy.exists()) return
        check(copy.isFile) { "not a file: $copy" }
        check(generateSequence(copy.canonicalFile.parentFile) { it.parentFile }.none { it.name == TRASH_FOLDER }) { "inside the trash: $copy" }
        copy.delete()
    }

    /** Discards the copies [copyFileFor] named in [folders], which a compression killed before its swap leaves behind. */
    internal fun discardCopiesIn(folders: Collection<File>) {
        for (folder in folders) {
            folder.listFiles { file -> COPY_NAME.matches(file.name) }.orEmpty().forEach { runCatching { discardCopy(it) } }
        }
    }

    override suspend fun trashed(): List<TrashedImage> = io { items() }

    override suspend fun purge(images: List<MediaRef>): List<MediaRef> = io {
        val wanted = images.groupingBy { it }.eachCount().toMutableMap()
        items().filter { item ->
            val left = wanted[item.original] ?: 0
            left > 0 && deleteItem(item.file.parentFile!!).also { if (it) wanted[item.original] = left - 1 }
        }.map { it.original }
    }

    override suspend fun purgeItems(files: Collection<File>): List<MediaRef> = io {
        // Compared canonically, as restore does: only an item folder directly inside a volume's own trash goes. A bad entry is skipped.
        val roots = volumes().map { File(it, TRASH_FOLDER).canonicalFile }
        files.mapNotNull { file ->
            runCatching {
                val folder = file.parentFile?.takeIf { it.canonicalFile.parentFile in roots } ?: return@runCatching null
                val item = readItem(folder)?.takeIf { it.file == file } ?: return@runCatching null
                item.original.takeIf { deleteItem(folder) }
            }.getOrNull()
        }
    }

    override suspend fun purgeExpired(): ExpiredPurge = io {
        val now = clock.instant()
        val expired = mutableListOf<MediaRef>()
        val held = HashSet<String>()
        for (volume in volumes()) {
            val trashFolder = File(volume, TRASH_FOLDER)
            for (folder in trashFolder.listFiles().orEmpty()) {
                val item = readItem(folder, syncSeenAt = true) ?: continue
                if (item.purgeAt <= now && deleteItem(folder)) expired += item.original else held += item.original.path
            }
        }
        ExpiredPurge(expired, held)
    }

    /** Returns the item folder the file went to, or null if it stayed where it was. */
    private fun move(image: MediaRef, compressedCopy: MediaRef? = null, favorite: Boolean = false, now: Instant = clock.instant()): File? {
        val file = File(image.path)
        if (file.name == ORIGIN) return null
        if (!file.isFile || runCatching { file.toMediaRef(image.mediaType) }.getOrNull() != image) return null
        val root = rootOf(file) ?: return null
        val folder = File(root, UUID.randomUUID().toString())
        try {
            if (!folder.mkdirs()) return null
            File(root, ".nomedia").createNewFile()
            recordWriter(folder, image, now, now, compressedCopy, favorite)
            if (file.renameTo(File(folder, file.name))) return folder
        } catch (_: IOException) {
        }
        deleteItem(folder)
        return null
    }

    /**
     * Reads the item folders [READS] at a time: each read waits on the file system, shared storage most of all.
     * The reads write nothing, and the items come in listing order before the sort, as one after another did.
     */
    private suspend fun items(): List<TrashedImage> = traceSuspend("Trash.items") {
        val folders = volumes().flatMap { volume -> File(volume, TRASH_FOLDER).listFiles().orEmpty().asList() }
        coroutineScope {
            folders.map { async(reads) { readItem(it) } }.awaitAll()
        }.filterNotNull().sortedByDescending { it.trashedAt }
    }

    private fun rootOf(file: File): File? {
        val path = file.canonicalPath
        val volume = volumes().firstOrNull { path.startsWith(it.canonicalPath + File.separator) } ?: return null
        return File(volume, TRASH_FOLDER)
    }

    private fun flagged(paths: Collection<String>): Set<String> = runCatching { favorites(paths) }.getOrDefault(emptySet())

    private fun writeOrigin(folder: File, image: MediaRef, trashedAt: Instant, seenAt: Instant, compressedCopy: MediaRef?, favorite: Boolean) {
        val origin = Properties()
        origin["path"] = image.path
        origin["size"] = image.size.toString()
        origin["mtime_ns"] = image.mtimeNs.toString()
        origin["media_type"] = image.mediaType.value
        origin["trashed_at"] = trashedAt.toString()
        origin["seen_at"] = seenAt.toString()
        if (compressedCopy != null) {
            origin["compressed_size"] = compressedCopy.size.toString()
            origin["compressed_mtime_ns"] = compressedCopy.mtimeNs.toString()
        }
        if (favorite) origin["favorite"] = "true"
        File(folder, ORIGIN).outputStream().use { origin.store(it, "Anteater trash: where this file came from") }
    }

    /** Null for anything the trash did not write, or that lost its file. */
    internal fun readItem(folder: File, syncSeenAt: Boolean = false): TrashedImage? = runCatching {
        val originFile = File(folder, ORIGIN)
        // No isFile first: opening a missing file or a folder throws, and the throw means null.
        val origin = Properties().apply { originFile.inputStream().use(::load) }
        val path = origin.getProperty("path") ?: return null
        val fileName = File(path).name
        if (fileName == ORIGIN) return null
        val file = File(folder, fileName).takeIf { it.isFile } ?: return null
        val now = clock.instant()
        val rawTrashedAt = origin.getProperty("trashed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val trashedAt = if (rawTrashedAt > now) now else rawTrashedAt
        val rawSeenAt = origin.parsedSeenAt()
        val seenAt = if (syncSeenAt && (rawSeenAt == null || rawSeenAt > now)) {
            origin["seen_at"] = now.toString()
            runCatching {
                originFile.outputStream().use { origin.store(it, "Anteater trash: where this file came from") }
            }
            now
        } else {
            origin.seenAt(now)
        }
        val size = origin.getProperty("size")?.toLongOrNull() ?: return null
        val mtimeNs = origin.getProperty("mtime_ns")?.toLongOrNull() ?: return null
        val purgeAt = maxOf(trashedAt, seenAt) + retention()
        // An item written before the trash recorded media types holds an image. An unknown type, from a later build, reads as one
        // too: skipping the item would hide its file from the screen and from the retention for good.
        val mediaType = MediaType.entries.firstOrNull { it.value == origin.getProperty("media_type") } ?: MediaType.IMAGE
        val compressedSize = origin.getProperty("compressed_size")?.toLongOrNull()
        val compressedMtimeNs = origin.getProperty("compressed_mtime_ns")?.toLongOrNull()
        TrashedImage(
            MediaRef(path, size, mtimeNs, mediaType),
            trashedAt,
            file,
            purgeAt,
            seenAt,
            if (compressedSize != null && compressedMtimeNs != null) MediaRef(path, compressedSize, compressedMtimeNs) else null,
            origin.getProperty("favorite") == "true",
        )
    }.getOrNull()

    /** Deletes one item folder of the trash, never anything outside it. Returns false if it could not delete all of it. */
    internal fun deleteItem(folder: File): Boolean {
        val trash = folder.canonicalFile.parentFile
        check(trash?.name == TRASH_FOLDER) { "not a trash item: $folder" }
        return folder.deleteRecursively()
    }

    companion object {
        private const val ORIGIN = "origin.properties"

        /** Item reads at once: enough for the file system to serve several, few enough to leave IO threads to the tiles. */
        private const val READS = 6
        private val reads = Dispatchers.IO.limitedParallelism(READS)

        fun forDevice(
            context: Context,
            clock: Clock = Clock.systemUTC(),
            retention: () -> Duration = { TrashRetention.DEFAULT },
        ): AppTrash {
            val storage = context.getSystemService(StorageManager::class.java)
            return AppTrash(
                volumes = { storage.storageVolumes.mapNotNull { it.directory } },
                clock = clock,
                retention = retention,
                favorites = { queryFavoritesAmong(context.contentResolver, it) },
                restoredFavorites = { paths -> FavoriteRequests.askAsync(context, paths) },
                changed = { paths ->
                    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
                },
            )
        }
    }
}

private fun Properties.parsedSeenAt(): Instant? = getProperty("seen_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun Properties.seenAt(now: Instant): Instant = parsedSeenAt()?.takeIf { it <= now } ?: now

private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
