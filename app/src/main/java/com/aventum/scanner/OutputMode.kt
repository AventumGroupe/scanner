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
    /** Texte imprimé reconnu sur l'appareil (OCR), un seul fichier .txt. */
    TXT(R.string.mode_txt, "TXT", "text/plain"),
    /** Texte imprimé reconnu sur l'appareil (OCR), un seul document Word. */
    DOCX(R.string.mode_docx, "Word", DOCX_MIME),
    /** Texte, y compris manuscrit, lu par l'IA Claude, un seul fichier .txt. */
    TXT_AI(R.string.mode_txt_ai, "TXT · IA", "text/plain"),
    /** Texte, y compris manuscrit, lu par l'IA Claude, un seul document Word. */
    DOCX_AI(R.string.mode_docx_ai, "Word · IA", DOCX_MIME);

    val usesAi get() = this == TXT_AI || this == DOCX_AI
}

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
