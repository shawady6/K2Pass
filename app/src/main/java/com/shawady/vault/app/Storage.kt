package com.shawady.vault.app

import android.content.Context
import android.util.Base64
import com.shawady.vault.core.BiometricUnlock
import com.shawady.vault.core.VaultCrypto
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private-storage persistence. The vault file is always encrypted; nothing sensitive is stored in clear. */
class VaultStore(ctx: Context) {
    private val dir: File = ctx.filesDir
    private val vaultFile = File(dir, "vault.json")
    private val bioFile = File(dir, "bio.json")
    private val prefs = ctx.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    fun exists(): Boolean = vaultFile.exists()
    fun rawJson(): String = vaultFile.readText()
    fun load(): VaultCrypto.VaultFile = VaultCrypto.VaultFile.fromJson(rawJson())
    fun save(f: VaultCrypto.VaultFile) = writeAtomic(vaultFile, f.toJson())

    /** Replace the local vault with a file from another device (validated first). */
    fun importRaw(json: String) {
        VaultCrypto.VaultFile.fromJson(json)
        writeAtomic(vaultFile, json)
        deleteBlob()
    }

    fun saveBlob(b: BiometricUnlock.Blob) = writeAtomic(
        bioFile,
        JSONObject().put("iv", enc(b.iv)).put("ct", enc(b.ciphertext)).toString(),
    )

    fun loadBlob(): BiometricUnlock.Blob? {
        if (!bioFile.exists()) return null
        return try {
            val o = JSONObject(bioFile.readText())
            BiometricUnlock.Blob(dec(o.getString("iv")), dec(o.getString("ct")))
        } catch (e: Exception) {
            null
        }
    }

    fun deleteBlob() { bioFile.delete() }

    /** Biometric unlock is only allowed for 7 days after the last master-password unlock. */
    fun markMasterUnlock() { prefs.edit().putLong("last_master", System.currentTimeMillis()).apply() }

    fun biometricStillAllowed(): Boolean =
        System.currentTimeMillis() - prefs.getLong("last_master", 0L) < 7L * 24 * 3600 * 1000

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
