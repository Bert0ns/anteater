package io.github.bert0ns.anteater.ui

import android.database.SQLException
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.anteater.AnteaterApp
import io.github.bert0ns.anteater.ui.gallery.Gallery
import io.github.bert0ns.anteater.ui.gallery.GalleryWorkerFactory
import io.github.bert0ns.anteater.ui.gallery.GalleryWorld
import io.github.bert0ns.anteater.ui.gallery.MovableClock
import io.github.bert0ns.anteater.ui.gallery.scenes
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the screens for the README (`docs/screenshots`) and the website (`site/screenshots/en` and
 * `site/screenshots/it`) on the JVM in dark mode at xhdpi (`822x1782`), driving the real app over a
 * sample-photo library (`android/README.md`). It runs only when `-PreadmeScreenshots` is set.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w411dp-h891dp-night-xhdpi", application = AnteaterApp::class)
class ReadmeScreenshots(private val language: String) {
    private var written = false

    // WorkManager's background threads can race test teardown against Room after the screenshot is written.
    @get:Rule(order = 0)
    val workManagerTeardown = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    val closed = generateSequence(e) { it.cause }.any { cause ->
                        cause is SQLException &&
                            cause.message.orEmpty().contains("connection is closed") &&
                            cause.stackTrace.any { it.className.startsWith("androidx.work.") }
                    }
                    if (!written || !closed) throw e
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val folder = TemporaryFolder()

    private val outputDirs: List<File> by lazy {
        val property = if (language == "it") "anteater.screenshots.it.dirs" else "anteater.screenshots.dirs"
        val dirsProperty = System.getProperty(property)
        if (!dirsProperty.isNullOrBlank()) {
            dirsProperty.split(",").map { File(it.trim()) }
        } else {
            listOfNotNull(System.getProperty("anteater.screenshots.dir")?.let { File(it, language) })
        }
    }

    @Test
    fun home() = render("clean-results", "home.png")

    @Test
    fun category() = render("review-blurry", "category.png")

    @Test
    fun group() = render("review-near-duplicates", "group.png")

    @Test
    fun fullSize() = render("review-full-size", "full-size.png")

    @Test
    fun videos() = render("videos-tab-categories", "videos.png")

    @Test
    fun shortClips() = render("review-short-clips", "short-clips.png")

    @Test
    fun videoFullSize() = render("videos-largest-full-size", "video-full-size.png")

    @Test
    fun compress() = render("compress-selected", "compress.png")

    @Test
    fun compressSummary() = render("compress-summary", "compress-summary.png")

    @Test
    fun trash() = render("trash-items", "trash.png")

    @Test
    fun settings() = render("settings-top", "settings.png")

    @Test
    fun storageMap() = render("map-root", "storage-map.png")

    private fun render(sceneId: String, fileName: String) {
        assumeTrue("set -PreadmeScreenshots to write the screenshots", outputDirs.isNotEmpty())
        RuntimeEnvironment.setQualifiers("$language-w411dp-h891dp-night-xhdpi")
        val app = ApplicationProvider.getApplicationContext<AnteaterApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(GalleryWorkerFactory()).build(),
        )
        val clock = MovableClock(Instant.parse("2026-11-04T12:00:00Z"))
        val gallery = Gallery(compose, app, GalleryWorld(app, folder.newFolder("sdcard"), clock = clock))
        try {
            prime(gallery)
            val scene = checkNotNull(scenes.firstOrNull { it.id == sceneId }) { "scene $sceneId not found" }
            scene.run(gallery)
            gallery.settle()
            val bitmap = gallery.capture()
            for (dir in outputDirs) {
                dir.mkdirs()
                File(dir, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            written = true
        } finally {
            gallery.close()
        }
    }

    /** Sets up native graphics so dialogs and complex views render safely. */
    private fun prime(gallery: Gallery) {
        gallery.launch { Text("Anteater") }
        gallery.capture()
        gallery.close()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("it"))
    }
}
