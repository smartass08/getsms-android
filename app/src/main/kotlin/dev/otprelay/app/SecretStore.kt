package dev.otprelay.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.otprelay.protocol.B64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    fun save(url: String, master: ByteArray): Boolean = synchronized(lock) {
        require(master.size == MASTER_SIZE)
        if (url == loadUrl()) {
            val previous = unwrapMaster()
            if (previous != null) {
                try {
                    if (MessageDigest.isEqual(previous, master)) return@synchronized false
                } finally {
                    previous.fill(0)
                }
            }
        }
        val key = keyStore().getKey(ALIAS, null) ?: generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        if (cipher.iv.size != IV_SIZE) throw IOException("Unexpected wrapping IV size")
        val wrapped = B64.encode(cipher.iv + cipher.doFinal(master))
        if (!preferences.edit().putString(URL, url).putString(WRAPPED, wrapped)
                .remove(LAST_RESULT).remove(LAST_AT).commit()) {
            throw IOException("Could not save relay settings")
        }
        true
    }

    fun loadUrl(): String? = preferences.getString(URL, null)

    fun unwrapMaster(): ByteArray? = synchronized(lock) {
        try {
            val wrapped = B64.decode(preferences.getString(WRAPPED, null) ?: return@synchronized null)
            require(wrapped.size == IV_SIZE + MASTER_SIZE + TAG_SIZE)
            val key = keyStore().getKey(ALIAS, null) ?: return@synchronized null
            val iv = GCMParameterSpec(TAG_SIZE * 8, wrapped, 0, IV_SIZE)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, iv)
                doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    // Use the public wrapping IV to bind queued ciphertext to this save of the settings.
    fun configurationTag(): String? =
        preferences.getString(WRAPPED, null)?.take(16)?.let { "relay-$it" }

    fun loadCredentials(tag: String): Credentials? = synchronized(lock) {
        if (tag != configurationTag()) return@synchronized null
        val url = loadUrl() ?: return@synchronized null
        val master = unwrapMaster() ?: return@synchronized null
        Credentials(url, master)
    }

    fun isConfigured(): Boolean = synchronized(lock) { preferences.contains(URL) && preferences.contains(WRAPPED) }

    fun recordResult(message: String, atMs: Long, tag: String) = synchronized(lock) {
        if (tag == configurationTag()) {
            preferences.edit().putString(LAST_RESULT, message).putLong(LAST_AT, atMs).apply()
        }
    }

    fun lastDelivery(): Delivery? = synchronized(lock) {
        val result = preferences.getString(LAST_RESULT, null) ?: return@synchronized null
        Delivery(result, preferences.getLong(LAST_AT, 0))
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    fun clear() = synchronized(lock) {
        keyStore().deleteEntry(ALIAS)
        if (!preferences.edit().clear().commit()) throw IOException("Could not clear relay settings")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

    class Credentials(val url: String, val master: ByteArray)
    data class Delivery(val result: String, val atMs: Long)

    private companion object {
        val lock = Any()
        const val ALIAS = "otp-relay-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val URL = "url"
        const val WRAPPED = "wrapped_master"
        const val LAST_RESULT = "last_result"
        const val LAST_AT = "last_at"
        const val MASTER_SIZE = 32
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
    }
}
