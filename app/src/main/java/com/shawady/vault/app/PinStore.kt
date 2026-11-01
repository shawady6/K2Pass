package com.shawady.vault.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shawady.vault.core.VaultCrypto
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Quick-unlock PIN. The vault key is wrapped twice:
 *   inner = AES-GCM(Argon2id(PIN, salt), vaultKey)        -> a PIN is useless without the device
 *   outer = AES-GCM(Android Keystore key (no auth), inner) -> the file can't be brute-forced off the phone
 * 5 wrong attempts delete the PIN. It is also dropped whenever a different vault file is imported.
 */
class PinStore(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "pin.json")
    private val prefs = ctx.applicationContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    fun isSet(): Boolean = file.exists()

    fun attemptsLeft(): Int = MAX_FAILS - prefs.getInt("pin_fails", 0)

    fun set(pin: CharArray, vaultKey: ByteArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pk = VaultCrypto.deriveKey(pin, salt, KDF)
        try {
            val inner = VaultCrypto.encrypt(pk, vaultKey, AAD)
            val outer = ksEncrypt(inner)
            file.writeText(
                JSONObject().put("salt", b64(salt)).put("blob", b64(outer)).toString(),
            )
            prefs.edit().putInt("pin_fails", 0).apply()
        } finally {
            pk.fill(0)
        }
    }

    /** Returns the vault key, or null when the PIN is wrong (the caller owns and must wipe the key). */
    fun unlock(pin: CharArray): ByteArray? {
        if (!file.exists()) return null
        try {
            val o = JSONObject(file.readText())
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val inner = ksDecrypt(Base64.decode(o.getString("blob"), Base64.NO_WRAP))
            val pk = VaultCrypto.deriveKey(pin, salt, KDF)
            try {
                val key = VaultCrypto.decrypt(pk, inner, AAD)
                prefs.edit().putInt("pin_fails", 0).apply()
                return key
            } finally {
                pk.fill(0)
            }
        } catch (e: Exception) {
            val f = prefs.getInt("pin_fails", 0) + 1
            if (f >= MAX_FAILS) clear() else prefs.edit().putInt("pin_fails", f).apply()
            return null
        }
    }

    fun clear() {
        file.delete()
        prefs.edit().remove("pin_fails").apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return kg.generateKey()
    }

    private fun ksEncrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(data)
    }

    private fun ksDecrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        const val MIN_LEN = 6
        private const val MAX_FAILS = 5
        private const val ALIAS = "k2pass_pin_wrap"
        private val KDF = VaultCrypto.KdfParams(32768, 3, 2)
        private val AAD = "k2pin-v1".toByteArray()
    }
}
