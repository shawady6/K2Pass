package com.shawady.vault.core

/**
 * Manual-sync merge. Properties (so both devices converge to the same vault):
 *  - commutative: merge(a, b) == merge(b, a)
 *  - idempotent:  merge(a, a) == a
 * Rules per entry id:
 *  1. only one side has it            -> keep it
 *  2. both have it                    -> newest updatedAt wins; ties are broken deterministically
 *                                        (deleted wins, then by password string compare)
 *  3. the losing version (if it has different content and is not a tombstone) is pushed
 *     into the winner's history, so a conflicting edit is never silently lost.
 */
object VaultMerge {

    private const val MAX_HISTORY = 20

    fun merge(a: VaultData, b: VaultData): VaultData {
        val byId = LinkedHashMap<String, Entry>()
        val ids = (a.entries.map { it.id } + b.entries.map { it.id }).distinct()
        val ma = a.entries.associateBy { it.id }
        val mb = b.entries.associateBy { it.id }
        for (id in ids) {
            val x = ma[id]; val y = mb[id]
            byId[id] = when {
                x == null -> y!!
                y == null -> x
                else -> mergeEntry(x, y)
            }
        }
        return VaultData(byId.values.sortedBy { it.id })
    }

    private fun mergeEntry(x: Entry, y: Entry): Entry {
        if (x.sameContent(y)) {
            return x.copy(history = mergeHistory(x.history, y.history, null))
        }
        val (winner, loser) = if (newer(x, y)) x to y else y to x
        // A deletion is final: drop all old versions so deleted secrets don't linger in history.
        if (winner.deleted) return winner.copy(history = emptyList())
        val extra = if (!loser.deleted) loser.copy(history = emptyList()) else null
        return winner.copy(history = mergeHistory(winner.history, loser.history, extra))
    }

    /** true if x should win over y. */
    private fun newer(x: Entry, y: Entry): Boolean = when {
        x.updatedAt != y.updatedAt -> x.updatedAt > y.updatedAt
        x.deleted != y.deleted -> x.deleted
        else -> key(x) > key(y)   // full-content compare keeps merge(a,b) == merge(b,a)
    }

    private fun key(e: Entry): String =
        listOf(
            e.title, e.username, e.password, e.url, e.notes, e.totpSecret ?: "", e.category,
            e.type, e.favorite.toString(), e.trashedAt.toString(), e.extra.toSortedMap().toString(),
        ).joinToString("\u0000")

    private fun mergeHistory(h1: List<Entry>, h2: List<Entry>, extra: Entry?): List<Entry> =
        (h1 + h2 + listOfNotNull(extra))
            .map { it.copy(history = emptyList()) }
            .distinctBy { Triple(it.updatedAt, it.password, it.username) }
            .sortedWith(compareByDescending<Entry> { it.updatedAt }.thenBy { it.password })
            .take(MAX_HISTORY)

    /**
     * Drop tombstones older than [olderThanMs]. Only do this if every device syncs at least
     * that often, otherwise a stale device can resurrect a deleted entry. Default off.
     */
    fun purgeTombstones(v: VaultData, now: Long, olderThanMs: Long): VaultData =
        VaultData(v.entries.filterNot { it.deleted && now - it.updatedAt > olderThanMs })
}
