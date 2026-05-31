package com.ritense.valtimoplugins.graphmail

/**
 * Thrown by [GraphMailClientImpl] when the Graph API returns 401 even after the
 * implementation has already refreshed and retried with a fresh token.
 *
 * Reaching this exception almost always means the Mail.Send permission is missing
 * or the configured mailbox does not exist for the Azure tenant — token refresh
 * has been attempted internally already.
 */
class GraphMailTokenExpiredException(message: String, cause: Throwable? = null) : GraphMailException(message, cause)
