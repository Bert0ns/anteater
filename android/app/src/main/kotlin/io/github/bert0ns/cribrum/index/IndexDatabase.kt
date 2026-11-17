// Room tables of the image index. The schema is the lab's (lab/src/cribrum_lab/index.py), table
// by table and column by column, so both indexes mean the same thing; IndexSchemaTest checks it.
package io.github.bert0ns.cribrum.index

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "images")
data class ImageRow(
    @PrimaryKey val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
    @ColumnInfo(name = "review_state", defaultValue = "'unreviewed'") val reviewState: String = "unreviewed",
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null,
    @ColumnInfo(name = "camera_make") val cameraMake: String? = null,
    @ColumnInfo(name = "camera_model") val cameraModel: String? = null,
    @ColumnInfo(name = "captured_at") val capturedAt: String? = null,
    @ColumnInfo(defaultValue = "0") val unreadable: Boolean = false,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: String? = null,
    /** `image` or `video`, from the source that found the file. */
    @ColumnInfo(name = "media_type", defaultValue = "'image'") val mediaType: String = "image",
    // A video's own metadata (docs/design.md §7). Its capture time goes in captured_at.
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "frame_rate") val frameRate: Double? = null,
    val bitrate: Long? = null,
    @ColumnInfo(name = "has_audio") val hasAudio: Boolean? = null,
    @ColumnInfo(name = "video_codec") val videoCodec: String? = null,
    val hdr: Boolean? = null,
    /** The moment [capturedAt] holds, as epoch milliseconds in UTC: a video's MP4 creation time. */
    @ColumnInfo(name = "captured_instant") val capturedInstant: Long? = null,
    /** The frame version at which a video's frames did not read: not tried again until the file or the version changes (docs/design.md §4). */
    @ColumnInfo(name = "frames_unreadable") val framesUnreadable: Int? = null,
)

/** Which detector version last analyzed an image, even if it emitted no signal. */
@Entity(
    tableName = "analyses",
    primaryKeys = ["path", "detector_id"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class AnalysisRow(
    val path: String,
    @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
)

/** [id] is the only column the lab doesn't name: Room needs a primary key, and it is the rowid. */
@Entity(
    tableName = "signals",
    foreignKeys = [
        ForeignKey(
            AnalysisRow::class,
            ["path", "detector_id"],
            ["path", "detector_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("path", "detector_id", name = "signals_by_analysis")],
)
data class SignalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
    val category: String,
    val score: Double,
    /** JSON object of strings. */
    val evidence: String,
)

/** Signal identities (Signal.identity) an image had when the user kept it. */
@Entity(
    tableName = "kept_signals",
    primaryKeys = ["path", "category", "group_id", "role"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class KeptSignalRow(
    val path: String,
    val category: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    val role: String,
)

/** Ids of the detectors that had stored a result when the user kept the image. */
@Entity(
    tableName = "kept_detectors",
    primaryKeys = ["path", "detector_id"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class KeptDetectorRow(
    val path: String,
    @ColumnInfo(name = "detector_id") val detectorId: String,
)

@Entity(
    tableName = "embeddings",
    primaryKeys = ["path", "model"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
class EmbeddingRow(
    val path: String,
    val model: String,
    @ColumnInfo(name = "model_version") val modelVersion: Int,
    /** float32, little-endian. */
    val vector: ByteArray,
)

/** A video's frames, embedded at the times frameTimes gives (docs/design.md §7). */
@Entity(
    tableName = "frame_embeddings",
    primaryKeys = ["path", "model", "time_ms"],
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
class FrameEmbeddingRow(
    val path: String,
    val model: String,
    @ColumnInfo(name = "model_version") val modelVersion: Int,
    /** FRAMES_VERSION when the frames were taken: frames of another version are taken again. */
    @ColumnInfo(name = "frames_version") val framesVersion: Int,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    /** float32, little-endian. */
    val vector: ByteArray,
)

/** The image set a group detector version last ran on, so unchanged sets are skipped, with the paths whose content hash failed in that run. */
@Entity(tableName = "group_runs")
data class GroupRunRow(
    @PrimaryKey @ColumnInfo(name = "detector_id") val detectorId: String,
    @ColumnInfo(name = "detector_version") val detectorVersion: Int,
    val fingerprint: String,
)

/** Folders the user excluded (docs/design.md §6.3). Their images are scanned but never candidates. */
@Entity(tableName = "excluded_folders")
data class ExcludedFolderRow(@PrimaryKey val path: String)

/** A file the walk sniffed and found to be neither an image nor a video, with the size and mtime it had (docs/design.md §4). */
@Entity(tableName = "not_images")
data class NotImageRow(
    @PrimaryKey val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
)

/**
 * What the scan read from a JPEG's headers for the compress page (docs/design.md §9), at the rules' [version].
 * A row with [pageWidth] was written by the app itself, for a file it had just put at the path, before any scan read
 * that file: the scan still reads its metadata and headers, and its own row replaces this one.
 * Android only: compression has no lab counterpart, so the lab's schema has no such table.
 */
@Entity(
    tableName = "jpegs",
    foreignKeys = [ForeignKey(ImageRow::class, ["path"], ["path"], onDelete = ForeignKey.CASCADE)],
)
data class JpegRow(
    @PrimaryKey val path: String,
    val version: Int,
    val status: String,
    @ColumnInfo(name = "gain_map") val gainMap: Boolean,
    val quality: Int?,
    /** The file has the Cribrum marker; the three columns after it hold its keys, null when the marker lacks one. */
    val compressed: Boolean,
    @ColumnInfo(name = "marker_version") val markerVersion: Int?,
    @ColumnInfo(name = "marker_level") val markerLevel: String?,
    @ColumnInfo(name = "marker_compressed_at") val markerCompressedAt: String?,
    @ColumnInfo(name = "re_edit") val reEdit: Boolean,
    @ColumnInfo(name = "depth_map") val depthMap: Boolean,
    val xmp: Boolean,
    val c2pa: Boolean,
    /** The resolution the compress page shows until the scan reads the file's metadata. Null on a row the scan wrote. */
    @ColumnInfo(name = "page_width") val pageWidth: Int? = null,
    @ColumnInfo(name = "page_height") val pageHeight: Int? = null,
)

data class StoredJpeg(
    val size: Long,
    val width: Int,
    val height: Int,
    @Embedded val jpeg: JpegRow,
)

data class StoredJpegCount(val status: String, @ColumnInfo(name = "gain_map") val gainMap: Boolean, val count: Int)

data class StoredSignalId(val path: String, val id: Long)

data class StoredRef(val path: String, val size: Long, @ColumnInfo(name = "mtime_ns") val mtimeNs: Long, @ColumnInfo(name = "media_type") val mediaType: String)

data class StoredFrameTime(val path: String, @ColumnInfo(name = "time_ms") val timeMs: Long)

data class StoredVideo(
    val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val unreadable: Boolean,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
)

data class StoredMetadata(
    val width: Int,
    val height: Int,
    val mime: String?,
    @ColumnInfo(name = "camera_make") val cameraMake: String?,
    @ColumnInfo(name = "camera_model") val cameraModel: String?,
    @ColumnInfo(name = "captured_at") val capturedAt: String?,
)

data class StoredVideoMetadata(
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "frame_rate") val frameRate: Double?,
    val bitrate: Long?,
    @ColumnInfo(name = "has_audio") val hasAudio: Boolean?,
    @ColumnInfo(name = "video_codec") val videoCodec: String?,
    val hdr: Boolean?,
    @ColumnInfo(name = "captured_at") val capturedAt: String?,
    @ColumnInfo(name = "captured_instant") val capturedInstant: Long?,
)

data class StoredPathMetadata(val path: String, @Embedded val metadata: StoredMetadata)

data class StoredPathVideoMetadata(val path: String, @Embedded val metadata: StoredVideoMetadata)

data class StoredPerceptualHash(val path: String, @ColumnInfo(name = "perceptual_hash") val perceptualHash: String)

data class StoredCapture(val path: String, @ColumnInfo(name = "captured_at") val capturedAt: String)

data class StoredState(
    val path: String,
    val size: Long,
    @ColumnInfo(name = "mtime_ns") val mtimeNs: Long,
    @ColumnInfo(name = "review_state") val reviewState: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val unreadable: Boolean,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
)

@Dao
interface IndexDao {
    @Query("SELECT path, size, mtime_ns, media_type FROM images WHERE path IN (:paths)")
    suspend fun refs(paths: List<String>): List<StoredRef>

    @Insert
    suspend fun insertImage(image: ImageRow)

    @Query(
        """UPDATE images SET size = :size, mtime_ns = :mtimeNs, width = NULL, height = NULL,
           mime = NULL, camera_make = NULL, camera_model = NULL, captured_at = NULL,
           unreadable = 0, content_hash = NULL, perceptual_hash = NULL, media_type = :mediaType,
           duration_ms = NULL, frame_rate = NULL, bitrate = NULL, has_audio = NULL, video_codec = NULL, hdr = NULL,
           captured_instant = NULL, frames_unreadable = NULL, review_state = CASE WHEN review_state = 'trashed' THEN 'unreviewed' ELSE review_state END WHERE path = :path""",
    )
    suspend fun resetImage(path: String, size: Long, mtimeNs: Long, mediaType: String)

    @Query("SELECT path, size, mtime_ns, media_type FROM images ORDER BY path")
    suspend fun images(): List<StoredRef>

    @Query("SELECT path, size, mtime_ns, media_type FROM images WHERE review_state != 'trashed' AND media_type = 'image' ORDER BY path")
    suspend fun notTrashed(): List<StoredRef>

    @Query("SELECT path, size, mtime_ns, media_type FROM images WHERE review_state != 'trashed' ORDER BY path")
    suspend fun notTrashedMedia(): List<StoredRef>

    @Query("SELECT path, size, mtime_ns, media_type, unreadable, duration_ms FROM images WHERE media_type = 'video' AND review_state = 'unreviewed' ORDER BY size DESC, path")
    suspend fun unreviewedVideos(): List<StoredVideo>

    @Query("SELECT EXISTS(SELECT 1 FROM images WHERE media_type = 'video')")
    suspend fun hasVideos(): Boolean

    @Query("SELECT count(*) FROM images WHERE media_type = 'video' AND width IS NULL AND NOT unreadable AND review_state != 'trashed'")
    suspend fun videosWaiting(): Int

    @Query("DELETE FROM images WHERE path IN (:paths)")
    suspend fun forget(paths: List<String>): Int

    @Query("DELETE FROM images WHERE path IN (:paths) AND review_state = 'trashed'")
    suspend fun forgetTrashed(paths: List<String>): Int

    @Query("SELECT path, size, mtime_ns, media_type FROM images WHERE width IS NULL AND NOT unreadable ORDER BY path")
    suspend fun withoutMetadata(): List<StoredRef>

    @Query("UPDATE images SET unreadable = 1 WHERE path = :path")
    suspend fun setUnreadable(path: String): Int

    @Query("SELECT path FROM images WHERE unreadable ORDER BY path")
    suspend fun unreadable(): List<String>

    @Query(
        """UPDATE images SET width = :width, height = :height, mime = :mime, camera_make = :cameraMake,
           camera_model = :cameraModel, captured_at = :capturedAt WHERE path = :path""",
    )
    suspend fun setMetadata(
        path: String,
        width: Int,
        height: Int,
        mime: String?,
        cameraMake: String?,
        cameraModel: String?,
        capturedAt: String?,
    ): Int

    @Query(
        """SELECT width, height, mime, camera_make, camera_model, captured_at
           FROM images WHERE path = :path AND width IS NOT NULL""",
    )
    suspend fun metadata(path: String): StoredMetadata?

    @Query(
        """UPDATE images SET width = :width, height = :height, duration_ms = :durationMs, frame_rate = :frameRate,
           bitrate = :bitrate, has_audio = :hasAudio, video_codec = :videoCodec, hdr = :hdr, captured_at = :capturedAt,
           captured_instant = :capturedInstant WHERE path = :path""",
    )
    suspend fun setVideoMetadata(
        path: String,
        width: Int,
        height: Int,
        durationMs: Long?,
        frameRate: Double?,
        bitrate: Long?,
        hasAudio: Boolean,
        videoCodec: String?,
        hdr: Boolean,
        capturedAt: String?,
        capturedInstant: Long?,
    ): Int

    @Query(
        """SELECT width, height, duration_ms, frame_rate, bitrate, has_audio, video_codec, hdr, captured_at,
           captured_instant FROM images WHERE path = :path AND width IS NOT NULL""",
    )
    suspend fun videoMetadata(path: String): StoredVideoMetadata?

    @Query(
        """SELECT path, width, height, duration_ms, frame_rate, bitrate, has_audio, video_codec, hdr, captured_at,
           captured_instant FROM images WHERE media_type = 'video' AND width IS NOT NULL""",
    )
    suspend fun allVideoMetadata(): List<StoredPathVideoMetadata>

    @Query("SELECT path, width, height, mime, camera_make, camera_model, captured_at FROM images WHERE width IS NOT NULL")
    suspend fun allMetadata(): List<StoredPathMetadata>

    @Query("UPDATE images SET content_hash = :digest WHERE path = :path")
    suspend fun setContentHash(path: String, digest: String): Int

    @Query("SELECT content_hash FROM images WHERE path = :path")
    suspend fun contentHash(path: String): String?

    @Query("UPDATE images SET perceptual_hash = :hash WHERE path = :path")
    suspend fun setPerceptualHash(path: String, hash: String): Int

    @Query("SELECT perceptual_hash FROM images WHERE path = :path")
    suspend fun perceptualHash(path: String): String?

    @Query("SELECT path FROM images WHERE perceptual_hash IS NOT NULL")
    suspend fun hashedPaths(): List<String>

    @Query("SELECT path, perceptual_hash FROM images WHERE perceptual_hash IS NOT NULL")
    suspend fun perceptualHashes(): List<StoredPerceptualHash>

    @Query("SELECT count(*) FROM images WHERE path = :path")
    suspend fun count(path: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEmbedding(embedding: EmbeddingRow)

    @Query("DELETE FROM embeddings WHERE path = :path")
    suspend fun deleteEmbeddings(path: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAllEmbeddings()

    @Insert
    suspend fun putFrameEmbeddings(frames: List<FrameEmbeddingRow>)

    @Query("DELETE FROM frame_embeddings WHERE path = :path AND model = :model")
    suspend fun deleteFrameEmbeddings(path: String, model: String)

    @Query("DELETE FROM frame_embeddings WHERE path = :path")
    suspend fun deleteFramesOf(path: String)

    @Query("DELETE FROM frame_embeddings")
    suspend fun deleteAllFrameEmbeddings()

    @Query("SELECT count(*) FROM frame_embeddings")
    suspend fun frameEmbeddingCount(): Int

    @Query("SELECT count(DISTINCT path) FROM frame_embeddings")
    suspend fun framedVideoCount(): Int

    @Query(
        """SELECT * FROM frame_embeddings WHERE model = :model AND model_version = :modelVersion AND frames_version = :framesVersion
           ORDER BY path, time_ms""",
    )
    suspend fun frameEmbeddings(model: String, modelVersion: Int, framesVersion: Int): List<FrameEmbeddingRow>

    @Query(
        """SELECT path, time_ms FROM frame_embeddings WHERE model = :model AND model_version = :modelVersion AND frames_version = :framesVersion
           ORDER BY path, time_ms""",
    )
    suspend fun sampledTimes(model: String, modelVersion: Int, framesVersion: Int): List<StoredFrameTime>

    @Query(
        """SELECT path, size, mtime_ns, media_type, unreadable, duration_ms FROM images
           WHERE media_type = 'video' AND width IS NOT NULL AND NOT unreadable AND review_state != 'trashed'
           AND frames_unreadable IS NOT :framesVersion ORDER BY path""",
    )
    suspend fun readVideos(framesVersion: Int): List<StoredVideo>

    @Query("UPDATE images SET frames_unreadable = :framesVersion WHERE path = :path")
    suspend fun setFramesUnreadable(path: String, framesVersion: Int): Int

    @Query("SELECT count(*) FROM images WHERE media_type = 'image'")
    suspend fun imageCount(): Int

    @Query("SELECT count(*) FROM embeddings")
    suspend fun embeddingCount(): Int

    @Query("DELETE FROM analyses WHERE path IN (:paths) AND detector_id IN (:detectorIds)")
    suspend fun forgetAnalyses(paths: List<String>, detectorIds: List<String>): Int

    @Query("SELECT * FROM embeddings WHERE model = :model AND model_version = :modelVersion")
    suspend fun embeddings(model: String, modelVersion: Int): List<EmbeddingRow>

    @Query("SELECT path FROM embeddings WHERE model = :model AND model_version = :modelVersion")
    suspend fun embeddedPaths(model: String, modelVersion: Int): List<String>

    @Query("SELECT path FROM analyses WHERE detector_id = :detectorId AND detector_version = :version")
    suspend fun analyzedPaths(detectorId: String, version: Int): List<String>

    @Query("SELECT path, detector_id, detector_version FROM analyses WHERE detector_id IN (:detectorIds)")
    suspend fun analyses(detectorIds: List<String>): List<AnalysisRow>

    @Query("SELECT path FROM images WHERE NOT unreadable AND review_state != 'trashed' AND media_type = 'image'")
    suspend fun readableNotTrashed(): List<String>

    @Query("DELETE FROM analyses WHERE path = :path")
    suspend fun deleteAnalyses(path: String)

    @Query("DELETE FROM analyses WHERE path = :path AND detector_id = :detectorId")
    suspend fun deleteAnalysis(path: String, detectorId: String)

    @Insert
    suspend fun insertAnalysis(analysis: AnalysisRow)

    @Insert
    suspend fun insertAnalyses(analyses: List<AnalysisRow>)

    @Insert
    suspend fun insertSignals(signals: List<SignalRow>)

    @Query("SELECT * FROM signals WHERE detector_id = :detectorId ORDER BY id")
    suspend fun detectorSignals(detectorId: String): List<SignalRow>

    /** The last id of the other detectors' signals of each image that has signals of this one. */
    @Query(
        """SELECT path, MAX(id) AS id FROM signals WHERE detector_id != :detectorId
           AND path IN (SELECT path FROM signals WHERE detector_id = :detectorId) GROUP BY path""",
    )
    suspend fun lastOtherSignals(detectorId: String): List<StoredSignalId>

    @Query("SELECT count(*) FROM analyses WHERE path = :path AND detector_id = :detectorId AND detector_version = :version")
    suspend fun analyses(path: String, detectorId: String, version: Int): Int

    @Query("SELECT * FROM signals WHERE path = :path ORDER BY id")
    suspend fun signals(path: String): List<SignalRow>

    /** Blocking, as [ImageIndex.indexedMedia] needs: its read-only transaction belongs to the thread. */
    @Query("SELECT * FROM signals ORDER BY id")
    fun allSignals(): List<SignalRow>

    @Query("SELECT fingerprint FROM group_runs WHERE detector_id = :detectorId AND detector_version = :version")
    suspend fun groupFingerprint(detectorId: String, version: Int): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGroupRun(run: GroupRunRow)

    @Query("UPDATE images SET review_state = :state WHERE path = :path")
    suspend fun setReviewState(path: String, state: String): Int

    @Query("SELECT review_state FROM images WHERE path = :path")
    suspend fun reviewState(path: String): String?

    @Query("DELETE FROM kept_signals WHERE path = :path")
    suspend fun deleteKeptSignals(path: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeptSignals(signals: List<KeptSignalRow>)

    /** Blocking, as [allSignals]. */
    @Query("SELECT * FROM kept_signals")
    fun allKeptSignals(): List<KeptSignalRow>

    @Query("SELECT * FROM kept_signals WHERE path = :path")
    suspend fun keptSignals(path: String): List<KeptSignalRow>

    @Query("DELETE FROM kept_detectors WHERE path = :path")
    suspend fun deleteKeptDetectors(path: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeptDetectors(detectors: List<KeptDetectorRow>)

    @Query("INSERT INTO kept_detectors SELECT path, detector_id FROM analyses WHERE path = :path")
    suspend fun keepRanDetectors(path: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM images WHERE path = :path AND review_state = 'kept') " +
            "AND NOT EXISTS(SELECT 1 FROM kept_detectors WHERE path = :path AND detector_id = :detectorId)",
    )
    suspend fun keptWithoutDetector(path: String, detectorId: String): Boolean

    @Query("SELECT path FROM images WHERE review_state = 'kept' AND path NOT IN (SELECT path FROM kept_detectors WHERE detector_id = :detectorId)")
    suspend fun keptWithoutDetector(detectorId: String): List<String>

    @Query("SELECT detector_id FROM kept_detectors WHERE path = :path")
    suspend fun keptDetectors(path: String): List<String>

    @Query("SELECT path, captured_at FROM images WHERE captured_at IS NOT NULL")
    suspend fun capturedAt(): List<StoredCapture>

    @Query("SELECT path, size, mtime_ns, review_state, media_type, unreadable, duration_ms FROM images ORDER BY path")
    suspend fun states(): List<StoredState>

    /** [states], blocking, as [allSignals]. */
    @Query("SELECT path, size, mtime_ns, review_state, media_type, unreadable, duration_ms FROM images ORDER BY path")
    fun statesNow(): List<StoredState>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExcludedFolder(folder: ExcludedFolderRow)

    @Query("DELETE FROM excluded_folders WHERE path = :path")
    suspend fun deleteExcludedFolder(path: String)

    @Query("SELECT path FROM excluded_folders ORDER BY path")
    suspend fun excludedFolders(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJpeg(row: JpegRow)

    @Query("DELETE FROM jpegs WHERE path = :path")
    suspend fun deleteJpeg(path: String)

    @Query(
        """SELECT i.path, i.size, i.mtime_ns, i.media_type FROM images i LEFT JOIN jpegs j ON j.path = i.path
           WHERE i.mime = 'image/jpeg' AND i.media_type = 'image' AND NOT i.unreadable AND i.review_state != 'trashed'
           AND (j.path IS NULL OR j.version != :version OR j.page_width IS NOT NULL) ORDER BY i.path""",
    )
    suspend fun jpegsToRead(version: Int): List<StoredRef>

    /**
     * The images a JPEG read at [version] found eligible, neither trashed nor unreadable, largest first. The resolution
     * is the metadata's, or the row's own until the scan reads the metadata.
     */
    @Query(
        """SELECT i.size, COALESCE(i.width, j.page_width) AS width, COALESCE(i.height, j.page_height) AS height, j.*
           FROM images i JOIN jpegs j ON j.path = i.path
           WHERE i.review_state != 'trashed' AND i.media_type = 'image' AND NOT i.unreadable AND COALESCE(i.width, j.page_width) IS NOT NULL
           AND j.version = :version AND j.status = 'eligible'
           ORDER BY i.size DESC, i.path""",
    )
    suspend fun eligibleJpegs(version: Int): List<StoredJpeg>

    @Query("SELECT status, gain_map, count(*) AS count FROM jpegs GROUP BY status, gain_map ORDER BY status, gain_map")
    suspend fun jpegCounts(): List<StoredJpegCount>

    @Query("SELECT * FROM not_images")
    suspend fun notImages(): List<NotImageRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNotImages(rows: List<NotImageRow>)

    @Query("DELETE FROM not_images WHERE path IN (:paths)")
    suspend fun forgetNotImages(paths: List<String>)
}

/**
 * Bump [version] on every schema change and add a migration with a test (IndexMigrationTest).
 * The lab's schema version is separate: it refuses older files instead of migrating them.
 */
@Database(
    entities = [
        ImageRow::class, AnalysisRow::class, SignalRow::class, KeptSignalRow::class, KeptDetectorRow::class, EmbeddingRow::class,
        FrameEmbeddingRow::class, GroupRunRow::class, ExcludedFolderRow::class, NotImageRow::class, JpegRow::class,
    ],
    version = 9,
)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun dao(): IndexDao
}

/** Version 1 had no excluded folders: the user's index is kept as it is. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `excluded_folders` (`path` TEXT NOT NULL, PRIMARY KEY(`path`))")
    }
}

/**
 * Version 2 did not record which detectors had run when an image was kept. A kept image gets the ones
 * that have a result now, so it behaves as it did.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `kept_detectors` (`path` TEXT NOT NULL, `detector_id` TEXT NOT NULL, " +
                "PRIMARY KEY(`path`, `detector_id`), FOREIGN KEY(`path`) REFERENCES `images`(`path`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO kept_detectors SELECT a.path, a.detector_id FROM analyses a JOIN images i ON i.path = a.path WHERE i.review_state = 'kept'",
        )
    }
}

/** Version 3 did not remember the files the walk found not to be images: the walk fills the table as it goes. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `not_images` (`path` TEXT NOT NULL, `size` INTEGER NOT NULL, `mtime_ns` INTEGER NOT NULL, PRIMARY KEY(`path`))")
    }
}

/** Version 4 had not read the JPEG headers for the compress page: the next scan reads every JPEG (docs/design.md §9). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jpegs` (`path` TEXT NOT NULL, `version` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`gain_map` INTEGER NOT NULL, `quality` INTEGER, `compressed` INTEGER NOT NULL, `marker_version` INTEGER, " +
                "`marker_level` TEXT, `marker_compressed_at` TEXT, `re_edit` INTEGER NOT NULL, `depth_map` INTEGER NOT NULL, " +
                "`xmp` INTEGER NOT NULL, `c2pa` INTEGER NOT NULL, PRIMARY KEY(`path`), " +
                "FOREIGN KEY(`path`) REFERENCES `images`(`path`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/**
 * Version 5 had no resolution on a `jpegs` row: only the scan wrote them. The app now writes one for a file it has just
 * put at a path itself, a compressed copy or a restored original, so the compress page shows it before a scan.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `jpegs` ADD COLUMN `page_width` INTEGER")
        db.execSQL("ALTER TABLE `jpegs` ADD COLUMN `page_height` INTEGER")
    }
}

/**
 * Version 6 held images only. Every row is one, and the walk now takes videos too: it sniffs again the files it
 * remembered as neither, which is how the videos an earlier scan turned away are found (docs/design.md §4).
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `images` ADD COLUMN `media_type` TEXT NOT NULL DEFAULT 'image'")
        db.execSQL("DELETE FROM `not_images`")
    }
}

/**
 * Version 7 had no columns for what only a video has. No video's metadata was read before, so they start empty and
 * the first video scan reads every video (docs/design.md §7).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `images` ADD COLUMN `duration_ms` INTEGER")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `frame_rate` REAL")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `bitrate` INTEGER")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `has_audio` INTEGER")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `video_codec` TEXT")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `hdr` INTEGER")
        db.execSQL("ALTER TABLE `images` ADD COLUMN `captured_instant` INTEGER")
    }
}

/** Version 8 had no frames of a video: the first video scan with the model samples every video (docs/design.md §7). */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `images` ADD COLUMN `frames_unreadable` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `frame_embeddings` (`path` TEXT NOT NULL, `model` TEXT NOT NULL, `model_version` INTEGER NOT NULL, " +
                "`frames_version` INTEGER NOT NULL, `time_ms` INTEGER NOT NULL, `vector` BLOB NOT NULL, PRIMARY KEY(`path`, `model`, `time_ms`), " +
                "FOREIGN KEY(`path`) REFERENCES `images`(`path`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
