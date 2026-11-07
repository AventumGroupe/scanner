package com.aventum.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Traitements longs (création des fichiers, OCR, enregistrement) et historique des scans.
 * Survit aux rotations d'écran : le travail en cours n'est ni perdu ni relancé.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    /** Traitement en cours, affiché par la barre de progression. */
    sealed interface Busy {
        data object Preparing : Busy
        data class Reading(val page: Int, val total: Int, val ai: Boolean) : Busy
        data object Cancelling : Busy
    }

    /** Résultats à afficher une seule fois (même si l'écran a été recréé entre-temps). */
    sealed interface Event {
        data class Ready(val record: ScanRecord) : Event
        data class Failed(val message: String) : Event
        data class Message(val text: String) : Event
    }

    private val context get() = getApplication<Application>()

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy = _busy.asStateFlow()

    private val _scans = MutableStateFlow<List<ScanRecord>>(emptyList())
    val scans = _scans.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _scans.value = withContext(Dispatchers.IO) { ScanStore.list(context) }
        }
    }

    /** Crée le document du format choisi à partir des pages scannées. */
    fun process(pageUris: List<Uri>, pdfUri: Uri?, mode: OutputMode) {
        if (pageUris.isEmpty() || job?.isActive == true) return
        _busy.value = Busy.Preparing
        job = viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { buildScan(pageUris, pdfUri, mode) }
                refresh()
                _events.send(Event.Ready(record))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(Event.Failed(e.localizedMessage ?: e.toString()))
            } finally {
                _busy.value = null
            }
        }
    }

    /** Interrompt l'OCR ; prend effet à la fin de la page en cours de lecture. */
    fun cancel() {
        if (job?.isActive == true) {
            _busy.value = Busy.Cancelling
            job?.cancel()
        }
    }

    /** Copie les fichiers aux emplacements habituels : galerie pour les JPG, Téléchargements sinon. */
    fun save(record: ScanRecord) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    record.files.forEach { file ->
                        if (record.mode == OutputMode.JPG) {
                            MediaStorage.saveImageToGallery(context, file, file.name)
                        } else {
                            MediaStorage.saveToDownloads(context, file, file.name, record.mode.mime)
                        }
                    }
                    ScanStore.markSaved(record)
                }
                val message = if (record.mode == OutputMode.JPG) R.plurals.saved_gallery else R.plurals.saved_downloads
                _events.send(Event.Message(context.resources.getQuantityString(message, record.files.size, record.files.size)))
                refresh()
            } catch (e: Exception) {
                _events.send(Event.Message(context.getString(R.string.save_error, e.localizedMessage ?: e.toString())))
            }
        }
    }

    fun delete(record: ScanRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ScanStore.delete(record) }
            refresh()
        }
    }

    /** Produit les fichiers dans un nouveau dossier de l'historique ; le supprime en cas d'échec ou d'annulation. */
    private fun CoroutineScope.buildScan(pageUris: List<Uri>, pdfUri: Uri?, mode: OutputMode): ScanRecord {
        val tmp = File(context.cacheDir, "pages").apply { deleteRecursively(); mkdirs() }
        val dir = ScanStore.newDir(context, "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()))
        val baseName = dir.name
        try {
            val pages = pageUris.mapIndexed { index, uri ->
                File(tmp, "page${index + 1}.jpg").also { copyUriToFile(uri, it) }
            }
            fun pageFile(index: Int, ext: String) =
                File(dir, if (pages.size == 1) "$baseName.$ext" else "${baseName}_p${index + 1}.$ext")

            when (mode) {
                OutputMode.JPG -> pages.forEachIndexed { index, page -> page.copyTo(pageFile(index, "jpg")) }
                OutputMode.PDF_PER_PAGE -> pages.forEachIndexed { index, page ->
                    PdfMaker.fromImages(listOf(page), pageFile(index, "pdf"))
                }
                OutputMode.PDF_BATCH -> {
                    val pdf = File(dir, "$baseName.pdf")
                    if (pdfUri != null) copyUriToFile(pdfUri, pdf) else PdfMaker.fromImages(pages, pdf)
                }
                OutputMode.OCR_PRINTED -> DocxWriter.write(recognize(pages), File(dir, "$baseName.docx"))
                OutputMode.OCR -> DocxWriter.write(transcribe(pages), File(dir, "$baseName.docx"))
            }
            ensureActive()
            return ScanStore.finish(dir, mode, pages.size, pages[0])
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Reconnaissance du texte imprimé sur le téléphone, avec suivi de la progression. */
    private fun CoroutineScope.recognize(pages: List<File>): List<String> {
        val texts = try {
            TextExtractor.recognize(pages) { page ->
                ensureActive()
                _busy.value = Busy.Reading(page, pages.size, ai = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cause = e.cause?.localizedMessage ?: e.localizedMessage ?: e.toString()
            throw IllegalStateException(context.getString(R.string.ocr_error, cause), e)
        }
        if (texts.all { it.isBlank() }) throw IllegalStateException(context.getString(R.string.no_text_found))
        return texts
    }

    /** Lecture de toutes les pages par l'IA Claude, avec suivi de la progression. */
    private fun CoroutineScope.transcribe(pages: List<File>): List<String> {
        val apiKey = SecretStore.loadApiKey(context)
            ?: throw IllegalStateException(context.getString(R.string.api_key_missing))
        val texts = ClaudeTranscriber.transcribe(context, apiKey, pages) { page ->
            ensureActive()
            _busy.value = Busy.Reading(page, pages.size, ai = true)
        }
        if (texts.all { it.isBlank() }) throw IllegalStateException(context.getString(R.string.no_text_found))
        return texts
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(context.getString(R.string.read_error))
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
    }
}
