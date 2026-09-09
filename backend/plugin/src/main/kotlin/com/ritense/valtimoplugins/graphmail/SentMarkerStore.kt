package com.ritense.valtimoplugins.graphmail

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Where [SendIdempotencyGuard] remembers that a key has already been sent.
 *
 * Split out as an interface for one reason: the default is in-memory, and in-memory only protects
 * against a retry handled by the same, still-running JVM. GZAC in production is often more than one
 * node with a cluster-wide job executor, and there the guard offers no protection at all — a retry
 * picked up by another node has never heard of the marker. `documentation/developer.md` is explicit
 * about that, but a deployment that needs the guarantee had no supported way to supply one; it had
 * to replace the whole guard, locking and eviction included.
 *
 * A durable implementation must commit **outside** the surrounding transaction — a
 * `REQUIRES_NEW`-scoped write, for instance. That is the whole point: the failure the guard exists
 * for is a transaction that rolls back *after* Graph already accepted the message, so a marker
 * sharing that transaction's fate rolls back with it and the retry never sees it. A plain
 * `@Transactional` JDBC store would be no better than a process variable.
 *
 * Deliberately not shipped with a JDBC implementation. That means a table, a changelog and a schema
 * change in every deployment, which is a bigger decision than a plugin should make on a user's
 * behalf while the topology is unknown. The seam is here when it is needed.
 *
 * Implementations must be thread-safe. [SendIdempotencyGuard] keeps the per-key locking, so an
 * implementation only has to store and read markers.
 */
interface SentMarkerStore {
    /** True when [key] was marked sent and that marking has not yet expired. */
    fun isSent(key: String): Boolean

    /** Records that [key] has been sent, now. */
    fun markSent(key: String)
}

/**
 * The default: markers in a [ConcurrentHashMap], expiring after [entryTtlMs].
 *
 * Non-transactional on purpose — see [SentMarkerStore]. Does not survive a restart, and is not
 * shared between nodes.
 */
class InMemorySentMarkerStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS,
) : SentMarkerStore {
    private val sentAt = ConcurrentHashMap<String, Instant>()

    override fun isSent(key: String): Boolean {
        val at = sentAt[key] ?: return false
        return Instant.now().isBefore(at.plusMillis(entryTtlMs))
    }

    override fun markSent(key: String) {
        evictStaleIfNeeded()
        sentAt[key] = Instant.now()
    }

    /** Visible to [SendIdempotencyGuard] so its lock eviction can tell a live key from a stale one. */
    internal fun contains(key: String): Boolean = sentAt.containsKey(key)

    // sentAt only grows (one entry per distinct execution+activity that has ever sent). Once it
    // gets large, sweep out entries already past their TTL — mirrors the eviction pattern in
    // GraphTokenCache / GraphMailTestSendController's rate-limit store.
    private fun evictStaleIfNeeded() {
        if (sentAt.size < maxEntries) return
        val now = Instant.now()
        sentAt.entries.removeIf { now.isAfter(it.value.plusMillis(entryTtlMs)) }
    }

    private companion object {
        // Sized generously above realistic concurrent in-flight-retry counts.
        const val DEFAULT_MAX_ENTRIES = 1_000

        // Must comfortably exceed the time between a job-executor retry attempt and the original
        // attempt that succeeded — MAX_DRAFT_SEND_WALL_CLOCK_MS (120s) plus normal Operaton retry
        // backoff is well under this.
        const val DEFAULT_ENTRY_TTL_MS = 30L * 60L * 1000L
    }
}
