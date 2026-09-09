package com.ritense.valtimoplugins.graphmail

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.TimeUnit

/**
 * The plugin's Micrometer instrumentation, behind one seam.
 *
 * Observability was logs and two Spring events. Neither answers the questions an administrator
 * actually has: how much mail is going out, how much of it is failing and why, and how close the
 * attachment limiter is running to its ceiling. GZAC ships an Actuator/Prometheus stack, so the
 * collection side already exists — nothing was feeding it.
 *
 * Everything routes through this class so that [GraphMailPlugin] does not grow a second concern,
 * and so a deployment without Micrometer keeps working: [GraphMailAutoConfiguration] hands over a
 * null registry there and every method below becomes a no-op.
 */
class GraphMailMetrics(
    private val registry: MeterRegistry?,
) {
    /**
     * Counts one finished send. [outcome] is the audit log's own verdict vocabulary — the value
     * `retryVerdictOf` already produces, plus `ok` — so a dashboard and a log line describe the
     * same failure with the same word instead of two parallel taxonomies.
     */
    fun recordSend(
        outcome: String,
        withAttachments: Boolean,
        durationMs: Long,
    ) {
        val registry = registry ?: return
        val tags =
            Tags.of(
                "outcome",
                outcome,
                "attachments",
                withAttachments.toString(),
            )
        registry.counter("graph.mail.sends", tags).increment()
        registry.timer("graph.mail.send.duration", tags).record(durationMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Publishes the attachment limiter's free slots. A gauge rather than a counter: the question is
     * "how close are we to the ceiling right now", and a send that waits for a slot is a send that
     * is about to be handed back to the job executor.
     */
    fun bindAttachmentLimiter(limiter: AttachmentConcurrencyLimiter) {
        val registry = registry ?: return
        registry.gauge("graph.mail.attachment.permits.available", limiter) {
            it.availablePermits().toDouble()
        }
    }

    /** Publishes how many tokens the shared cache is holding. */
    fun bindTokenCache(cache: GraphTokenCache) {
        val registry = registry ?: return
        registry.gauge("graph.mail.token.cache.size", cache) { it.size().toDouble() }
    }
}
