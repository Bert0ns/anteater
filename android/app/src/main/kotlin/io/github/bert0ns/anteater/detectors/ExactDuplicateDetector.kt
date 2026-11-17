// EXACT_DUPLICATE: byte-identical files (docs/design.md §5). Mirrors
// lab/src/anteater_lab/detectors/exact_duplicate.py.
package io.github.bert0ns.anteater.detectors

import io.github.bert0ns.anteater.folders.isCameraFolder
import io.github.bert0ns.anteater.folders.isMessagingFolder
import io.github.bert0ns.anteater.model.Category
import io.github.bert0ns.anteater.model.GROUP_KEY
import io.github.bert0ns.anteater.model.MediaRef
import io.github.bert0ns.anteater.model.MediaType
import io.github.bert0ns.anteater.model.ROLE_KEY
import io.github.bert0ns.anteater.model.Role
import io.github.bert0ns.anteater.model.Signal
import java.io.File
import java.io.IOException

/**
 * Groups files by type and size first, and hashes only the ones that share both, so a video with no twin is never read and
 * a group never mixes images and videos.
 */
class ExactDuplicateDetector : GroupDetector {
    override val id = "exact_duplicate"
    override val version = 1
    override val category = Category.EXACT_DUPLICATE
    override val mediaTypes = setOf(MediaType.IMAGE, MediaType.VIDEO)

    override suspend fun analyze(images: List<MediaRef>, context: AnalysisContext): Map<MediaRef, List<Signal>> {
        val byHash = LinkedHashMap<String, MutableList<MediaRef>>()
        for (sameSize in images.groupBy { it.mediaType to it.size }.values) {
            if (sameSize.size < 2) continue
            for (image in sameSize) {
                val digest = try {
                    context.contentHash(image)
                } catch (_: IOException) {
                    continue // unreadable or gone since the scan
                }
                byHash.getOrPut(digest) { mutableListOf() } += image
            }
        }
        val result = LinkedHashMap<MediaRef, List<Signal>>()
        for ((digest, copies) in byHash) {
            if (copies.size < 2) continue
            val best = copies.minWith(PREFERENCE)
            for (image in copies) {
                val (role, score) = if (image == best) Role.BEST to 0f else Role.INFERIOR to 1f
                val evidence = mapOf(GROUP_KEY to digest, ROLE_KEY to role.value)
                result[image] = listOf(Signal(id, version, Category.EXACT_DUPLICATE, score, evidence))
            }
        }
        return result
    }

    private companion object {
        /** The copy to keep: camera folder first, messaging folders last, then the shortest path. */
        val PREFERENCE = compareBy<MediaRef>(
            {
                when {
                    isMessagingFolder(it.path) -> 2
                    isCameraFolder(it.path) -> 0
                    else -> 1
                }
            },
            { File(it.path).toPath().nameCount },
            { it.path },
        )
    }
}
