package org.szres.glyphlens.tables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.SequenceTable
import java.io.ByteArrayInputStream
import java.nio.charset.MalformedInputException

class TableFileStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun importPersistsBytesAndLoadsTablesIndependently() {
        val store = TableFileStore(temporary.root)

        val shapes = store.import(
            TableFileStore.Kind.SHAPES,
            ByteArrayInputStream("advance,038\n".toByteArray()),
            GlyphTable::parse,
        )
        val sequences = store.import(
            TableFileStore.Kind.SEQUENCES,
            ByteArrayInputStream("advance,advance,,,\r\n".toByteArray()),
            SequenceTable::parse,
        )

        assertEquals(1, shapes.count)
        assertEquals(1, sequences.count)
        assertEquals(2, sequences.sequences.single().size)
        assertEquals(1, store.stored(TableFileStore.Kind.SHAPES, GlyphTable::parse)?.count)
        assertEquals(1, store.stored(TableFileStore.Kind.SEQUENCES, SequenceTable::parse)?.count)
    }

    @Test
    fun missingTableIsDistinctFromAnUnreadableTable() {
        val store = TableFileStore(temporary.root)

        assertNull(store.stored(TableFileStore.Kind.SHAPES, String::trim))
        assertThrows(MalformedInputException::class.java) {
            store.import(
                TableFileStore.Kind.SHAPES,
                ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)),
                String::trim,
            )
        }
        assertNull(store.stored(TableFileStore.Kind.SHAPES, String::trim))
    }

    @Test
    fun builtInTableIsUsedUntilAUserImportReplacesIt() {
        val store = TableFileStore(temporary.root) { kind ->
            if (kind == TableFileStore.Kind.SHAPES) {
                ByteArrayInputStream("advance,038\n".toByteArray())
            } else {
                null
            }
        }

        assert(store.exists(TableFileStore.Kind.SHAPES))
        assertEquals(1, store.stored(TableFileStore.Kind.SHAPES, GlyphTable::parse)?.count)

        store.import(
            TableFileStore.Kind.SHAPES,
            ByteArrayInputStream("clear,05a\n".toByteArray()),
            GlyphTable::parse,
        )

        assertEquals("clear", store.stored(TableFileStore.Kind.SHAPES, GlyphTable::parse)
            ?.glyphs?.single()?.name)
    }

    @Test
    fun failedParseDoesNotReplaceThePreviousTable() {
        val store = TableFileStore(temporary.root)
        store.import(
            TableFileStore.Kind.SEQUENCES,
            ByteArrayInputStream("advance".toByteArray()),
            SequenceTable::parse,
        )

        assertThrows(SequenceTable.ParseError.InteriorGap::class.java) {
            store.import(
                TableFileStore.Kind.SEQUENCES,
                ByteArrayInputStream("advance,,attack,,,".toByteArray()),
                SequenceTable::parse,
            )
        }

        assertEquals("advance", store.stored(TableFileStore.Kind.SEQUENCES, SequenceTable::parse)
            ?.sequences?.single()?.single()?.name)
    }
}
