package com.aventum.scanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clé d'API Anthropic, chiffrée (AES-GCM) avec une clé du Keystore Android : elle ne quitte
 * jamais le téléphone et reste illisible si les données de l'application sont copiées.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "anthropic_api_key"
    private const val PREFS = "secrets"
    private const val ENCRYPTED = "anthropic_api_key_encrypted"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    /** Emplacement en clair utilisé par la version 1.5 ; migré puis effacé. */
    private const val LEGACY_PREFS = "settings"
    private const val LEGACY_KEY = "anthropic_api_key"

    fun loadApiKey(context: Context): String? {
        migrateLegacy(context)
        val stored = prefs(context).getString(ENCRYPTED, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // Clé du Keystore perdue (restauration, nouveau téléphone…) : il faudra ressaisir la clé.
            clearApiKey(context)
            null
        }
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(ENCRYPTED, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    fun clearApiKey(context: Context) {
        prefs(context).edit().remove(ENCRYPTED).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun migrateLegacy(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val plain = legacy.getString(LEGACY_KEY, null) ?: return
        if (plain.isNotBlank()) saveApiKey(context, plain)
        legacy.edit().remove(LEGACY_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
