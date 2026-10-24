package com.aventum.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    private lateinit var btnScan: Button
    private lateinit var btnShareImages: Button
    private lateinit var btnSharePdf: Button
    private lateinit var txtStatus: TextView
    private val adapter = PageAdapter()

    /** Copies privées du dernier scan, partagées via le FileProvider. */
    private var pageFiles: List<File> = emptyList()
    private var pdfFile: File? = null

    private val sharedDir by lazy { File(cacheDir, "shared") }

    private val scanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

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
        btnShareImages = findViewById(R.id.btnShareImages)
        btnSharePdf = findViewById(R.id.btnSharePdf)
        txtStatus = findViewById(R.id.txtStatus)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
        }

        btnScan.setOnClickListener { onScanClicked() }
        btnShareImages.setOnClickListener { share(pageFiles, "image/jpeg") }
        btnSharePdf.setOnClickListener { pdfFile?.let { share(listOf(it), "application/pdf") } }

        // Restaure le dernier scan après une rotation d'écran.
        savedInstanceState?.let { state ->
            val pages = state.getStringArrayList(KEY_PAGES).orEmpty().map(::File).filter(File::exists)
            val pdf = state.getString(KEY_PDF)?.let(::File)?.takeIf(File::exists)
            if (pages.isNotEmpty()) {
                txtStatus.text = state.getString(KEY_STATUS)
                showScan(pages, pdf)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PAGES, ArrayList(pageFiles.map(File::getAbsolutePath)))
        outState.putString(KEY_PDF, pdfFile?.absolutePath)
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
        scanner.getStartScanIntent(this)
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

        btnScan.isEnabled = false
        txtStatus.text = getString(R.string.saving)

        lifecycleScope.launch {
            try {
                val (pages, pdf, pdfSaved) = withContext(Dispatchers.IO) { savePages(pageUris, pdfUri) }
                txtStatus.text = buildString {
                    append(resources.getQuantityString(R.plurals.saved, pages.size, pages.size))
                    if (pdfSaved) append('\n').append(getString(R.string.pdf_saved))
                }
                showScan(pages, pdf)
            } catch (e: Exception) {
                txtStatus.text = getString(R.string.save_error, e.localizedMessage ?: e.toString())
            } finally {
                btnScan.isEnabled = true
            }
        }
    }

    private data class Saved(val pages: List<File>, val pdf: File?, val pdfSaved: Boolean)

    /** Copie le scan dans le cache (pour le partage) puis dans la galerie / Téléchargements. */
    private fun savePages(pageUris: List<Uri>, pdfUri: Uri?): Saved {
        sharedDir.deleteRecursively()
        sharedDir.mkdirs()
        val baseName = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())

        val pages = pageUris.mapIndexed { index, uri ->
            val name = if (pageUris.size == 1) "$baseName.jpg" else "${baseName}_p${index + 1}.jpg"
            val file = File(sharedDir, name)
            copyUriToFile(uri, file)
            MediaStorage.saveImageToGallery(this, file, name)
            file
        }

        var pdfSaved = false
        val pdf = pdfUri?.let { uri ->
            val file = File(sharedDir, "$baseName.pdf")
            copyUriToFile(uri, file)
            pdfSaved = runCatching { MediaStorage.savePdfToDownloads(this, file, file.name) }
                .getOrDefault(false)
            file
        }
        return Saved(pages, pdf, pdfSaved)
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Lecture impossible : $uri")
        input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
    }

    private fun showScan(pages: List<File>, pdf: File?) {
        pageFiles = pages
        pdfFile = pdf
        btnShareImages.isEnabled = pages.isNotEmpty()
        btnSharePdf.isEnabled = pdf != null
        lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) { pages.mapNotNull { decodeThumbnail(it, 360) } }
            adapter.submit(thumbs)
        }
    }

    private fun decodeThumbnail(file: File, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
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
        const val KEY_PAGES = "pages"
        const val KEY_PDF = "pdf"
        const val KEY_STATUS = "status"
    }
}
