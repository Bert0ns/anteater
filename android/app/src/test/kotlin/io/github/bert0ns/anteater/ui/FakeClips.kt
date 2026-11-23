package io.github.bert0ns.anteater.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The previews of a screen under test, which Robolectric cannot transcode (#482): [previews] is what the cache holds, by
 * video path, and [make] adds to it, at once or once [gate] opens, unless the video is [failing]. A video the index did not
 * size reads as [headers] says.
 */
internal class FakeClips : Clips {
    val previews: MutableMap<String, File> = ConcurrentHashMap()

    /** The videos a preview was asked for, in order, with the end of each loop, and those whose making was cancelled. */
    val made = ConcurrentLinkedQueue<String>()
    val ends: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
    val cancelled = ConcurrentLinkedQueue<String>()

    var failing = emptySet<String>()
    var headers = emptyMap<String, Long>()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    fun preview(path: String) = File("/data/cache/previews/${File(path).name}")

    override suspend fun find(mover: Mover): File? = if (mover.playsFile) File(mover.video.path) else previews[mover.video.path]

    override suspend fun make(mover: Mover): File? {
        val path = mover.video.path
        val pixels = mover.pixels ?: headers[path] ?: return null
        if (pixels <= FILE_LOOP_PIXELS) return File(path)
        made += path
        ends += mover.loopEndMs
        try {
            gate?.await()
        } catch (e: CancellationException) {
            cancelled += path
            throw e
        }
        if (path in failing) return null
        return preview(path).also { previews[path] = it }
    }
}

internal const val FULL_HD = 1920L * 1080
internal const val UHD = 3840L * 2160
