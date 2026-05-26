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
