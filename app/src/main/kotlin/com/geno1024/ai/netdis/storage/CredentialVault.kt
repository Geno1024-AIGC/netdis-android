package com.geno1024.ai.netdis.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Router passwords live in the Android keystore, never on disk in the clear.
 * The keystore entry is created on first use and cannot be exported, so a
 * backup of the preferences file alone is not enough to read a password.
 */
class CredentialVault : SecretVault {

    private val store: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    override fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        if (store == null) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    override fun unseal(token: String): String? {
        if (token.isEmpty()) return ""
        if (store == null) return null
        return runCatching {
            val raw = Base64.decode(token, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE),
            )
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = store ?: error("AndroidKeyStore unavailable")
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "netdis.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/** Byte-for-byte fake used by unit tests and as a graceful fallback. */
class InMemoryVault : SecretVault {
    private val values = HashMap<String, String>()
    private var next = 0

    override fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val token = "mem:${next++}"
        values[token] = plain
        return token
    }

    override fun unseal(token: String): String? = values[token]
}

interface SecretVault {
    fun seal(plain: String): String

    fun unseal(token: String): String?
}
