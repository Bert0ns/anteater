package io.github.bert0ns.anteater.ui

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import io.github.bert0ns.anteater.AnteaterApp
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.review.Review
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many files of one kind the index holds, and how many bytes they take. */
data class MediaTotal(val count: Int, val bytes: Long)

/** Bytes Anteater takes on the phone (docs/design.md §8). [app] and [cache] are null when Android does not say. */
data class StorageUse(
    val app: Long?,
    val cache: Long?,
    /** The index file without the [embeddings]' data. */
    val index: Long,
    /** An estimate of what the stored vectors take in the index file. */
    val embeddings: Long,
    /** The files in the `.cribrum-trash` folders. */
    val trash: Long,
    /** Every indexed image and video not in the trash. Unlike the storage map, these count the files in excluded folders, which stay in the index (docs/design.md §6.3). */
    val images: MediaTotal,
    val videos: MediaTotal,
)

suspend fun measureStorage(app: AnteaterApp, review: Review): StorageUse {
    val stats = withContext(Dispatchers.IO) { appStats(app) }
    val embeddings = app.index.embeddingBytes()
    val (videos, images) = app.index.notTrashedMedia().partition { it.mediaType == MediaType.VIDEO }
    return StorageUse(
        app = stats?.appBytes,
        cache = stats?.cacheBytes,
        index = (app.index.fileBytes() - embeddings).coerceAtLeast(0),
        embeddings = embeddings,
        trash = withContext(Dispatchers.IO) { review.trashed().sumOf { it.file.length() } },
        images = MediaTotal(images.size, images.sumOf { it.size }),
        videos = MediaTotal(videos.size, videos.sumOf { it.size }),
    )
}

/** An app may query its own package without the usage-access permission. Any failure hides the lines. */
private fun appStats(context: Context) = runCatching {
    context.getSystemService(StorageStatsManager::class.java)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
}.getOrNull()
