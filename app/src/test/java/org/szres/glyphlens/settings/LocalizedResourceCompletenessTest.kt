package org.szres.glyphlens.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Executes against the actual shipped XML, with no fallback or mocked resource table. */
class LocalizedResourceCompletenessTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test
    fun allFourLocalesHaveEveryKeyAndMatchingFormatArguments() {
        val base = read(File(res, "values/strings.xml"))
        assertTrue(base.isNotEmpty())
        for (folder in listOf("values-en", "values-ja", "values-zh-rCN", "values-zh-rTW")) {
            val localized = read(File(res, "$folder/strings.xml"))
            assertEquals(folder, base.keys, localized.keys)
            base.forEach { (name, value) ->
                assertEquals(
                    "$folder/$name",
                    placeholder.findAll(value).map { it.value }.toList(),
                    placeholder.findAll(localized.getValue(name)).map { it.value }.toList(),
                )
            }
        }
    }

    @Test
    fun machineTranslationsAreExplicitlyUnreviewedAndKoreanUiIsAbsent() {
        for (folder in listOf("values-ja", "values-zh-rCN", "values-zh-rTW")) {
            val source = File(res, "$folder/strings.xml").readText()
            assertTrue(source.contains("Machine-written"))
            assertTrue(source.contains("Needs Review"))
            assertTrue(source.contains("Not human-reviewed"))
        }
        val koreanDirectories = res.listFiles().orEmpty().filter {
            it.isDirectory && it.name.startsWith("values-ko")
        }
        assertEquals(emptyList<File>(), koreanDirectories)
        assertFalse(File(res, "values-ko/strings.xml").exists())
    }

    private fun read(file: File): Map<String, String> {
        assertTrue("Missing ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }
}
