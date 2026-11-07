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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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

    private lateinit var btnScan: Button
    private lateinit var formatField: MaterialAutoCompleteTextView
    private lateinit var txtStatus: TextView
    private lateinit var txtHistoryTitle: TextView
    private val adapter = ScanAdapter { showActions(it, getString(R.string.actions_title, it.name)) }

    private var mode = OutputMode.JPG
    /** Scan à enregistrer une fois l'autorisation de stockage accordée (Android 8 et 9). */
    private var pendingSave: ScanRecord? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.let(::onScanned)
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val record = pendingSave
            pendingSave = null
            when {
                !granted -> toast(getString(R.string.permission_denied))
                record != null -> save(record)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        formatField = findViewById(R.id.formatField)
        txtStatus = findViewById(R.id.txtStatus)
        txtHistoryTitle = findViewById(R.id.txtHistoryTitle)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Format choisi, mémorisé d'une utilisation à l'autre.
        mode = OutputMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: OutputMode.JPG
        formatField.setSimpleItems(OutputMode.entries.map { getString(it.label) }.toTypedArray())
        formatField.setText(getString(mode.label), false)
        formatField.setOnItemClickListener { _, _, position, _ ->
            mode = OutputMode.entries[position]
            prefs.edit().putString(KEY_MODE, mode.name).apply()
        }

        btnScan.setOnClickListener { startScan() }
        refreshHistory()
    }

    private fun refreshHistory() {
        lifecycleScope.launch {
            val scans = withContext(Dispatchers.IO) { ScanStore.list(this@MainActivity) }
            adapter.submit(scans)
            txtHistoryTitle.isVisible = scans.isNotEmpty()
        }
    }

    private fun startScan() {
        val builder = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        // Le PDF multipage produit par le scanner n'est utile qu'en mode lot.
        if (mode == OutputMode.PDF_BATCH) {
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
        val scanMode = mode

        btnScan.isEnabled = false
        showStatus(getString(
            if (scanMode == OutputMode.TXT || scanMode == OutputMode.DOCX) R.string.recognizing else R.string.preparing
        ))

        lifecycleScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { buildScan(pageUris, pdfUri, scanMode) }
                showStatus(null)
                refreshHistory()
                showActions(record, getString(R.string.scan_ready))
            } catch (e: Exception) {
                showStatus(getString(R.string.process_error, e.localizedMessage ?: e.toString()))
            } finally {
                btnScan.isEnabled = true
            }
        }
    }

    /** Produit les fichiers du format choisi dans un nouveau dossier de l'historique. */
    private fun buildScan(pageUris: List<Uri>, pdfUri: Uri?, mode: OutputMode): ScanRecord {
        val tmp = File(cacheDir, "pages").apply { deleteRecursively(); mkdirs() }
        val dir = ScanStore.newDir(this, "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()))
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
                OutputMode.TXT -> writeTxt(TextExtractor.recognize(this, pages), File(dir, "$baseName.txt"))
                OutputMode.DOCX -> DocxWriter.write(TextExtractor.recognize(this, pages), File(dir, "$baseName.docx"))
            }
            return ScanStore.finish(dir, mode, pages.size, pages[0])
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Lecture impossible : $uri")
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
    }

    private fun showStatus(message: String?) {
        txtStatus.text = message
        txtStatus.isVisible = message != null
    }

    /** Menu d'actions d'un scan : affiché après chaque scan et en touchant un scan de la liste. */
    private fun showActions(record: ScanRecord, title: String) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.action_open) to { open(record) })
            if (!record.saved) {
                val label = if (record.mode == OutputMode.JPG) R.string.action_save_gallery else R.string.action_save_downloads
                add(getString(label) to { requestSave(record) })
            }
            add(getString(R.string.action_email) to { share(record, email = true) })
            add(getString(R.string.action_share) to { share(record, email = false) })
            add(getString(R.string.action_delete) to { confirmDelete(record) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun open(record: ScanRecord) {
        val file = record.files.firstOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(file), record.mode.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun requestSave(record: ScanRecord) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSave = record
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(record)
        }
    }

    /** Copie les fichiers aux emplacements habituels : galerie pour les JPG, Téléchargements sinon. */
    private fun save(record: ScanRecord) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    record.files.forEach { file ->
                        if (record.mode == OutputMode.JPG) {
                            MediaStorage.saveImageToGallery(this@MainActivity, file, file.name)
                        } else {
                            MediaStorage.saveToDownloads(this@MainActivity, file, file.name, record.mode.mime)
                        }
                    }
                    ScanStore.markSaved(record)
                }
                val message = if (record.mode == OutputMode.JPG) R.plurals.saved_gallery else R.plurals.saved_downloads
                toast(resources.getQuantityString(message, record.files.size, record.files.size))
                refreshHistory()
            } catch (e: Exception) {
                toast(getString(R.string.save_error, e.localizedMessage ?: e.toString()))
            }
        }
    }

    /**
     * Envoie les fichiers du scan. Avec [email], seules les applications de messagerie
     * (Gmail, Outlook…) sont proposées ; sinon toutes : Messages, WhatsApp, Drive…
     */
    private fun share(record: ScanRecord, email: Boolean) {
        val files = record.files
        if (files.isEmpty()) return
        val uris = files.map(::uriOf)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.apply {
            type = record.mode.mime
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

    private fun confirmDelete(record: ScanRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_title, record.name))
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ScanStore.delete(record) }
                    refreshHistory()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_MODE = "output_mode"
    }
}
