package com.ritense.valtimoplugins.graphmail

open class GraphMailException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int = 500,
) : RuntimeException(message, cause)

/**
 * Permanent failure: retrying is guaranteed to produce the same result, so the job executor should
 * not spend its retry budget on it. Something has to change first — a plugin property, an Azure
 * permission, the mailbox, or the input data.
 *
 * [GraphMailPlugin] records this as a PERMANENT_REMOTE verdict in the audit log and rethrows it. It
 * is deliberately NOT converted into a BpmnError — see the comment on GraphMailPlugin.retryVerdictOf
 * for why that would make things worse for existing process models.
 */
class GraphMailPermanentException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int = 400,
) : GraphMailException(message, cause, statusCode)

/**
 * Transient failure: the same call may well succeed later. Deliberately allowed to propagate to the
 * Operaton job executor, which reschedules it without holding a thread — unlike an in-call
 * Thread.sleep() retry, which does.
 */
class GraphMailRetryableException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int = 503,
) : GraphMailException(message, cause, statusCode)

/**
 * Raised when a request failed at the transport layer *after* the body was handed to the server, so
 * the outcome is unknown — the message may or may not have been accepted for delivery.
 *
 * Never retried automatically for non-idempotent operations: a retry that guesses wrong sends the
 * recipient a second copy. Surfaced as a distinct type so operators can tell "definitely not sent"
 * apart from "possibly sent" when reading the logs.
 */
class GraphMailUnknownOutcomeException(
    message: String,
    cause: Throwable? = null,
) : GraphMailException(message, cause, statusCode = 500)
