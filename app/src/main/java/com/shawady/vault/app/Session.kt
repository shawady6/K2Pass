package com.shawady.vault.app

import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData

/** In-memory unlocked state. [lock] wipes the key. Nothing here is ever written to disk. */
object Session {
    var file: VaultCrypto.VaultFile? = null
        private set
    var data: VaultData? = null
        private set
    private var key: ByteArray? = null

    val unlocked: Boolean get() = key != null

    fun open(f: VaultCrypto.VaultFile, k: ByteArray) {
        lock()
        data = VaultData.fromBytes(VaultCrypto.readPayload(f, k))
        file = f
        key = k
    }

    /** Copy of the vault key; the caller must wipe it. */
    fun keyCopy(): ByteArray? = key?.copyOf()

    fun setFile(f: VaultCrypto.VaultFile) { file = f }

    /** Another screen (autofill) wrote a new version to disk; pick it up if the vault is open. */
    fun reloadIfOpen(f: VaultCrypto.VaultFile) {
        val k = key ?: return
        try {
            data = VaultData.fromBytes(VaultCrypto.readPayload(f, k))
            file = f
        } catch (e: Exception) {
            lock()
        }
    }

    /** Called after every successful save (not for saves made by the sync itself). */
    @Volatile var onSaved: (() -> Unit)? = null
    @Volatile var quiet = false

    fun persist(store: VaultStore, newData: VaultData) {
        val f = file ?: return
        val k = key ?: return
        val nf = VaultCrypto.writePayload(f, k, newData.toBytes())
        store.save(nf)
        file = nf
        data = newData
        if (!quiet) onSaved?.invoke()
    }

    fun lock() {
        key?.fill(0)
        key = null
        data = null
        file = null
    }
}
