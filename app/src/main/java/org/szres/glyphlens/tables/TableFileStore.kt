package org.szres.glyphlens.tables

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TableFileStore(
    private val directory: File,
    private val builtIn: (Kind) -> InputStream? = { null },
) {
    enum class Kind(val fileName: String) {
        SHAPES("glyph_line.csv"),
        SEQUENCES("glyph.csv"),
    }

    fun <T> import(kind: Kind, input: InputStream, parse: (String) -> T): T {
        val bytes = input.use(InputStream::readBytes)
        val table = parse(decodeUtf8(bytes))
        directory.mkdirs()
        val temporary = File(directory, ".${kind.fileName}.tmp")
        Files.write(temporary.toPath(), bytes)
        Files.move(
            temporary.toPath(),
            File(directory, kind.fileName).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return table
    }

    fun exists(kind: Kind): Boolean {
        if (File(directory, kind.fileName).isFile) return true
        return builtIn(kind)?.use { true } ?: false
    }

    fun <T> stored(kind: Kind, parse: (String) -> T): T? {
        val file = File(directory, kind.fileName)
        if (file.isFile) return parse(decodeUtf8(file.readBytes()))
        return builtIn(kind)?.use { input -> parse(decodeUtf8(input.readBytes())) }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
}
