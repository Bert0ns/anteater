// DOCUMENT: slides, whiteboards, notes, homework, receipts, printed pages (docs/design.md §5).
// Mirrors lab/src/anteater_lab/detectors/document.py.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.Signal
import io.github.bert0ns.anteater.prompts.VERSION
import io.github.bert0ns.anteater.prompts.classify
import io.github.bert0ns.anteater.text.textCoverage
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Two pieces of evidence, combined as independent chances (noisy OR): the MobileCLIP zero-shot
 * class, and how much of the image is text. The text weight saturates at [FULL_COVERAGE], a page
 * of printed text; photos stay well under 1%.
 */
class DocumentDetector(private val coverage: (File) -> Float = ::textCoverage) : Detector {
    override val id = "document"

    /** Bump with [VERSION]. */
    override val version = 3
    override val category = Category.DOCUMENT
    override val usesEmbeddings = true

    override suspend fun analyze(image: MediaRef, context: AnalysisContext): List<Signal> {
        val (probability, prompt) = classify(context.embedding(image)).getValue("document")
        val coverage = coverage(File(image.path))
        val textChance = TEXT_WEIGHT * min(1f, coverage / FULL_COVERAGE)
        val score = 1f - (1f - probability) * (1f - textChance)
        val evidence = mapOf(
            "prompt" to prompt,
            "probability" to "%.3f".format(Locale.ROOT, probability),
            "text_coverage" to "%.4f".format(Locale.ROOT, coverage),
        )
        return listOf(Signal(id, version, Category.DOCUMENT, score, evidence))
    }

    companion object {
        const val FULL_COVERAGE = 0.10f
        const val TEXT_WEIGHT = 0.8f
    }
}
