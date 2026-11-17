package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThresholdsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each category shows its value and how many images it lists, and a raised one says it lists fewer`() {
        compose.setContent {
            ThresholdsScreen(
                thresholds = THRESHOLDS + (Category.BLURRY to 0.7f),
                counts = mapOf(Category.BLURRY to 10, Category.DOCUMENT to 1),
                defaultCounts = mapOf(Category.BLURRY to 25, Category.DOCUMENT to 1),
                onChange = { _, _ -> },
                onReset = {},
            )
        }

        compose.onNodeWithText("Move a slider left to see more images, right to see fewer.").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blurry"))
        compose.onAllNodesWithText("Blurry").onFirst().assertIsDisplayed() // the Videos section has one too
        compose.onNodeWithText("10 images to review").assertIsDisplayed()
        compose.onNodeWithText("15 images fewer than at the default").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Documents and notes"))
        compose.onNodeWithText("Documents and notes").assertIsDisplayed()
        compose.onNodeWithText("1 image to review").assertIsDisplayed()
    }

    @Test
    fun `moving a slider reports the new value once the finger lifts`() {
        val changes = mutableListOf<Pair<Category, Float>>()
        compose.setContent {
            ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { c, v -> changes += c to v }, {})
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Blurry"))
        compose.onNodeWithContentDescription("Blurry").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        assertEquals(listOf(Category.BLURRY to 0.75f), changes)
    }

    @Test
    fun `stickers get no slider, since their detector scores only 0 or 1`() {
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {}) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onAllNodes(hasText("WhatsApp stickers", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("WhatsApp stickers")).assertCountEquals(0)
    }

    @Test
    fun `exact duplicates get no slider, since their detector scores only 0 or 1`() {
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {}) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onAllNodes(hasText("Exact copies", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Exact copies")).assertCountEquals(0)
    }

    @Test
    fun `reset to defaults is offered`() {
        var resets = 0
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, { resets++ }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").performClick()

        assertEquals(1, resets)
    }

    @Test
    fun `each step of a slider gives a tick, and reset gives a confirm`() {
        val feedback = RecordingHaptics()
        val given = feedback.types
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {})
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Blurry"))
        compose.onNodeWithContentDescription("Blurry").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        assertEquals(listOf(Haptic.TICK.type), given)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").performClick()
        assertEquals(listOf(Haptic.TICK.type, Haptic.KEEP.type), given)
    }

    @Test
    fun `two sections, Photos and Videos, and a video's slider reports on its own`() {
        val photos = mutableListOf<Pair<Category, Float>>()
        val videos = mutableListOf<Pair<Category, Float>>()
        compose.setContent {
            ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { c, v -> photos += c to v }, {}, onChangeVideo = { c, v -> videos += c to v })
        }

        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Videos"))
        compose.onNodeWithText("Videos").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Blurry, videos"))
        compose.onNodeWithContentDescription("Blurry, videos").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }

        assertEquals(listOf(Category.BLURRY to 0.3f), videos)
        assertEquals(emptyList<Pair<Category, Float>>(), photos)
    }

    @Test
    fun `only the categories whose video score is graded have a slider under Videos`() {
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {}) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onAllNodes(hasContentDescription(", videos", substring = true)).assertCountEquals(2)
        compose.onNodeWithContentDescription("Blurry, videos").assertExists()
        compose.onNodeWithContentDescription("Too dark or too bright, videos").assertExists()
    }

    @Test
    fun `the Videos section counts videos, not images`() {
        compose.setContent {
            ThresholdsScreen(
                THRESHOLDS,
                emptyMap(),
                emptyMap(),
                { _, _ -> },
                {},
                videoCounts = mapOf(Category.BLURRY to 3, Category.BAD_EXPOSURE to 1),
                videoDefaultCounts = mapOf(Category.BLURRY to 6, Category.BAD_EXPOSURE to 0),
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("3 videos to review").assertExists()
        compose.onNodeWithText("3 videos fewer than at the default").assertExists()
        compose.onNodeWithText("1 video to review").assertExists()
        compose.onNodeWithText("1 video more than at the default").assertExists()
        compose.onAllNodes(hasText("3 images", substring = true)).assertCountEquals(0)
    }
}
