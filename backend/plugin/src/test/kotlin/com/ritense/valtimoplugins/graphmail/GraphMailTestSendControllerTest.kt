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

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication

private const val VALID_CONFIG_ID = "11111111-1111-1111-1111-111111111111"
private const val VALID_RECIPIENT = "recipient@test.nl"
private const val VALID_SENDER = "sender@test.nl"

class GraphMailTestSendControllerTest {

    private val mailClient: GraphMailClient = mock()
    private val pluginService: PluginService = mock()
    private val storage: TemporaryResourceStorageService = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private val authentication: Authentication = mock<Authentication>().also {
        whenever(it.name).thenReturn("test-admin")
    }
    private lateinit var controller: GraphMailTestSendController

    private fun plugin(sender: String? = VALID_SENDER): GraphMailPlugin =
        GraphMailPlugin(mailClient, storage, eventPublisher).apply {
            tenantId = "tenant-id"
            clientId = "client-id"
            clientSecret = "client-secret"
            testSenderMailbox = sender
        }

    @BeforeEach
    fun setUp() {
        controller = GraphMailTestSendController(mailClient, pluginService, eventPublisher)
    }

    private fun send(request: GraphMailTestSendRequest = GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, VALID_SENDER)) =
        controller.testSend(request, authentication)

    // sendMail signature (12 params):
    // tenantId(1), clientId(2), clientSecret(3), senderMailbox(4),
    // toRecipients(5), ccRecipients(6), bccRecipients(7), replyToRecipients(8),
    // subject(9), bodyHtml(10), attachments(11), saveToSentItems(12)

    private fun stubSendMail() =
        whenever(mailClient.sendMail(any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any()))

    private fun stubPlugin(sender: String? = VALID_SENDER) =
        whenever(pluginService.createInstance(any<PluginConfigurationId>())).thenReturn(plugin(sender))

    // ── Input validation ──────────────────────────────────────────────────────────

    @Test fun `rejects invalid pluginConfigurationId`() {
        val response = send(GraphMailTestSendRequest("not-a-uuid", VALID_RECIPIENT, VALID_SENDER))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(400, response.body!!.statusCode)
    }

    @Test fun `rejects invalid recipient email`() {
        val response = send(GraphMailTestSendRequest(VALID_CONFIG_ID, "not-an-email", VALID_SENDER))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(400, response.body!!.statusCode)
    }

    // ── Plugin lookup ──────────────────────────────────────────────────────────────

    @Test fun `returns 404 when createInstance throws`() {
        whenever(pluginService.createInstance(any<PluginConfigurationId>())).thenThrow(RuntimeException("not found"))
        val response = send()
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(404, response.body!!.statusCode)
    }

    @Test fun `returns 404 when createInstance returns null`() {
        whenever(pluginService.createInstance(any<PluginConfigurationId>())).thenReturn(null)
        val response = send()
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertFalse(response.body!!.success)
    }

    @Test fun `resolves plugin using PluginConfigurationId`() {
        stubPlugin()
        send()
        verify(pluginService).createInstance(any<PluginConfigurationId>())
    }

    // ── Sender mailbox validation ──────────────────────────────────────────────────

    @Test fun `returns 400 when request senderMailbox is blank and testSenderMailbox is null`() {
        // Both request.senderMailbox and plugin.testSenderMailbox are empty — no fallback available.
        stubPlugin(sender = null)
        val response = send(GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, "   "))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(400, response.body!!.statusCode)
    }

    @Test fun `uses testSenderMailbox as fallback when request senderMailbox is blank`() {
        // plugin.testSenderMailbox has a valid address — blank request field should not cause 400.
        stubPlugin(sender = VALID_SENDER)
        val response = send(GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, "   "))
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.success)
    }

    @Test fun `returns 400 when request senderMailbox is invalid email`() {
        stubPlugin()
        val response = send(GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, "not-an-email"))
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
    }

    // ── Happy path ─────────────────────────────────────────────────────────────────

    @Test fun `returns success when mail is sent`() {
        stubPlugin()
        val response = send()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.success)
        assertEquals(202, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains(VALID_RECIPIENT))
    }

    @Test fun `publishes GraphMailEmailSentEvent on success`() {
        stubPlugin()
        send()
        verify(eventPublisher).publishEvent(any<GraphMailEmailSentEvent>())
    }

    @Test fun `rate limits same user within 10 seconds`() {
        stubPlugin()
        send() // first call succeeds
        val secondResponse = send()
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, secondResponse.statusCode)
        assertEquals(429, secondResponse.body!!.statusCode)
    }

    @Test fun `passes decrypted credentials from plugin instance to mailClient`() {
        stubPlugin()
        send()
        verify(mailClient).sendMail(
            eq("tenant-id"), eq("client-id"), eq("client-secret"),
            eq(VALID_SENDER), any(), any(), any(), any(), any(), any(), any(), any()
        )
    }

    @Test fun `sends to the recipient from the request`() {
        stubPlugin()
        send()
        val captor = argumentCaptor<List<GraphRecipient>>()
        verify(mailClient).sendMail(
            any(), any(), any(), any(),
            captor.capture(), any(), any(), any(), any(), any(), any(), any()
        )
        assertEquals(1, captor.firstValue.size)
        assertEquals(VALID_RECIPIENT, captor.firstValue[0].emailAddress.address)
    }

    @Test fun `sends with saveToSentItems false`() {
        stubPlugin()
        val captor = argumentCaptor<Boolean>()
        send()
        verify(mailClient).sendMail(
            any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), captor.capture()
        )
        assertFalse(captor.firstValue)
    }

    // ── Error mapping ──────────────────────────────────────────────────────────────

    @Test fun `maps GraphMailTokenExpiredException to 401`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailTokenExpiredException("token expired"))
        val response = send()
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(401, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("Client Secret"))
    }

    @Test fun `maps 403 GraphMailException to Mail Send permission message`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("forbidden", statusCode = 403))
        val response = send()
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(403, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("Mail.Send"))
    }

    @Test fun `maps 429 GraphMailException to rate-limit message`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("rate limited", statusCode = 429))
        val response = send()
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(429, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("429"))
    }

    @Test fun `maps 503 GraphMailException to service-unavailable message`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("unavailable", statusCode = 503))
        val response = send()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(503, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("503"))
    }

    @Test fun `maps unexpected exception to 500`() {
        stubPlugin()
        stubSendMail().thenThrow(RuntimeException("unexpected boom"))
        val response = send()
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(500, response.body!!.statusCode)
    }
}
