package org.szres.glyphlens.tables

import android.content.Context
import android.net.Uri
import org.szres.glyphlens.R
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.SequenceTable
import java.io.File
import java.io.IOException

class TableController(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val store = TableFileStore(File(appContext.filesDir, "tables")) { kind ->
        appContext.assets.open(kind.fileName)
    }

    var shapeTable: GlyphTable? = null
        private set
    var sequenceTable: SequenceTable? = null
        private set
    var shapeStatus: String = appContext.getString(R.string.table_shapes_missing)
        private set
    var sequenceStatus: String = appContext.getString(R.string.table_sequences_missing)
        private set

    val assistsGlyphs: Boolean
        get() = shapeTable != null

    fun loadStored() {
        loadStoredShapes()
        loadStoredSequences()
    }

    fun importShapes(uri: Uri) {
        try {
            val table = store.import(TableFileStore.Kind.SHAPES, open(uri), GlyphTable::parse)
            shapeTable = table
            shapeStatus = appContext.getString(R.string.table_shapes_loaded, table.count)
            sequenceTable?.let { describeSequences() }
        } catch (error: Exception) {
            shapeStatus = errorMessage(error)
        }
    }

    fun importSequences(uri: Uri) {
        try {
            sequenceTable = store.import(TableFileStore.Kind.SEQUENCES, open(uri), SequenceTable::parse)
            describeSequences()
        } catch (error: Exception) {
            sequenceStatus = errorMessage(error)
        }
    }

    private fun loadStoredShapes() {
        if (!store.exists(TableFileStore.Kind.SHAPES)) return
        try {
            val table = store.stored(TableFileStore.Kind.SHAPES, GlyphTable::parse) ?: return
            shapeTable = table
            shapeStatus = appContext.getString(R.string.table_shapes_loaded, table.count)
        } catch (error: Exception) {
            shapeStatus = errorMessage(error)
        }
    }

    private fun loadStoredSequences() {
        if (!store.exists(TableFileStore.Kind.SEQUENCES)) return
        try {
            sequenceTable = store.stored(TableFileStore.Kind.SEQUENCES, SequenceTable::parse) ?: return
            describeSequences()
        } catch (error: Exception) {
            sequenceStatus = errorMessage(error)
        }
    }

    private fun describeSequences() {
        val table = sequenceTable ?: return
        val findings = mutableListOf(appContext.getString(R.string.table_sequences_loaded, table.count))
        if (table.rowCount != table.count) {
            findings += appContext.getString(R.string.table_sequences_duplicates, table.rowCount)
        }
        val shapes = shapeTable
        if (shapes == null) {
            findings += appContext.getString(R.string.table_sequences_unchecked)
        } else {
            val known = shapes.glyphs.toSet()
            val unknown = table.sequences.flatten().toSet().subtract(known).sorted()
            if (unknown.isNotEmpty()) {
                val examples = unknown.take(3).joinToString(", ") { it.name } +
                    if (unknown.size > 3) "…" else ""
                findings += appContext.getString(
                    R.string.table_sequences_missing_names,
                    unknown.size,
                    examples,
                )
            }
        }
        sequenceStatus = findings.joinToString(" · ")
    }

    private fun open(uri: Uri) =
        resolver.openInputStream(uri) ?: throw IOException("Unable to open the selected file")

    private fun errorMessage(error: Exception): String =
        appContext.getString(R.string.table_import_failed, error.message ?: error.javaClass.simpleName)

}
