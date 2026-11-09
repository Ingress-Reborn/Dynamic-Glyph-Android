package org.szres.glyphlens.recognition

/** The name of a Glyph, shared by the shape table and the sequence table. */
data class GlyphID(val name: String) : Comparable<GlyphID> {
    override fun compareTo(other: GlyphID): Int = name.compareTo(other.name)

    override fun toString(): String = name
}
