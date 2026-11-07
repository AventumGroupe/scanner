package com.aventum.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val viewModel: ScanViewModel by viewModels()

    private lateinit var btnScan: Button
    private lateinit var formatField: MaterialAutoCompleteTextView
    private lateinit var progressCard: MaterialCardView
    private lateinit var progressLabel: TextView
    private lateinit var progressDeterminate: LinearProgressIndicator
    private lateinit var progressIndeterminate: LinearProgressIndicator
    private lateinit var btnCancel: Button
    private lateinit var txtHistoryTitle: TextView
    private val adapter = ScanAdapter { showActions(it, ready = false) }

    private var mode = OutputMode.JPG
    /** Évite d'ouvrir deux scanners sur un double appui. */
    private var scannerOpening = false
    /** Scan à enregistrer une fois l'autorisation de stockage accordée (Android 8 et 9). */
    private var pendingSave: ScanRecord? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scannerOpening = false
            if (result.resultCode == RESULT_OK) {
                GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.let { scan ->
                    viewModel.process(scan.pages?.map { it.imageUri }.orEmpty(), scan.pdf?.uri, mode)
                }
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val record = pendingSave
            pendingSave = null
            when {
                !granted -> toast(getString(R.string.permission_denied))
                record != null -> viewModel.save(record)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        formatField = findViewById(R.id.formatField)
        progressCard = findViewById(R.id.progressCard)
        progressLabel = findViewById(R.id.progressLabel)
        progressDeterminate = findViewById(R.id.progressDeterminate)
        progressIndeterminate = findViewById(R.id.progressIndeterminate)
        btnCancel = findViewById(R.id.btnCancel)
        txtHistoryTitle = findViewById(R.id.txtHistoryTitle)
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Format choisi, mémorisé d'une utilisation à l'autre.
        mode = OutputMode.fromName(prefs.getString(KEY_MODE, null)) ?: OutputMode.JPG
        formatField.setSimpleItems(OutputMode.entries.map { getString(it.label) }.toTypedArray())
        formatField.setText(getString(mode.label), false)
        formatField.setOnItemClickListener { _, _, position, _ ->
            mode = OutputMode.entries[position]
            prefs.edit().putString(KEY_MODE, mode.name).apply()
        }

        findViewById<View>(R.id.btnFormatInfo).setOnClickListener {
            info(R.string.formats_info_title, getString(R.string.formats_info_message))
        }
        btnScan.setOnClickListener { onScanClicked() }
        btnCancel.setOnClickListener { viewModel.cancel() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect(::showBusy) }
                launch {
                    viewModel.scans.collect { scans ->
                        adapter.submit(scans)
                        txtHistoryTitle.isVisible = scans.isNotEmpty()
                    }
                }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menuApiKey -> {
            showApiKeyDialog()
            true
        }
        R.id.menuHelp -> {
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            info(R.string.help_title, getString(R.string.help_message, version))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun onEvent(event: ScanViewModel.Event) {
        when (event) {
            is ScanViewModel.Event.Ready -> showActions(event.record, ready = true)
            is ScanViewModel.Event.Failed -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.process_error_title)
                .setMessage(event.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            is ScanViewModel.Event.Message -> toast(event.text)
        }
    }

    /** Carte de progression ; l'écran reste allumé pendant un traitement. */
    private fun showBusy(busy: ScanViewModel.Busy?) {
        btnScan.isEnabled = busy == null
        progressCard.isVisible = busy != null
        if (busy != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val reading = busy as? ScanViewModel.Busy.Reading
        progressDeterminate.isVisible = reading != null
        progressIndeterminate.isVisible = busy != null && reading == null
        btnCancel.isVisible = reading != null
        progressLabel.text = when (busy) {
            is ScanViewModel.Busy.Reading -> getString(
                if (busy.ai) R.string.progress_reading_ai else R.string.progress_reading, busy.page, busy.total
            )
            ScanViewModel.Busy.Cancelling -> getString(R.string.cancelling)
            else -> getString(R.string.preparing)
        }
        // La page en cours compte pour moitié : la barre avance dès le début de sa lecture.
        if (reading != null) progressDeterminate.setProgressCompat((reading.page * 2 - 1) * 50 / reading.total, true)
    }

    private fun onScanClicked() {
        if (scannerOpening) return
        // L'OCR par IA a besoin de la clé d'API : la demander avant de scanner.
        if (mode == OutputMode.OCR && SecretStore.loadApiKey(this) == null) {
            showApiKeyDialog(onSaved = ::startScan)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        scannerOpening = true
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
                scannerOpening = false
                toast(getString(R.string.scan_error, e.localizedMessage ?: e.toString()))
            }
    }

    /** Saisie de la clé d'API Anthropic, chiffrée sur le téléphone. */
    private fun showApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val hasKey = SecretStore.loadApiKey(this) != null
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        val field = TextInputLayout(this).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            hint = getString(R.string.api_key_hint)
            addView(input)
        }
        val container = LinearLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.api_key_title)
            .setMessage(R.string.api_key_message)
            .setView(container)
            .setPositiveButton(R.string.api_key_save) { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (!key.startsWith("sk-ant-")) {
                    toast(getString(R.string.api_key_invalid))
                } else {
                    SecretStore.saveApiKey(this, key)
                    toast(getString(R.string.api_key_saved))
                    onSaved?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
        if (hasKey) {
            builder.setNeutralButton(R.string.api_key_delete) { _, _ ->
                SecretStore.clearApiKey(this)
                toast(getString(R.string.api_key_deleted))
            }
        }
        builder.show()
    }

    /** Actions d'un scan, sous forme d'icônes : après chaque scan et en touchant un scan de la liste. */
    private fun showActions(record: ScanRecord, ready: Boolean) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_actions, null)
        view.findViewById<TextView>(R.id.sheetTitle).text = if (ready) getString(R.string.scan_ready) else record.name
        view.findViewById<TextView>(R.id.sheetSubtitle).text =
            if (ready) record.name else adapter.details(this, record)
        val row = view.findViewById<LinearLayout>(R.id.sheetActions)

        fun action(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean = true, onClick: () -> Unit) {
            val item = LayoutInflater.from(this).inflate(R.layout.item_action, row, false)
            item.findViewById<ImageView>(R.id.actionIcon).setImageResource(icon)
            item.findViewById<TextView>(R.id.actionLabel).setText(label)
            item.isEnabled = enabled
            item.alpha = if (enabled) 1f else 0.5f
            item.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            row.addView(item)
        }

        action(R.drawable.ic_open, R.string.action_open) { open(record) }
        if (record.saved) {
            action(R.drawable.ic_check, R.string.action_saved, enabled = false) {}
        } else {
            action(R.drawable.ic_save, R.string.action_save) { requestSave(record) }
        }
        action(R.drawable.ic_email, R.string.action_email) { share(record, email = true) }
        action(R.drawable.ic_share, R.string.action_share) { share(record, email = false) }
        action(R.drawable.ic_delete, R.string.action_delete) { confirmDelete(record) }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun info(@StringRes title: Int, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
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
            viewModel.save(record)
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
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.delete(record) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_MODE = "output_mode"
    }
}
