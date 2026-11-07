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
    /** Texte reconnu (OCR), un seul fichier .txt. */
    TXT(R.string.mode_txt, "TXT", "text/plain"),
    /** Texte reconnu (OCR), un seul document Word. */
    DOCX(R.string.mode_docx, "Word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}
