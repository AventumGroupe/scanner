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
    private enum class OutputMode(val radioId: Int) {
        /** Une image JPEG par page, dans la galerie. */
        JPG(R.id.modeJpg),
        /** Un PDF par page. */
        PDF_PER_PAGE(R.id.modePdfPerPage),
        /** Un seul PDF regroupant toutes les pages. */
        PDF_BATCH(R.id.modePdfBatch);

        companion object {
            fun fromRadioId(id: Int) = entries.firstOrNull { it.radioId == id } ?: JPG
        }
    }

    private lateinit var btnScan: Button
    private lateinit var btnShare: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var txtStatus: TextView
    private val adapter = PageAdapter()

    /** Pages du dernier scan (pour les miniatures). */
    private var pageFiles: List<File> = emptyList()
    /** Fichiers produits par le dernier scan, partagés via le FileProvider. */
    private var outputFiles: List<File> = emptyList()
    private var outputMime = "image/jpeg"

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
            if (granted) startScan() else toast(getString(R.string.permission_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
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

        btnScan.setOnClickListener { onScanClicked() }
        btnShare.setOnClickListener { share(outputFiles, outputMime) }

        // Restaure le dernier scan après une rotation d'écran.
        savedInstanceState?.let { state ->
            val pages = state.getStringArrayList(KEY_PAGES).orEmpty().map(::File).filter(File::exists)
            val outputs = state.getStringArrayList(KEY_OUTPUTS).orEmpty().map(::File).filter(File::exists)
            if (outputs.isNotEmpty()) {
                txtStatus.text = state.getString(KEY_STATUS)
                showScan(pages, outputs, state.getString(KEY_MIME) ?: outputMime)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PAGES, ArrayList(pageFiles.map(File::getAbsolutePath)))
        outState.putStringArrayList(KEY_OUTPUTS, ArrayList(outputFiles.map(File::getAbsolutePath)))
        outState.putString(KEY_MIME, outputMime)
        outState.putString(KEY_STATUS, txtStatus.text.toString())
    }

    private fun onScanClicked() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startScan()
        }
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

        btnScan.isEnabled = false
        btnShare.isEnabled = false
        txtStatus.text = getString(R.string.saving)

        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { saveScan(pageUris, pdfUri, mode) }
                txtStatus.text = saved.status
                showScan(saved.pages, saved.outputs, saved.mime)
            } catch (e: Exception) {
                txtStatus.text = getString(R.string.save_error, e.localizedMessage ?: e.toString())
            } finally {
                btnScan.isEnabled = true
            }
        }
    }

    private class Saved(val pages: List<File>, val outputs: List<File>, val mime: String, val status: String)

    /** Produit les fichiers du format choisi, dans le cache (pour le partage) et en stockage public. */
    private fun saveScan(pageUris: List<Uri>, pdfUri: Uri?, mode: OutputMode): Saved {
        sharedDir.deleteRecursively()
        sharedDir.mkdirs()
        val baseName = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val single = pageUris.size == 1
        fun pageName(index: Int, ext: String) = if (single) "$baseName.$ext" else "${baseName}_p${index + 1}.$ext"

        val pages = pageUris.mapIndexed { index, uri ->
            File(sharedDir, pageName(index, "jpg")).also { copyUriToFile(uri, it) }
        }

        return when (mode) {
            OutputMode.JPG -> {
                pages.forEach { MediaStorage.saveImageToGallery(this, it, it.name) }
                Saved(pages, pages, "image/jpeg",
                    resources.getQuantityString(R.plurals.saved_jpg, pages.size, pages.size))
            }
            OutputMode.PDF_PER_PAGE -> {
                val pdfs = pages.mapIndexed { index, page ->
                    File(sharedDir, pageName(index, "pdf")).also { pdf ->
                        PdfMaker.fromImages(listOf(page), pdf)
                        MediaStorage.savePdfToDownloads(this, pdf, pdf.name)
                    }
                }
                Saved(pages, pdfs, "application/pdf",
                    resources.getQuantityString(R.plurals.saved_pdf_per_page, pdfs.size, pdfs.size))
            }
            OutputMode.PDF_BATCH -> {
                val pdf = File(sharedDir, "$baseName.pdf")
                if (pdfUri != null) copyUriToFile(pdfUri, pdf) else PdfMaker.fromImages(pages, pdf)
                MediaStorage.savePdfToDownloads(this, pdf, pdf.name)
                Saved(pages, listOf(pdf), "application/pdf",
                    resources.getQuantityString(R.plurals.saved_pdf_batch, pages.size, pages.size))
            }
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Lecture impossible : $uri")
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
    }

    private fun showScan(pages: List<File>, outputs: List<File>, mime: String) {
        pageFiles = pages
        outputFiles = outputs
        outputMime = mime
        btnShare.isEnabled = outputs.isNotEmpty()
        lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) { pages.mapNotNull { decodeSampled(it, 360) } }
            adapter.submit(thumbs)
        }
    }

    /** Ouvre le sélecteur Android : Gmail, Outlook, Messages, WhatsApp, Drive… */
    private fun share(files: List<File>, mimeType: String) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            clipData = ClipData.newUri(contentResolver, files[0].name, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_MODE = "output_mode"
        const val KEY_PAGES = "pages"
        const val KEY_OUTPUTS = "outputs"
        const val KEY_MIME = "mime"
        const val KEY_STATUS = "status"
    }
}
