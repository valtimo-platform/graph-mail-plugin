package com.ritense.valtimoplugins.graphmail

/**
 * Contract for the Graph Mail client.
 *
 * Token lifecycle (acquire, cache, refresh on 401) is entirely owned by the
 * implementation — callers pass credentials, never raw tokens. This keeps
 * retry logic in one place and prevents stale tokens leaking to callers.
 *
 * Separated interface enables easy mocking in tests with any framework
 * (Mockito, MockK, Spring Test) without coupling to the HTTP implementation.
 */
interface GraphMailClient {

    fun sendMail(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        toRecipients: List<GraphRecipient>,
        ccRecipients: List<GraphRecipient>,
        bccRecipients: List<GraphRecipient>,
        replyToRecipients: List<GraphRecipient>,
        subject: String,
        bodyHtml: String,
        attachments: List<ResolvedAttachment>,
        saveToSentItems: Boolean,
    )

    /**
     * Invalidate the cached token for one tenant/client pair.
     * If both args are null the entire cache is flushed (rare — global compromise).
     */
    fun invalidateCache(tenantId: String? = null, clientId: String? = null)
}
