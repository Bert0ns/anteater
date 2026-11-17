// STICKER: stickers saved by messaging apps (docs/design.md §5). Mirrors
// lab/src/anteater_lab/detectors/sticker.py.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.folders.isStickerFolder
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.Signal

/** Nearly deterministic: WhatsApp keeps every sticker it receives in its sticker folders, and nothing else goes there. */
class StickerDetector : Detector {
    override val id = "sticker"
    override val version = 1
    override val category = Category.STICKER

    override suspend fun analyze(image: MediaRef, context: AnalysisContext): List<Signal> {
        val inFolder = isStickerFolder(image.path)
        val evidence = mapOf("sticker_folder" to if (inFolder) "yes" else "no")
        return listOf(Signal(id, version, Category.STICKER, if (inFolder) 1f else 0f, evidence))
    }
}
