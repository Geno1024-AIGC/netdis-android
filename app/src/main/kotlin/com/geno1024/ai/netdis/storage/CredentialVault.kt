package com.geno1024.ai.netdis.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Router passwords live in the Android keystore, never on disk in the clear.
 * The keystore entry is created on first use and cannot be exported, so a
 * backup of the preferences file alone is not enough to read a password.
 *
 * Some devices cannot generate keystore keys at all: rooted ROMs, custom
 * builds, or units whose attestation keys were never provisioned fail with
 * `ATTESTATION_KEYS_NOT_PROVISIONED` even for a plain AES key. Instead of
 * dropping credentials, the vault then falls back to an AES key kept in
 * app-private preferences. That is weaker (no hardware protection) but keeps
 * the router login usable, and each token records which key sealed it so the
 * hardware key is used again if the keystore recovers.
 */
class CredentialVault(context: Context) : SecretVault {

    /** Where the AES key behind a token lives. */
    enum class Mode(val prefix: String) {
        /** AndroidKeyStore key, normally TEE/StrongBox backed. */
        HARDWARE("ks:"),

        /** App-private key used when the keystore cannot generate keys. */
        SOFTWARE("sw:"),
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)

    private val store: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    @Volatile
    private var resolved: Resolved? = null

    private data class Resolved(val mode: Mode, val key: SecretKey)

    /**
     * The key new passwords will use. Resolving may generate one, so call it
     * from a background thread.
     */
    fun mode(): Mode = resolveForKeyGeneration().mode

    override fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val resolved = resolveForKeyGeneration()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, resolved.key)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            resolved.mode.prefix + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.e(TAG, "Failed to seal a router password", it)
            ""
        }
    }

    override fun unseal(token: String): String? {
        if (token.isEmpty()) return ""
        val mode = Mode.entries.firstOrNull { token.startsWith(it.prefix) }
        if (mode != null) return decrypt(mode, token.substring(mode.prefix.length))
        // Legacy tokens predate the mode prefix; try both keys.
        return decrypt(Mode.HARDWARE, token) ?: decrypt(Mode.SOFTWARE, token)
    }

    private fun decrypt(mode: Mode, body: String): String? {
        val key = when (mode) {
            Mode.HARDWARE -> existingHardwareKey() ?: return null
            Mode.SOFTWARE -> softwareKey(create = false) ?: return null
        }
        return runCatching {
            val raw = Base64.decode(body, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE),
            )
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun resolveForKeyGeneration(): Resolved {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }
            val hardware = runCatching { hardwareKey() }
            hardware.exceptionOrNull()?.let {
                Log.w(TAG, "Android Keystore cannot create a key; using a software key", it)
            }
            val result = hardware.getOrNull()
                ?.let { Resolved(Mode.HARDWARE, it) }
                ?: Resolved(Mode.SOFTWARE, softwareKey(create = true)!!)
            resolved = result
            return result
        }
    }

    private fun existingHardwareKey(): SecretKey? {
        val keyStore = store ?: return null
        return runCatching {
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
    }

    private fun hardwareKey(): SecretKey {
        val keyStore = store ?: throw IllegalStateException("AndroidKeyStore unavailable")
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

    private fun softwareKey(create: Boolean): SecretKey? {
        val stored = prefs.getString(SOFTWARE_KEY, null)
        if (stored != null) {
            val bytes = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
            if (bytes != null && bytes.size == SOFTWARE_KEY_SIZE) return SecretKeySpec(bytes, "AES")
            Log.w(TAG, "Discarding a malformed software key")
        }
        if (!create) return null
        val bytes = ByteArray(SOFTWARE_KEY_SIZE).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(SOFTWARE_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return SecretKeySpec(bytes, "AES")
    }

    private companion object {
        const val TAG = "CredentialVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "netdis.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val VAULT_PREFS = "netdis.vault"
        const val SOFTWARE_KEY = "software-key.v1"
        const val SOFTWARE_KEY_SIZE = 32
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
