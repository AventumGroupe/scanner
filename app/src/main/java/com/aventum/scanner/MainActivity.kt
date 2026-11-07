package com.aventum.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /** Format des fichiers produits par un scan. */
    private enum class OutputMode(val radioId: Int, val mime: String) {
        /** Une image JPEG par page, enregistrée dans la galerie. */
        JPG(R.id.modeJpg, "image/jpeg"),
        /** Un PDF par page. */
        PDF_PER_PAGE(R.id.modePdfPerPage, "application/pdf"),
        /** Un seul PDF regroupant toutes les pages. */
        PDF_BATCH(R.id.modePdfBatch, "application/pdf"),
        /** Texte reconnu (OCR), un seul fichier .txt. */
        TXT(R.id.modeTxt, "text/plain"),
        /** Texte reconnu (OCR), un seul document Word. */
        DOCX(R.id.modeDocx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        companion object {
            fun fromRadioId(id: Int) = entries.firstOrNull { it.radioId == id } ?: JPG
        }
    }

    private lateinit var btnScan: Button
    private lateinit var btnSave: Button
    private lateinit var btnEmail: Button
    private lateinit var btnShare: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var txtStatus: TextView
    private val adapter = PageAdapter()

    /** Pages du dernier scan (pour les miniatures). */
    private var pageFiles: List<File> = emptyList()
    /** Fichiers produits par le dernier scan, dans le cache, partagés via le FileProvider. */
    private var outputFiles: List<File> = emptyList()
    private var outputMode = OutputMode.JPG
    private var saved = false

    private val sharedDir by lazy { File(cacheDir, "shared") }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val selectedMode get() = OutputMode.fromRadioId(modeGroup.checkedRadioButtonId)

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.let(::onScanned)
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveOutputs() else toast(getString(R.string.permission_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnSave = findViewById(R.id.btnSave)
        btnEmail = findViewById(R.id.btnEmail)
        btnShare = findViewById(R.id.btnShare)
        modeGroup = findViewById(R.id.modeGroup)
        txtStatus = findViewById(R.id.txtStatus)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
        }

        // Mémorise le dernier format choisi.
        val savedMode = prefs.getString(KEY_MODE, null)
        modeGroup.check((OutputMode.entries.firstOrNull { it.name == savedMode } ?: OutputMode.JPG).radioId)
        modeGroup.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putString(KEY_MODE, selectedMode.name).apply()
        }

        btnScan.setOnClickListener { startScan() }
        btnSave.setOnClickListener { onSaveClicked() }
        btnEmail.setOnClickListener { share(email = true) }
        btnShare.setOnClickListener { share(email = false) }

        // Restaure le dernier scan après une rotation d'écran.
        savedInstanceState?.let { state ->
            val pages = state.getStringArrayList(KEY_PAGES).orEmpty().map(::File).filter(File::exists)
            val outputs = state.getStringArrayList(KEY_OUTPUTS).orEmpty().map(::File).filter(File::exists)
            if (outputs.isNotEmpty()) {
                txtStatus.text = state.getString(KEY_STATUS)
                val mode = OutputMode.entries.firstOrNull { it.name == state.getString(KEY_OUTPUT_MODE) }
                showScan(pages, outputs, mode ?: OutputMode.JPG, state.getBoolean(KEY_SAVED))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PAGES, ArrayList(pageFiles.map(File::getAbsolutePath)))
        outState.putStringArrayList(KEY_OUTPUTS, ArrayList(outputFiles.map(File::getAbsolutePath)))
        outState.putString(KEY_OUTPUT_MODE, outputMode.name)
        outState.putBoolean(KEY_SAVED, saved)
        outState.putString(KEY_STATUS, txtStatus.text.toString())
    }

    private fun startScan() {
        val builder = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        // Le PDF multipage produit par le scanner n'est utile qu'en mode lot.
        if (selectedMode == OutputMode.PDF_BATCH) {
            builder.setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
        } else {
            builder.setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        }
        GmsDocumentScanning.getClient(builder.build()).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.scan_error, e.localizedMessage ?: e.toString()))
            }
    }

    private fun onScanned(result: GmsDocumentScanningResult) {
        val pageUris = result.pages?.map { it.imageUri }.orEmpty()
        val pdfUri = result.pdf?.uri
        if (pageUris.isEmpty()) return
        val mode = selectedMode

        setBusy(true)
        txtStatus.text = getString(
            if (mode == OutputMode.TXT || mode == OutputMode.DOCX) R.string.recognizing else R.string.preparing
        )

        lifecycleScope.launch {
            try {
                val (pages, outputs) = withContext(Dispatchers.IO) { buildOutputs(pageUris, pdfUri, mode) }
                txtStatus.text = resources.getQuantityString(R.plurals.scanned, pages.size, pages.size)
                showScan(pages, outputs, mode, saved = false)
            } catch (e: Exception) {
                txtStatus.text = getString(R.string.process_error, e.localizedMessage ?: e.toString())
            } finally {
                setBusy(false)
            }
        }
    }

    /** Produit, dans le cache, les fichiers du format choisi. Renvoie les pages et les fichiers produits. */
    private fun buildOutputs(pageUris: List<Uri>, pdfUri: Uri?, mode: OutputMode): Pair<List<File>, List<File>> {
        sharedDir.deleteRecursively()
        sharedDir.mkdirs()
        val baseName = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val single = pageUris.size == 1
        fun pageName(index: Int, ext: String) = if (single) "$baseName.$ext" else "${baseName}_p${index + 1}.$ext"

        val pages = pageUris.mapIndexed { index, uri ->
            File(sharedDir, pageName(index, "jpg")).also { copyUriToFile(uri, it) }
        }

        val outputs = when (mode) {
            OutputMode.JPG -> pages
            OutputMode.PDF_PER_PAGE -> pages.mapIndexed { index, page ->
                File(sharedDir, pageName(index, "pdf")).also { PdfMaker.fromImages(listOf(page), it) }
            }
            OutputMode.PDF_BATCH -> {
                val pdf = File(sharedDir, "$baseName.pdf")
                if (pdfUri != null) copyUriToFile(pdfUri, pdf) else PdfMaker.fromImages(pages, pdf)
                listOf(pdf)
            }
            OutputMode.TXT -> {
                val txt = File(sharedDir, "$baseName.txt")
                writeTxt(TextExtractor.recognize(this, pages), txt)
                listOf(txt)
            }
            OutputMode.DOCX -> {
                val docx = File(sharedDir, "$baseName.docx")
                DocxWriter.write(TextExtractor.recognize(this, pages), docx)
                listOf(docx)
            }
        }
        return pages to outputs
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Lecture impossible : $uri")
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
    }

    private fun showScan(pages: List<File>, outputs: List<File>, mode: OutputMode, saved: Boolean) {
        pageFiles = pages
        outputFiles = outputs
        outputMode = mode
        this.saved = saved
        updateButtons()
        lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) { pages.mapNotNull { decodeSampled(it, 360) } }
            adapter.submit(thumbs)
        }
    }

    private fun setBusy(busy: Boolean) {
        btnScan.isEnabled = !busy
        if (busy) {
            btnSave.isEnabled = false
            btnEmail.isEnabled = false
            btnShare.isEnabled = false
        } else {
            updateButtons()
        }
    }

    private fun updateButtons() {
        val ready = outputFiles.isNotEmpty()
        btnSave.isEnabled = ready && !saved
        btnEmail.isEnabled = ready
        btnShare.isEnabled = ready
    }

    private fun onSaveClicked() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveOutputs()
        }
    }

    /** Copie les fichiers produits aux emplacements habituels : galerie pour les JPG, Téléchargements sinon. */
    private fun saveOutputs() {
        val files = outputFiles
        val mode = outputMode
        if (files.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    files.forEach { file ->
                        if (mode == OutputMode.JPG) {
                            MediaStorage.saveImageToGallery(this@MainActivity, file, file.name)
                        } else {
                            MediaStorage.saveToDownloads(this@MainActivity, file, file.name, mode.mime)
                        }
                    }
                }
                saved = true
                val message = if (mode == OutputMode.JPG) R.plurals.saved_gallery else R.plurals.saved_downloads
                txtStatus.text = resources.getQuantityString(message, files.size, files.size)
            } catch (e: Exception) {
                txtStatus.text = getString(R.string.save_error, e.localizedMessage ?: e.toString())
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * Envoie les fichiers produits. Avec [email], seules les applications de messagerie
     * (Gmail, Outlook…) sont proposées ; sinon toutes : Messages, WhatsApp, Drive…
     */
    private fun share(email: Boolean) {
        val files = outputFiles
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.apply {
            type = outputMode.mime
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            if (email) {
                putExtra(Intent.EXTRA_TEXT, getString(R.string.email_body))
                selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            }
            clipData = ClipData.newUri(contentResolver, files[0].name, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = getString(if (email) R.string.email_via else R.string.share_via)
        try {
            startActivity(Intent.createChooser(intent, title))
        } catch (e: ActivityNotFoundException) {
            toast(getString(if (email) R.string.no_email_app else R.string.no_app))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_MODE = "output_mode"
        const val KEY_PAGES = "pages"
        const val KEY_OUTPUTS = "outputs"
        const val KEY_OUTPUT_MODE = "current_output_mode"
        const val KEY_SAVED = "saved"
        const val KEY_STATUS = "status"
    }
}
