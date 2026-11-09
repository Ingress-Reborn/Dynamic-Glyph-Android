package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeAndPathTests {
    private fun path(encoded: String): GlyphPath = requireNotNull(GlyphPath.fromEncoded(encoded))

    @Test
    fun nodeAlphabetIsExactlyZeroThroughNineAndA() {
        assertEquals(11, NodeIndex.count)
        assertEquals("0123456789a", NodeIndex.all.joinToString("") { it.character.toString() })
        assertNull(NodeIndex.fromCharacter('b'))
        assertNotNull(NodeIndex.fromCharacter('A'))
        assertEquals(10, NodeIndex.fromCharacter('A')?.rawValue)
        assertNull(NodeIndex.fromRawValue(11))
        assertNull(NodeIndex.fromRawValue(-1))
    }

    @Test
    fun pathsRoundTripThroughDataSetEncoding() {
        val path = path("0568a975")
        assertEquals("0568a975", path.encoded)
        assertEquals(8, path.nodes.size)
        assertEquals("0568a975", path("0568A975").encoded)
        assertNull(GlyphPath.fromEncoded(""))
        assertNull(GlyphPath.fromEncoded("0z8"))
    }

    @Test
    fun reversingPreservesEdgesButNotOrder() {
        val path = path("038")
        assertEquals("830", path.reversed.encoded)
        assertEquals(path.undirectedEdges, path.reversed.undirectedEdges)
        assertNotEquals(path, path.reversed)
    }

    @Test
    fun editDistanceCountsSubstitutionsInsertionsAndEmptyWalks() {
        val a = path("038")
        assertEquals(0, a.editDistance(a))
        assertEquals(1, a.editDistance(path("039")))
        assertEquals(1, a.editDistance(path("0389")))
        assertEquals(3, GlyphPath(emptyList()).editDistance(a))
        assertEquals(3, a.editDistance(GlyphPath(emptyList())))
    }
}
