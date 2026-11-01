package com.shawady.vault.core

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.json.JSONObject
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault encryption design:
 *   masterPassword --Argon2id(salt)--> KEK (32 bytes)
 *   random vaultKey (32 bytes) is wrapped by KEK with AES-256-GCM
 *   vault payload (JSON of entries) is encrypted by vaultKey with AES-256-GCM
 * Changing the master password only re-wraps vaultKey; payload is untouched.
 * The header (version + KDF params) is bound as AAD so it can't be tampered with.
 */
object VaultCrypto {

    private const val FORMAT_VERSION = 1
    private const val KEY_LEN = 32
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    data class KdfParams(
        val memoryKiB: Int = 65536,   // 64 MiB; raise on desktop, benchmark on the phone
        val iterations: Int = 3,
        val parallelism: Int = 2,
    )

    /** Serializable vault container (this is what is stored/synced on disk). */
    class VaultFile(
        val version: Int,
        val kdf: KdfParams,
        val salt: ByteArray,
        val wrappedKey: ByteArray,   // iv || ciphertext+tag
        val payload: ByteArray,      // iv || ciphertext+tag
    ) {
        fun toJson(): String = JSONObject().apply {
            put("v", version)
            put("m", kdf.memoryKiB); put("t", kdf.iterations); put("p", kdf.parallelism)
            put("salt", b64(salt)); put("wk", b64(wrappedKey)); put("data", b64(payload))
        }.toString()

        companion object {
            fun fromJson(s: String): VaultFile {
                val o = JSONObject(s)
                require(o.getInt("v") == FORMAT_VERSION) { "Unsupported vault version" }
                return VaultFile(
                    o.getInt("v"),
                    KdfParams(o.getInt("m"), o.getInt("t"), o.getInt("p")),
                    unb64(o.getString("salt")), unb64(o.getString("wk")), unb64(o.getString("data")),
                )
            }
        }
    }

    /** Create a brand-new vault. Caller must wipe [vaultKey] when locking. */
    fun create(masterPassword: CharArray, plaintext: ByteArray, kdf: KdfParams = KdfParams()): Pair<VaultFile, ByteArray> {
        val salt = randomBytes(16)
        val vaultKey = randomBytes(KEY_LEN)
        val kek = deriveKey(masterPassword, salt, kdf)
        try {
            val aad = header(FORMAT_VERSION, kdf, salt)
            val wrapped = encrypt(kek, vaultKey, aad)
            val payload = encrypt(vaultKey, plaintext, aad)
            return VaultFile(FORMAT_VERSION, kdf, salt, wrapped, payload) to vaultKey
        } finally {
            kek.fill(0)
        }
    }

    /** Unlock with master password. Returns vaultKey. Throws on wrong password / tampering. */
    fun unlock(file: VaultFile, masterPassword: CharArray): ByteArray {
        val kek = deriveKey(masterPassword, file.salt, file.kdf)
        try {
            return decrypt(kek, file.wrappedKey, header(file.version, file.kdf, file.salt))
        } finally {
            kek.fill(0)
        }
    }

    fun readPayload(file: VaultFile, vaultKey: ByteArray): ByteArray =
        decrypt(vaultKey, file.payload, header(file.version, file.kdf, file.salt))

    /** Re-encrypt payload with a fresh IV on every save. */
    fun writePayload(file: VaultFile, vaultKey: ByteArray, plaintext: ByteArray): VaultFile =
        VaultFile(
            file.version, file.kdf, file.salt, file.wrappedKey,
            encrypt(vaultKey, plaintext, header(file.version, file.kdf, file.salt)),
        )

    /** New salt + new KEK; payload stays valid because vaultKey is unchanged. */
    fun changeMasterPassword(file: VaultFile, vaultKey: ByteArray, newPassword: CharArray, kdf: KdfParams = file.kdf): VaultFile {
        val plaintext = readPayload(file, vaultKey)
        val salt = randomBytes(16)
        val kek = deriveKey(newPassword, salt, kdf)
        try {
            val aad = header(file.version, kdf, salt)
            return VaultFile(file.version, kdf, salt, encrypt(kek, vaultKey, aad), encrypt(vaultKey, plaintext, aad))
        } finally {
            kek.fill(0); plaintext.fill(0)
        }
    }

    // ---- primitives ----

    fun deriveKey(password: CharArray, salt: ByteArray, p: KdfParams): ByteArray {
        val pwBytes = Charsets.UTF_8.encode(CharBuffer.wrap(password)).let { bb ->
            ByteArray(bb.remaining()).also { bb.get(it) }
        }
        try {
            val res = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pwBytes,
                salt = salt,
                tCostInIterations = p.iterations,
                mCostInKibibyte = p.memoryKiB,
                parallelism = p.parallelism,
                hashLengthInBytes = KEY_LEN,
            )
            return res.rawHashAsByteArray()
        } finally {
            pwBytes.fill(0)
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_LEN + 16) { "Corrupt blob" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)))
        c.updateAAD(aad)
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    private fun header(v: Int, k: KdfParams, salt: ByteArray): ByteArray =
        "$v|${k.memoryKiB}|${k.iterations}|${k.parallelism}|${b64(salt)}".toByteArray(Charsets.UTF_8)

    private fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
