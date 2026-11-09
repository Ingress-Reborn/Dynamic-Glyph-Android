package org.szres.glyphlens.recognition

/** One of the eleven nodes in the Glyph array, encoded as 0-9 or a. */
class NodeIndex private constructor(val rawValue: Int) : Comparable<NodeIndex> {
    val character: Char
        get() = rawValue.toString(16)[0]

    override fun compareTo(other: NodeIndex): Int = rawValue.compareTo(other.rawValue)

    override fun equals(other: Any?): Boolean = other is NodeIndex && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue

    override fun toString(): String = character.toString()

    companion object {
        const val count: Int = 11

        /** Every node, in ascending order. */
        @JvmStatic
        val all: List<NodeIndex> = (0 until count).map { NodeIndex(it) }

        @JvmStatic
        fun fromRawValue(rawValue: Int): NodeIndex? = all.getOrNull(rawValue)

        /** Hex digits are accepted case-insensitively, just as in the data-set parser. */
        @JvmStatic
        fun fromCharacter(character: Char): NodeIndex? {
            val value = character.digitToIntOrNull(16) ?: return null
            return fromRawValue(value)
        }
    }
}
