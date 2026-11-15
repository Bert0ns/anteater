package io.github.bert0ns.cribrum.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toMediaRef
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.Stats
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.review.PAGE_SIZE
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.ExpiredPurge
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun blurry(path: String, score: Float) = runBlocking {
        app.index.record(MediaRef(path, 1_000, 1L))
        app.index.setSignals(path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, score)))
    }

    private fun paths(model: ReviewViewModel) = model.state.value.page.flatMap { group -> group.images.map { it.image.path } }

    /** The view model changes state on its own scope: run the main looper until the change is done. */
    private fun settle(model: ReviewViewModel, done: (ReviewViewModel) -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.state.value.loading && done(model)) return
            Thread.sleep(10)
        }
        throw AssertionError("never settled: ${model.state.value}")
    }

    @Test
    fun `skipping a page shows the next candidates, reviews nothing, and the skipped come back when reopened`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }

        model.skip(shownIds(model))
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        assertEquals(1, model.state.value.remaining)
        assertNull(model.state.value.lastDecision)
        runBlocking {
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/a0.jpg"))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/b.jpg"))
        }

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        assertEquals(PAGE_SIZE + 1, model.state.value.remaining)
    }

    @Test
    fun `a second skip of the page already skipped does not skip the next one unseen`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        val shown = shownIds(model)

        model.skip(shown)
        model.skip(shown)
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        assertEquals(1, model.state.value.remaining)
    }

    @Test
    fun `applying or undoing after a skip keeps the skipped images out`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        model.skip(shownIds(model))
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        // The file is not on disk, so nothing moves, but the page is rebuilt and the decision can be undone.
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
        model.undo(model.state.value.lastDecision!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
    }

    @Test
    fun `applyPage on a near-duplicate group does not mark sticker or blurry candidates as kept`() {
        val best = "/sdcard/best.jpg"
        val blurry = "/sdcard/blurry.jpg"
        val sticker = "/sdcard/sticker.jpg"
        val plain = "/sdcard/plain.jpg"

        runBlocking {
            app.index.record(MediaRef(best, 1_000, 1L))
            app.index.setSignals(
                best,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.BEST.value))),
            )

            app.index.record(MediaRef(plain, 1_000, 1L))
            app.index.setSignals(
                plain,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )

            app.index.record(MediaRef(blurry, 1_000, 1L))
            app.index.setSignals(
                blurry,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )
            app.index.setSignals(blurry, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.9f)))

            app.index.record(MediaRef(sticker, 1_000, 1L))
            app.index.setSignals(
                sticker,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )
            app.index.setSignals(sticker, "sticker", 1, listOf(Signal("sticker", 1, Category.STICKER, 1f)))
        }

        val model = ReviewViewModel(app)
        model.open(Category.NEAR_DUPLICATE)
        settle(model) { paths(it).isNotEmpty() }

        assertEquals(listOf(best, plain, blurry).sorted(), paths(model).sorted())

        if (plain in model.state.value.marked) model.toggle(MediaRef(plain, 1_000, 1L))
        if (blurry in model.state.value.marked) model.toggle(MediaRef(blurry, 1_000, 1L))
        settle(model) { model.state.value.marked.isEmpty() }

        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }

        runBlocking {
            assertEquals(ReviewState.KEPT, app.index.reviewState(best))
            assertEquals(ReviewState.KEPT, app.index.reviewState(plain))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(blurry))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(sticker))
        }
    }

    @Test
    fun `grouping a category by date reorders its page, keeps the user's marks, and is remembered for that category`() {
        blurry("/sdcard/old.jpg", 0.9f)
        blurry("/sdcard/new.jpg", 0.5f)
        runBlocking {
            app.index.setMetadata("/sdcard/old.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(2020, 1, 1, 12, 0)))
            app.index.setMetadata("/sdcard/new.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(2026, 1, 1, 12, 0)))
        }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        assertEquals(ReviewOrder.OFF, model.state.value.reviewOrder)
        assertEquals(listOf("/sdcard/old.jpg", "/sdcard/new.jpg"), paths(model))
        model.toggle(model.state.value.page.first().images.single().image) // keep old.jpg

        model.setReviewOrder(ReviewOrder.MONTH)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH }

        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))
        assertEquals(setOf("/sdcard/new.jpg"), model.state.value.marked)
        val next = ReviewViewModel(app)
        next.open(Category.BLURRY)
        settle(next) { paths(it).size == 2 }
        assertEquals(ReviewOrder.MONTH, next.state.value.reviewOrder)
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(next))
        next.open(Category.SCREENSHOT)
        settle(next) { it.state.value.screen == Screen.Category(Category.SCREENSHOT) }
        assertEquals(ReviewOrder.OFF, next.state.value.reviewOrder)
    }

    @Test
    fun `the date order holds after skipping, applying and undoing`() {
        listOf("old" to (0.9f to 2020), "mid" to (0.7f to 2024), "new" to (0.5f to 2026)).forEach { (name, found) ->
            val (score, year) = found
            blurry("/sdcard/$name.jpg", score)
            runBlocking { app.index.setMetadata("/sdcard/$name.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(year, 1, 1, 12, 0))) }
        }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        model.setReviewOrder(ReviewOrder.MONTH)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/mid.jpg", "/sdcard/old.jpg"), paths(model))

        model.skip(setOf("/sdcard/mid.jpg"))
        settle(model) { paths(it).size == 2 }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))

        // The files are not on disk, so nothing moves, but the page is rebuilt and the decision can be undone.
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))

        model.undo(model.state.value.lastDecision!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))
    }

    @Test
    fun `a category without the control ignores a date order`() {
        val model = ReviewViewModel(app)
        model.open(Category.STICKER)
        settle(model) { it.state.value.screen == Screen.Category(Category.STICKER) }

        model.setReviewOrder(ReviewOrder.DAY)
        settle(model) { true }

        assertEquals(ReviewOrder.OFF, model.state.value.reviewOrder)
    }

    @Test
    fun `sorting a category by size is remembered, and the home total counts each image once`() {
        blurry("/sdcard/small.jpg", 0.9f) // the index says 1,000 bytes
        runBlocking { app.index.record(MediaRef("/sdcard/large.jpg", 5_000, 1L)) }
        runBlocking {
            app.index.setSignals("/sdcard/large.jpg", "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.5f)))
            app.index.setSignals("/sdcard/large.jpg", "shot", 1, listOf(Signal("shot", 1, Category.SCREENSHOT, 0.9f)))
        }
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        assertEquals(6_000L, model.state.value.freeableBytes)

        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        model.setReviewOrder(ReviewOrder.SIZE)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.SIZE }

        assertEquals(listOf("/sdcard/large.jpg", "/sdcard/small.jpg"), paths(model))
        model.open(Category.STICKER)
        settle(model) { it.state.value.screen == Screen.Category(Category.STICKER) }
        model.setReviewOrder(ReviewOrder.SIZE)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.SIZE }
        val next = ReviewViewModel(app)
        next.open(Category.STICKER)
        settle(next) { it.state.value.screen == Screen.Category(Category.STICKER) }
        assertEquals(ReviewOrder.SIZE, next.state.value.reviewOrder)
    }

    /** A file in a trash of its own, trashed [daysAgo] days ago (the trash reads its time from origin.properties). */
    private fun trashed(trash: AppTrash, volume: File, name: String, daysAgo: Long) = runBlocking {
        val image = File(volume, name).apply { writeText(name) }.toMediaRef()
        trash.moveToTrash(listOf(image))
        val origin = trash.trashed().single { it.original == image }.file.parentFile!!.resolve("origin.properties")
        val trashedAt = Instant.now() - Duration.ofDays(daysAgo)
        origin.writeText(
            origin.readLines().joinToString("\n") { line ->
                when {
                    line.startsWith("trashed_at=") -> "trashed_at=$trashedAt"
                    line.startsWith("seen_at=") -> "seen_at=$trashedAt"
                    else -> line
                }
            },
        )
    }

    private fun modelWithTrash(vararg daysAgo: Long): ReviewViewModel {
        val volume = folder.newFolder("storage")
        val trash = AppTrash({ listOf(volume) })
        daysAgo.forEach { trashed(trash, volume, "$it.jpg", it) }
        return ReviewViewModel(app, Review(app.index, trash))
    }

    @Test
    fun `lowering the retention asks first when images would be purged, and cancel keeps the setting`() {
        app.trashRetention.days = 90
        val model = modelWithTrash(20, 2)
        settle(model) { it.state.value.retentionDays == 90 }

        model.chooseRetention(7)
        settle(model) { it.state.value.pendingRetention != null }
        assertEquals(RetentionChange(days = 7, expiring = 1), model.state.value.pendingRetention)
        assertEquals(90, model.state.value.retentionDays)

        model.cancelRetention()
        assertNull(model.state.value.pendingRetention)
        assertEquals(90, model.state.value.retentionDays)
        assertEquals(90, app.trashRetention.days)

        model.chooseRetention(7)
        settle(model) { it.state.value.pendingRetention != null }
        model.confirmRetention()
        settle(model) { it.state.value.retentionDays == 7 }
        assertNull(model.state.value.pendingRetention)
        assertEquals(7, app.trashRetention.days)
    }

    @Test
    fun `raising the retention, or lowering it when nothing would expire, needs no confirmation`() {
        app.trashRetention.days = 30
        val model = modelWithTrash(2)
        settle(model) { it.state.value.retentionDays == 30 }

        model.chooseRetention(90)
        settle(model) { it.state.value.retentionDays == 90 }
        model.chooseRetention(14)
        settle(model) { it.state.value.retentionDays == 14 }

        assertNull(model.state.value.pendingRetention)
        assertEquals(14, app.trashRetention.days)
    }

    @Test
    fun `excluding a folder hides its images, and removing the exclusion from settings brings them back`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Family/2020/b.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }

        model.excludeFolder(MediaRef("/sdcard/Family/a.jpg", 1_000, 1L))
        settle(model) { paths(it) == listOf("/sdcard/Other/c.jpg") }
        assertEquals(1, model.state.value.remaining)

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.openSettings()
        settle(model) { it.state.value.excludedFolders == listOf("/sdcard/Family") }
        model.removeExcludedFolder("/sdcard/Family")
        settle(model) { it.state.value.excludedFolders.isEmpty() }

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
    }

    @Test
    fun `excluding a folder keeps the marks the user changed on the page`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/A/b.jpg", 0.8f)
        blurry("/sdcard/B/c.jpg", 0.7f)
        blurry("/sdcard/C/d.jpg", 0.6f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 4 }
        model.toggle(MediaRef("/sdcard/A/a.jpg", 1_000, 1L))

        model.excludeFolder(MediaRef("/sdcard/C/d.jpg", 1_000, 1L))
        settle(model) { paths(it).size == 3 }

        assertEquals(setOf("/sdcard/A/b.jpg", "/sdcard/B/c.jpg"), model.state.value.marked)
    }

    @Test
    fun `undoing an exclusion brings the images back and keeps the marks`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/B/b.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        model.toggle(MediaRef("/sdcard/A/a.jpg", 1_000, 1L))

        model.excludeFolder(MediaRef("/sdcard/B/b.jpg", 1_000, 1L))
        settle(model) { it.state.value.lastExclusion == "/sdcard/B" }
        assertEquals(listOf("/sdcard/A/a.jpg"), paths(model))

        model.undoExclusion()
        settle(model) { paths(it).size == 2 }

        assertNull(model.state.value.lastExclusion)
        assertEquals(setOf("/sdcard/B/b.jpg"), model.state.value.marked)
        assertEquals(emptyList<String>(), runBlocking { app.index.excludedFolders() })
    }

    @Test
    fun `excluding a folder from the storage map takes it off the map and out of the review`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Family/2020/b.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        assertEquals(3, model.state.value.storageMap.single().count)

        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion == "/sdcard/Family" }

        assertEquals(listOf("/sdcard/Other"), model.state.value.storageMap.map { it.path })
        assertEquals(listOf("/sdcard/Family"), runBlocking { app.index.excludedFolders() })
        model.open(Category.BLURRY)
        settle(model) { paths(it) == listOf("/sdcard/Other/c.jpg") }
    }

    @Test
    fun `the storage map counts a video, and excluding its folder takes it off the map`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        runBlocking { app.index.record(MediaRef("/sdcard/Family/v.mp4", 9_000, 1L, MediaType.VIDEO)) }
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        val root = model.state.value.storageMap.single()
        assertEquals(2, root.count)
        assertEquals(1, root.videoCount)
        assertEquals(9_000L, root.videoSize)

        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion != null }
        assertEquals(Folder.EMPTY, model.state.value.storageMap.single())
    }

    @Test
    fun `settings counts the indexed images and videos, the ones in an excluded folder left out`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        runBlocking {
            app.index.record(MediaRef("/sdcard/Family/v.mp4", 9_000, 1L, MediaType.VIDEO))
            app.index.record(MediaRef("/sdcard/Other/w.mp4", 4_000, 1L, MediaType.VIDEO))
        }
        val model = ReviewViewModel(app)
        model.openSettings()
        settle(model) { it.state.value.storage != null }
        assertEquals(MediaTotal(1, 1_000L), model.state.value.storage?.images)
        assertEquals(MediaTotal(2, 13_000L), model.state.value.storage?.videos)

        model.excludeFolder("/sdcard/Family")
        model.openSettings()
        settle(model) { it.state.value.storage?.videos == MediaTotal(1, 4_000L) }
        assertEquals(MediaTotal(0, 0L), model.state.value.storage?.images)
    }

    @Test
    fun `undoing an exclusion made on the storage map puts the folder back on the map`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        model.zoomStorageMap(model.state.value.storageMap.single().children.first())
        settle(model) { it.state.value.storageMap.size == 2 }
        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion != null }
        assertEquals(1, model.state.value.storageMap.size)

        model.undoExclusion()
        settle(model) { it.state.value.lastExclusion == null }

        assertEquals(2, model.state.value.storageMap.single().count)
        assertEquals(emptyList<String>(), runBlocking { app.index.excludedFolders() })
    }

    @Test
    fun `leaving the storage map after an exclusion shows the folder in settings`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion != null }

        model.selectDestination(Destination.Settings)
        settle(model) { it.state.value.screen == Screen.Settings }

        assertEquals(listOf("/sdcard/Family"), model.state.value.excludedFolders)
    }

    @Test
    fun `the map opens from settings, belongs to it, and Back at its root returns to settings`() {
        val model = ReviewViewModel(app)
        model.selectDestination(Destination.Settings)
        settle(model) { it.state.value.screen == Screen.Settings }

        model.openStorageMap()
        settle(model) { it.state.value.screen == Screen.StorageMap }
        assertEquals(Destination.Settings, model.state.value.screen.destination)
        assertTrue(model.state.value.screen.coversNavigation)

        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
    }

    @Test
    fun `back from the map reads settings again, the analysis data included`() {
        val model = ReviewViewModel(app)
        model.selectDestination(Destination.Settings)
        settle(model) { it.state.value.screen == Screen.Settings && it.state.value.embeddingCount == 0 }
        model.openStorageMap()
        settle(model) { it.state.value.screen == Screen.StorageMap }
        runBlocking {
            app.index.record(MediaRef("/sdcard/a.jpg", 1, 1L))
            app.index.setEmbeddings("clip", 1, mapOf("/sdcard/a.jpg" to floatArrayOf(1f)))
        }

        model.back()

        settle(model) { it.state.value.screen == Screen.Settings && it.state.value.embeddingCount == 1 }
    }

    @Test
    fun `Back from compress and settings goes to clean`() {
        val model = ReviewViewModel(app)
        for (destination in listOf(Destination.Compress, Destination.Settings)) {
            model.selectDestination(destination)
            settle(model) { it.state.value.screen.destination == destination }

            model.back()
            settle(model) { it.state.value.screen == Screen.Home }
        }
    }

    @Test
    fun `the bar switches between destinations`() {
        val model = ReviewViewModel(app)
        for (destination in Destination.entries) {
            model.selectDestination(destination)
            settle(model) { it.state.value.screen.destination == destination }
        }
        assertEquals(Screen.Settings, model.state.value.screen)
    }

    @Test
    fun `full screen views cover the bar and Back returns to the destination they were opened from`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val model = ReviewViewModel(app)

        model.open(Category.BLURRY)
        settle(model) { it.state.value.screen == Screen.Category(Category.BLURRY) }
        assertEquals(Destination.Clean, model.state.value.screen.destination)
        assertTrue(model.state.value.screen.coversNavigation)
        model.back()
        settle(model) { it.state.value.screen == Screen.Home }

        model.openTrash()
        settle(model) { it.state.value.screen == Screen.Trash(Destination.Clean) }
        assertEquals(Destination.Clean, model.state.value.screen.destination)
        assertTrue(model.state.value.screen.coversNavigation)
        model.back()
        settle(model) { it.state.value.screen == Screen.Home }

        model.selectDestination(Destination.Settings)
        settle(model) { it.state.value.screen == Screen.Settings }
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }
        assertEquals(Destination.Settings, model.state.value.screen.destination)
        assertTrue(model.state.value.screen.coversNavigation)
        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
        assertFalse(model.state.value.screen.coversNavigation)
    }

    @Test
    fun `the map keeps its zoom while the user is on another destination`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        val family = model.state.value.storageMap.single().children.single { it.path == "/sdcard/Family" }
        model.zoomStorageMap(family)
        settle(model) { it.state.value.storageMap.size == 2 }

        model.selectDestination(Destination.Clean)
        settle(model) { it.state.value.screen == Screen.Home }
        model.openStorageMap()
        settle(model) { it.state.value.screen == Screen.StorageMap }

        assertEquals(2, model.state.value.storageMap.size)
        assertEquals("/sdcard/Family", model.state.value.storageMap.last().path)
    }

    @Test
    fun `a threshold set in settings changes the lists at once, is stored, and reset restores the defaults`() {
        blurry("/sdcard/a.jpg", 0.9f)
        blurry("/sdcard/b.jpg", 0.6f)
        blurry("/sdcard/c.jpg", 0.45f)
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
        assertEquals(3, model.state.value.thresholdCounts[Category.BLURRY])

        model.setThreshold(Category.BLURRY, 0.7f)
        settle(model) { it.state.value.thresholds[Category.BLURRY] == 0.7f }

        assertEquals(1, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(3, model.state.value.defaultCounts[Category.BLURRY])
        assertEquals(0.7f, app.thresholds.values[Category.BLURRY])
        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.back()
        settle(model) { it.state.value.screen == Screen.Home && it.state.value.categories.isNotEmpty() }
        assertEquals(1, model.state.value.categories.single { it.category == Category.BLURRY }.count)

        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }
        model.resetThresholds()
        settle(model) { it.state.value.thresholds == THRESHOLDS }

        assertEquals(3, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(THRESHOLDS, app.thresholds.values)
    }

    @Test
    fun `back from the thresholds returns to settings`() {
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }

        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
    }

    @Test
    fun `thresholdCounts and defaultCounts update when index changes while on Thresholds screen`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
        assertEquals(1, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(1, model.state.value.defaultCounts[Category.BLURRY])

        blurry("/sdcard/b.jpg", 0.9f)
        model.openThresholds()
        settle(model) { it.state.value.thresholdCounts[Category.BLURRY] == 2 }

        assertEquals(2, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(2, model.state.value.defaultCounts[Category.BLURRY])
    }

    private fun shownIds(model: ReviewViewModel) = model.state.value.page.map { it.id }.toSet()

    @Test
    fun `deleting the embeddings updates the count and keeps the candidates`() {
        blurry("/sdcard/a.jpg", 0.9f)
        runBlocking { app.index.setEmbeddings("clip", 1, mapOf("/sdcard/a.jpg" to floatArrayOf(1f))) }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it) == listOf("/sdcard/a.jpg") }
        model.openSettings()
        settle(model) { it.state.value.embeddingCount == 1 }

        model.deleteEmbeddings()
        settle(model) { it.state.value.embeddingCount == 0 }
        assertEquals(emptyMap<String, FloatArray>(), runBlocking { app.index.embeddings("clip", 1) })
        assertEquals(listOf("/sdcard/a.jpg"), paths(model))
        runBlocking { assertEquals(1, app.index.signals("/sdcard/a.jpg").size) }
    }

    @Test
    fun `haptic feedback is on until the user turns it off, and the choice is kept`() {
        val model = ReviewViewModel(app)
        settle(model) { true }
        assertEquals(true, model.state.value.hapticsEnabled)

        model.setHaptics(false)
        settle(model) { !it.state.value.hapticsEnabled }

        val next = ReviewViewModel(app)
        settle(next) { true }
        assertEquals(false, next.state.value.hapticsEnabled)
    }

    @Test
    fun `the home screen knows whether anything is indexed`() {
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        assertTrue(model.state.value.indexEmpty)

        blurry("/sdcard/a.jpg", 0.9f)
        model.refresh()
        settle(model) { !it.state.value.indexEmpty }
    }

    @Test
    fun `back on the home reads the empty index and the last scan again, which a scan left while another screen showed`() {
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        assertTrue(model.state.value.indexEmpty)
        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }

        blurry("/sdcard/a.jpg", 0.9f)
        app.lastScan.record(Stats(unreadable = 3), finishedAt = 5)
        model.back()
        settle(model) { it.state.value.screen == Screen.Home && !it.state.value.indexEmpty }
        assertEquals(3, model.state.value.lastScan!!.unreadable)
    }

    /** A view model whose [Review] counts the overview loads: each one reads the thresholds once. */
    private fun modelCountingLoads(): Pair<ReviewViewModel, AtomicInteger> {
        val loads = AtomicInteger()
        val review = Review(app.index, app.trash, thresholds = {
            loads.incrementAndGet()
            THRESHOLDS
        })
        return ReviewViewModel(app, review) to loads
    }

    @Test
    fun `a cold start loads the overview once, for the resume that sees access and the first composition together`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()

        model.refreshAfterAccessGranted() // MainActivity.onResume
        model.refresh() // the first composition of the home
        settle(model) { it.state.value.categories.isNotEmpty() }
        Thread.sleep(100)

        assertEquals(1, loads.get())
    }

    @Test
    fun `access granted while another screen is open refreshes the overview`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()
        model.open(Category.BLURRY)
        settle(model) { it.state.value.screen == Screen.Category(Category.BLURRY) }
        val before = loads.get()

        model.refreshAfterAccessGranted()
        settle(model) { it.state.value.categories.isNotEmpty() }

        assertEquals(before + 1, loads.get())
    }

    @Test
    fun `refreshes asked one after another are not merged`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()

        repeat(4) {
            // the start, two steps, the end
            model.refresh()
            settle(model) { true }
        }

        assertEquals(4, loads.get())
    }

    @Test
    fun `a refresh replaces the one before it, so overlapping refreshes load once, and the queue is left alone`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()
        model.setHaptics(false)

        model.refresh() // replaced by the next
        model.refresh() // replaced by the last
        model.setHaptics(true) // not a read: must not be cancelled
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, loads.get())
        assertEquals(true, model.state.value.hapticsEnabled)
        assertEquals(false, model.state.value.loading)
        assertEquals(1, model.state.value.categories.first { it.category == Category.BLURRY }.count)
    }

    @Test
    fun `settings shows what each detector analysed and the last finished scan`() {
        blurry("/sdcard/a.jpg", 0.9f)
        app.lastScan.record(Stats(unreadable = 3), finishedAt = 5)
        val model = ReviewViewModel(app)

        model.openSettings()
        settle(model) { it.state.value.coverage != null }

        assertEquals(Coverage("blur", 1, analysed = 1, needed = 1), model.state.value.coverage!!.single { it.id == "blur" })
        assertEquals(3, model.state.value.lastScan!!.unreadable)
    }

    @Test
    fun `settings measures the trash and the embeddings, and measures again after a delete`() {
        val volume = folder.newFolder()
        val trash = AppTrash({ listOf(volume) })
        val image = File(volume, "a.jpg").apply { writeText("0123456789") }.toMediaRef()
        runBlocking {
            app.index.record(image)
            trash.moveToTrash(listOf(image))
            app.index.setEmbeddings("clip", 1, mapOf(image.path to FloatArray(4)))
        }
        val model = ReviewViewModel(app, Review(app.index, trash))

        model.openSettings()
        settle(model) { it.state.value.storage != null }
        val storage = model.state.value.storage!!
        assertEquals(10L, storage.trash)
        assertEquals(true, storage.embeddings > 0)
        assertEquals(true, storage.index > 0)

        model.deleteEmbeddings()
        settle(model) { it.state.value.storage?.embeddings == 0L }
        assertEquals(10L, model.state.value.storage?.trash)
    }

    /** A trash whose listing waits for [release], like a big one on a slow phone. */
    private class SlowTrash(private val release: CompletableDeferred<Unit>, private val items: List<TrashedImage> = emptyList()) : Trash {
        override suspend fun moveToTrash(images: List<MediaRef>) = images.asItems()

        override suspend fun restore(images: List<MediaRef>) = images

        override suspend fun trashed(): List<TrashedImage> {
            release.await()
            return items
        }

        override suspend fun purge(images: List<MediaRef>) = images

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    @Test
    fun `leaving settings does not wait for the storage measure, and its late sizes are dropped`() {
        val release = CompletableDeferred<Unit>()
        val model = ReviewViewModel(app, Review(app.index, SlowTrash(release)))

        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.back()
        settle(model) { it.state.value.screen == Screen.Home } // while the measure is still stuck
        assertNull(model.state.value.storage)

        release.complete(Unit)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.storage)
    }

    @Test
    fun `the trash screen opens at once, empty and loading, and fills when the list is read`() {
        val release = CompletableDeferred<Unit>()
        val items = listOf(TrashedImage(MediaRef("/sdcard/DCIM/a.jpg", 1, 0), Instant.EPOCH, File("/trash/0/a.jpg"), Instant.EPOCH))
        val model = ReviewViewModel(app, Review(app.index, SlowTrash(release, items)))
        settle(model) { true }

        model.openTrash()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Screen.Trash(Destination.Clean), model.state.value.screen)
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.trashed.isEmpty())

        release.complete(Unit)
        settle(model) { !it.state.value.loading }
        assertEquals(Screen.Trash(Destination.Clean), model.state.value.screen)
        assertEquals(items, model.state.value.trashed)
    }

    /** A trash that records what it was asked and can hold the moves, like a slow volume. */
    private class RecordingTrash(private val gate: CompletableDeferred<Unit>? = null) : Trash {
        val moves = mutableListOf<List<MediaRef>>()
        val restores = mutableListOf<List<MediaRef>>()

        override suspend fun moveToTrash(images: List<MediaRef>): List<TrashedImage> {
            gate?.await()
            moves += images
            return images.asItems()
        }

        override suspend fun restore(images: List<MediaRef>): List<MediaRef> {
            restores += images
            return images
        }

        override suspend fun trashed() = emptyList<TrashedImage>()

        override suspend fun purge(images: List<MediaRef>) = images

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    /** What the screen hands over when the user confirms: the marked paths to trash, and the others on the page to keep. */
    private fun confirm(model: ReviewViewModel): Pair<Set<String>, Set<String>> {
        val marked = model.state.value.marked
        return marked to (paths(model).toSet() - marked)
    }

    private fun applyShown(model: ReviewViewModel) {
        val (trash, keep) = confirm(model)
        model.applyPage(trash, keep)
    }

    /** A page of [PAGE_SIZE] blurry images, and [more] on the next one. */
    private fun twoPages(more: Int = 1) {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        repeat(more) { blurry("/sdcard/b$it.jpg", 0.5f) }
    }

    @Test
    fun `confirming the same page twice while the first apply runs trashes the next page for no one`() {
        twoPages()
        val gate = CompletableDeferred<Unit>()
        val trash = RecordingTrash(gate)
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        val (toTrash, toKeep) = confirm(model)

        model.applyPage(toTrash, toKeep)
        model.applyPage(toTrash, toKeep) // a second tap, while the first still moves files
        gate.complete(Unit)
        settle(model) { paths(it) == listOf("/sdcard/b0.jpg") && it.state.value.lastDecision != null }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, trash.moves.size)
        assertEquals(PAGE_SIZE, model.state.value.lastDecision!!.trashed.size)
        assertEquals(listOf("/sdcard/b0.jpg"), paths(model))
        runBlocking { assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/b0.jpg")) }
    }

    @Test
    fun `a favourite the gallery marked after the page loaded is not trashed until the user has seen and confirmed it`() {
        blurry("/sdcard/a.jpg", 0.9f)
        blurry("/sdcard/b.jpg", 0.8f)
        var favorites = emptySet<String>()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash, favorites = { favorites }))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        favorites = setOf("/sdcard/a.jpg") // the user hearted it in the gallery meanwhile
        val (toTrash, toKeep) = confirm(model)

        model.applyPage(toTrash, toKeep) // the dialog on screen did not mention a favourite
        settle(model) { true }

        assertEquals(emptyList<List<MediaRef>>(), trash.moves)
        assertNull(model.state.value.lastDecision)
        assertEquals(setOf("/sdcard/a.jpg"), model.state.value.page.flatMap { it.images }.filter { it.isFavorite }.map { it.image.path }.toSet())
        assertEquals(toTrash, model.state.value.marked) // the page is back as it was, with its marks
        assertTrue(model.state.value.favoriteAppeared) // and a snackbar says why
        model.dismissFavoriteAppeared()
        settle(model) { !it.state.value.favoriteAppeared }

        model.applyPage(toTrash, toKeep) // now the page, and so the dialog, shows the heart
        settle(model) { it.state.value.lastDecision != null }

        assertEquals(setOf("/sdcard/a.jpg", "/sdcard/b.jpg"), trash.moves.single().map { it.path }.toSet())
    }

    private fun clip(path: String, size: Long, unreadable: Boolean = false) = runBlocking {
        app.index.record(MediaRef(path, size, 1L, MediaType.VIDEO))
        if (unreadable) app.index.setUnreadable(path)
    }

    private fun videoPaths(model: ReviewViewModel) = model.state.value.videos.map { it.video.path }

    @Test
    fun `the home reads the videos, largest first, and knows whether the index holds any`() {
        val empty = ReviewViewModel(app)
        empty.refresh()
        settle(empty) { true }
        assertFalse(empty.state.value.videosIndexed)

        clip("/sdcard/small.mp4", 10)
        clip("/sdcard/big.mp4", 300)
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.videos.isNotEmpty() }

        assertEquals(listOf("/sdcard/big.mp4", "/sdcard/small.mp4"), videoPaths(model))
        assertTrue(model.state.value.videosIndexed)
    }

    @Test
    fun `a video that was all reviewed leaves the list, but the tab does not say videos wait for a scan`() {
        clip("/sdcard/a.mp4", 10)
        runBlocking { app.index.setReviewState("/sdcard/a.mp4", ReviewState.KEPT) }
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.videosIndexed }

        assertEquals(emptyList<String>(), videoPaths(model))
    }

    @Test
    fun `largest videos opens with nothing selected, a tap selects and a second tap unselects`() {
        clip("/sdcard/a.mp4", 10)
        clip("/sdcard/b.mp4", 20)
        val model = ReviewViewModel(app)
        model.openVideos()
        settle(model) { videoPaths(it).size == 2 }

        assertEquals(Screen.Videos, model.state.value.screen)
        assertEquals(emptySet<String>(), model.state.value.videoSelected)
        model.toggleVideo("/sdcard/a.mp4")
        model.toggleVideo("/sdcard/gone.mp4") // not on the list: nothing to flip
        settle(model) { it.state.value.videoSelected.isNotEmpty() }
        assertEquals(setOf("/sdcard/a.mp4"), model.state.value.videoSelected)
        model.toggleVideo("/sdcard/a.mp4")
        settle(model) { it.state.value.videoSelected.isEmpty() }
    }

    @Test
    fun `trashing the selected videos moves them, takes them off the list and offers Undo, which brings them back`() {
        clip("/sdcard/a.mp4", 10)
        clip("/sdcard/b.mp4", 20)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openVideos()
        settle(model) { videoPaths(it).size == 2 }
        model.toggleVideo("/sdcard/b.mp4")
        settle(model) { it.state.value.videoSelected.isNotEmpty() }

        model.applyVideos(setOf("/sdcard/b.mp4"), trash = true)
        settle(model) { it.state.value.lastDecision != null }

        assertEquals(listOf("/sdcard/b.mp4"), trash.moves.single().map { it.path })
        assertEquals(listOf("/sdcard/a.mp4"), videoPaths(model))
        assertEquals(emptySet<String>(), model.state.value.videoSelected)

        model.undo(model.state.value.lastDecision!!)
        settle(model) { videoPaths(it).size == 2 }

        assertEquals(listOf("/sdcard/b.mp4", "/sdcard/a.mp4"), videoPaths(model))
    }

    @Test
    fun `keeping the selected videos marks them kept and takes them off the list, trashing nothing`() {
        clip("/sdcard/a.mp4", 10)
        clip("/sdcard/b.mp4", 20)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openVideos()
        settle(model) { videoPaths(it).size == 2 }
        model.toggleVideo("/sdcard/b.mp4")
        settle(model) { it.state.value.videoSelected.isNotEmpty() }

        model.applyVideos(setOf("/sdcard/b.mp4"), trash = false)
        settle(model) { it.state.value.lastDecision != null }

        assertEquals(emptyList<MediaRef>(), trash.moves.flatten())
        assertEquals(listOf("/sdcard/a.mp4"), videoPaths(model))
        runBlocking { assertEquals(ReviewState.KEPT, app.index.reviewState("/sdcard/b.mp4")) }
    }

    @Test
    fun `a selection the user did not see does nothing`() {
        clip("/sdcard/a.mp4", 10)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openVideos()
        settle(model) { videoPaths(it).size == 1 }

        model.applyVideos(setOf("/sdcard/a.mp4"), trash = true) // nothing is selected

        settle(model) { true }
        assertEquals(emptyList<List<MediaRef>>(), trash.moves)
    }

    @Test
    fun `a video that became a favourite since the list was read is not trashed until the user has seen it`() {
        clip("/sdcard/a.mp4", 10)
        var favorites = emptySet<String>()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash, favorites = { favorites }))
        model.openVideos()
        settle(model) { videoPaths(it).size == 1 }
        model.toggleVideo("/sdcard/a.mp4")
        settle(model) { it.state.value.videoSelected.isNotEmpty() }
        favorites = setOf("/sdcard/a.mp4") // hearted in the gallery meanwhile

        model.applyVideos(setOf("/sdcard/a.mp4"), trash = true)
        settle(model) { it.state.value.favoriteAppeared }

        assertEquals(emptyList<List<MediaRef>>(), trash.moves)
        assertEquals(listOf(true), model.state.value.videos.map { it.isFavorite })
        assertEquals(setOf("/sdcard/a.mp4"), model.state.value.videoSelected) // the selection stays, so the next tap asks

        model.applyVideos(setOf("/sdcard/a.mp4"), trash = true) // now the screen's dialog has asked about the heart
        settle(model) { it.state.value.lastDecision != null }

        assertEquals(listOf("/sdcard/a.mp4"), trash.moves.single().map { it.path })
    }

    @Test
    fun `Clean opens on the tab the user used last`() {
        val model = ReviewViewModel(app)
        model.selectCleanTab(CleanTab.VIDEOS)
        settle(model) { it.state.value.cleanTab == CleanTab.VIDEOS }

        val reopened = ReviewViewModel(app)
        settle(reopened) { it.state.value.cleanTab == CleanTab.VIDEOS }
    }

    @Test
    fun `Back from largest videos returns to the home, with nothing selected`() {
        clip("/sdcard/a.mp4", 10)
        val model = ReviewViewModel(app)
        model.openVideos()
        settle(model) { videoPaths(it).size == 1 }
        model.toggleVideo("/sdcard/a.mp4")
        settle(model) { it.state.value.videoSelected.isNotEmpty() }

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }

        assertEquals(emptySet<String>(), model.state.value.videoSelected)
        assertEquals(Destination.Clean, model.state.value.screen.destination)
    }

    /** A view model on a page of [count] blurry images, whose favourite requests the user answers with [granted] and the gallery records. */
    private fun withFavorites(count: Int, favorites: MutableSet<String>, asked: MutableList<Pair<String, Boolean>>, granted: Boolean = true, trash: RecordingTrash = RecordingTrash()): ReviewViewModel {
        repeat(count) { blurry("/sdcard/a$it.jpg", 0.9f - it * 0.1f) }
        val review = Review(app.index, trash, favorites = { favorites.toSet() })
        val model = ReviewViewModel(app, review) { path, favorite ->
            asked += path to favorite
            if (granted) if (favorite) favorites += path else favorites -= path
            granted
        }
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == count }
        return model
    }

    private fun hearts(model: ReviewViewModel) = model.state.value.page.flatMap { it.images }.filter { it.isFavorite }.map { it.image.path }

    @Test
    fun `hearting a kept image keeps the page, its order and its marks, and shows the heart`() {
        val favorites = mutableSetOf<String>()
        val asked = mutableListOf<Pair<String, Boolean>>()
        val model = withFavorites(4, favorites, asked)
        val image = model.state.value.page[1].images.single().image
        model.toggle(image) // the user keeps it
        settle(model) { image.path !in it.state.value.marked }
        val before = model.state.value

        model.setFavorite(image, true)
        settle(model) { hearts(it).isNotEmpty() }

        assertEquals(listOf(image.path to true), asked)
        assertEquals(listOf(image.path), hearts(model))
        assertEquals(paths(model), before.page.flatMap { g -> g.images.map { it.image.path } })
        assertEquals(before.marked, model.state.value.marked)
        assertEquals(before.remaining, model.state.value.remaining)
        assertFalse(model.state.value.favoriteAppeared)
    }

    @Test
    fun `a favourite made in the review is no surprise at the apply, and trashing the rest asks nothing`() {
        val favorites = mutableSetOf<String>()
        val trash = RecordingTrash()
        val model = withFavorites(3, favorites, mutableListOf(), trash = trash)
        val image = model.state.value.page[0].images.single().image
        model.toggle(image)
        settle(model) { image.path !in it.state.value.marked }
        model.setFavorite(image, true)
        settle(model) { hearts(it).isNotEmpty() }

        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }

        assertFalse(model.state.value.favoriteAppeared)
        assertEquals(2, trash.moves.single().size)
    }

    @Test
    fun `a favourite removed in the review loses its heart on the page and nothing else changes`() {
        val image = "/sdcard/a0.jpg"
        val favorites = mutableSetOf(image)
        val asked = mutableListOf<Pair<String, Boolean>>()
        val model = withFavorites(3, favorites, asked)
        val marked = model.state.value.marked
        assertEquals(listOf(image), hearts(model))

        model.setFavorite(MediaRef(image, 1_000, 1L), false)
        settle(model) { hearts(it).isEmpty() }

        assertEquals(listOf(image to false), asked)
        assertEquals(marked, model.state.value.marked)
        assertEquals(3, paths(model).size)
    }

    @Test
    fun `a declined request changes nothing`() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val model = withFavorites(3, mutableSetOf(), asked, granted = false)
        val image = model.state.value.page[0].images.single().image
        model.toggle(image)
        settle(model) { image.path !in it.state.value.marked }
        val before = model.state.value

        model.setFavorite(image, true)
        settle(model) { asked.isNotEmpty() }

        assertEquals(before, model.state.value)
    }

    @Test
    fun `an image marked for the trash is not asked about`() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val model = withFavorites(2, mutableSetOf(), asked)
        val image = model.state.value.page[0].images.single().image
        assertTrue(image.path in model.state.value.marked)

        model.setFavorite(image, true)
        settle(model) { true }

        assertEquals(emptyList<Pair<String, Boolean>>(), asked)
        assertEquals(emptyList<String>(), hearts(model))
    }

    @Test
    fun `an image that became a favourite elsewhere while it is marked still asks at the apply`() {
        val favorites = mutableSetOf<String>()
        val trash = RecordingTrash()
        val model = withFavorites(2, favorites, mutableListOf(), trash = trash)
        favorites += "/sdcard/a0.jpg" // hearted in the gallery, not through the heart
        val (toTrash, toKeep) = confirm(model)

        model.applyPage(toTrash, toKeep)
        settle(model) { it.state.value.favoriteAppeared }

        assertEquals(emptyList<List<MediaRef>>(), trash.moves)
    }

    @Test
    fun `a favourite starts kept, so trashing the rest of the page needs no confirmation`() {
        blurry("/sdcard/a.jpg", 0.9f)
        blurry("/sdcard/b.jpg", 0.8f)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash, favorites = { setOf("/sdcard/a.jpg") }))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }

        assertEquals(setOf("/sdcard/b.jpg"), model.state.value.marked)
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }

        assertEquals(listOf("/sdcard/b.jpg"), trash.moves.single().map { it.path })
    }

    @Test
    fun `an apply for a page that is no longer shown does nothing`() {
        twoPages()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }

        model.applyPage(setOf("/sdcard/a0.jpg"), emptySet()) // not what the page holds
        settle(model) { true }

        assertEquals(emptyList<List<MediaRef>>(), trash.moves)
        assertNull(model.state.value.lastDecision)
        assertEquals(PAGE_SIZE, paths(model).size)
    }

    @Test
    fun `undo of a decision that is no longer the last one does nothing`() {
        twoPages()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val first = model.state.value.lastDecision!!
        applyShown(model)
        settle(model) { it.state.value.lastDecision != first }
        val second = model.state.value.lastDecision!!

        model.undo(first) // the snackbar of the first page, still on screen
        settle(model) { true }

        assertEquals(emptyList<List<MediaRef>>(), trash.restores)
        assertEquals(second, model.state.value.lastDecision)
        model.undo(second)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf(second.trashed), trash.restores)
    }

    @Test
    fun `a tap made while a page loads is kept`() {
        repeat(3) { blurry("/sdcard/a$it.jpg", 0.9f - it / 10f) }
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        slow.set(true)

        model.setReviewOrder(ReviewOrder.MONTH)
        shadowOf(Looper.getMainLooper()).idle()
        model.toggle(MediaRef("/sdcard/a0.jpg", 1_000, 1L)) // the page is still the old one on screen
        held.countDown()
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH && "/sdcard/a0.jpg" !in it.state.value.marked }

        assertEquals(setOf("/sdcard/a1.jpg", "/sdcard/a2.jpg"), model.state.value.marked)
    }

    @Test
    fun `skipping after an apply keeps the undo of that apply`() {
        twoPages(more = 2)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val applied = model.state.value.lastDecision

        model.skip(shownIds(model))
        settle(model) { paths(it).isEmpty() }

        assertEquals(applied, model.state.value.lastDecision)
        model.undo(applied!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf(applied.trashed), trash.restores)
    }

    /** A trash holding [held], whose restore and purge handle only the first image asked. */
    private class StubbornTrash(held: List<MediaRef>) : Trash {
        private val inside = held.toMutableList()

        override suspend fun moveToTrash(images: List<MediaRef>) = images.asItems()

        override suspend fun restore(images: List<MediaRef>) = images.take(1).also { inside.removeAll(it) }

        override suspend fun trashed() = inside.map { TrashedImage(it, Instant.EPOCH, File(it.path), Instant.EPOCH) }

        override suspend fun purge(images: List<MediaRef>) = images.take(1).also { inside.removeAll(it) }

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    /** A trash that remembers what it was asked to purge, and purges it. */
    private class PurgingTrash(held: List<MediaRef>) : Trash {
        private val inside = held.toMutableList()
        val purged = mutableListOf<List<MediaRef>>()

        override suspend fun moveToTrash(images: List<MediaRef>) = images.asItems()

        override suspend fun restore(images: List<MediaRef>) = images

        override suspend fun trashed() = inside.map { TrashedImage(it, Instant.EPOCH, File(it.path), Instant.EPOCH) }

        override suspend fun purge(images: List<MediaRef>) = images.also {
            purged += it
            inside.removeAll(it)
        }

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    @Test
    fun `restoring from the trash keeps only what came back and shows what is left`() {
        val one = MediaRef("/sdcard/one.jpg", 1_000, 1L)
        val two = MediaRef("/sdcard/two.jpg", 1_000, 1L)
        runBlocking {
            listOf(one, two).forEach {
                app.index.record(it)
                app.index.setReviewState(it.path, ReviewState.TRASHED)
            }
        }
        val model = ReviewViewModel(app, Review(app.index, StubbornTrash(listOf(one, two))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.select(model.state.value.trashed[0])
        model.select(model.state.value.trashed[1])

        model.restoreSelected()
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.trashed.map { it.original })
        assertEquals(setOf(two.path), model.state.value.selected) // what stayed in the trash stays selected
        assertEquals(listOf(two), model.state.value.stayedInTrash)
        runBlocking {
            assertEquals(ReviewState.KEPT, app.index.reviewState(one.path))
            assertEquals(ReviewState.TRASHED, app.index.reviewState(two.path))
        }
    }

    /** A trash holding [held], whose restore refuses the paths in [occupied], like a file that took the name. */
    private class OccupiedTrash(held: List<MediaRef>, var occupied: Set<String> = emptySet()) : Trash {
        private val inside = held.toMutableList()

        override suspend fun moveToTrash(images: List<MediaRef>) = images.also { inside += it }.asItems()

        override suspend fun restore(images: List<MediaRef>) = images.filter { it.path !in occupied }.also { inside.removeAll(it) }

        override suspend fun trashed() = inside.map { TrashedImage(it, Instant.EPOCH, File(it.path), Instant.EPOCH) }

        override suspend fun purge(images: List<MediaRef>) = images.also { inside.removeAll(it) }

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    @Test
    fun `restoring an image whose path is taken keeps it selected and says it stayed`() {
        val one = MediaRef("/sdcard/one.jpg", 1_000, 1L)
        val two = MediaRef("/sdcard/two.jpg", 1_000, 1L)
        runBlocking {
            listOf(one, two).forEach {
                app.index.record(it)
                app.index.setReviewState(it.path, ReviewState.TRASHED)
            }
        }
        val model = ReviewViewModel(app, Review(app.index, OccupiedTrash(listOf(one, two), occupied = setOf(two.path))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.selectAll(model.state.value.trashed, true)
        settle(model) { it.state.value.selected.size == 2 }

        model.restoreSelected()
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.stayedInTrash)
        assertEquals(setOf(two.path), model.state.value.selected)

        model.dismissStayedInTrash(listOf(two))
        settle(model) { it.state.value.stayedInTrash.isEmpty() }
    }

    @Test
    fun `restoring an item whose path is on another volume says so`() {
        val phone = folder.newFolder("phone")
        val card = folder.newFolder("card")
        val trash = AppTrash({ listOf(phone, card) })
        val image = File(phone, "a.jpg").apply { writeText("0123456789") }.toMediaRef()
        runBlocking { trash.moveToTrash(listOf(image)) }
        // A trash folder copied by hand from the card: its record names the card.
        val origin = File(runBlocking { trash.trashed() }.single().file.parentFile, "origin.properties")
        val props = java.util.Properties().apply { origin.inputStream().use(::load) }
        val elsewhere = image.copy(path = File(card, "a.jpg").path)
        props["path"] = elsewhere.path
        origin.outputStream().use { props.store(it, null) }
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 1 }
        model.selectAll(model.state.value.trashed, true)
        settle(model) { it.state.value.selected.size == 1 }

        model.restoreSelected()
        settle(model) { it.state.value.stayedInTrash.isNotEmpty() }

        assertEquals(listOf(elsewhere), model.state.value.stayedInTrash)
        assertEquals(listOf(elsewhere), model.state.value.stayedElsewhere)
    }

    @Test
    fun `undo of a page whose file now has another file at its path says it stayed in the trash`() {
        twoPages(more = 0)
        val trash = OccupiedTrash(emptyList())
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val decision = model.state.value.lastDecision!!
        val taken = decision.trashed.first()
        trash.occupied = setOf(taken.path)

        model.undo(decision)
        settle(model) { it.state.value.lastDecision == null }

        assertEquals(listOf(taken), model.state.value.stayedInTrash)
        runBlocking {
            assertEquals(ReviewState.TRASHED, app.index.reviewState(taken.path))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(decision.trashed.last().path))
        }
    }

    @Test
    fun `restoring from the trash with an empty index brings the file back and does not crash`() {
        val volume = folder.newFolder()
        val trash = AppTrash({ listOf(volume) })
        val image = File(volume, "a.jpg").apply { writeText("0123456789") }.toMediaRef()
        runBlocking { trash.moveToTrash(listOf(image)) } // the trash survived a reinstall, the index did not
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 1 }
        model.select(model.state.value.trashed.single())
        settle(model) { it.state.value.selected.size == 1 }

        model.restoreSelected()
        settle(model) { it.state.value.trashed.isEmpty() }

        assertTrue(File(image.path).exists())
        assertEquals(false, model.state.value.changeFailed)
        runBlocking { assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(image.path)) }
    }

    /** A trash that cannot be read, like a volume that went away. */
    private class BrokenTrash : Trash {
        override suspend fun moveToTrash(images: List<MediaRef>) = images.asItems()

        override suspend fun restore(images: List<MediaRef>) = images

        override suspend fun trashed(): List<TrashedImage> = throw IOException("volume gone")

        override suspend fun purge(images: List<MediaRef>) = images

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    @Test
    fun `a change that fails stops the loading, reports an error and leaves the screen as it was`() {
        val model = ReviewViewModel(app, Review(app.index, BrokenTrash()))
        settle(model) { true }

        model.openTrash()
        settle(model) { it.state.value.changeFailed }

        assertEquals(false, model.state.value.loading)
        assertEquals(Screen.Home, model.state.value.screen)

        model.back() // later changes still run
        model.dismissChangeFailed()
        settle(model) { !it.state.value.changeFailed }
    }

    @Test
    fun `emptying the trash shows what the trash still holds`() {
        val one = MediaRef("/sdcard/one.jpg", 1_000, 1L)
        val two = MediaRef("/sdcard/two.jpg", 1_000, 1L)
        val model = ReviewViewModel(app, Review(app.index, StubbornTrash(listOf(one, two))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.select(model.state.value.trashed[0])

        model.deleteFromTrash(setOf(one.path, two.path))
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.trashed.map { it.original })
        assertTrue(model.state.value.selected.isEmpty())
    }

    /** A trash on a folder of its own, whose clock the test moves, and a page of one blurry photo, [name], ready to apply. */
    private inner class FreeNowWorld {
        val volume = folder.newFolder()
        var now: Instant = Instant.parse("2026-11-15T10:00:00Z")
        val trash = AppTrash(
            { listOf(volume) },
            clock = object : java.time.Clock() {
                override fun instant() = now
                override fun getZone() = java.time.ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId) = this
            },
        )
        val review = Review(app.index, trash)
        val model = ReviewViewModel(app, review)

        fun file(name: String) = File(volume, name).apply {
            writeText("0123456789")
            setLastModified(1_700_000_000_000)
        }.toMediaRef()

        fun blurry(name: String): MediaRef = file(name).also {
            runBlocking {
                app.index.record(it)
                app.index.setReviewState(it.path, ReviewState.UNREVIEWED) // the same file may have been trashed before
                app.index.setSignals(it.path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.9f)))
            }
        }

        /** Trashes [ref] outside the review, as an earlier page would have. */
        fun trashedBefore(ref: MediaRef) = runBlocking {
            app.index.record(ref)
            trash.moveToTrash(listOf(ref))
            app.index.setReviewState(ref.path, ReviewState.TRASHED)
        }

        fun applyPage(): Decision {
            model.open(Category.BLURRY)
            settle(model) { paths(it).isNotEmpty() }
            applyShown(model)
            settle(model) { it.state.value.lastDecision != null }
            return model.state.value.lastDecision!!
        }

        fun inTrash() = runBlocking { trash.trashed() }
    }

    @Test
    fun `freeing the space of a page deletes for good only what the page trashed, and ends its undo`() {
        val world = FreeNowWorld()
        val older = world.file("older.jpg").also { world.trashedBefore(it) }
        val blurred = world.blurry("blurred.jpg")
        val decision = world.applyPage()

        world.model.askFreeNow(decision)
        settle(world.model) { it.state.value.freeNow != null }
        assertEquals(decision.items, world.model.state.value.freeNow!!.items)
        world.model.confirmFreeNow()
        settle(world.model) { it.state.value.lastDecision == null && it.state.value.freeNow == null }

        assertEquals(listOf(older), world.inTrash().map { it.original })
        assertFalse(File(blurred.path).exists())
        assertEquals(mapOf(older.path to ReviewState.TRASHED), runBlocking { app.index.reviewStates() })
    }

    @Test
    fun `after the page's file was restored, free now offers nothing and never deletes an equal item trashed later`() {
        val world = FreeNowWorld()
        val photo = world.blurry("again.jpg")
        val decision = world.applyPage()
        // A sync app puts the same file back, with its size and mtime, and it is trashed again an hour later.
        world.now += Duration.ofHours(1)
        world.trashedBefore(world.file("again.jpg"))
        val later = world.inTrash().first()
        // The restore takes the page's item, the older of the two.
        runBlocking { world.review.restore(listOf(photo)) }
        assertEquals(listOf(later), world.inTrash())

        world.model.askFreeNow(decision)
        settle(world.model) { true }
        assertNull(world.model.state.value.freeNow)
        world.model.confirmFreeNow()
        settle(world.model) { true }

        assertEquals(listOf(later), world.inTrash())
    }

    @Test
    fun `a second confirm of free now deletes nothing more, not even an equal item`() {
        val world = FreeNowWorld()
        val earlier = world.file("twice.jpg").also { world.trashedBefore(it) }
        world.now += Duration.ofHours(1)
        world.blurry("twice.jpg")
        val decision = world.applyPage()

        world.model.askFreeNow(decision)
        settle(world.model) { it.state.value.freeNow != null }
        world.model.confirmFreeNow()
        world.model.confirmFreeNow() // a second tap before the dialog leaves
        settle(world.model) { it.state.value.freeNow == null }

        assertEquals(listOf(earlier), world.inTrash().map { it.original })
        assertEquals(1, world.inTrash().size)
    }

    @Test
    fun `free now confirmed on the trash screen takes the freed items out of the selection`() {
        val world = FreeNowWorld()
        world.blurry("selected.jpg")
        val decision = world.applyPage()
        world.model.openTrash()
        settle(world.model) { it.state.value.trashed.size == 1 }
        world.model.select(world.model.state.value.trashed.single())
        settle(world.model) { it.state.value.selected.isNotEmpty() }

        world.model.askFreeNow(decision)
        settle(world.model) { it.state.value.freeNow != null }
        world.model.confirmFreeNow()
        settle(world.model) { it.state.value.freeNow == null }

        assertTrue(world.model.state.value.trashed.isEmpty())
        assertTrue(world.model.state.value.selected.isEmpty())
    }

    @Test
    fun `deleting the selection purges only the selected images, through the trash`() {
        val images = listOf("one", "two", "three").map { MediaRef("/sdcard/$it.jpg", 1_000, 1L) }
        val trash = PurgingTrash(images)
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 3 }
        model.select(model.state.value.trashed[1])

        model.deleteFromTrash(setOf(images[1].path))
        settle(model) { it.state.value.trashed.size == 2 }

        assertEquals(listOf(listOf(images[1])), trash.purged)
        assertEquals(listOf(images[0], images[2]), model.state.value.trashed.map { it.original })
        assertTrue(model.state.value.selected.isEmpty())
    }

    @Test
    fun `deleting one of two items with the same path purges only that one`() {
        val original = MediaRef("/sdcard/a.jpg", 9_000, 1L)
        val copy = MediaRef("/sdcard/a.jpg", 4_000, 2L)
        val trash = ItemTrash(listOf(original, copy))
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        val first = model.state.value.trashed[0]
        model.select(first)
        settle(model) { it.state.value.selected == setOf(first.file.path) }

        model.deleteFromTrash(model.state.value.selected)
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(listOf(original)), trash.purged)
        assertEquals(listOf(copy), model.state.value.trashed.map { it.original })
    }

    /** A trash whose items each have a file of their own, as the real one does, so two may share an original path. */
    private class ItemTrash(held: List<MediaRef>) : Trash {
        private val inside = held.mapIndexed { i, image -> TrashedImage(image, Instant.EPOCH, File("/trash/$i/${File(image.path).name}"), Instant.EPOCH) }.toMutableList()
        val purged = mutableListOf<List<MediaRef>>()

        override suspend fun moveToTrash(images: List<MediaRef>) = images.asItems()

        override suspend fun restore(images: List<MediaRef>) = images

        override suspend fun trashed() = inside.toList()

        override suspend fun purge(images: List<MediaRef>) = images.also {
            purged += it
            for (image in it) inside.remove(inside.first { item -> item.original == image })
        }

        override suspend fun purgeItems(files: Collection<File>) = emptyList<MediaRef>()

        override suspend fun purgeExpired() = ExpiredPurge.NONE
    }

    @Test
    fun `back from the trash returns to the destination it was opened from`() {
        val model = ReviewViewModel(app)
        for ((destination, screen) in listOf(Destination.Settings to Screen.Settings, Destination.Compress to Screen.Compress, Destination.Clean to Screen.Home)) {
            model.selectDestination(destination)
            settle(model) { it.state.value.screen == screen }
            model.openTrash()
            settle(model) { it.state.value.screen == Screen.Trash(destination) }

            model.back()
            settle(model) { it.state.value.screen == screen }
        }
    }

    @Test
    fun `a settings reload queued behind Back does not pull the user back to settings`() {
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        slow.set(true)

        model.back() // waits for the overview
        shadowOf(Looper.getMainLooper()).idle()
        model.reloadSettings() // a scan changed phase meanwhile
        held.countDown()
        settle(model) { it.state.value.screen == Screen.Home }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Screen.Home, model.state.value.screen)
    }

    @Test
    fun `the old snackbar timing out during the next apply keeps the undo of that apply`() {
        twoPages(more = PAGE_SIZE + 1)
        var gate: CompletableDeferred<Unit>? = null
        val trash = object : Trash by RecordingTrash() {
            override suspend fun moveToTrash(images: List<MediaRef>): List<TrashedImage> {
                gate?.await()
                return images.asItems()
            }
        }
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val first = model.state.value.lastDecision!!
        gate = CompletableDeferred()

        applyShown(model)
        model.dismissUndo(first) // the first snackbar times out while the second apply runs
        gate!!.complete(Unit)
        settle(model) { it.state.value.lastDecision != first }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(model.state.value.lastDecision)
    }

    @Test
    fun `the old exclusion snackbar timing out during the next exclusion keeps the undo of that one`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/B/b.jpg", 0.8f)
        blurry("/sdcard/C/c.jpg", 0.7f)
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        model.excludeFolder(MediaRef("/sdcard/A/a.jpg", 1_000, 1L))
        settle(model) { it.state.value.lastExclusion == "/sdcard/A" }
        slow.set(true)

        model.excludeFolder(MediaRef("/sdcard/B/b.jpg", 1_000, 1L)) // waits in the page read
        shadowOf(Looper.getMainLooper()).idle()
        model.dismissExclusion("/sdcard/A") // the first snackbar times out meanwhile
        held.countDown()
        settle(model) { it.state.value.lastExclusion != "/sdcard/A" }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("/sdcard/B", model.state.value.lastExclusion)
    }

    @Test
    fun `Back from a trash opened on Compress returns to Compress, and from Clean to the home`() {
        val model = ReviewViewModel(app)

        model.selectDestination(Destination.Compress)
        settle(model) { it.state.value.screen == Screen.Compress }
        model.openTrash()
        settle(model) { it.state.value.screen == Screen.Trash(Destination.Compress) }
        assertEquals(Destination.Compress, model.state.value.screen.destination)
        model.back()
        settle(model) { it.state.value.screen == Screen.Compress }
        assertEquals(Destination.Compress, model.state.value.screen.destination)

        model.selectDestination(Destination.Clean)
        settle(model) { it.state.value.screen == Screen.Home }
        model.openTrash()
        settle(model) { it.state.value.screen == Screen.Trash(Destination.Clean) }
        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
    }

    @Test
    fun `a thresholds reload queued behind another screen does not pull the user back to thresholds`() {
        val release = CompletableDeferred<Unit>()
        val model = ReviewViewModel(app, Review(app.index, SlowTrash(release)))
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }

        model.openTrash() // waits for the trash listing
        shadowOf(Looper.getMainLooper()).idle()
        model.reloadThresholds() // a scan changed phase meanwhile
        release.complete(Unit)
        settle(model) { it.state.value.screen == Screen.Trash(Destination.Settings) }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Screen.Trash(Destination.Settings), model.state.value.screen)
    }
}

/** Trash items for [this], for a fake trash that moves nothing: a file name per path, in no real folder. */
private fun List<MediaRef>.asItems() = map { TrashedImage(it, Instant.EPOCH, File("/trash/${it.path}"), Instant.EPOCH) }
