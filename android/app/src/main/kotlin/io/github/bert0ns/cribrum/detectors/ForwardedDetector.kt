// FORWARDED: memes, stickers and greeting images from messaging apps (docs/design.md §5).
// Mirrors lab/src/cribrum_lab/detectors/forwarded.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isMessagingFolder
import io.github.bert0ns.cribrum.folders.isSentFolder
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.MediaRef
import io.github.bert0ns.cribrum.model.MediaType
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.prompts.VERSION
import io.github.bert0ns.cribrum.prompts.classify
import java.util.Locale

/**
 * Three pieces of evidence, combined as independent chances (noisy OR) like [ScreenshotDetector]:
 * the zero-shot class is the strongest, because messaging folders and missing camera EXIF
 * also describe every photo a friend sends. The weights are starting points, to be tuned
 * for recall in the lab.
 *
 * A video has no zero-shot term: its signal reads no frame and no model, only its folder. On the 49 video labels of #390
 * the zero-shot class did not tell what the maintainer deleted from what they kept (docs/design.md §5).
 */
class ForwardedDetector : Detector {
    override val id = "forwarded"

    /** Bump with [VERSION]. */
    override val version = 2
    override val category = Category.FORWARDED

    /** Of an image: a video's signal reads no embedding. */
    override val usesEmbeddings = true
    override val mediaTypes = setOf(MediaType.IMAGE, MediaType.VIDEO)

    override suspend fun analyze(image: MediaRef, context: AnalysisContext): List<Signal> {
        if (image.mediaType == MediaType.VIDEO) return video(image)
        val (probability, prompt) = classify(context.embedding(image)).getValue("forwarded")
        val messaging = isMessagingFolder(image.path)
        val cameraExif = context.metadata(image).hasCameraExif
        val miss = (1f - ZERO_SHOT_WEIGHT * probability) *
            (1f - (if (messaging) MESSAGING_FOLDER_WEIGHT else 0f)) *
            (1f - (if (cameraExif) 0f else NO_CAMERA_EXIF_WEIGHT))
        val evidence = mapOf(
            "prompt" to prompt,
            "probability" to "%.3f".format(Locale.ROOT, probability),
            "messaging_folder" to if (messaging) "yes" else "no",
            "camera_exif" to if (cameraExif) "yes" else "no",
        )
        return listOf(Signal(id, version, Category.FORWARDED, 1f - miss, evidence))
    }

    /** Every video in a messaging app's folder is a candidate, the ones in "Sent" more than the others, and no other video gets a signal. */
    private fun video(video: MediaRef): List<Signal> {
        if (!isMessagingFolder(video.path)) return emptyList()
        val sent = isSentFolder(video.path)
        val score = 1f - (1f - VIDEO_MESSAGING_FOLDER_WEIGHT) * (1f - (if (sent) VIDEO_SENT_FOLDER_WEIGHT else 0f))
        val evidence = mapOf("messaging_folder" to "yes", "sent_folder" to if (sent) "yes" else "no")
        return listOf(Signal(id, version, Category.FORWARDED, score, evidence))
    }

    companion object {
        const val ZERO_SHOT_WEIGHT = 0.9f
        const val MESSAGING_FOLDER_WEIGHT = 0.4f
        const val NO_CAMERA_EXIF_WEIGHT = 0.1f
        const val VIDEO_MESSAGING_FOLDER_WEIGHT = 0.6f
        const val VIDEO_SENT_FOLDER_WEIGHT = 0.5f
    }
}
