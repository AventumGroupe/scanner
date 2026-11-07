package com.aventum.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlin.math.roundToInt

/** Assemble des images JPEG en un PDF, une image par page. */
object PdfMaker {

    /** Côté court d'une page, en points (largeur A4). */
    private const val SHORT_SIDE = 595
    /** Résolution maximale des images insérées (~240 dpi sur du A4). */
    private const val MAX_PIXELS = 2000

    fun fromImages(images: List<File>, target: File) {
        val document = PdfDocument()
        try {
            images.forEachIndexed { index, image ->
                val bitmap = decodeSampled(image, MAX_PIXELS)
                    ?: throw IllegalStateException("Image illisible : ${image.name}")
                val (width, height) = if (bitmap.width <= bitmap.height) {
                    SHORT_SIDE to (SHORT_SIDE.toFloat() * bitmap.height / bitmap.width).roundToInt()
                } else {
                    (SHORT_SIDE.toFloat() * bitmap.width / bitmap.height).roundToInt() to SHORT_SIDE
                }
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
                bitmap.recycle()
            }
            target.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}

/** Décode une image en la réduisant pour que son plus grand côté reste proche de [maxSize]. */
fun decodeSampled(file: File, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}
