/*
 * Copyright 2015-2025 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.fail

class GraphTokenCacheTest {
    @Test fun `a fresh cached token is returned without calling fetch again`() {
        val cache = GraphTokenCache()
        val fetchCount = AtomicInteger(0)
        val fetch = {
            fetchCount.incrementAndGet()
            "token" to Instant.now().plusSeconds(3600)
        }

        val first = cache.getOrFetch("key", fetch)
        val second = cache.getOrFetch("key", fetch)

        assertEquals("token", first)
        assertEquals("token", second)
        assertEquals(1, fetchCount.get(), "second call should hit the cache, not fetch again")
    }

    @Test fun `concurrent misses for the same key collapse into a single fetch`() {
        // Two callers race a cache miss for the same key. The per-key lock in getOrFetch must
        // serialise them so fetch() only actually runs once — the second caller should see the
        // token the first one just cached, not trigger its own redundant fetch.
        val cache = GraphTokenCache()
        val fetchCount = AtomicInteger(0)
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val fetch = {
            fetchCount.incrementAndGet()
            startedLatch.countDown()
            releaseLatch.await()
            "token" to Instant.now().plusSeconds(3600)
        }

        val firstError = AtomicReference<Throwable?>()
        val secondError = AtomicReference<Throwable?>()
        val firstResult = AtomicReference<String?>()
        val secondResult = AtomicReference<String?>()

        val firstThread =
            Thread {
                runCatching { firstResult.set(cache.getOrFetch("key", fetch)) }
                    .onFailure { firstError.set(it) }
            }
        firstThread.start()
        assertTrue(
            startedLatch.await(5, TimeUnit.SECONDS),
            "first caller never reached fetch() — it should have entered within 5s",
        )

        val secondThread =
            Thread {
                runCatching { secondResult.set(cache.getOrFetch("key", fetch)) }
                    .onFailure { secondError.set(it) }
            }
        secondThread.start()

        releaseLatch.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)
        assertTrue(!firstThread.isAlive, "first caller thread did not finish within 5s")
        assertTrue(!secondThread.isAlive, "second caller thread did not finish within 5s")
        assertEquals(null, firstError.get(), "first caller threw unexpectedly")
        assertEquals(null, secondError.get(), "second caller threw unexpectedly")

        assertEquals("token", firstResult.get())
        assertEquals("token", secondResult.get())
        assertEquals(1, fetchCount.get(), "concurrent misses for the same key must collapse into one fetch")
    }

    @Test fun `invalidateByPrefix clears only matching entries`() {
        val cache = GraphTokenCache()
        cache.getOrFetch("tenant1:client1:secretHash") { "token1" to Instant.now().plusSeconds(3600) }
        cache.getOrFetch("tenant1:client2:secretHash") { "token2" to Instant.now().plusSeconds(3600) }
        cache.getOrFetch("tenant2:client1:secretHash") { "token3" to Instant.now().plusSeconds(3600) }

        val removed = cache.invalidateByPrefix("tenant1:")

        assertEquals(2, removed)
        val fetchCount = AtomicInteger(0)
        cache.getOrFetch("tenant1:client1:secretHash") {
            fetchCount.incrementAndGet()
            "token1-new" to Instant.now().plusSeconds(3600)
        }
        assertEquals(1, fetchCount.get(), "invalidated key must be re-fetched")

        val fetchCountUnaffected = AtomicInteger(0)
        cache.getOrFetch("tenant2:client1:secretHash") {
            fetchCountUnaffected.incrementAndGet()
            "token3-new" to Instant.now().plusSeconds(3600)
        }
        assertEquals(0, fetchCountUnaffected.get(), "unrelated tenant's cached token must be untouched")
    }

    @Test fun `invalidateIfMatches removes the entry only when the token still matches`() {
        val cache = GraphTokenCache()
        cache.getOrFetch("k") { "token-1" to Instant.now().plusSeconds(300) }

        // A stale token that is no longer the cached one must be left alone — otherwise a 401
        // handler throws away a token another thread has already refreshed, and every caller of
        // that key pays for an extra Azure round-trip.
        assertFalse(cache.invalidateIfMatches("k", "some-other-token"))
        assertEquals("token-1", cache.getOrFetch("k") { fail("should still be cached") })

        assertTrue(cache.invalidateIfMatches("k", "token-1"))
        assertEquals("token-2", cache.getOrFetch("k") { "token-2" to Instant.now().plusSeconds(300) })
    }

    @Test fun `forceFetch bypasses a still-valid cache entry`() {
        val cache = GraphTokenCache()
        cache.getOrFetch("k") { "token-1" to Instant.now().plusSeconds(300) }

        assertEquals("token-2", cache.forceFetch("k") { "token-2" to Instant.now().plusSeconds(300) })
        // And the fresh value replaces the old one for subsequent readers.
        assertEquals("token-2", cache.getOrFetch("k") { fail("should be cached") })
    }

    @Test fun `eviction at capacity drops expired entries before valid ones`() {
        val cache = GraphTokenCache(maxCachedTokens = 2)
        cache.getOrFetch("expired") { "old" to Instant.now().minusSeconds(1) }
        cache.getOrFetch("live") { "live-token" to Instant.now().plusSeconds(300) }

        // At capacity: the expired entry is the one that should go, not the oldest live one.
        cache.getOrFetch("new") { "new-token" to Instant.now().plusSeconds(300) }

        assertEquals("live-token", cache.getOrFetch("live") { fail("live token was evicted") })
    }
}
