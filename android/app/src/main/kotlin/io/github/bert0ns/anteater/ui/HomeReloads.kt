package io.github.bert0ns.anteater.ui

import io.github.bert0ns.anteater.pipeline.CLASSIFY_STEP
import io.github.bert0ns.anteater.pipeline.Indexing
import io.github.bert0ns.anteater.pipeline.ScanKind

/**
 * When the home reads the index again during a scan (docs/design.md §4). Each read takes the whole index and a
 * ranking, so it happens only once a phase that can add candidates is over, and when the scan ends:
 * - the walk (phase 1): a trashed image put back by hand becomes unreviewed with its signals, and the first scan
 *   ends the empty index;
 * - the detectors (phase 3) and the detectors that read the embeddings, which run with the embeddings (phase 4),
 *   store signals;
 * - the group detectors (phase 5) store signals too, and are the last steps, so the end of the scan covers them.
 *
 * The metadata and JPEG headers (phase 2) store no signal or review state.
 *
 * The video scan numbers its own phases. Its walk is the one that can change the home, and its metadata fills the videos'
 * lengths, which the end of the scan covers. A night pass that goes on with the videos has ended its image phases.
 */
internal class HomeReloads {
    private var first = true
    private var at: Pair<ScanKind, String>? = null

    /** Whether the home reloads now that the scan [kind] reports [step], or null once no scan runs. */
    fun next(step: String?, kind: ScanKind = ScanKind.IMAGES): Boolean {
        val from = at
        val to = step?.let { kind to it }
        at = to
        if (first) {
            first = false
            return true
        }
        if (to == from) return false
        if (to == null) return true
        if (from == null) return false
        if (to.first != from.first) return true
        val before = Indexing.phaseNumber(from.second, kind)
        val after = Indexing.phaseNumber(to.second, kind)
        // A short phase can pass unseen, so any candidate phase between the two counts; a lower phase is a new scan.
        return after < before || candidatePhases(kind).any { it in before until after }
    }

    private companion object {
        val CANDIDATE_PHASES = listOf("scan", "detectors", CLASSIFY_STEP).map(Indexing::phaseNumber).toSet()

        fun candidatePhases(kind: ScanKind) = if (kind == ScanKind.VIDEOS) setOf(Indexing.phaseNumber("scan", kind)) else CANDIDATE_PHASES
    }
}
