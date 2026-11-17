package io.github.bert0ns.anteater.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.anteater.compress.CompressibleImage
import io.github.bert0ns.anteater.compress.JPEG_RULES_VERSION
import io.github.bert0ns.anteater.compress.JpegInfo
import io.github.bert0ns.anteater.compress.JpegStatus
import io.github.bert0ns.anteater.model.FrameMeasures
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ReviewState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val NAME = "migration-test.sqlite"

/**
 * Creates a database exactly as schema [version] in app/schemas describes it. Room's own
 * MigrationTestHelper does the same, but reads the schemas from assets, which local tests lack.
 */
private fun createDatabase(context: Context, version: Int): SQLiteDatabase {
    val schema = File("schemas/${IndexDatabase::class.java.name}/$version.json").readText()
    val database = JSONObject(schema).getJSONObject("database")
    val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile!!.mkdirs() }, null)
    val entities = database.getJSONArray("entities")
    for (i in 0 until entities.length()) {
        val entity = entities.getJSONObject(i)
        val table = entity.getString("tableName")
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices") ?: continue
        for (j in 0 until indices.length()) {
            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
    }
    val setup = database.getJSONArray("setupQueries")
    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    db.version = version
    return db
}

/**
 * Every schema version in app/schemas must open as the current one, with its data. Add a case
 * for each migration. Room refuses to open a database whose schema it does not expect.
 */
@RunWith(RobolectricTestRunner::class)
class IndexMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `version 1 opens with the current schema`() {
        createDatabase(context, 1).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/a.jpg', 100, 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(MediaRef("/photos/a.jpg", 100, 1)), runBlocking { it.images() })
            assertEquals(emptyList<String>(), runBlocking { it.excludedFolders() })
        }
    }

    @Test
    fun `version 2 opens with the detectors a kept image had run`() {
        createDatabase(context, 2).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, review_state) VALUES ('/photos/a.jpg', 100, 1, 'kept')")
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/b.jpg', 100, 1)")
            it.execSQL("INSERT INTO analyses (path, detector_id, detector_version) VALUES ('/photos/a.jpg', 'blur', 1)")
            it.execSQL("INSERT INTO analyses (path, detector_id, detector_version) VALUES ('/photos/b.jpg', 'blur', 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(setOf("blur"), runBlocking { it.keptDetectors("/photos/a.jpg") })
            assertEquals(emptySet<String>(), runBlocking { it.keptDetectors("/photos/b.jpg") })
        }
    }

    @Test
    fun `version 3 opens with an empty memory of non-images`() {
        createDatabase(context, 3).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns) VALUES ('/photos/a.jpg', 100, 1)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(MediaRef("/photos/a.jpg", 100, 1)), runBlocking { it.images() })
            assertEquals(emptyList<MediaRef>(), runBlocking { it.notImages() })
            runBlocking { it.addNotImages(listOf(MediaRef("/photos/notes.txt", 5, 2))) }
            assertEquals(listOf(MediaRef("/photos/notes.txt", 5, 2)), runBlocking { it.notImages() })
        }
    }

    @Test
    fun `version 4 opens with no JPEG read, so the next scan reads every JPEG`() {
        createDatabase(context, 4).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, width, height, mime) VALUES ('/photos/a.jpg', 100, 1, 4000, 3000, 'image/jpeg')")
            it.execSQL("INSERT INTO images (path, size, mtime_ns, width, height, mime) VALUES ('/photos/b.png', 100, 1, 40, 30, 'image/png')")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(MediaRef("/photos/a.jpg", 100, 1)), runBlocking { it.jpegsToRead() })
            runBlocking { it.setJpeg("/photos/a.jpg", JpegInfo(JpegStatus.ELIGIBLE, quality = 95)) }
            assertEquals(listOf("/photos/a.jpg"), runBlocking { it.compressible(34) }.map { image -> image.path })
            runBlocking { it.forget(listOf("/photos/a.jpg")) }
            assertEquals(emptyList<String>(), runBlocking { it.compressible(34) }.map { image -> image.path })
        }
    }

    @Test
    fun `version 5 opens with its JPEG rows, which the scan read and does not read again`() {
        createDatabase(context, 5).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, width, height, mime) VALUES ('/photos/a.jpg', 100, 1, 4000, 3000, 'image/jpeg')")
            it.execSQL(
                "INSERT INTO jpegs (path, version, status, gain_map, quality, compressed, re_edit, depth_map, xmp, c2pa) " +
                    "VALUES ('/photos/a.jpg', $JPEG_RULES_VERSION, 'eligible', 0, 95, 0, 0, 0, 0, 0)",
            )
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(emptyList<MediaRef>(), runBlocking { it.jpegsToRead() })
            assertEquals(listOf(CompressibleImage("/photos/a.jpg", 100, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 95))), runBlocking { it.compressible(34) })
        }
    }

    @Test
    fun `version 6 opens with every row an image and no memory of non-images, so the walk sniffs those files again`() {
        createDatabase(context, 6).use {
            it.execSQL(
                "INSERT INTO images (path, size, mtime_ns, review_state, width, height, mime, perceptual_hash) " +
                    "VALUES ('/photos/a.jpg', 100, 1, 'kept', 4000, 3000, 'image/jpeg', '00000000000000ff')",
            )
            it.execSQL("INSERT INTO images (path, size, mtime_ns, review_state) VALUES ('/photos/b.png', 50, 2, 'trashed')")
            it.execSQL("INSERT INTO analyses (path, detector_id, detector_version) VALUES ('/photos/a.jpg', 'blur', 1)")
            it.execSQL("INSERT INTO signals (path, detector_id, detector_version, category, score, evidence) VALUES ('/photos/a.jpg', 'blur', 1, 'blurry', 0.5, '{}')")
            it.execSQL(
                "INSERT INTO jpegs (path, version, status, gain_map, quality, compressed, re_edit, depth_map, xmp, c2pa) " +
                    "VALUES ('/photos/a.jpg', $JPEG_RULES_VERSION, 'eligible', 0, 95, 0, 0, 0, 0, 0)",
            )
            it.execSQL("INSERT INTO not_images (path, size, mtime_ns) VALUES ('/videos/clip.mp4', 900, 3)")
            it.execSQL("INSERT INTO not_images (path, size, mtime_ns) VALUES ('/docs/notes.txt', 5, 4)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(MediaRef("/photos/a.jpg", 100, 1), MediaRef("/photos/b.png", 50, 2)), runBlocking { it.images() })
            assertEquals(listOf("/photos/a.jpg", "/photos/b.png"), runBlocking { it.indexedMedia() }.map { image -> image.ref.path })
            assertEquals(ReviewState.KEPT, runBlocking { it.reviewState("/photos/a.jpg") })
            assertEquals(ReviewState.TRASHED, runBlocking { it.reviewState("/photos/b.png") })
            assertEquals(listOf("blur"), runBlocking { it.signals("/photos/a.jpg") }.map { signal -> signal.detectorId })
            assertEquals(0xffuL, runBlocking { it.perceptualHash("/photos/a.jpg") })
            assertEquals(listOf("/photos/a.jpg"), runBlocking { it.compressible(34) }.map { image -> image.path })
            assertEquals(emptyList<MediaRef>(), runBlocking { it.notImages() })
        }
    }

    @Test
    fun `version 7 opens with no video read yet, so a video scan reads every video`() {
        createDatabase(context, 7).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, width, height, mime) VALUES ('/photos/a.jpg', 100, 1, 4000, 3000, 'image/jpeg')")
            it.execSQL("INSERT INTO images (path, size, mtime_ns, media_type) VALUES ('/videos/clip.mp4', 900, 2, 'video')")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(listOf(MediaRef("/videos/clip.mp4", 900, 2, MediaType.VIDEO)), runBlocking { it.withoutMetadata() })
            assertEquals(4000, runBlocking { it.metadata("/photos/a.jpg") }?.width)
            assertNull(runBlocking { it.videoMetadata("/photos/a.jpg") }?.durationMs)
            assertNull(runBlocking { it.videoMetadata("/photos/a.jpg") }?.capturedInstant)
        }
    }

    @Test
    fun `version 8 opens with no frame embedded yet, so a video scan samples every video`() {
        createDatabase(context, 8).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, media_type, width, height, duration_ms) VALUES ('/videos/clip.mp4', 900, 2, 'video', 64, 48, 1000)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(emptyMap<String, List<Long>>(), runBlocking { it.sampledTimes("mobileclip2_s0", 1, 1) })
            assertEquals(listOf("/videos/clip.mp4"), runBlocking { it.readVideos(1) }.map { video -> video.ref.path })
        }
    }

    @Test
    fun `version 9 opens with no frame measured yet, so a video scan samples every video again`() {
        createDatabase(context, 9).use {
            it.execSQL("INSERT INTO images (path, size, mtime_ns, media_type, width, height, duration_ms) VALUES ('/videos/clip.mp4', 900, 2, 'video', 64, 48, 1000)")
        }

        ImageIndex.open(context, NAME).use {
            assertEquals(emptyMap<String, List<FrameMeasures>>(), runBlocking { it.frameMeasures(3) })
            assertEquals(listOf("/videos/clip.mp4"), runBlocking { it.readVideos(3) }.map { video -> video.ref.path })
        }
    }
}
