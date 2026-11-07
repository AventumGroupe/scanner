package com.aventum.scanner

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.Properties

private const val META = "scan.properties"
private const val THUMBNAIL = "thumbnail.jpg"

/** Un scan de l'historique : son dossier privé, ses fichiers et ses informations. */
class ScanRecord(
    val dir: File,
    val mode: OutputMode,
    val pageCount: Int,
    val created: Long,
    val saved: Boolean,
) {
    val name: String get() = dir.name
    val thumbnail: File get() = File(dir, THUMBNAIL)
    val files: List<File> = dir.listFiles()
        ?.filter { it.name != META && it.name != THUMBNAIL }
        ?.sortedBy { it.name }
        .orEmpty()
    val totalSize: Long = files.sumOf { it.length() }
}

/** Historique des scans, conservé dans le stockage privé de l'application. */
object ScanStore {

    private fun root(context: Context) = File(context.filesDir, "scans").apply { mkdirs() }

    /** Scans du plus récent au plus ancien. À appeler hors du thread principal. */
    fun list(context: Context): List<ScanRecord> =
        root(context).listFiles()
            ?.filter(File::isDirectory)
            ?.mapNotNull(::load)
            ?.sortedByDescending { it.created }
            .orEmpty()

    /** Crée un dossier vide pour un nouveau scan, avec un nom unique. */
    fun newDir(context: Context, baseName: String): File {
        var dir = File(root(context), baseName)
        var suffix = 2
        while (dir.exists()) dir = File(root(context), "${baseName}_${suffix++}")
        dir.mkdirs()
        return dir
    }

    /** Termine un scan dont les fichiers sont déjà écrits dans [dir]. */
    fun finish(dir: File, mode: OutputMode, pageCount: Int, firstPage: File): ScanRecord {
        decodeSampled(firstPage, 240)?.let { bitmap ->
            File(dir, THUMBNAIL).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
        }
        writeMeta(dir, mode, pageCount, System.currentTimeMillis(), saved = false)
        return load(dir) ?: throw IllegalStateException("Scan illisible : ${dir.name}")
    }

    fun markSaved(record: ScanRecord) =
        writeMeta(record.dir, record.mode, record.pageCount, record.created, saved = true)

    fun delete(record: ScanRecord) = record.dir.deleteRecursively()

    private fun writeMeta(dir: File, mode: OutputMode, pageCount: Int, created: Long, saved: Boolean) {
        val props = Properties().apply {
            setProperty("mode", mode.name)
            setProperty("pages", pageCount.toString())
            setProperty("created", created.toString())
            setProperty("saved", saved.toString())
        }
        File(dir, META).outputStream().use { props.store(it, null) }
    }

    /** Lit un scan ; null pour un dossier incomplet (scan interrompu). */
    private fun load(dir: File): ScanRecord? {
        val meta = File(dir, META)
        if (!meta.exists()) return null
        val props = Properties().apply { meta.inputStream().use { load(it) } }
        val mode = OutputMode.fromName(props.getProperty("mode")) ?: return null
        return ScanRecord(
            dir = dir,
            mode = mode,
            pageCount = props.getProperty("pages")?.toIntOrNull() ?: 1,
            created = props.getProperty("created")?.toLongOrNull() ?: dir.lastModified(),
            saved = props.getProperty("saved").toBoolean(),
        )
    }
}
