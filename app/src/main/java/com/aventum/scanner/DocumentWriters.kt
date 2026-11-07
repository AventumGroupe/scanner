package com.aventum.scanner

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Écrit le texte reconnu en .docx minimal (Word, LibreOffice, Google Docs), un saut de page entre les pages. */
object DocxWriter {

    fun write(pages: List<String>, target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/document.xml", document(pages))
        }
    }

    private fun document(pages: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        pages.forEachIndexed { index, page ->
            if (index > 0) append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            page.split('\n').forEach { line ->
                append("""<w:p><w:r><w:t xml:space="preserve">""").append(escape(line)).append("</w:t></w:r></w:p>")
            }
        }
        append("</w:body></w:document>")
    }

    private fun escape(text: String) = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' && c != '\t' -> Unit // caractères de contrôle interdits en XML
                else -> append(c)
            }
        }
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
}
