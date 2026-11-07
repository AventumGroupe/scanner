package com.aventum.scanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Reconnaissance de texte (OCR) sur l'appareil, sans envoi des images sur internet. */
object TextExtractor {

    /** Renvoie le texte de chaque image, paragraphes séparés par une ligne vide. À appeler hors du thread principal. */
    fun recognize(context: Context, images: List<File>): List<String> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return images.map { file ->
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                Tasks.await(recognizer.process(image)).textBlocks.joinToString("\n\n") { it.text }
            }
        } finally {
            recognizer.close()
        }
    }
}
