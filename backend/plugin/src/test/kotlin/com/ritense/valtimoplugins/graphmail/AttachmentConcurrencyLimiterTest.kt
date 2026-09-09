package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AttachmentConcurrencyLimiterTest {
    @Test fun `sends without attachments are never blocked`() {
        // Zero permits: an attachment send could not possibly get through, but a plain send must.
        val limiter = AttachmentConcurrencyLimiter(permits = 1, acquireTimeoutMs = 100)
        val blocker = CountDownLatch(1)
        val holding = CountDownLatch(1)

        val hog =
            Thread {
                limiter.withPermit(hasAttachments = true) {
                    holding.countDown()
                    blocker.await(5, TimeUnit.SECONDS)
                }
            }
        hog.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS), "permit holder did not start")

        assertEquals("done", limiter.withPermit(hasAttachments = false) { "done" })

        blocker.countDown()
        hog.join(5_000)
    }

    @Test fun `an attachment send waits when no permit is available and fails as retryable`() {
        val limiter = AttachmentConcurrencyLimiter(permits = 1, acquireTimeoutMs = 100)
        val blocker = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()

        val hog =
            Thread {
                limiter.withPermit(hasAttachments = true) {
                    holding.countDown()
                    blocker.await(5, TimeUnit.SECONDS)
                }
            }
        hog.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS), "permit holder did not start")

        val ex =
            assertThrows(GraphMailRetryableException::class.java) {
                limiter.withPermit(hasAttachments = true) { "never reached" }
            }
        // Retryable on purpose: the engine reschedules without holding a thread, which is what
        // makes capping concurrency safe rather than lossy.
        assertTrue(ex.message!!.contains("attachment-concurrency"))

        blocker.countDown()
        hog.join(5_000)
        assertEquals(null, error.get())
    }

    @Test fun `a permit is released even when the send throws`() {
        val limiter = AttachmentConcurrencyLimiter(permits = 1, acquireTimeoutMs = 100)

        assertThrows(IllegalStateException::class.java) {
            limiter.withPermit(hasAttachments = true) { throw IllegalStateException("boom") }
        }

        assertEquals(1, limiter.availablePermits())
        assertEquals("ok", limiter.withPermit(hasAttachments = true) { "ok" })
    }

    @Test fun `an interrupted wait restores the flag and reports a transient`() {
        // An engine shutting down interrupts its job-executor threads. Catching the interrupt
        // clears the flag, so the rest of the shutdown path can no longer see it; and a raw
        // InterruptedException escaping produces an UNCLASSIFIED verdict plus a stack trace,
        // rather than the transient this actually is.
        val limiter = AttachmentConcurrencyLimiter(permits = 1, acquireTimeoutMs = 10_000)
        val occupied = CountDownLatch(1)
        val holder =
            thread {
                limiter.withPermit(hasAttachments = true) {
                    occupied.countDown()
                    Thread.sleep(5_000)
                }
            }
        assertTrue(occupied.await(5, TimeUnit.SECONDS), "the first send never took its permit")

        val caught = AtomicReference<Throwable>()
        val flagStillSet = AtomicBoolean(false)
        val waiter =
            thread {
                try {
                    limiter.withPermit(hasAttachments = true) { }
                } catch (ex: Throwable) {
                    caught.set(ex)
                    flagStillSet.set(Thread.currentThread().isInterrupted)
                }
            }

        Thread.sleep(200)
        waiter.interrupt()
        waiter.join(5_000)
        holder.interrupt()
        holder.join(5_000)

        assertTrue(
            caught.get() is GraphMailRetryableException,
            "expected a transient, got ${caught.get()?.let { it::class.simpleName }}",
        )
        assertTrue(flagStillSet.get(), "the interrupt flag was swallowed")
    }

    @Test fun `concurrency never exceeds the configured permits`() {
        val permits = 3
        val limiter = AttachmentConcurrencyLimiter(permits = permits, acquireTimeoutMs = 5_000)
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val start = CountDownLatch(1)

        val threads =
            (1..20).map {
                Thread {
                    start.await(5, TimeUnit.SECONDS)
                    limiter.withPermit(hasAttachments = true) {
                        val now = inFlight.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        Thread.sleep(10)
                        inFlight.decrementAndGet()
                    }
                }
            }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(15_000) }

        assertTrue(peak.get() <= permits, "peak concurrency was ${peak.get()}, expected at most $permits")
    }
}
