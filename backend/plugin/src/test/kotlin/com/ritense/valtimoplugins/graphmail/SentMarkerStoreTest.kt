package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * The extension point exists because the in-memory default only protects a retry handled by the
 * same, still-running JVM — which a multi-node GZAC is not. These pin down that a replacement store
 * actually takes over the decision, so the seam is real rather than decorative.
 */
class SentMarkerStoreTest {
    @Test
    fun `the default store keeps the guard's existing behaviour`() {
        val guard = SendIdempotencyGuard()

        assertEquals("sent", guard.ifNotAlreadySent("k") { "sent" })
        assertNull(guard.ifNotAlreadySent("k") { "sent again" }, "the second send was not suppressed")
    }

    @Test
    fun `a replacement store decides what counts as already sent`() {
        // A store that has never heard of any key: every call must go through, which is what a
        // durable store would do after the marker expired or on a node that never saw the send.
        val guard = SendIdempotencyGuard(AlwaysUnsentStore)

        assertEquals("first", guard.ifNotAlreadySent("k") { "first" })
        assertEquals("second", guard.ifNotAlreadySent("k") { "second" }, "the store's answer was ignored")
    }

    @Test
    fun `a replacement store receives the markers the guard writes`() {
        val store = RecordingStore()
        val guard = SendIdempotencyGuard(store)

        guard.ifNotAlreadySent("execution-1:send-email:0") { "sent" }

        assertTrue(
            store.marked.contains("execution-1:send-email:0"),
            "the guard did not hand the marker to the store: ${store.marked}",
        )
    }

    @Test
    fun `a failed action is never marked sent`() {
        // The guard's own contract, re-checked through the seam: a send that threw has to stay
        // retryable, or a transient failure becomes a permanently swallowed email.
        val store = RecordingStore()
        val guard = SendIdempotencyGuard(store)

        runCatching { guard.ifNotAlreadySent("k") { throw IllegalStateException("boom") } }

        assertFalse(store.marked.contains("k"), "a failed attempt was marked as sent")
    }

    private object AlwaysUnsentStore : SentMarkerStore {
        override fun isSent(key: String) = false

        override fun markSent(key: String) = Unit
    }

    private class RecordingStore : SentMarkerStore {
        val marked: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun isSent(key: String) = marked.contains(key)

        override fun markSent(key: String) {
            marked += key
        }
    }
}
