package io.github.bert0ns.anteater.detectors

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupSupportTest {
    // Known xxh3 digests, taken from the lab (xxhash.xxh3_64_hexdigest). The first starts with a
    // zero digit and the second has the top bit set.
    @Test
    fun `group ids are the xxh3 hex the lab writes`() {
        assertEquals("0169276d1980ff75", groupId("a.jpg"))
        assertEquals("f217b24abc767173", groupId("/pics/b.jpg"))
        assertEquals("2d06800538d394c2", groupId(""))
    }

    @Test
    fun `the first index at or after a target, and the first after it, for equal and missing times`() {
        val sorted = listOf(1, 3, 3, 5)

        assertEquals(listOf(0, 0, 1, 3, 3, 4), listOf(0, 1, 3, 4, 5, 6).map { firstAtOrAfter(sorted, it) })
        assertEquals(listOf(0, 1, 3, 3, 4, 4), listOf(0, 1, 3, 4, 5, 6).map { firstAfter(sorted, it) })
        assertEquals(0, firstAtOrAfter(emptyList<Int>(), 1))
    }
}
