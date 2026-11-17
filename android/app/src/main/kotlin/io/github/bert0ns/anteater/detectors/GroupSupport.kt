// Helpers shared by the group detectors: embedding similarity and group ids (xxh3 of the best
// copy's path, as in lab/src/anteater_lab/detectors/).
package io.github.bert0ns.anteater.detectors

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.anteater.hashing.toHex

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) {
        sum += a[i] * b[i]
    }
    return sum
}

/** The index of the first of [sorted] that is at or after [target], and the size when none is. */
internal fun <T : Comparable<T>> firstAtOrAfter(sorted: List<T>, target: T): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val middle = (low + high) / 2
        if (sorted[middle] < target) low = middle + 1 else high = middle
    }
    return low
}

/** The index of the first of [sorted] that is after [target], and the size when none is. */
internal fun <T : Comparable<T>> firstAfter(sorted: List<T>, target: T): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val middle = (low + high) / 2
        if (sorted[middle] <= target) low = middle + 1 else high = middle
    }
    return low
}

internal fun groupId(path: String): String = pathDigest(path).toHex()

/** The big-endian xxh3 digest of the path, 8 bytes. */
internal fun pathDigest(path: String): ByteArray {
    val digest = Algorithm.XXH3_64().createDigest()
    val bytes = path.toByteArray()
    digest.update(bytes, 0, bytes.size)
    return digest.digest()
}
