package io.github.bert0ns.anteater.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withSave
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pictures for the UI gallery and README screenshots: sample photos from `site/src/assets/samples` when
 * `anteater.samples.dir` is set (`-PreadmeScreenshots`), or fast procedural drawings otherwise (`-PuiGallery`).
 */
object GalleryPictures {
    private val CAMERA_SAMPLES = listOf(
        "pet-cat-window.webp",
        "scene-fountain-pigeons.webp",
        "food-pasta-lunch.webp",
        "street-cobblestone-alley.webp",
        "landscape-coast-hills.webp",
        "food-espresso-pastry.webp",
        "street-crosswalk-rain.webp",
        "objects-books-plant.webp",
        "landscape-sunset-park.webp",
        "objects-desk-flatlay.webp",
        "compression-foliage-detail.png",
    )
    private val COMPRESS_SAMPLES = listOf(
        "compression-foliage-detail.png",
        "objects-desk-flatlay.webp",
        "pet-cat-window.webp",
        "food-pasta-lunch.webp",
        "landscape-coast-hills.webp",
        "street-cobblestone-alley.webp",
        "food-espresso-pastry.webp",
        "scene-fountain-pigeons.webp",
        "landscape-sunset-park.webp",
        "street-crosswalk-rain.webp",
        "objects-books-plant.webp",
    )
    private val DOCUMENT_SAMPLES = listOf(
        "doc-receipt-cafe.webp",
        "doc-whiteboard-diagram.webp",
        "doc-handwritten-notes.webp",
    )
    private val FORWARDED_SAMPLES = listOf(
        "forwarded-meme-cat.webp",
        "forwarded-greeting-flowers.webp",
    )
    private const val STICKER_SAMPLE = "sticker-cartoon.webp"

    private val sampleCache = mutableMapOf<String, Bitmap>()

    private fun samplesDir(): File? = System.getProperty("anteater.samples.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isDirectory }

    private fun sample(dir: File, name: String): Bitmap = sampleCache.getOrPut(name) {
        val file = File(dir, name)
        checkNotNull(BitmapFactory.decodeFile(file.path)) { "cannot decode sample ${file.path}" }
    }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

    /** A camera scene or still life. [shift] pans and zooms the crop so near-duplicate copies look like distinct takes. */
    fun scene(seed: Int, width: Int = 480, height: Int = 360, shift: Float = 0f, brightness: Float = 1f): Bitmap {
        val dir = samplesDir() ?: return proceduralScene(seed, width, height, shift, brightness)
        return sampleScene(dir, seed, width, height, shift, brightness)
    }

    private fun sampleScene(dir: File, seed: Int, width: Int, height: Int, shift: Float, brightness: Float): Bitmap {
        if (seed in 5_000..5_099 && shift == 0f && brightness == 1f) {
            return trashSample(dir, seed - 5_000, seed, width, height)
        }
        val (name, pass, fullFrame) = when (seed) {
            in 11..99 -> {
                val index = seed - 11
                Triple(COMPRESS_SAMPLES[index.mod(COMPRESS_SAMPLES.size)], index / COMPRESS_SAMPLES.size, seed == 11)
            }
            in 2_000..2_999 -> {
                val index = seed - 2_000
                Triple(CAMERA_SAMPLES[(index + 4).mod(CAMERA_SAMPLES.size)], index / CAMERA_SAMPLES.size, false)
            }
            in 7_000..7_099 -> {
                val index = seed - 7_000
                Triple(CAMERA_SAMPLES[(index + 4).mod(CAMERA_SAMPLES.size)], index / CAMERA_SAMPLES.size, true)
            }
            in 7_100..7_499 -> {
                val index = seed - 7_100
                Triple(CAMERA_SAMPLES[(index + 8).mod(CAMERA_SAMPLES.size)], index / CAMERA_SAMPLES.size, false)
            }
            else -> {
                val index = (seed % 1_000).let { if (it < 0) it + 1_000 else it }
                Triple(CAMERA_SAMPLES[index.mod(CAMERA_SAMPLES.size)], index / CAMERA_SAMPLES.size, false)
            }
        }
        val targetWidth = if (seed in 7_000..7_099) maxOf(width, 960) else width
        val targetHeight = if (seed in 7_000..7_099) maxOf(height, 720) else height
        return cropSample(
            source = sample(dir, name),
            width = targetWidth,
            height = targetHeight,
            seed = seed,
            shift = shift,
            brightness = brightness,
            fullFrame = fullFrame,
            flip = pass % 2 == 1,
        )
    }

    private fun trashSample(dir: File, index: Int, seed: Int, width: Int, height: Int): Bitmap = when (index.mod(12)) {
        0 -> cropSample(sample(dir, "doc-receipt-cafe.webp"), width, height, seed, fullFrame = true)
        1 -> cropSample(sample(dir, "forwarded-meme-cat.webp"), width, height, seed, fullFrame = true)
        2 -> blurred(cropSample(sample(dir, "pet-cat-window.webp"), width, height, seed))
        3 -> cropSample(sample(dir, "doc-whiteboard-diagram.webp"), width, height, seed, fullFrame = true)
        4 -> exposure(4_006, dark = true)
        5 -> cropSample(sample(dir, "forwarded-greeting-flowers.webp"), width, height, seed, fullFrame = true)
        6 -> sticker(seed, size = minOf(width, height))
        7 -> cropSample(sample(dir, "doc-handwritten-notes.webp"), width, height, seed, fullFrame = true)
        8 -> cropSample(sample(dir, "food-pasta-lunch.webp"), width, height, seed)
        9 -> exposure(4_008, dark = false)
        10 -> cropSample(sample(dir, "objects-desk-flatlay.webp"), width, height, seed)
        else -> cropSample(sample(dir, "street-cobblestone-alley.webp"), width, height, seed)
    }

    private fun cropSample(
        source: Bitmap,
        width: Int,
        height: Int,
        seed: Int,
        shift: Float = 0f,
        brightness: Float = 1f,
        fullFrame: Boolean = false,
        flip: Boolean = false,
    ): Bitmap {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val targetAspect = width.toFloat() / height.toFloat()
        val srcAspect = srcW / srcH
        val baseW = if (srcAspect > targetAspect) srcH * targetAspect else srcW
        val baseH = if (srcAspect > targetAspect) srcH else srcW / targetAspect

        val random = Random(seed)
        val baseZoom = 0.85f + random.nextFloat() * 0.07f
        val basePanX = 0.25f + random.nextFloat() * 0.50f
        val basePanY = 0.25f + random.nextFloat() * 0.50f

        val (zoom, panX, panY) = when {
            fullFrame -> Triple(1f, 0.5f, 0.5f)
            shift <= 0f -> Triple(baseZoom, basePanX, basePanY)
            shift < 9f -> Triple(
                (baseZoom - 0.09f).coerceAtLeast(0.72f),
                (basePanX + 0.30f).coerceIn(0f, 1f),
                (basePanY - 0.24f).coerceIn(0f, 1f),
            )
            else -> Triple(
                (baseZoom + 0.08f).coerceAtMost(0.98f),
                (basePanX - 0.30f).coerceIn(0f, 1f),
                (basePanY + 0.26f).coerceIn(0f, 1f),
            )
        }

        val cropW = baseW * zoom
        val cropH = baseH * zoom
        val left = ((srcW - cropW) * panX).toInt().coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val top = ((srcH - cropH) * panY).toInt().coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val right = (left + cropW.toInt()).coerceIn(left + 1, source.width)
        val bottom = (top + cropH.toInt()).coerceIn(top + 1, source.height)

        val effectiveBrightness = when {
            shift <= 0f -> brightness
            shift < 9f -> brightness * 0.91f
            else -> brightness * 1.08f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (effectiveBrightness != 1f) {
            paint.colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setScale(effectiveBrightness, effectiveBrightness, effectiveBrightness, 1f) },
            )
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.withSave {
            if (flip) scale(-1f, 1f, width / 2f, height / 2f)
            drawBitmap(source, Rect(left, top, right, bottom), Rect(0, 0, width, height), paint)
        }
        return bitmap
    }

    private fun proceduralScene(seed: Int, width: Int, height: Int, shift: Float, brightness: Float): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = random.nextFloat() * 360f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), hsv(hue, 0.45f, 0.98f * brightness), hsv(hue + 35f, 0.35f, 0.85f * brightness), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = hsv(hue + 50f, 0.2f, 1f * brightness)
        canvas.drawCircle(width * (0.2f + random.nextFloat() * 0.6f) + shift, height * 0.25f, height * 0.09f, paint)
        for (layer in 0 until 3) {
            val base = height * (0.55f + layer * 0.14f)
            val phase = random.nextFloat() * 6f
            val path = Path().apply { moveTo(0f, height.toFloat()) }
            var x = 0f
            while (x <= width) {
                path.lineTo(x, base + sin(x / width * 5f + phase) * height * 0.06f)
                x += 8f
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            paint.color = hsv(hue + 120f + layer * 18f, 0.5f, (0.7f - layer * 0.12f) * brightness)
            canvas.drawPath(path, paint)
        }
        repeat(3) {
            paint.color = hsv(random.nextFloat() * 360f, 0.7f, 0.95f * brightness)
            val cx = width * random.nextFloat() + shift
            val cy = height * (0.45f + random.nextFloat() * 0.45f)
            if (random.nextBoolean()) {
                canvas.drawCircle(cx, cy, height * (0.05f + random.nextFloat() * 0.07f), paint)
            } else {
                val half = height * (0.05f + random.nextFloat() * 0.07f)
                canvas.drawRoundRect(RectF(cx - half, cy - half, cx + half, cy + half), 8f, 8f, paint)
            }
        }
        return bitmap
    }

    /** A soft copy: shrunk and stretched back with filtering, like a photo out of focus. */
    fun blurred(source: Bitmap, factor: Int = 14): Bitmap {
        if (samplesDir() != null && factor == 14) {
            val smallW = 84
            val smallH = (smallW * source.height / source.width.coerceAtLeast(1)).coerceAtLeast(2)
            val midW = (smallW * 2).coerceAtMost(source.width)
            val midH = (smallH * 2).coerceAtMost(source.height)
            val downMid = Bitmap.createScaledBitmap(source, midW, midH, true)
            val small = Bitmap.createScaledBitmap(downMid, smallW, smallH, true)
            val upMid = Bitmap.createScaledBitmap(small, midW, midH, true)
            return Bitmap.createScaledBitmap(upMid, source.width, source.height, true)
        }
        val small = Bitmap.createScaledBitmap(source, (source.width / factor).coerceAtLeast(2), (source.height / factor).coerceAtLeast(2), true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, true)
    }

    /** A document photo: receipt, whiteboard or handwritten notes when sample photos are available. */
    fun document(seed: Int, width: Int = 480, height: Int = 360): Bitmap {
        val dir = samplesDir() ?: return scene(seed, width, height, brightness = 0.95f)
        val name = DOCUMENT_SAMPLES[seed.mod(DOCUMENT_SAMPLES.size)]
        return cropSample(sample(dir, name), width, height, seed, fullFrame = true)
    }

    /** A forwarded messaging image: greeting or meme when sample photos are available. */
    fun forwarded(seed: Int, width: Int = 480, height: Int = 360): Bitmap {
        val dir = samplesDir() ?: return scene(seed, width, height)
        val name = FORWARDED_SAMPLES[seed.mod(FORWARDED_SAMPLES.size)]
        return cropSample(sample(dir, name), width, height, seed, fullFrame = true)
    }

    /** A phone screenshot: a status bar, a title bar and rows of an imaginary list. */
    fun screenshot(seed: Int, width: Int = 270, height: Int = 585): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = hsv(random.nextFloat() * 360f, 0.6f, 0.75f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(248, 248, 250))
        paint.color = Color.rgb(30, 30, 34)
        canvas.drawRect(0f, 0f, width.toFloat(), 22f, paint)
        paint.color = accent
        canvas.drawRect(0f, 22f, width.toFloat(), 70f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(16f, 38f, 16f + width * 0.4f, 52f), 7f, 7f, paint)
        var y = 86f
        while (y < height - 70f) {
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(10f, y, width - 10f, y + 52f), 10f, 10f, paint)
            paint.color = hsv(random.nextFloat() * 360f, 0.35f, 0.9f)
            canvas.drawCircle(36f, y + 26f, 16f, paint)
            paint.color = Color.rgb(70, 70, 76)
            canvas.drawRoundRect(RectF(62f, y + 12f, 62f + width * (0.3f + random.nextFloat() * 0.3f), y + 22f), 5f, 5f, paint)
            paint.color = Color.rgb(170, 170, 178)
            canvas.drawRoundRect(RectF(62f, y + 30f, 62f + width * (0.4f + random.nextFloat() * 0.3f), y + 38f), 4f, 4f, paint)
            y += 60f
        }
        paint.color = Color.rgb(235, 235, 240)
        canvas.drawRect(0f, height - 48f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.rgb(40, 40, 44)
        canvas.drawRoundRect(RectF(width * 0.35f, height - 14f, width * 0.65f, height - 9f), 3f, 3f, paint)
        return bitmap
    }

    /** A messaging sticker: the cartoon cat sample on a soft backdrop when sample photos are available, or a drawn face. */
    fun sticker(seed: Int, size: Int = 256): Bitmap {
        val dir = samplesDir()
        if (dir != null) {
            val source = sample(dir, STICKER_SAMPLE)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hue = (seed * 67f) % 360f
            canvas.drawColor(hsv(hue + 200f, 0.24f, 0.24f))
            val pad = size * 0.06f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, null, RectF(pad, pad, size - pad, size - pad), paint)
            return bitmap
        }
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = random.nextFloat() * 360f
        canvas.drawColor(hsv(hue + 180f, 0.2f, 0.97f))
        paint.color = hsv(hue, 0.7f, 1f)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)
        paint.color = Color.rgb(40, 30, 30)
        canvas.drawCircle(size * 0.38f, size * 0.42f, size * 0.04f, paint)
        canvas.drawCircle(size * 0.62f, size * 0.42f, size * 0.04f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.025f
        canvas.drawArc(RectF(size * 0.34f, size * 0.42f, size * 0.66f, size * 0.7f), 15f, 150f, false, paint)
        return bitmap
    }

    /** A dark or washed out frame: an underexposed or overexposed camera scene. */
    fun exposure(seed: Int, dark: Boolean): Bitmap {
        val hasSamples = samplesDir() != null
        val bitmap = scene(seed, brightness = if (dark) (if (hasSamples) 0.26f else 0.22f) else (if (hasSamples) 1.35f else 1f))
        if (!dark) {
            Canvas(bitmap).drawColor(Color.argb(if (hasSamples) 135 else 170, 255, 255, 255))
        }
        return bitmap
    }

    /** The same picture as a hard compression leaves it: coarse JPEG blocks. The summary's "after" is this, since the real encoder is not run. */
    fun degraded(file: File): ByteArray {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "cannot decode ${file.path}" }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 12, it) }.toByteArray()
    }

    /**
     * Writes [bitmap] to [file] and stretches the file to [bytes] with zeros after the image data. Decoders stop at the
     * end of the image, so it still opens, and the index and the trash see the size a real photo has. The stretch is sparse.
     */
    fun write(file: File, bitmap: Bitmap, bytes: Long, quality: Int = 88) {
        file.parentFile?.mkdirs()
        val png = file.extension.equals("png", ignoreCase = true)
        file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it) }
        if (file.length() < bytes) RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }
}
