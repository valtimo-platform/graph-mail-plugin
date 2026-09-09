package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Caps how many attachment-carrying sends may run at once.
 *
 * Without it, concurrency is whatever the Operaton job-executor pool happens to be, and peak heap is
 * that number × [MAX_TOTAL_ATTACHMENT_BYTES] × the copies each send makes (raw bytes, then either a
 * base64 string ~1.33× on the inline path or a chunk buffer on the upload path). At the pool size
 * this plugin's own startup warning recommends, that is several gigabytes — an OutOfMemoryError on
 * any normally-sized GZAC container, triggered by nothing more unusual than a batch of emails with
 * attachments.
 *
 * Bounding it here rather than by shrinking the thread pool keeps attachment-free sends — the vast
 * majority, and negligible in memory — running at full concurrency.
 */
class AttachmentConcurrencyLimiter(
    permits: Int = DEFAULT_PERMITS,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
) {
    private val logger = LoggerFactory.getLogger(AttachmentConcurrencyLimiter::class.java)

    // Fair, so a burst of large sends cannot starve one that has been waiting.
    private val semaphore = Semaphore(permits, true)

    fun <T> withPermit(
        hasAttachments: Boolean,
        block: () -> T,
    ): T {
        if (!hasAttachments) return block()

        val waitStart = System.currentTimeMillis()
        val acquired =
            try {
                semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (ex: InterruptedException) {
                // Restore the flag the catch just cleared. An engine shutting down interrupts its
                // job-executor threads, and swallowing that leaves the rest of the shutdown path
                // unable to see it — while letting a raw InterruptedException escape surfaces as an
                // UNCLASSIFIED verdict and a stack trace, rather than the transient this is.
                Thread.currentThread().interrupt()
                throw GraphMailRetryableException(
                    "Interrupted while waiting for an attachment send slot — the application is " +
                        "most likely shutting down. The job executor will retry.",
                    ex,
                )
            }
        if (!acquired) {
            // Retryable on purpose: the engine reschedules this without holding a thread, which is
            // precisely the behaviour that makes the cap safe to enforce.
            throw GraphMailRetryableException(
                "Could not acquire an attachment send slot within ${acquireTimeoutMs}ms — " +
                    "too many concurrent attachment sends. Raise " +
                    "graph-mail.http.attachment-concurrency if the heap allows it, or lower the " +
                    "job-executor pool size.",
            )
        }
        val waited = System.currentTimeMillis() - waitStart
        if (waited > 0) {
            logger.debug(
                "Waited {}ms for an attachment send slot ({} still available)",
                waited,
                semaphore.availablePermits(),
            )
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    internal fun availablePermits(): Int = semaphore.availablePermits()

    private companion object {
        const val DEFAULT_PERMITS = 8
        const val DEFAULT_ACQUIRE_TIMEOUT_MS = 30_000L
    }
}
