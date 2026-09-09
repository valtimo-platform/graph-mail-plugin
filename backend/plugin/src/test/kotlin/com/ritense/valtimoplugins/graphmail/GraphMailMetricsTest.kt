package com.ritense.valtimoplugins.graphmail

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class GraphMailMetricsTest {
    @Test
    fun `a send is counted and timed under its verdict`() {
        val registry = SimpleMeterRegistry()

        GraphMailMetrics(registry).recordSend("ok", withAttachments = true, durationMs = 120)

        val counter =
            registry
                .find("graph.mail.sends")
                .tag("outcome", "ok")
                .tag("attachments", "true")
                .counter()
        assertNotNull(counter, "no counter was published: ${registry.meters.map { it.id }}")
        assertEquals(1.0, counter!!.count())

        val timer = registry.find("graph.mail.send.duration").tag("outcome", "ok").timer()
        assertNotNull(timer)
        assertEquals(1L, timer!!.count())
    }

    @Test
    fun `failure verdicts keep the audit log's own vocabulary`() {
        // A dashboard and a log line should name the same failure with the same word, rather than
        // maintaining two parallel taxonomies that drift.
        val registry = SimpleMeterRegistry()
        val metrics = GraphMailMetrics(registry)

        listOf("PERMANENT_INPUT", "PERMANENT_REMOTE", "UNKNOWN", "TRANSIENT").forEach {
            metrics.recordSend(it, withAttachments = false, durationMs = 1)
        }

        listOf("PERMANENT_INPUT", "PERMANENT_REMOTE", "UNKNOWN", "TRANSIENT").forEach { verdict ->
            assertNotNull(
                registry.find("graph.mail.sends").tag("outcome", verdict).counter(),
                "no counter for verdict $verdict",
            )
        }
    }

    @Test
    fun `the limiter and the token cache are published as gauges`() {
        val registry = SimpleMeterRegistry()
        val limiter = AttachmentConcurrencyLimiter(permits = 4)
        val cache = GraphTokenCache()

        GraphMailMetrics(registry).apply {
            bindAttachmentLimiter(limiter)
            bindTokenCache(cache)
        }

        assertEquals(4.0, registry.find("graph.mail.attachment.permits.available").gauge()!!.value())
        assertEquals(0.0, registry.find("graph.mail.token.cache.size").gauge()!!.value())
    }

    @Test
    fun `without a registry every call is a no-op`() {
        // The plugin must keep working in a host application that has no Micrometer at all.
        val metrics = GraphMailMetrics(null)

        assertDoesNotThrow {
            metrics.recordSend("ok", withAttachments = false, durationMs = 1)
            metrics.bindAttachmentLimiter(AttachmentConcurrencyLimiter())
            metrics.bindTokenCache(GraphTokenCache())
        }
    }
}
