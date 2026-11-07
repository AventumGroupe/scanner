package com.aventum.scanner

import androidx.annotation.StringRes

/** Format des fichiers produits par un scan. */
enum class OutputMode(@StringRes val label: Int, val shortLabel: String, val mime: String) {
    /** Une image JPEG par page. */
    JPG(R.string.mode_jpg, "JPG", "image/jpeg"),
    /** Un PDF par page. */
    PDF_PER_PAGE(R.string.mode_pdf_per_page, "PDF", "application/pdf"),
    /** Un seul PDF regroupant toutes les pages. */
    PDF_BATCH(R.string.mode_pdf_batch, "PDF", "application/pdf"),
    /** Texte imprimé de toutes les pages, reconnu sur le téléphone (hors ligne), dans un seul document Word. */
    OCR_PRINTED(R.string.mode_ocr_printed, "Word · imprimé", DOCX_MIME),
    /** Texte de toutes les pages, y compris manuscrit, lu par l'IA Claude, dans un seul document Word. */
    OCR(R.string.mode_ocr, "Word · manuscrit", DOCX_MIME);

    companion object {
        /** Retrouve un format enregistré, y compris sous les noms des versions précédentes. */
        fun fromName(name: String?): OutputMode? = when (name) {
            "DOCX" -> OCR_PRINTED
            "DOCX_AI" -> OCR
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
