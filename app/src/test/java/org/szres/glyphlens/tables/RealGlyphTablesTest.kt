package org.szres.glyphlens.tables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.SequenceTable
import java.io.File
import java.security.MessageDigest

class RealGlyphTablesTest {
    @Test
    fun bundledReferenceTablesMatchPinnedHashesAndCrossReferences() {
        val shapeFile = File("src/main/assets/glyph_line.csv")
        val sequenceFile = File("src/main/assets/glyph.csv")
        assertEquals(
            "72c3e0f1b93992cf725bf2ce8adfdb2df4daddf6b5f8a6c8f7e8629e6934b6e7",
            sha256(shapeFile.readBytes()),
        )
        assertEquals(
            "b1eaac450aa60643e25c17ae5c12ba8803dd0c50d60823dda5f6768ba9e2c5bf",
            sha256(sequenceFile.readBytes()),
        )

        val shapes = GlyphTable.parse(shapeFile.readText())
        val sequences = SequenceTable.parse(sequenceFile.readText())
        assertEquals(142, shapes.count)
        assertEquals(542, sequences.rowCount)
        assertEquals(540, sequences.count)

        val known = shapes.glyphs.toSet()
        val missing = sequences.sequences.flatten().toSet() - known
        assertTrue("Sequences reference missing Glyphs: $missing", missing.isEmpty())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
