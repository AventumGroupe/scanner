package com.aventum.scanner

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Reconnaissance de texte (OCR) sur l'appareil, sans envoi des images sur internet. */
object TextExtractor {

    /** Assez de détail pour du texte de petite taille, sans saturer la mémoire. */
    private const val MAX_PIXELS = 3000

    /** Renvoie le texte de chaque image, paragraphes séparés par une ligne vide. À appeler hors du thread principal. */
    fun recognize(images: List<File>): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return images.map { file ->
                val bitmap = decodeSampled(file, MAX_PIXELS)
                    ?: throw IllegalStateException("Image illisible : ${file.name}")
                try {
                    Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        .textBlocks
                        .joinToString("\n\n") { it.text }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            recognizer.close()
        }
    }
}
