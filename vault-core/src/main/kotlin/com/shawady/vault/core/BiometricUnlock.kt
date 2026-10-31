package com.shawady.vault.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric quick-unlock. The vaultKey is wrapped by an AES key that lives in the Android
 * Keystore (hardware-backed when available) and can ONLY be used after a successful
 * Class 3 / "strong" biometric authentication (CryptoObject-bound, not just a UI check).
 *  - The key is invalidated if a new fingerprint/face is enrolled -> falls back to master password.
 *  - The master password is still the root of trust; this is only a convenience layer.
 * Requires minSdk 30 (setUserAuthenticationParameters).
 */
class BiometricUnlock(private val activity: FragmentActivity, private val alias: String = "vault_bio_key") {

    /** What you persist (e.g. in a private file). Useless without the Keystore key + biometric. */
    class Blob(val iv: ByteArray, val ciphertext: ByteArray)

    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isAvailable(): Boolean =
        BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun hasKey(): Boolean = ks.containsAlias(alias)

    fun deleteKey() { if (hasKey()) ks.deleteEntry(alias) }

    /** Call after the user unlocked with the master password and turned biometrics on. */
    fun enroll(vaultKey: ByteArray, onDone: (Blob) -> Unit, onError: (String) -> Unit) {
        deleteKey()
        createKey()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        prompt(cipher, "Enable biometric unlock", onError) { c ->
            onDone(Blob(c.iv, c.doFinal(vaultKey)))
        }
    }

    /** Returns the vaultKey. Caller must wipe it when the vault locks. */
    fun unlock(blob: Blob, onKey: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val cipher = try {
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            onError("Biometrics changed. Unlock with the master password."); return
        } catch (e: Exception) {
            onError("Biometric key unavailable. Unlock with the master password."); return
        }
        prompt(cipher, "Unlock vault", onError) { c -> onKey(c.doFinal(blob.ciphertext)) }
    }

    // ---- internals ----

    private fun createKey() {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // auth on EVERY use
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun key(): SecretKey = ks.getKey(alias, null) as SecretKey

    private fun prompt(cipher: Cipher, title: String, onError: (String) -> Unit, onSuccess: (Cipher) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use master password")
            .build()
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) onError("No cipher") else try { onSuccess(c) } catch (e: Exception) { onError("Crypto error") }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private companion object { const val TRANSFORM = "AES/GCM/NoPadding" }
}
