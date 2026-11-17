package io.github.bert0ns.anteater.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.ranker.THRESHOLDS
import io.github.bert0ns.anteater.ranker.VIDEO_THRESHOLDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThresholdsTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun `fixed categories include stickers, exact duplicates and takes`() {
        assertTrue(Thresholds.FIXED.contains(Category.STICKER))
        assertTrue(Thresholds.FIXED.contains(Category.EXACT_DUPLICATE))
        assertTrue(Thresholds.FIXED.contains(Category.TAKES))
    }

    @Test
    fun `without overrides the values are the ranker defaults`() {
        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test
    fun `an override is kept for the next start and leaves the other categories alone`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f

        assertEquals(THRESHOLDS + (Category.BLURRY to 0.75f), Thresholds(preferences).values)
    }

    @Test
    fun `setting the default value stores nothing, so a retuned default still reaches the user`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f
        Thresholds(preferences)[Category.BLURRY] = THRESHOLDS.getValue(Category.BLURRY)

        assertEquals(emptyMap<String, Any>(), preferences.all)
    }

    @Test
    fun `reset restores every default`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f
        Thresholds(preferences)[Category.DOCUMENT] = Thresholds.MIN
        Thresholds(preferences).reset()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test
    fun `a stored sticker override is ignored, since stickers have no slider`() {
        preferences.edit().putFloat("threshold_STICKER", 1f).commit()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test
    fun `a stored exact duplicate override is ignored, since exact duplicates have no slider`() {
        preferences.edit().putFloat("threshold_EXACT_DUPLICATE", 1f).commit()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a sticker threshold cannot be set`() {
        Thresholds(preferences)[Category.STICKER] = 0.8f
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an exact duplicate threshold cannot be set`() {
        Thresholds(preferences)[Category.EXACT_DUPLICATE] = 0.8f
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold above 1 is refused`() {
        Thresholds(preferences)[Category.BLURRY] = 1.5f
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold of 0 is refused, since it would suggest the best copy of a group`() {
        Thresholds(preferences)[Category.BLURRY] = 0f
    }

    @Test
    fun `a stored value outside the range falls back to the default`() {
        preferences.edit().putFloat("threshold_BLURRY", 7f).putFloat("threshold_DOCUMENT", 0f).commit()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test
    fun `a video's threshold is stored apart from the photos' and is not touched by it`() {
        Thresholds(preferences).setVideo(Category.BLURRY, 0.6f)

        assertEquals(VIDEO_THRESHOLDS + (Category.BLURRY to 0.6f), Thresholds(preferences).videoValues)
        assertEquals(THRESHOLDS, Thresholds(preferences).values)
        Thresholds(preferences)[Category.BLURRY] = 0.9f
        assertEquals(0.6f, Thresholds(preferences).videoValues.getValue(Category.BLURRY))
    }

    @Test
    fun `setting a video's default stores nothing, and reset restores both`() {
        Thresholds(preferences).setVideo(Category.BAD_EXPOSURE, 0.9f)
        Thresholds(preferences).setVideo(Category.BAD_EXPOSURE, VIDEO_THRESHOLDS.getValue(Category.BAD_EXPOSURE))
        assertEquals(emptyMap<String, Any>(), preferences.all)

        Thresholds(preferences).setVideo(Category.BLURRY, 0.6f)
        Thresholds(preferences)[Category.BLURRY] = 0.9f
        Thresholds(preferences).reset()

        assertEquals(VIDEO_THRESHOLDS, Thresholds(preferences).videoValues)
        assertEquals(THRESHOLDS, Thresholds(preferences).values)
        assertEquals(emptyMap<String, Any>(), preferences.all)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a category with no graded video score has no video threshold to set`() {
        Thresholds(preferences).setVideo(Category.FORWARDED, 0.5f)
    }
}
