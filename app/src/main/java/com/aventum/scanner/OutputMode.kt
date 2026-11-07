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
    /** Texte de toutes les pages, y compris manuscrit, lu par l'IA Claude, dans un seul document Word. */
    OCR(R.string.mode_ocr, "Word · OCR", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    companion object {
        /** Retrouve un format enregistré, y compris sous les noms des versions précédentes. */
        fun fromName(name: String?): OutputMode? = when (name) {
            "DOCX", "DOCX_AI" -> OCR
            else -> entries.firstOrNull { it.name == name }
        }
    }
}
