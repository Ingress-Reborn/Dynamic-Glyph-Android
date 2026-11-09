package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphTable

internal fun glyphTable(csv: String): GlyphTable = GlyphTable.parse(csv)

internal fun pixelTestTable(): GlyphTable = glyphTable(
    """
    advance,038
    all,018a920
    balance,0568a975
    clear,05a
    clear_all,018a9205a
    see,03
    attack,0389
    use,275
    capture,2750
    """.trimIndent()
)

internal fun id(name: String): GlyphID = GlyphID(name)
