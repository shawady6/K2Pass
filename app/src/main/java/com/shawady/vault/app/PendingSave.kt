package com.shawady.vault.app

/**
 * Credentials captured by the autofill "save" request, handed from the service to [AuthActivity].
 * Process-local, kept in memory only, and dropped after 2 minutes or on first use.
 */
object PendingSave {
    class Data(val domain: String?, val pkg: String?, val user: String, val pass: String, val at: Long)

    private var data: Data? = null

    @Synchronized
    fun put(d: Data) { data = d }

    @Synchronized
    fun take(): Data? {
        val d = data
        data = null
        return if (d != null && System.currentTimeMillis() - d.at < 120_000L) d else null
    }
}
