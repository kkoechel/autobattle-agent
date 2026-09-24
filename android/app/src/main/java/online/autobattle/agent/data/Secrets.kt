package online.autobattle.agent.data

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
 * Stores the player's API key encrypted by a hardware-backed Keystore key.
 *
 * The key grants full read access to their account and, on an API-enabled
 * account, the ability to rewrite their decks. Plain SharedPreferences is
 * readable by anything with root or a backup extraction, so the value is
 * sealed with AES/GCM under a key that never leaves the Keystore.
 *
 * It is never logged. An exception carrying a request URL is fine; one
 * carrying the Authorization header is not, which is why ApiException holds a
 * body rather than the request.
 */
object Secrets {

    private const val PREFS = "abagent"
    private const val PREF_KEY = "api_key"
    private const val ALIAS = "abagent_api_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun save(ctx: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(apiKey.toByteArray())
        // The IV is generated per encryption and must be kept with the
        // ciphertext; GCM is catastrophically broken if one is reused.
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_KEY, blob).apply()
    }

    fun load(ctx: Context): String? {
        val blob = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null) ?: return null
        return try {
            val (ivB64, dataB64) = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(
                    Cipher.DECRYPT_MODE, secretKey(),
                    GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
                )
            }
            String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)))
        } catch (e: Exception) {
            // A Keystore key can be invalidated (device re-secured, app
            // restored to another device). Losing the stored key means asking
            // for it again, not crashing.
            null
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_KEY).apply()
    }
}
