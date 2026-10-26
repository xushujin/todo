package app.todo.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.todo.core.codec.Base64Codec
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the two secrets the app owns (git token, SMTP password) with a
 * non-exportable AES-256-GCM key that lives in the Android Keystore.
 *
 * Stored format: `v1:<base64 iv>:<base64 ciphertext>`. A value that cannot be
 * decrypted (key invalidated, restored backup, tampered value) reads as `null`
 * so the UI asks for the secret again instead of crashing.
 */
interface SecretCipher {
    fun encrypt(plainText: String): String
    fun decryptOrNull(stored: String?): String?
}

class KeystoreSecretCipher : SecretCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return "$PREFIX${Base64Codec.encode(iv)}:${Base64Codec.encode(encrypted)}"
    }

    override fun decryptOrNull(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        if (!stored.startsWith(PREFIX)) return null
        val parts = stored.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return try {
            val iv = Base64Codec.decode(parts[0])
            val cipherText = Base64Codec.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(cipherText).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "todo.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFIX = "v1:"
    }
}

/** Only used by unit tests and Compose previews, never by the app. */
class InMemorySecretCipher : SecretCipher {
    override fun encrypt(plainText: String): String = plainText
    override fun decryptOrNull(stored: String?): String? = stored
}
