package io.github.bert0ns.cribrum.ui.gallery

import android.text.format.Formatter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.compress.Marker
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.metadata.VideoMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.pipeline.Failure
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress
import io.github.bert0ns.cribrum.pipeline.Stats
import io.github.bert0ns.cribrum.pipeline.VIDEO_METADATA_STEP
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import io.github.bert0ns.cribrum.ui.ReviewViewModel
import io.github.bert0ns.cribrum.ui.Screen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration

/** Read before the list: what a reviewer needs to know about how the images were made. */
const val NOTES = "Notes: every picture is drawn in code and the numbers are made up. " +
    "Paths in the full size view and on the map start with a temporary folder of the test, not the phone's, and its name changes at each image. " +
    "The test window has no status bar or gesture bar, so the top bar starts at the top edge. " +
    "A dialog sits at the top edge and is as wide as the screen, where the phone centers it with a margin on each side. " +
    "The dark images are the app in night mode, with its dark color scheme."

private fun Gallery.awaitModel(model: ReviewViewModel, what: String, condition: (ReviewViewModel) -> Boolean) = await(what) { !model.state.value.loading && condition(model) }

private fun Gallery.awaitHome(model: ReviewViewModel) = awaitModel(model, "home") { it.state.value.screen == Screen.Home && it.state.value.categories.isNotEmpty() }

private fun Gallery.openCategory(model: ReviewViewModel, name: Int) {
    awaitHome(model)
    click(str(name))
    awaitModel(model, "a page") { it.state.value.screen is Screen.Category && it.state.value.page.isNotEmpty() }
    awaitText(applyText(model))
}

/** The text of the button under the grid: how many images the page trashes and how many candidates it keeps. */
private fun Gallery.applyText(model: ReviewViewModel): String {
    val state = model.state.value
    return str(R.string.apply, state.marked.size, state.page.sumOf { group -> group.images.count { it.suggestTrash && it.image.path !in state.marked } })
}

private fun Gallery.goTo(model: ReviewViewModel, destination: Int, ready: (ReviewViewModel) -> Boolean) {
    awaitHome(model)
    click(str(destination))
    awaitModel(model, "the destination", ready)
}

/** The map opens from Settings, which it belongs to. */
private fun Gallery.openMap(model: ReviewViewModel, ready: (ReviewViewModel) -> Boolean) {
    goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings }
    scrollTo(str(R.string.storage_map))
    click(str(R.string.storage_map))
    awaitModel(model, "the map", ready)
}

/** A library of videos for "Largest videos": from 1.8 GB down, one that cannot be read and one the gallery has hearted. Returns the one hearted and the unreadable one. */
private fun Gallery.videoLibrary(): Pair<MediaRef, MediaRef> {
    val clips = listOf(
        Triple("VID_20261019_201512.mp4", 1_820_000_000L, 612_000L),
        Triple("VID_20261011_182240.mp4", 1_460_000_000L, 484_000L),
        Triple("VID_20260926_153008.mp4", 1_110_000_000L, 356_000L),
        Triple("VID_20261024_090511.mp4", 960_000_000L, 301_000L),
        Triple("VID_20261003_214437.mp4", 640_000_000L, 205_000L),
        Triple("VID_20261014_121903.mp4", 410_000_000L, 131_000L),
        Triple("VID_20260921_170826.mp4", 255_000_000L, 82_000L),
        Triple("VID_20261030_193345.mp4", 120_000_000L, 38_000L),
        Triple("VID_20260915_111207.mp4", 64_000_000L, 21_000L),
        Triple("VID_20261001_080452.mp4", 38_000_000L, 12_000L),
    )
    val refs = clips.mapIndexed { index, (name, bytes, millis) ->
        val folder = if (index % 3 == 1) "WhatsApp/Media/WhatsApp Video" else CAMERA
        val ref = world.video(folder, name, bytes, taken(index))
        // The fifth cannot be read: it has no frame and no length.
        if (index != 4) world.show(ref.path, millis, GalleryPictures.scene(7_000 + index))
        ref
    }
    world.commit()
    runBlocking { app.index.setUnreadable(refs[4].path) }
    world.favorites += refs[1].path
    return refs[1] to refs[4]
}

/** Short camera clips and clips in a chat folder, flagged as the video detectors flag them, for the Videos tab's categories. */
private fun Gallery.clipsLibrary() {
    val clips = listOf(
        Triple("VID_20261030_193345.mp4", 41_000_000L, 4_200L),
        Triple("VID_20261029_101822.mp4", 27_000_000L, 3_100L),
        Triple("VID_20261027_164410.mp4", 66_000_000L, 7_800L),
        Triple("VID_20261024_090511.mp4", 18_000_000L, 2_400L),
        Triple("VID_20261022_201407.mp4", 93_000_000L, 9_600L),
        Triple("VID_20261019_121903.mp4", 52_000_000L, 5_900L),
        Triple("VID_20261016_183005.mp4", 31_000_000L, 3_700L),
        Triple("VID_20261012_075540.mp4", 74_000_000L, 8_300L),
        Triple("VID_20261008_142251.mp4", 22_000_000L, 2_800L),
    )
    val lengths = mutableMapOf<String, Long>()
    clips.forEachIndexed { index, (name, bytes, millis) ->
        val score = 0.4f + (index % 4) * 0.12f
        val signal = world.signal(Category.SHORT_CLIPS, score, "duration_ms" to millis.toString(), "duration_chance" to "0.400", "photo_chance" to if (index % 3 == 0) "0.500" else "0.000")
        val ref = world.video(CAMERA, name, bytes, taken(index), signal)
        world.show(ref.path, millis, GalleryPictures.scene(7_100 + index))
        lengths[ref.path] = millis
    }
    for (index in 0 until 3) {
        val ref = world.video("WhatsApp/Media/WhatsApp Video", "VID-2026101$index-WA000${index + 3}.mp4", 24_000_000L + index * 61_000_000L, taken(index + 3), world.signal(Category.FORWARDED, 1f, "messaging_folder" to "yes"))
        world.show(ref.path, 18_000L + index * 41_000L, GalleryPictures.scene(7_200 + index))
        lengths[ref.path] = 18_000L + index * 41_000L
    }
    // A camera original and WhatsApp's re-encoded copy in Sent: the same length, a tenth of the size.
    val original = world.video(CAMERA, "VID_20261021_171233.mp4", 310_000_000L, taken(2))
    val sent = world.video("WhatsApp/Media/WhatsApp Video/Sent", "VID-20261021-WA0011.mp4", 31_000_000L, taken(2), world.signal(Category.FORWARDED, 1f, "messaging_folder" to "yes"))
    world.group("sent-copy", Category.NEAR_DUPLICATE, original, listOf(sent to 1f))
    world.show(original.path, 28_400L, GalleryPictures.scene(7_300))
    world.show(sent.path, 28_400L, GalleryPictures.scene(7_300))
    lengths[original.path] = 28_400L
    lengths[sent.path] = 28_400L
    // Three takes of one moment, filmed one after the other: no best take, so every take scores 1.
    for (index in 0 until 3) {
        val take = world.video(CAMERA, "VID_20261018_1932${index}0.mp4", 140_000_000L + index * 45_000_000L, taken(5), world.signal(Category.TAKES, 1f, GROUP_KEY to "takes-1", ROLE_KEY to Role.TAKE.value))
        world.show(take.path, 21_000L + index * 6_000L, GalleryPictures.scene(7_400 + index))
        lengths[take.path] = 21_000L + index * 6_000L
    }
    world.commit()
    // The video steps have run: each video has its length, so tiles show it and none waits for a scan.
    runBlocking {
        for ((path, millis) in lengths) app.index.setVideoMetadata(path, VideoMetadata(1920, 1080, millis, 30.0, 8_000_000L, true, "video/avc", false))
    }
    app.videoScans.ran = true
}

/** Opens "Largest videos" from the Videos tab of the home. */
private fun Gallery.openVideos(model: ReviewViewModel) {
    awaitHome(model)
    click(str(R.string.tab_videos))
    click(str(R.string.videos_largest))
    awaitModel(model, "the videos") { it.state.value.screen == Screen.Videos && it.state.value.videos.isNotEmpty() }
    await("the tiles") { described("VID_").fetchSemanticsNodes().isNotEmpty() }
}

private fun Gallery.openTrash(model: ReviewViewModel) {
    awaitHome(model)
    clickDescription(str(R.string.trash))
    awaitModel(model, "the trash") { it.state.value.screen is Screen.Trash }
}

/** Keeps the tiles at [positions] of the marked ones, from the first, one after the other. */
private fun Gallery.keep(vararg positions: Int) {
    for (position in positions) described(str(R.string.selected_for_trash))[position].performClick()
}

/** Taps the button under the grid with a favourite marked, which asks first. */
private fun Gallery.applyWithFavorite(model: ReviewViewModel) {
    val trash = model.state.value.marked.size
    click(applyText(model))
    awaitText(plural(R.plurals.summary_title, trash, trash))
}

/** Taps the button under the grid, which applies the page at once (D6). */
private fun Gallery.confirmApply(model: ReviewViewModel) {
    click(applyText(model))
    awaitText(str(R.string.undo))
}

/** A camera roll for the Compress page: from a 200 MP shot to ordinary phone photos, and three that were compressed before. */
private fun Gallery.cameraRoll(): CompressSetup {
    val eligible = JpegInfo(JpegStatus.ELIGIBLE, quality = 92)
    fun marked(level: String) = JpegInfo(JpegStatus.ELIGIBLE, quality = 85, marker = Marker(1, level, "2026-10-21T20:04:00Z"))
    val photos = listOf(
        world.compressible("IMG_20261019_181144.jpg", 11, 24.1, 16320, 12240, JpegInfo(JpegStatus.ELIGIBLE, quality = 94, losses = GalleryWorld.reEdited)),
        world.compressible("IMG_20261011_120533.jpg", 12, 15.2, 8160, 6120, JpegInfo(JpegStatus.ELIGIBLE, quality = 93)),
        world.compressible("IMG_20260927_093208.jpg", 13, 14.7, 8160, 6120, JpegInfo(JpegStatus.ELIGIBLE, quality = 93, losses = Losses(xmp = true))),
        world.compressible("IMG_20261024_201457.jpg", 14, 9.8, 4624, 3472, eligible),
        world.compressible("IMG_20261003_150911.jpg", 15, 7.1, 4000, 3000, eligible),
        world.compressible("IMG_20261008_112340.jpg", 16, 6.8, 4000, 3000, eligible),
        world.compressible("IMG_20260921_174502.jpg", 17, 6.2, 4000, 3000, eligible),
        world.compressible("IMG_20261014_183422.jpg", 18, 5.9, 4000, 3000, eligible),
        world.compressible("IMG_20261030_082219.jpg", 19, 5.4, 4000, 3000, eligible),
        world.compressible("IMG_20260915_134008.jpg", 20, 4.8, 4000, 3000, eligible),
        world.compressible("IMG_20261001_192756.jpg", 21, 4.4, 4000, 3000, eligible),
        world.compressible("IMG_20260929_101133.jpg", 22, 4.1, 4000, 3000, eligible),
        world.compressible("IMG_20260918_163845.jpg", 23, 3.9, 4000, 3000, eligible),
        world.compressible("IMG_20261021_194201.jpg", 24, 3.1, 4000, 3000, marked("balanced")),
        world.compressible("IMG_20261021_194230.jpg", 25, 2.8, 4000, 3000, marked("high")),
        world.compressible("IMG_20261021_194302.jpg", 26, 1.4, 3000, 2250, marked("smallest")),
    )
    return CompressSetup(photos, favorites = setOf(photos[1].path, photos[7].path))
}

private fun Gallery.openCompress(model: ReviewViewModel, setup: CompressSetup) {
    goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
    if (setup.photos.isEmpty()) return
    awaitText(plural(R.plurals.compress_photos_heading, setup.photos.count { it.info.marker == null }, setup.photos.count { it.info.marker == null }))
    val first = setup.photos.firstOrNull { it.info.marker == null } ?: return
    await("the photos") { described(File(first.path).name).fetchSemanticsNodes().isNotEmpty() }
}

private fun Gallery.select(setup: CompressSetup, vararg positions: Int) {
    for (position in positions) described(File(setup.photos[position].path).name)[0].performClick()
}

private fun Gallery.summary(setup: CompressSetup, model: ReviewViewModel) {
    openCompress(model, setup)
    select(setup, 0, 1, 3)
    click(plural(R.plurals.compress_continue, 3, 3))
    awaitText(plural(R.plurals.compress_summary_title, 3, 3))
}

private fun Gallery.scanRunning(step: String, done: Int, total: Int, leftMs: Long? = null) {
    RunningScanWorker.progress = ScanProgress(step, done, total, leftMs)
    Indexing.scanNow(app)
    awaitNoText(str(R.string.scan_now))
}

/** 03:12 this morning, when the nightly scan ends. */
private fun Gallery.lastNight(): Long {
    val zdt = world.clock.now.atZone(java.time.ZoneId.systemDefault())
    return java.util.Calendar.getInstance().apply {
        set(zdt.year, zdt.monthValue - 1, zdt.dayOfMonth, 3, 12, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun Gallery.settings(model: ReviewViewModel) {
    world.library(others = true)
    world.commit()
    runBlocking {
        app.index.excludeFolder("/sdcard/Pictures/Memes")
        app.index.excludeFolder("/sdcard/WhatsApp/Media/WhatsApp Video")
    }
    app.lastScan.record(
        Stats(images = 2_350, newOrChanged = 18, unreadable = 2, unreadablePaths = listOf("/sdcard/Download/broken.jpg", "/sdcard/Pictures/old.heic"))
            .also { it.failures += Failure("/sdcard/DCIM/Camera/IMG_20261014_183422.jpg", "blur", "OutOfMemoryError: bitmap too large") },
        finishedAt = world.clock.now.toEpochMilli() - 5 * 3_600_000L,
        durationMs = 412_000,
    )
    goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings && it.state.value.coverage != null && it.state.value.storage != null }
}

val scenes: List<Scene> = listOf(
    // Clean
    Scene("clean-results", "Clean, the home with a finished scan", "See how many images each category lists and how much they can free, and when the last scan ran. Open a category, scan again, open the trash from the top bar, or use the bar to go to Compress or Settings.", Variant.full) {
        world.library()
        world.commit()
        app.lastScan.record(Stats(images = 2_350), finishedAt = lastNight(), durationMs = 412_000)
        val model = app()
        awaitHome(model)
        awaitText(str(R.string.scan_last, ""), substring = true)
    },
    Scene("clean-first-run", "Clean, first run with nothing scanned", "Start the first scan with Scan now. The categories are empty until it ends.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        awaitModel(model, "an empty index") { it.state.value.indexEmpty }
        awaitText(str(R.string.scan_empty))
    },
    Scene("clean-scanning", "Clean, a scan running in its detector phase", "Watch the phase, the count and the time left. Stop the scan, open a category that already has candidates, or leave the app while the scan goes on.", Variant.wide) {
        world.library()
        world.commit()
        val model = app()
        awaitHome(model)
        scanRunning("detectors", 4_320, 10_220, 11 * 60_000L)
    },
    Scene("clean-scanning-first", "Clean, the first scan still finding files", "Watch the count of images found so far. Nothing can be reviewed yet.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        awaitModel(model, "an empty index") { it.state.value.indexEmpty }
        scanRunning("scan", 1_230, 0)
    },
    // Review
    Scene("review-near-duplicates", "Review of Similar copies, groups with their best copy", "Compare each best copy with its copies, tap a photo to flip its mark, hold one to see it full size, trash the marked ones or skip the page.", Variant.base) {
        world.library(pictures = setOf(Category.NEAR_DUPLICATE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_near_duplicate)
        keep(1)
    },
    Scene("review-blurry", "Review of Blurry photos, three kept", "Tap a photo to keep it or mark it for the trash, hold one to see it full size, trash the marked ones or skip the page.", Variant.full) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        keep(2, 5, 8)
    },
    Scene("review-screenshots", "Review of Screenshots", "Tap a screenshot to keep it or mark it for the trash, hold one to see it full size, trash the marked ones or skip the page.", Variant.base) {
        world.library(pictures = setOf(Category.SCREENSHOT))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_screenshot)
    },
    Scene("review-order-menu", "Review with the date order menu open", "Choose how the page is ordered: off, by day, by week, by month or largest first.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        compose.onNodeWithContentDescription(str(R.string.review_order)).performClick()
        awaitText(str(R.string.review_order_week))
    },
    Scene("review-by-day", "Review of Blurry photos grouped by day", "Read the photos under their dates, newest first, and review them as usual.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        compose.onNodeWithContentDescription(str(R.string.review_order)).performClick()
        click(str(R.string.review_order_day))
        awaitModel(model, "the day order") { it.state.value.reviewOrder == ReviewOrder.DAY && it.state.value.page.isNotEmpty() }
    },
    Scene("review-full-size", "Review, a photo at full size with why it is a candidate", "Swipe to the next photo, pinch to zoom, tick or untick the checkbox that marks it for the trash, find the heart, which is off while the photo is marked, read its folder, never suggest the folder again, or close the view.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        val itemPath = model.state.value.page.flatMap { it.images }[1].image.path
        val targetFile = File(itemPath)
        val large = GalleryPictures.scene(1_001, width = 1080, height = 810)
        GalleryPictures.write(targetFile, large, targetFile.length())
        described(str(R.string.selected_for_trash))[1].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("review-full-size-favorite", "Review, a favourite at full size with its heart on", "Tap the heart to take the photo out of the gallery favourites, or swipe to a photo that is not one and heart it. The system asks first.", Variant.base) {
        world.library(pictures = setOf(Category.BAD_EXPOSURE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_bad_exposure)
        scrollToEnd()
        described(str(R.string.favorite))[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        await(str(R.string.favorite_remove)) { described(str(R.string.favorite_remove)).fetchSemanticsNodes().isNotEmpty() }
    },
    Scene("review-favorites", "Review of Too dark or too bright photos, favourites kept at the end", "Find the gallery favourites, which start kept with a heart, and decide whether to trash them anyway.", Variant.base) {
        world.library(pictures = setOf(Category.BAD_EXPOSURE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_bad_exposure)
        scrollToEnd()
    },
    Scene("review-summary-favorite", "Review, the confirmation when a favourite is marked", "Read the warning that a favourite goes to the trash, then confirm with the stronger button or cancel.", Variant.base) {
        world.library(pictures = setOf(Category.BAD_EXPOSURE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_bad_exposure)
        scrollToEnd()
        described(str(R.string.favorite))[0].performClick()
        applyWithFavorite(model)
    },
    Scene("review-snackbar-undo", "Review, the next page with the Undo snackbar right after tapping the trash button", "Read how many images went to the trash and how much space they take, undo the last page from the snackbar, or go on with the next page.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        confirmApply(model)
    },
    Scene("review-snackbar-free-now", "Review, the snackbar after a page that trashed more than 1 GB", "Read how much went to the trash, free that space now or undo the page. The photos here are made larger than real ones. A page of videos reaches 1 GB much sooner, as videos-largest-snackbar-free-now shows.", Variant.base) {
        bigPage(world)
        val model = app()
        openCategory(model, R.string.category_blurry)
        confirmApply(model)
        awaitText(freeNowLabel(model))
    },
    Scene("review-free-now-confirm", "Review, the confirmation before freeing the space of the last page", "Read how many files go for good, that only the ones just trashed go and that they cannot be restored, then delete them or cancel.", Variant.base) {
        bigPage(world)
        val model = app()
        openCategory(model, R.string.category_blurry)
        confirmApply(model)
        click(freeNowLabel(model))
        val count = model.state.value.lastDecision!!.trashed.size
        awaitText(plural(R.plurals.delete_files_title, count, count))
    },
    Scene("review-favorite-appeared", "Review, the page back after a photo on it became a favourite", "Read why nothing went to the trash, find the new heart, then trash the page again or keep the favourite.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        world.favorites += model.state.value.marked.first() // hearted in the gallery while the page was open
        click(applyText(model))
        awaitText(str(R.string.favorite_appeared_snackbar))
    },
    Scene("review-excluded-snackbar", "Review, after excluding a photo's folder", "Undo the exclusion from the snackbar. The photos of that folder left the page.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        described(str(R.string.selected_for_trash))[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        click(str(R.string.exclude_folder))
        awaitText(str(R.string.undo))
    },
    // Videos
    Scene("videos-tab", "Clean, the Videos tab", "See how many videos Scan videos has not analysed yet, how many wait for a review and how much space they can free. Start Scan videos, or open the largest videos. The Photos tab is one tap away, and the tab used last opens first.", Variant.wide) {
        world.library()
        videoLibrary()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        awaitText(str(R.string.videos_largest))
    },
    Scene("videos-tab-categories", "Clean, the Videos tab with its categories", "See the video categories next to Largest videos, each with how many videos it lists and how much space they take. Open one, or start Scan videos.", Variant.base) {
        clipsLibrary()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        awaitText(str(R.string.category_short_clips))
    },
    Scene("review-short-clips", "Review of Short clips, a page of tiles with a frame, the length and the size", "Tap a clip to keep it or mark it for the trash, hold one to see it full size, trash the marked ones or skip the page.", Variant.base) {
        clipsLibrary()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        click(str(R.string.category_short_clips))
        awaitModel(model, "a page") { it.state.value.screen is Screen.Category && it.state.value.page.isNotEmpty() }
        awaitText(applyText(model))
    },
    Scene("videos-tab-before-scan", "Clean, the Videos tab before a scan has found a video", "Read that videos appear after the next scan, and start Scan videos to look for them.", Variant.base) {
        world.library()
        world.commit()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        awaitText(str(R.string.videos_after_scan))
    },
    Scene("videos-tab-no-videos", "Clean, the Videos tab once a scan found no video", "Read that the phone has no video. Scan videos looks again.", Variant.base) {
        world.library()
        world.commit()
        app.lastScan.record(Stats(images = 2_350), finishedAt = lastNight())
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        awaitText(str(R.string.videos_none_found))
    },
    Scene("videos-tab-scanning", "Clean, a photo scan running, seen from the Videos tab", "Watch the photo scan's phase and count from either tab and stop it. Scan videos comes back when it ends.", Variant.base) {
        world.library()
        videoLibrary()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        RunningScanWorker.progress = ScanProgress("detectors", 4_320, 10_220, 11 * 60_000L)
        Indexing.scanNow(app)
        awaitText(str(R.string.scan_stop))
    },
    Scene("videos-tab-video-scan", "Clean, Scan videos reading the videos", "Watch the video scan's own phases, its count and the time left, and stop it. What it finished stays.", Variant.wide) {
        world.library()
        videoLibrary()
        val model = app()
        awaitHome(model)
        click(str(R.string.tab_videos))
        RunningScanWorker.progress = ScanProgress(VIDEO_METADATA_STEP, 312, 1_419, 9 * 60_000L)
        Indexing.scanVideos(app)
        awaitText(str(R.string.scan_stop))
    },
    Scene("clean-video-scan", "Clean, the Photos tab while Scan videos runs", "Read that the video scan is running, follow its phase and stop it. Scan now comes back when it ends.", Variant.base) {
        world.library()
        world.commit()
        val model = app()
        awaitHome(model)
        RunningScanWorker.progress = ScanProgress("scan", 3_870, 0)
        Indexing.scanVideos(app)
        awaitText(str(R.string.scan_stop))
    },
    Scene("clean-scan-waiting", "Clean, the Photos tab while a Scan videos waits to start", "Read that a video scan is about to start, as after the phone restarted in the middle of one, and stop it. Scan now comes back when it ends.", Variant.base) {
        world.library()
        world.commit()
        val model = app()
        awaitHome(model)
        // A request WorkManager put back after its process was killed: it waits, here for its delay, to start again.
        val request = OneTimeWorkRequestBuilder<IndexWorker>().addTag(Indexing.TAG).addTag(Indexing.VIDEO_TAG).setInitialDelay(Duration.ofHours(1)).build()
        WorkManager.getInstance(app).enqueueUniqueWork(Indexing.WORK, ExistingWorkPolicy.KEEP, request)
        awaitText(str(R.string.scan_stop))
    },
    Scene("videos-largest", "Largest videos, the list with nothing selected", "Look at the videos from the largest, with their length and size, one with a heart from the gallery and one that cannot be read. Tap a video to select it, hold one to see it full size.", Variant.full) {
        videoLibrary()
        openVideos(app())
    },
    Scene("videos-largest-selected", "Largest videos, two videos selected", "Read how many videos and how many GB the main button moves to the trash, trash them, or keep them instead.", Variant.wide) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performClick()
        described("VID_20260926")[0].performClick()
        awaitText(plural(R.plurals.videos_trash, 2, 2, Formatter.formatShortFileSize(app, 1_820_000_000L + 1_110_000_000L).replace(' ', '\u00A0')))
    },
    Scene("videos-largest-favorite", "Largest videos, the confirmation when a favourite is selected", "Read that one of the videos is a favourite in the gallery, then move it to the trash anyway or cancel.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261011")[0].performClick()
        click(plural(R.plurals.videos_trash, 1, 1, Formatter.formatShortFileSize(app, 1_460_000_000L).replace(' ', '\u00A0')))
        awaitText(plural(R.plurals.videos_summary_title, 1, 1))
    },
    Scene("videos-largest-full-size", "Largest videos, a video at full size", "The video starts playing, muted, as the view opens, and the sound icon says it is off. Pause it with a tap, drag the seek bar, jump to one of the five frames, unmute it, select it with the checkbox, swipe to the next one, which stops this one and plays that, or close the view. Robolectric draws no video, so the picture stays the frame.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        await("the video plays") { described(str(R.string.video_pause)).fetchSemanticsNodes().isNotEmpty() }
    },
    Scene("videos-largest-paused", "Largest videos, a video paused at full size", "Play the video again with the button over the frame or the one in the bar, or tap the picture.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        await("the video plays") { described(str(R.string.video_pause)).fetchSemanticsNodes().isNotEmpty() }
        described(str(R.string.video_pause))[0].performClick()
        await("the video pauses") { described(str(R.string.video_play)).fetchSemanticsNodes().isNotEmpty() }
    },
    Scene("videos-largest-play-failed", "Largest videos, a video that fails to play", "Read that the video cannot be played. The frame stays, and the video can still be selected, kept or trashed.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        await("the video plays") { players.made.isNotEmpty() }
        players.made.single().fail()
        awaitText(str(R.string.video_play_failed))
    },
    Scene("videos-largest-unreadable-full-size", "Largest videos, a video that cannot be read at full size", "Read that the video cannot be read. It stays in the list so it can still be kept or trashed.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261003")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("videos-largest-snackbar-free-now", "Largest videos, the snackbar after trashing more than 1 GB", "Read how much went to the trash, free that space now or undo. The videos left the list.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performClick()
        described("VID_20260926")[0].performClick()
        click(plural(R.plurals.videos_trash, 2, 2, Formatter.formatShortFileSize(app, 1_820_000_000L + 1_110_000_000L).replace(' ', '\u00A0')))
        awaitText(str(R.string.undo))
        awaitText(freeNowLabel(model))
    },
    Scene("videos-largest-empty", "Largest videos, nothing left to review", "Read that no video is left to review. A scan brings new ones.", Variant.base) {
        val kept = world.video(CAMERA, "VID_20261019_201512.mp4", 640_000_000L, taken(0))
        world.commit()
        runBlocking { app.index.setReviewState(kept.path, ReviewState.KEPT) }
        val model = app()
        awaitHome(model)
        model.openVideos()
        awaitText(str(R.string.videos_none_left))
    },
    Scene("videos-largest-favorite-appeared", "Largest videos, the list back after a selected video became a favourite", "Read why nothing went to the trash, find the new heart, then trash it again or keep it.", Variant.base) {
        videoLibrary()
        val model = app()
        openVideos(model)
        described("VID_20261019")[0].performClick()
        world.favorites += model.state.value.videoSelected.first() // hearted in the gallery while the list was open
        click(plural(R.plurals.videos_trash, 1, 1, Formatter.formatShortFileSize(app, 1_820_000_000L).replace(' ', '\u00A0')))
        awaitText(str(R.string.favorite_appeared_videos_snackbar))
    },
    // Trash
    Scene("trash-empty", "Trash, empty", "Read that the trash is empty and go back.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        openTrash(model)
        awaitText(str(R.string.trash_empty))
    },
    Scene("trash-loading", "Trash, opened while a big trash is still being read", "Wait under the progress bar for the photos to appear, or go back.", Variant.base) {
        world.commit(scanned = false)
        val reading = object : Trash by world.trash {
            override suspend fun trashed(): List<TrashedImage> = awaitCancellation()
        }
        val model = app(world.model(reading))
        awaitHome(model)
        clickDescription(str(R.string.trash))
        await("the trash, still reading") { model.state.value.screen is Screen.Trash && model.state.value.loading }
    },
    Scene("trash-items", "Trash with photos, videos, screenshots and stickers, nothing selected", "Select photos or videos to bring them back, select all, hold one to see it full size, read how much space the trash still takes, empty the trash, or go back. The days left count down to the automatic deletion, and a video shows its length and size.", Variant.full) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
    },
    Scene("trash-selected", "Trash with three photos selected", "Restore the selected photos to their folders, delete only those for good, or select more.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        for (position in listOf(0, 2, 5)) described("IMG_")[position].performClick()
        awaitText(str(R.string.restore, 3))
    },
    Scene("trash-empty-confirm", "Trash, the confirmation before emptying it", "Read how much space deleting frees and that it is permanent, then delete everything or cancel.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        click(str(R.string.empty_trash))
        val count = model.state.value.trashed.size
        awaitText(plural(R.plurals.delete_files_title, count, count))
    },
    Scene("trash-full-size", "Trash, a photo at full size", "Look at a trashed photo before restoring it or deleting it for good, tick the checkbox to select it, read its folder and its days left, swipe to the next one, or close the view.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        described("IMG_")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("trash-video-full-size", "Trash, a video at full size", "Look at a frame of a trashed video, with its length and size and the strip of frames across it, see it play before restoring it or deleting it for good, tick the checkbox to select it, read its folder and its days left, swipe to the next one, or close the view.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        scrollToVideos()
        described("VID_")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("trash-video-unreadable", "Trash, a video that cannot be read", "Read the note on the placeholder where the frame would be. The video can still be restored or deleted for good.", Variant.base) {
        val model = app(trashed(this, unreadable = setOf(1)))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        scrollToVideos()
        awaitText(str(R.string.video_unreadable))
    },
    Scene("trash-videos", "Trash, scrolled to its videos", "Read each video's length, size and days left on its frame, select videos to bring them back or to delete them for good, or hold one to see it full size.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        scrollToVideos()
    },
    // Compress
    Scene("compress-empty", "Compress, nothing to compress", "Read why the list is empty, or open the sheet of what can be compressed.", Variant.base) {
        world.commit(scanned = false)
        val setup = CompressSetup()
        val model = app(compress = setup)
        openCompress(model, setup)
        awaitText(str(R.string.compress_none))
    },
    Scene("compress-list", "Compress, the list with nothing selected", "Pick photos, change the level, open what can be compressed from the info icon, see the photos compressed before, or open the trash from the top bar.", Variant.full) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
    },
    Scene("compress-selected", "Compress, three photos selected with the Continue button", "Change the level to see the saving, select more or fewer photos, or continue to the summary.", Variant.wide) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        select(setup, 0, 1, 3)
        awaitText(plural(R.plurals.compress_continue, 3, 3))
    },
    Scene("compress-formats", "Compress, the sheet about supported formats", "Read the two short lists of what is compressed and what is not. Close the sheet to go on.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        clickDescription(str(R.string.compress_formats_open))
        awaitText(str(R.string.compress_sheet_title))
    },
    Scene("compress-summary", "Compress, the summary with the level and the before and after slider", "Change the level and see the previews made again, drag the line to compare the original and the compressed copy, pinch to zoom, read the saving, then compress or cancel.", Variant.full) {
        val setup = cameraRoll()
        summary(setup, app(compress = setup))
    },
    Scene("compress-summary-no-loss", "Compress, the summary of photos that lose nothing that matters", "Read that the date, the place and the camera details stay, in a neutral box with no warning, then compress or cancel.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        select(setup, 3, 4)
        click(plural(R.plurals.compress_continue, 2, 2))
        awaitText(plural(R.plurals.compress_summary_title, 2, 2))
        scrollToEnd(inDialog = true)
    },
    Scene("compress-summary-losses", "Compress, the summary scrolled to what the copy loses", "Read what the compressed copy loses and how long the originals stay in the trash, then compress or cancel.", Variant.base) {
        val setup = cameraRoll()
        summary(setup, app(compress = setup))
        scrollToEnd(inDialog = true)
    },
    Scene("compress-full-size", "Compress, a photo at full size from the list", "Swipe through the photos of the page, zoom to the full resolution, select or deselect this photo, or tap to close.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        described(File(setup.photos[0].path).name)[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("compress-running", "Compress, a batch running", "Watch how many photos are done and how much space they saved so far, or stop after the photo in progress.", Variant.base) {
        val setup = CompressSetup(cameraRoll().photos, GalleryBatches(BatchState.Running(done = 4, total = 12, savedBytes = 41_300_000)))
        val model = app(compress = setup)
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_screen_cancel))
    },
    Scene("compress-waiting-scan", "Compress, a batch waiting for the scan to end", "Read that the batch starts when the scan ends and which phase the scan is in, or cancel it.", Variant.base) {
        val setup = CompressSetup(cameraRoll().photos, GalleryBatches(BatchState.WaitingForScan(total = 12)))
        val model = app(compress = setup)
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        RunningScanWorker.progress = ScanProgress("embedded_detectors", 840, 1_900, 4 * 60_000L)
        Indexing.scanNow(app)
        awaitText(str(R.string.compress_waiting_scan))
        awaitText(str(R.string.scan_phase_classification), substring = true)
    },
    Scene("compress-result", "Compress, the result of a batch with Undo", "Read how many photos were compressed and how much space they saved, close the result with Done, or undo the batch to bring the originals back.", Variant.wide) {
        val roll = cameraRoll()
        val result = BatchResult(12, 10, mapOf(Refusal.NOT_SMALLER to 2), 96_400_000, emptyList())
        val model = app(compress = CompressSetup(roll.photos, GalleryBatches(BatchState.Finished(result))))
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_result_done))
    },
    Scene("compress-result-partial", "Compress, a batch that stopped early with refusals", "Read why some photos were left as they are, how many were not reached, and how many favourites lost their mark, then undo or close.", Variant.base) {
        val roll = cameraRoll()
        val result = BatchResult(12, 6, mapOf(Refusal.NOT_SMALLER to 2, Refusal.LOW_MEMORY to 1, Refusal.GAIN_MAP_LOST to 1), 52_100_000, emptyList())
        val model = app(compress = CompressSetup(roll.photos, GalleryBatches(BatchState.Finished(result, favoritesLost = 2))))
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_result_done))
    },
    Scene("compress-compressed-before", "Compress, the photos compressed before, expanded", "Read which level each photo was compressed at, select one to compress it again, or collapse the section.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        val heading = plural(R.plurals.compress_already_heading, 3, 3)
        scrollTo(heading)
        click(heading)
        awaitText(str(R.string.compress_already_hint))
        scrollToEnd()
    },
    // Map
    Scene("map-root", "Storage map, opened from Settings, the treemap of the whole library", "Read the names on the larger folders, tap a folder to select it and read its size, open it, or go back to Settings.", Variant.full) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
    },
    Scene("map-zoomed", "Storage map, zoomed into Pictures", "Read the folder's name in the top bar, go back to the whole map, tap a subfolder to select it, or zoom further.", Variant.base + Variant.italianLargeText) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        click("Pictures") // a row selects its folder, as a block does
        click(str(R.string.storage_map_open))
        awaitModel(model, "the zoom") { it.state.value.storageMap.size > 1 }
    },
    Scene("map-selected", "Storage map, a folder selected with its details", "Read the folder's name, size and count and how much of its space is video, open it with Show contents, or exclude it from the review and the map.", Variant.base + Variant.italianDark + Variant.italianLargeText) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(60f, 60f)) }
        awaitText(str(R.string.storage_map_exclude))
    },
    Scene("map-selected-videos", "Storage map, a folder that holds videos selected", "Read how much of the folder's space is video, next to its size and count, or open it to find the videos.", Variant.base + Variant.italianDark) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        click("DCIM") // a row selects its folder, as a block does
        awaitText("Video:", substring = true)
    },
    Scene("map-excluded-snackbar", "Storage map, after excluding a folder", "Undo the exclusion from the snackbar. The map starts again from its root without the folder.", Variant.base) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(60f, 60f)) }
        click(str(R.string.storage_map_exclude))
        awaitText(str(R.string.undo))
    },
    Scene("map-empty", "Storage map, nothing found yet", "Read that no images or videos were found yet. Scan from Clean to fill the map.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap }
        awaitText(str(R.string.storage_map_empty))
    },
    // Settings
    Scene("settings-top", "Settings, the top: what the user changes", "Choose how long the trash keeps images, remove an excluded folder, open the storage map or the detection thresholds, turn the haptics off, or keep videos out of the night scan.", Variant.full) {
        settings(app())
    },
    Scene("settings-bottom", "Settings, scrolled to the end", "Read how many images and videos are indexed and the space they take, the storage the app takes, delete the analysis data, or read the version and open the website or the source.", Variant.base) {
        settings(app())
        scrollToEnd()
    },
    Scene("settings-scan-status", "Settings, the scans and storage", "Read what the last scan did, how long it took, and how many images each step analysed.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_scan_status_hint))
    },
    Scene("settings-retention-menu", "Settings, the choices of how long the trash keeps images", "Choose 7, 14, 30, 60 or 90 days.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_trash_retention))
        click(str(R.string.settings_trash_retention))
        awaitText(plural(R.plurals.settings_trash_days, 7, 7))
    },
    Scene("settings-retention-confirm", "Settings, the confirmation before a shorter retention deletes trashed photos", "Read how many trashed photos the new retention deletes at the next scan, then delete them or cancel.", Variant.base) {
        val model = app(trashed(this))
        settings(model)
        scrollTo(str(R.string.settings_trash_retention))
        click(str(R.string.settings_trash_retention))
        click(plural(R.plurals.settings_trash_days, 7, 7))
        awaitModel(model, "the question") { it.state.value.pendingRetention != null }
    },
    Scene("settings-delete-embeddings", "Settings, the confirmation before deleting the analysis data", "Read that the analysis data comes back at the next scan, then delete it or cancel.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_delete_embeddings))
        click(str(R.string.settings_delete_embeddings))
        awaitText(str(R.string.settings_delete_embeddings_confirm))
    },
    Scene("thresholds", "Detection thresholds", "Move a slider to list more or fewer images in a category, read how many that adds or removes, or reset to the defaults.", Variant.full) {
        world.library()
        world.commit()
        val model = app()
        goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings }
        scrollTo(str(R.string.settings_thresholds))
        click(str(R.string.settings_thresholds))
        awaitModel(model, "the thresholds") { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
    },
    // Permissions
    Scene("permission-all-files", "First run, the screen that asks for All files access", "Open the system settings to grant All files access. Nothing else in the app works before that.", Variant.wide) {
        launchMainActivity(allFilesAccess = false)
        awaitText(str(R.string.access_title))
    },
)

/** The label of the snackbar's offer, with the size of the last page as the app writes it in the scene's language. */
private fun Gallery.freeNowLabel(model: ReviewViewModel) = str(R.string.free_now, Formatter.formatShortFileSize(app, model.state.value.lastDecision!!.trashed.sumOf { it.size }))

private fun Gallery.scrollToVideos() {
    compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("VID_", substring = true))
    settle()
}

/** Twelve blurry photos of about 92 MB each: one page that trashes more than 1 GB. */
private fun bigPage(world: GalleryWorld) {
    repeat(12) { world.picture(CAMERA, cameraName(taken(it)), GalleryPictures.blurred(GalleryPictures.scene(1_000 + it)), 92_000_000L + it * 100_000L, taken(it), world.signal(Category.BLURRY, 0.99f - it * 0.01f)) }
    world.commit()
}

/**
 * Photos, videos, screenshots and stickers in the trash, trashed between one and 25 days ago, with the app on the empty
 * home. The three videos have a frame and a length from Robolectric's retriever ([GalleryWorld.showVideos]), except
 * those at the positions in [unreadable].
 */
private fun trashed(gallery: Gallery, unreadable: Set<Int> = emptySet()): ReviewViewModel = with(gallery) {
    val photos = (0 until 12).map { world.picture(CAMERA, cameraName(taken(it)), GalleryPictures.scene(5_000 + it), 3_500_000L + it * 90_000L, taken(it)) }
    val videos = (0 until 3).map { world.video(CAMERA, "VID_20261020_18${it}500.mp4", 180_000_000L + it * 230_000_000L, taken(it)) }
    val shots = (0 until 3).map { world.picture(SCREENSHOTS, "Screenshot_2026102$it-1${it}2030.png", GalleryPictures.screenshot(5_100 + it), 1_800_000L, taken(it)) }
    val stickers = (0 until 4).map { world.picture(STICKERS, "${500_000 + it}-$it.webp", GalleryPictures.sticker(5_200 + it), 120_000L, taken(it)) }
    world.commit(scanned = false)
    world.trashed(photos + videos + shots + stickers) { 1L + it * 2 }
    world.showVideos(unreadable) { GalleryPictures.scene(5_300 + it) }
    return world.model()
}
