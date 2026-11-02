package com.dynamicglyph.recognition

/**
 * Index of a node in the 11-node Glyph array.
 *
 * Swift: NodeIndex (0..<11, hex-encoded: 0-9 then 'a').
 *
 * A plain data class rather than an inline value class: it is used as a map key
 * and set element throughout the recogniser, and the clarity is worth more here
 * than the allocation saving.
 */
data class NodeIndex(val rawValue: Int) : Comparable<NodeIndex> {

    init {
        require(rawValue in 0 until COUNT) { "NodeIndex out of range: $rawValue" }
    }

    /** Hex character: 0-9 then 'a'. */
    val character: Char
        get() = if (rawValue < 10) ('0' + rawValue) else 'a'

    override fun compareTo(other: NodeIndex): Int = rawValue.compareTo(other.rawValue)

    override fun toString(): String = character.toString()

    companion object {
        const val COUNT = 11

        /** All 11 nodes in ascending order. */
        val ALL: List<NodeIndex> = (0 until COUNT).map { NodeIndex(it) }

        /** Parse from a hex character (0-9, a). Returns null if invalid. */
        fun fromCharacter(ch: Char): NodeIndex? {
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> 10 + (ch - 'a')
                in 'A'..'F' -> 10 + (ch - 'A')
                else -> -1
            }
            return if (v in 0 until COUNT) NodeIndex(v) else null
        }
    }
}
