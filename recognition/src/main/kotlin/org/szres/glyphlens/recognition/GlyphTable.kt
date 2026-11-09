package org.szres.glyphlens.recognition

/** Maps Glyph names to their node walks, with ordered and direction-independent lookups. */
class GlyphTable(entries: Map<GlyphID, GlyphPath>) {
    sealed class ParseError(message: String) : Exception(message) {
        data object Empty : ParseError("no rows")

        data class WrongColumnCount(val line: Int, val found: Int) :
            ParseError("line $line: expected 2 columns, found $found")

        data class EmptyName(val line: Int) : ParseError("line $line: empty name")

        data class InvalidShape(val line: Int, val shape: String) :
            ParseError("line $line: invalid shape \"$shape\"")

        data class DuplicateName(val line: Int, val name: GlyphID) :
            ParseError("line $line: duplicate name $name")
    }

    private val pathsByID: Map<GlyphID, GlyphPath> = entries.toMap()
    private val idsByPath: Map<GlyphPath, GlyphID>
    private val idsByEdges: Map<Set<GlyphPath.Edge>, GlyphID>

    init {
        val byPath = mutableMapOf<GlyphPath, GlyphID>()
        val byEdges = mutableMapOf<Set<GlyphPath.Edge>, GlyphID>()
        for ((id, path) in pathsByID) {
            byPath[path] = byPath[path]?.let { minOf(it, id) } ?: id
            byEdges[path.undirectedEdges] = byEdges[path.undirectedEdges]?.let { minOf(it, id) } ?: id
        }
        idsByPath = byPath
        idsByEdges = byEdges
    }

    val count: Int
        get() = pathsByID.size

    val glyphs: List<GlyphID>
        get() = pathsByID.keys.sorted()

    fun path(forID: GlyphID): GlyphPath? = pathsByID[forID]

    /** Recognition lookup: direction and walk order do not affect the result. */
    fun glyphMatchingEdges(path: GlyphPath): GlyphID? = idsByEdges[path.undirectedEdges]

    /** Closest edge sets by symmetric-difference size, then Glyph name. */
    fun nearestByEdges(to: GlyphPath, maxDistance: Int = 2): List<GlyphCandidate> {
        val edges = to.undirectedEdges
        return pathsByID.map { (id, candidatePath) ->
            val candidateEdges = candidatePath.undirectedEdges
            val distance = (edges - candidateEdges).size + (candidateEdges - edges).size
            GlyphCandidate(id, distance)
        }.filter { it.distance <= maxDistance }.sorted()
    }

    /** Exact ordered-walk lookup, intentionally sensitive to reversal. */
    fun glyphMatching(path: GlyphPath): GlyphID? = idsByPath[path]

    /** Closest ordered walks by Levenshtein distance. */
    fun nearest(to: GlyphPath, maxDistance: Int = 2): List<GlyphCandidate> =
        pathsByID.map { (id, candidatePath) ->
            GlyphCandidate(id, to.editDistance(candidatePath))
        }.filter { it.distance <= maxDistance }.sorted()

    /** Distinct names that trace the same nodes in opposite order. */
    val mirrorPairs: List<Pair<GlyphID, GlyphID>>
        get() {
            val seen = mutableSetOf<GlyphID>()
            val pairs = mutableListOf<Pair<GlyphID, GlyphID>>()
            for (id in glyphs) {
                if (id in seen) continue
                val path = pathsByID[id] ?: continue
                val partner = idsByPath[path.reversed] ?: continue
                if (partner == id) continue
                seen.add(id)
                seen.add(partner)
                pairs.add(minOf(id, partner) to maxOf(id, partner))
            }
            return pairs
        }

    /** Groups whose undirected edge sets cannot be distinguished. */
    val edgeSetCollisions: List<List<GlyphID>>
        get() = glyphs.groupBy { pathsByID.getValue(it).undirectedEdges }
            .values.filter { it.size > 1 }.map { it.sorted() }

    companion object {
        /** Strictly parses exactly name,shape per nonblank row. */
        @JvmStatic
        fun parse(csv: String): GlyphTable {
            val entries = mutableMapOf<GlyphID, GlyphPath>()
            var lineNumber = 0
            for (rawLine in csv.splitIntoLines()) {
                lineNumber += 1
                val line = rawLine.trimmingWhitespace()
                if (line.isEmpty()) continue
                val fields = line.split(',').map { it.trimmingWhitespace() }

                if (lineNumber == 1 && fields.size >= 2 && fields[1].startsWith("shape")) continue
                if (fields.size != 2) {
                    throw ParseError.WrongColumnCount(lineNumber, fields.size)
                }
                if (fields[0].isEmpty()) throw ParseError.EmptyName(lineNumber)
                val path = GlyphPath.fromEncoded(fields[1])
                    ?: throw ParseError.InvalidShape(lineNumber, fields[1])
                val id = GlyphID(fields[0])
                if (entries.containsKey(id)) throw ParseError.DuplicateName(lineNumber, id)
                entries[id] = path
            }
            if (entries.isEmpty()) throw ParseError.Empty
            return GlyphTable(entries)
        }
    }
}

data class GlyphCandidate(val id: GlyphID, val distance: Int) : Comparable<GlyphCandidate> {
    override fun compareTo(other: GlyphCandidate): Int =
        compareValuesBy(this, other, { it.distance }, { it.id })
}

private val glyphNewlinePattern = Regex("\\r\\n|[\\n\\r\\u000B\\f\\u0085\\u2028\\u2029]")

/** Matches Swift split(whereSeparator: \.isNewline), including omitted empty lines. */
internal fun String.splitIntoLines(): List<String> =
    split(glyphNewlinePattern).filter { it.isNotEmpty() }

internal fun String.trimmingWhitespace(): String = trim { it.isWhitespace() }
