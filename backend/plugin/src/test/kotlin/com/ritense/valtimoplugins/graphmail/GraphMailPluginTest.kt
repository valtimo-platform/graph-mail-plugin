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

import com.ritense.resource.service.TemporaryResourceStorageService
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.io.ByteArrayInputStream

private const val VALID_CONTENT_UUID = "22222222-2222-2222-2222-222222222222"
private const val VALID_UUID = "11111111-1111-1111-1111-111111111111"

class GraphMailPluginTest {

    private val mailClient: GraphMailClient = mock()
    private val storage: TemporaryResourceStorageService = mock()
    private val execution: DelegateExecution = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private lateinit var plugin: GraphMailPlugin

    @BeforeEach
    fun setUp() {
        plugin = GraphMailPlugin(mailClient, storage, eventPublisher).apply {
            tenantId = "test-tenant"
            clientId = "test-client"
            clientSecret = "test-secret"
        }
        whenever(storage.getResourceContentAsInputStream(VALID_CONTENT_UUID))
            .thenReturn(ByteArrayInputStream("<p>Test</p>".toByteArray()))
    }

    private fun send(
        mailbox: String = "afzender@test.nl",
        to: String = "ontvanger@test.nl",
        cc: String? = null,
        bcc: String? = null,
        replyTo: String? = null,
        subject: String = "Test",
        body: String = VALID_CONTENT_UUID,
        attachments: String? = null,
    ) = plugin.sendEmail(execution, mailbox, to, cc, bcc, replyTo, subject, body, attachments)

    private fun verifySend() = verify(mailClient).sendMail(
        any(), any(), any(), any(), any(), any(), any(), any(), any(),
        any(), any(), any()
    )

    private fun mockBodyHtml(html: String) {
        whenever(storage.getResourceContentAsInputStream(VALID_CONTENT_UUID))
            .thenReturn(ByteArrayInputStream(html.toByteArray()))
    }

    // ── Sender mailbox ───────────────────────────────────────────────────────

    @Test fun `uses provided senderMailbox`() {
        val captor = argumentCaptor<String>()
        send(mailbox = "afdeling@test.nl")
        verify(mailClient).sendMail(
            any(), any(), any(),
            captor.capture(),
            any(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals("afdeling@test.nl", captor.firstValue)
    }

    @Test fun `passes credentials to mailClient`() {
        val tenant = argumentCaptor<String>()
        val client = argumentCaptor<String>()
        val secret = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            tenant.capture(), client.capture(), secret.capture(),
            any(), any(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals("test-tenant", tenant.firstValue)
        assertEquals("test-client", client.firstValue)
        assertEquals("test-secret", secret.firstValue)
    }

    @Test fun `rejects invalid sender mailbox`() {
        assertThrows<IllegalArgumentException> { send(mailbox = "not-an-email") }
    }

    // ── Subject / body validation ──────────────────────────────────────────────────

    @Test fun `rejects blank subject`() {
        assertThrows<IllegalArgumentException> { send(subject = "   ") }
    }

    @Test fun `rejects blank contentId`() {
        assertThrows<IllegalArgumentException> { send(body = "") }
    }

    @Test fun `throws IllegalArgumentException when contentId is a path-like string`() {
        assertThrows<IllegalArgumentException> { send(body = "../etc/passwd") }
    }

    @Test fun `throws GraphMailException when content resource not found in storage`() {
        assertThrows<GraphMailException> { send(body = "00000000-0000-0000-0000-000000000000") }
    }

    @Test fun `accepts non-UUID resource id for contentId`() {
        whenever(storage.getResourceContentAsInputStream("6701022396959743596-11964920214272695939"))
            .thenReturn(ByteArrayInputStream("<p>Test</p>".toByteArray()))
        send(body = "6701022396959743596-11964920214272695939")
        verifySend()
    }

    @Test fun `rejects subject exceeding 255 chars`() {
        assertThrows<IllegalArgumentException> { send(subject = "a".repeat(256)) }
    }

    // ── CRLF / header injection guards (server-side defense in depth) ──────

    @Test fun `rejects CR in subject`() {
        assertThrows<IllegalArgumentException> { send(subject = "phishy\rBcc: evil@x.nl") }
    }

    @Test fun `rejects LF in subject`() {
        assertThrows<IllegalArgumentException> { send(subject = "phishy\nBcc: evil@x.nl") }
    }

    @Test fun `rejects CRLF in senderMailbox`() {
        assertThrows<IllegalArgumentException> { send(mailbox = "ok@test.nl\rfoo") }
    }

    @Test fun `rejects CRLF in recipients`() {
        assertThrows<IllegalArgumentException> { send(to = "ok@test.nl\nevil@test.nl") }
    }

    // ── HTML sanitisation (jsoup allowlist) ────────────────────────────────────

    @Test fun `strips script tags from body content`() {
        mockBodyHtml("<p>Hello</p><script>alert('xss')</script>")
        val captor = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            captor.capture(), any(), any()
        )
        assert(!captor.firstValue.contains("<script", ignoreCase = true))
    }

    @Test fun `strips iframe and object tags`() {
        mockBodyHtml("""<p>ok</p><iframe src="evil"></iframe><object data="x"></object>""")
        val captor = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            captor.capture(), any(), any()
        )
        assert(!captor.firstValue.contains("<iframe", ignoreCase = true))
        assert(!captor.firstValue.contains("<object", ignoreCase = true))
    }

    @Test fun `neutralises javascript protocol in href`() {
        mockBodyHtml("""<p><a href="javascript:alert(1)">click</a></p>""")
        val captor = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            captor.capture(), any(), any()
        )
        assert(!captor.firstValue.contains("javascript:", ignoreCase = true))
    }

    @Test fun `strips inline event handlers without leading whitespace`() {
        mockBodyHtml("""<p><img src="x"onerror="alert(1)" /></p>""")
        val captor = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            captor.capture(), any(), any()
        )
        assert(!captor.firstValue.contains("onerror", ignoreCase = true))
    }

    @Test fun `preserves legitimate inline style attributes`() {
        mockBodyHtml("""<p style="color:red">text</p>""")
        val captor = argumentCaptor<String>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            captor.capture(), any(), any()
        )
        assert(captor.firstValue.contains("style=", ignoreCase = true))
    }

    @Test fun `rejects blank body content`() {
        mockBodyHtml("")
        assertThrows<IllegalArgumentException> { send() }
    }

    @Test fun `rejects body content exceeding 5 MB`() {
        // MAX_BODY_CONTENT_BYTES = 5 * 1_048_576 is private; use the literal value
        val oversizedHtml = "<p>" + "x".repeat(5 * 1_048_576) + "</p>"
        mockBodyHtml(oversizedHtml)
        assertThrows<IllegalArgumentException> { send() }
    }

    // ── Email validation ─────────────────────────────────────────────────────────

    @Test fun `rejects invalid recipient`() {
        assertThrows<IllegalArgumentException> { send(to = "not-an-email") }
    }

    @Test fun `rejects invalid CC`() {
        assertThrows<IllegalArgumentException> { send(cc = "invalid") }
    }

    @Test fun `accepts valid email`() {
        send(to = "valid@gemeente.nl")
        verifySend()
    }

    @Test fun `accepts plus-addressing`() {
        send(to = "user+tag@test.nl")
        verifySend()
    }

    @Test fun `rejects double dots in domain`() {
        assertThrows<IllegalArgumentException> { send(to = "user@test..nl") }
    }

    @Test fun `rejects email exceeding 254 chars`() {
        assertThrows<IllegalArgumentException> { send(to = "a".repeat(250) + "@t.nl") }
    }

    // ── Recipient limits ─────────────────────────────────────────────────────────

    @Test fun `rejects more than 100 recipients in one field`() {
        assertThrows<IllegalArgumentException> {
            send(to = (1..101).joinToString(",") { "user$it@test.nl" })
        }
    }

    @Test fun `accepts exactly 100 recipients in one field`() {
        send(to = (1..100).joinToString(",") { "user$it@test.nl" })
        verifySend()
    }

    @Test fun `rejects total addresses over 200 across all fields`() {
        assertThrows<IllegalArgumentException> {
            send(
                to = (1..100).joinToString(",") { "to$it@test.nl" },
                cc = (1..100).joinToString(",") { "cc$it@test.nl" },
                bcc = "extra@test.nl",
            )
        }
    }

    // ── Recipient list handling ───────────────────────────────────────────────────

    @Test fun `passes multiple recipients to mailClient`() {
        val captor = argumentCaptor<List<GraphRecipient>>()
        send(to = "a@t.nl,b@t.nl,c@t.nl")
        verify(mailClient).sendMail(
            any(), any(), any(), any(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals(3, captor.firstValue.size)
        assertEquals("a@t.nl", captor.firstValue[0].emailAddress.address)
    }

    @Test fun `ignores blank entries in recipient list`() {
        val captor = argumentCaptor<List<GraphRecipient>>()
        send(to = "a@t.nl,  ,b@t.nl")
        verify(mailClient).sendMail(
            any(), any(), any(), any(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals(2, captor.firstValue.size)
    }

    @Test fun `accepts JSON array string for recipients`() {
        val captor = argumentCaptor<List<GraphRecipient>>()
        send(to = """["a@t.nl","b@t.nl"]""")
        verify(mailClient).sendMail(
            any(), any(), any(), any(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals(2, captor.firstValue.size)
        assertEquals("a@t.nl", captor.firstValue[0].emailAddress.address)
    }

    // ── Attachments ──────────────────────────────────────────────────────────────

    @Test fun `resolves attachments from storage`() {
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("fileName" to "doc.pdf", "contentType" to "application/pdf"))
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<List<ResolvedAttachment>>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), captor.capture(), any()
        )
        assertEquals(1, captor.firstValue.size)
        assertEquals("doc.pdf", captor.firstValue[0].name)
    }

    @Test fun `empty attachments when ids null`() {
        val captor = argumentCaptor<List<ResolvedAttachment>>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), captor.capture(), any()
        )
        assertEquals(0, captor.firstValue.size)
    }

    @Test fun `accepts attachment up to 25 MB`() {
        val bigBytes = ByteArray(MAX_SINGLE_ATTACHMENT_BYTES.toInt())
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("fileName" to "large.bin", "contentType" to "application/octet-stream"))
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream(bigBytes))
        send(attachments = VALID_UUID)
        verifySend()
    }

    @Test fun `rejects path-traversal attachment id`() {
        assertThrows<IllegalArgumentException> { send(attachments = "../etc/passwd") }
    }

    @Test fun `rejects more than MAX_ATTACHMENTS attachments`() {
        assertThrows<IllegalArgumentException> {
            send(attachments = (1..(MAX_ATTACHMENTS + 1)).joinToString(",") { VALID_UUID })
        }
    }

    @Test fun `rejects single attachment exceeding size cap`() {
        val oversized = ByteArray((MAX_SINGLE_ATTACHMENT_BYTES + 1).toInt())
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("fileName" to "big.bin", "contentType" to "application/octet-stream"))
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream(oversized))
        assertThrows<IllegalArgumentException> { send(attachments = VALID_UUID) }
    }

    // ── Error propagation ────────────────────────────────────────────────────────

    @Test fun `propagates GraphMailException from mailClient`() {
        whenever(
            mailClient.sendMail(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())
        ).doThrow(GraphMailException("error"))
        assertThrows<GraphMailException> { send() }
    }

    // ── Event listener isolation ─────────────────────────────────────────────────

    @Test fun `listener exception on success does not fail the send`() {
        whenever(eventPublisher.publishEvent(any<GraphMailEmailSentEvent>()))
            .doThrow(RuntimeException("listener failed"))
        send()
        verifySend()
    }

    @Test fun `listener exception on failure still re-throws original send exception`() {
        whenever(
            mailClient.sendMail(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())
        ).doThrow(GraphMailException("send failed"))
        whenever(eventPublisher.publishEvent(any<GraphMailEmailFailedEvent>()))
            .doThrow(RuntimeException("listener failed"))
        val ex = assertThrows<GraphMailException> { send() }
        assert(ex.message == "send failed")
    }
}
