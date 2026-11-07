package com.aventum.scanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Transcription de pages (y compris manuscrites) par l'API Claude d'Anthropic.
 * Les images sont envoyées sur internet ; la clé d'API est celle saisie par l'utilisateur.
 */
object ClaudeTranscriber {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-opus-5-5"
    /** Résolution maximale exploitée par le modèle (côté long, en pixels). */
    private const val MAX_IMAGE_SIDE = 2576
    private const val MAX_ATTEMPTS = 3

    private const val SYSTEM_PROMPT = """Tu es un transcripteur de documents. On te fournit la photo d'une page scannée, souvent manuscrite et en français. Transcris fidèlement tout le texte lisible, dans l'ordre de lecture.

Règles :
- Reproduis le texte tel qu'il est écrit, sans le corriger, le résumer ni le reformuler. Garde les abréviations, les symboles (→, ≠, =…) et la numérotation (1, 2, ①…).
- Conserve la structure : un retour à la ligne par ligne logique ou par élément de liste, une ligne vide entre les paragraphes.
- Ignore le texte barré. Place les ajouts écrits au-dessus de la ligne ou en marge à l'endroit où ils s'insèrent.
- Pour un mot difficile à lire, écris ta meilleure lecture suivie de [?]. S'il est totalement illisible, écris [illisible].
- Réponds uniquement avec la transcription, en texte brut : pas de Markdown, pas d'introduction, pas de commentaire. Si la page ne contient aucun texte, ne réponds rien."""

    private const val USER_PROMPT = "Transcris cette page."

    /** Erreur à afficher telle quelle à l'utilisateur. */
    class TranscriptionException(message: String) : Exception(message)

    /** Renvoie le texte de chaque page. À appeler hors du thread principal. */
    fun transcribe(context: Context, apiKey: String, images: List<File>): List<String> =
        images.map { transcribePage(context, apiKey, encodeImage(it)) }

    private fun encodeImage(file: File): String {
        val decoded = decodeSampled(file, MAX_IMAGE_SIDE)
            ?: throw IllegalStateException("Image illisible : ${file.name}")
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true
            ).also { decoded.recycle() }
        } else {
            decoded
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun requestBody(imageBase64: String): String {
        val image = JSONObject()
            .put("type", "image")
            .put("source", JSONObject()
                .put("type", "base64")
                .put("media_type", "image/jpeg")
                .put("data", imageBase64))
        val text = JSONObject().put("type", "text").put("text", USER_PROMPT)
        return JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 16000)
            // En cas de refus par les filtres de sécurité, l'API relance la requête sur le modèle de repli recommandé.
            .put("fallbacks", "default")
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(image).put(text))))
            .toString()
    }

    private fun transcribePage(context: Context, apiKey: String, imageBase64: String): String {
        val body = requestBody(imageBase64).toByteArray(Charsets.UTF_8)
        var attempt = 1
        while (true) {
            val (code, response) = try {
                post(apiKey, body)
            } catch (e: IOException) {
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(2000L * attempt++)
                    continue
                }
                throw TranscriptionException(context.getString(R.string.ai_error_network))
            }
            if (code in 200..299) return parseText(context, response)
            // Surcharge ou erreur passagère du service : nouvel essai après une pause.
            if ((code == 429 || code >= 500) && attempt < MAX_ATTEMPTS) {
                Thread.sleep(2000L * attempt++)
                continue
            }
            throw TranscriptionException(errorMessage(context, code, response))
        }
    }

    private fun post(apiKey: String, body: ByteArray): Pair<Int, String> {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2026-10-21")
            setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseText(context: Context, response: String): String {
        val json = JSONObject(response)
        if (json.optString("stop_reason") == "refusal") {
            throw TranscriptionException(context.getString(R.string.ai_error_refusal))
        }
        val content = json.optJSONArray("content") ?: return ""
        return (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
            .trim()
    }

    private fun errorMessage(context: Context, code: Int, response: String): String {
        val detail = runCatching { JSONObject(response).getJSONObject("error").getString("message") }
            .getOrDefault(response.take(200))
        return when (code) {
            401 -> context.getString(R.string.ai_error_key)
            402, 403 -> context.getString(R.string.ai_error_account, detail)
            429, 529 -> context.getString(R.string.ai_error_busy)
            else -> context.getString(R.string.ai_error_other, code, detail)
        }
    }
}
