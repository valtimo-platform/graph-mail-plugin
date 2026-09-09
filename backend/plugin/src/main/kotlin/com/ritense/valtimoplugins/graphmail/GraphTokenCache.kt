package com.ritense.valtimoplugins.graphmail

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

// Default token cache capacity — override via constructor parameter.
// Increase if the deployment manages more than 64 distinct Entra app registrations.
private const val DEFAULT_MAX_CACHED_TOKENS = 64

/**
 * Thread-safe OAuth2 access-token cache. Keys are opaque strings chosen by the caller; see
 * [GraphMailClientImpl.cacheKey] for the "tenantId:clientId:secretHash" format actually used —
 * the secret hash is part of the key so a wrong or stale secret can never reuse a token that a
 * different, correct secret already cached for the same tenant/client.
 *
 * This is registered as a single Spring bean (see [GraphMailAutoConfiguration]) and shared
 * between the [GraphMailClient] used by the plugin action path and the one used by the
 * test-send endpoint. Valtimo hydrates a fresh [GraphMailPlugin] — and therefore, previously,
 * a fresh [GraphMailClientImpl] with its own private cache — per plugin action invocation.
 * A cache that lives inside the client instance never accumulates hits under that lifecycle;
 * pulling it out into a shared bean is what makes caching actually effective.
 *
 * - Cache hits never block (plain map read).
 * - Concurrent misses for the same key collapse into a single fetch via a per-key lock.
 * - Lock eviction only removes a lock that is provably free right now (`tryLock` succeeds),
 *   never merely "not yet in the token cache" — a key mid-fetch is legitimately lock-held but
 *   not yet cached. Even so, a lock can be evicted in the narrow window between a caller
 *   reading it from the map and actually acquiring it; [lockFor] re-validates identity after
 *   locking and retries if it lost that race, so two callers can never end up holding two
 *   different Lock instances for the same key.
 */
class GraphTokenCache(
    private val maxCachedTokens: Int = DEFAULT_MAX_CACHED_TOKENS,
) {
    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
        val createdAt: Instant,
    ) {
        // Same reasoning as GraphCredentials.toString() in GraphMailModels.kt — this holds a
        // live bearer token; never let a default toString() print it in a log line or assertion.
        override fun toString(): String = "CachedToken(token=***, expiresAt=$expiresAt, createdAt=$createdAt)"
    }

    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    // Size of `locks` at the last eviction scan that found nothing to remove — see
    // evictStaleLocksIfNeeded.
    private val locksSizeAtLastEviction = AtomicInteger(0)

    private fun freshToken(key: String): String? = tokens[key]?.takeIf { Instant.now().isBefore(it.expiresAt) }?.token

    /**
     * Returns the cached token for [key] if still valid, otherwise calls [fetch] to obtain a
     * new (token, expiresAt) pair, caches it, and returns the token. Concurrent callers for
     * the same [key] serialise on a per-key lock so only one of them actually calls [fetch].
     */
    fun getOrFetch(
        key: String,
        fetch: () -> Pair<String, Instant>,
    ): String {
        freshToken(key)?.let { return it }
        val lock = lockFor(key)
        try {
            freshToken(key)?.let { return it }
            val (token, expiresAt) = fetch()
            evictIfFull()
            tokens[key] = CachedToken(token, expiresAt, Instant.now())
            return token
        } finally {
            lock.unlock()
            evictStaleLocksIfNeeded(key)
        }
    }

    /**
     * Ignores any cached entry for [key] and unconditionally calls [fetch], caching and returning
     * the result. Used by the 401 handler: after a token is rejected, re-reading the cache is not
     * good enough — a concurrent caller that started its fetch *before* the invalidation can write
     * the very same rejected token back, and the retry would then repeat with a token that is
     * already known to be refused.
     *
     * Concurrent refreshers for the same key collapse into a single Azure call: a caller that had
     * to wait for the lock accepts a token that was cached *after* it started waiting, since such a
     * token cannot be the one it set out to replace. Only the first caller through actually
     * refetches. Without that check, a broad token rotation would turn every in-flight send into
     * its own token request.
     *
     * This does not weaken the guarantee the 401 handler relies on. Entra may hand back the same
     * token string for repeated client-credentials requests inside its validity window, so a
     * refused token can come back either way — from a sibling's fetch or from our own. The caller's
     * one-refresh limit is what bounds that case, and it is unaffected here.
     */
    fun forceFetch(
        key: String,
        fetch: () -> Pair<String, Instant>,
    ): String {
        val enteredAt = Instant.now()
        val lock = lockFor(key)
        try {
            tokens[key]
                ?.takeIf { it.createdAt.isAfter(enteredAt) && Instant.now().isBefore(it.expiresAt) }
                ?.let { return it.token }
            val (token, expiresAt) = fetch()
            evictIfFull()
            tokens[key] = CachedToken(token, expiresAt, Instant.now())
            return token
        } finally {
            lock.unlock()
            evictStaleLocksIfNeeded(key)
        }
    }

    /**
     * Removes the entry for [key] only while it still holds [staleToken]. Returns true when the
     * entry was actually removed.
     *
     * Invalidating by key alone would let a 401 handler throw away a token that another thread has
     * already refreshed in the meantime, forcing an unnecessary extra Azure round-trip for every
     * caller of that key.
     */
    fun invalidateIfMatches(
        key: String,
        staleToken: String,
    ): Boolean {
        var removed = false
        tokens.computeIfPresent(key) { _, cached ->
            if (cached.token == staleToken) {
                removed = true
                null
            } else {
                cached
            }
        }
        return removed
    }

    /** Removes every entry whose key starts with [prefix]. Returns the number of entries cleared. */
    fun invalidateByPrefix(prefix: String): Int {
        val matching = tokens.keys.filter { it.startsWith(prefix) }
        matching.forEach { tokens.remove(it) }
        return matching.size
    }

    /** How many tokens are currently held. Exposed for the metrics gauge. */
    fun size(): Int = tokens.size

    /** Returns the number of entries that were cleared. */
    fun invalidateAll(): Int {
        val count = tokens.size
        tokens.clear()
        return count
    }

    // Returns a Lock for `key`, held (locked) by the caller on return — the caller must unlock
    // it. Acquires and validates in a loop rather than a single computeIfAbsent+lock: a lock is
    // only ever evicted while free (tryLock succeeds in evictStaleLocksIfNeeded), so there is a
    // narrow window between us reading the map's current instance and actually locking it where
    // a concurrent eviction (triggered by a *different* key's call) can slip in, remove it, and
    // leave us holding an orphaned Lock that a later caller for the same key will never see —
    // two callers would then hold two different Lock objects for one key, and the exclusion this
    // class exists to provide is gone. Re-checking identity against the live map entry after
    // locking closes that window: if it no longer matches, our lock was orphaned mid-acquisition,
    // so we drop it and retry against whatever is current.
    private fun lockFor(key: String): ReentrantLock {
        while (true) {
            val lock = locks.computeIfAbsent(key) { ReentrantLock() }
            lock.lock()
            if (locks[key] === lock) return lock
            lock.unlock()
        }
    }

    // Bounded: once a scan at a given map size finds nothing evictable, further calls are a
    // no-op until the map actually grows past that size again — without this, a map that's full
    // of keys all still legitimately in use would pay for a full linear rescan on every single
    // call, with a guaranteed empty result each time.
    private fun evictStaleLocksIfNeeded(currentKey: String) {
        val size = locks.size
        if (size <= maxCachedTokens + maxCachedTokens / 2) return
        if (size <= locksSizeAtLastEviction.get()) return
        locksSizeAtLastEviction.set(size)
        locks.keys
            .filter { it != currentKey && !tokens.containsKey(it) }
            .toList()
            .forEach { k ->
                val lock = locks[k] ?: return@forEach
                if (lock.tryLock()) {
                    try {
                        if (!tokens.containsKey(k)) locks.remove(k)
                    } finally {
                        lock.unlock()
                    }
                }
            }
    }

    private fun evictIfFull() {
        if (tokens.size < maxCachedTokens) return
        val now = Instant.now()
        // Expired entries are free to drop and are never coming back into use — clear those before
        // touching anything still valid. Nothing else sweeps them: freshToken() only filters them
        // out on read, so without this they sit at capacity and push out live tokens.
        tokens.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        if (tokens.size < maxCachedTokens) return
        // Still full: evict the oldest entry by createdAt — bounded scan, only runs at capacity.
        tokens.entries
            .minByOrNull { it.value.createdAt }
            ?.key
            ?.let { tokens.remove(it) }
    }
}
