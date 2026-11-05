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
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val VALID_CONFIG_ID = "11111111-1111-1111-1111-111111111111"
private const val VALID_RECIPIENT = "recipient@test.nl"
private const val VALID_SENDER = "sender@test.nl"
private const val LOGIN_ID = "22222222-2222-2222-2222-222222222222"

class GraphMailTestSendControllerTest {
    private val mailClient: GraphMailClient = mock()
    private val pluginService: PluginService = mock()
    private val storage: TemporaryResourceStorageService = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private val authentication: Authentication =
        mock<Authentication>().also {
            whenever(it.name).thenReturn("test-admin")
        }
    private val graphLogin = GraphMailAuthentication("the login", null) { HttpHeaders().apply { setBearerAuth("token") } }
    private val login: GraphMailLogin = mock { on { resolve(LOGIN_ID) } doReturn graphLogin }
    private lateinit var controller: GraphMailTestSendController

    private fun plugin(
        sender: String? = VALID_SENDER,
        allowlist: String? = "@test.nl",
    ): GraphMailPlugin =
        GraphMailPlugin(
            mailClient,
            storage,
            eventPublisher,
            login = login,
        ).apply {
            authenticationPluginConfiguration = LOGIN_ID
            // allowlist == null simulates a pre-allowlist configuration (property never injected).
            if (allowlist != null) allowedSenders = allowlist
            testSenderMailbox = sender
        }

    @BeforeEach
    fun setUp() {
        controller = GraphMailTestSendController(mailClient, pluginService, eventPublisher)
    }

    private fun send(
        request: GraphMailTestSendRequest = GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, VALID_SENDER),
    ) = controller.testSend(request, authentication)

    private fun stubSendMail() = whenever(mailClient.sendMail(any(), any()))

    private fun stubPlugin(
        sender: String? = VALID_SENDER,
        allowlist: String? = "@test.nl",
    ) = whenever(pluginService.createInstance(any<PluginConfigurationId>())).thenReturn(plugin(sender, allowlist))

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

    // ── Sender allowlist enforcement ───────────────────────────────────────────────

    @Test fun `returns 400 when allowedSenders is not configured`() {
        stubPlugin(allowlist = null)
        val response = send()
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
        assertTrue(response.body!!.message.contains("allowedSenders"))
    }

    @Test fun `returns 400 when allowedSenders is blank`() {
        stubPlugin(allowlist = "   ")
        val response = send()
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertFalse(response.body!!.success)
    }

    @Test fun `returns 403 when test sender is not on the allowlist`() {
        stubPlugin(allowlist = "noreply@gemeente.nl")
        val response = send(GraphMailTestSendRequest(VALID_CONFIG_ID, VALID_RECIPIENT, "ceo@gemeente.nl"))
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(403, response.body!!.statusCode)
    }

    @Test fun `accepts test sender matching a domain allowlist entry`() {
        stubPlugin(allowlist = "@test.nl")
        val response = send()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.success)
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

    @Test fun `evicts stale rate limit entries once the store grows large`() {
        stubPlugin()
        val field = GraphMailTestSendController::class.java.getDeclaredField("rateLimitStore")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val store = field.get(controller) as ConcurrentHashMap<String, AtomicLong>

        // Mirrors the private RATE_LIMIT_INTERVAL_MS / RATE_LIMIT_STORE_MAX_ENTRIES constants in
        // GraphMailTestSendController.kt. Pre-populate the store with entries that are already
        // stale (older than the rate-limit window) to simulate a long-running instance that many
        // distinct admins have used over time.
        val rateLimitIntervalMs = 10_000L
        val rateLimitStoreMaxEntries = 1_000
        val staleTimestamp = System.currentTimeMillis() - rateLimitIntervalMs - 1_000
        repeat(rateLimitStoreMaxEntries) { i -> store["stale-user-$i"] = AtomicLong(staleTimestamp) }
        assertEquals(rateLimitStoreMaxEntries, store.size)

        // The next request — from any user — crosses the size threshold and triggers the sweep;
        // every pre-populated stale entry should be evicted, leaving only the new caller's entry.
        send()

        assertTrue(store.size < rateLimitStoreMaxEntries)
    }

    @Test fun `sends with the login of the configuration`() {
        stubPlugin()
        send()
        verify(mailClient).sendMail(eq(graphLogin), argThat { senderMailbox == VALID_SENDER })
    }

    @Test fun `a login that can't be used is a 400 with its message, and nothing is sent`() {
        stubPlugin()
        whenever(login.resolve(LOGIN_ID)).thenThrow(IllegalStateException("'Graph login' has an audience"))

        val response = send()

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!.message.contains("'Graph login' has an audience"), response.body!!.message)
        verify(mailClient, never()).sendMail(any(), any())
    }

    @Test fun `certificates that can't be loaded are reported as such`() {
        stubPlugin()
        whenever(
            login.resolve(LOGIN_ID),
        ).thenThrow(GraphMailCertificateException("The certificates of X could not be loaded"))

        val response = send()

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertTrue(response.body!!.message.startsWith("Certificaten — The certificates of X"), response.body!!.message)
    }

    @Test fun `an unexpected failure of the login is reported, and nothing is sent`() {
        stubPlugin()
        whenever(login.resolve(LOGIN_ID)).thenThrow(RuntimeException("could not create the instance"))

        val response = send()

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertFalse(response.body!!.success)
        assertTrue(response.body!!.message.contains("could not create the instance"), response.body!!.message)
        verify(mailClient, never()).sendMail(any(), any())
    }

    @Test fun `sends to the recipient from the request`() {
        stubPlugin()
        send()
        val captor = argumentCaptor<OutboundMail>()
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(1, captor.firstValue.toRecipients.size)
        assertEquals(
            VALID_RECIPIENT,
            captor.firstValue.toRecipients[0]
                .emailAddress.address,
        )
    }

    @Test fun `sends with saveToSentItems false`() {
        stubPlugin()
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assertFalse(captor.firstValue.saveToSentItems)
    }

    // ── Error mapping ──────────────────────────────────────────────────────────────

    @Test fun `a refused token is HTTP 502 with Graph status 401 and the advice of the exception`() {
        stubPlugin()
        stubSendMail().thenThrow(
            GraphMailPermanentException(
                "Graph refused the token of the login (401): grant Mail.Send",
                statusCode = 401,
            ),
        )
        val response = send()
        // Upstream 401 is surfaced as 502: Graph refused OUR token, the admin's session is fine.
        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(401, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("Graph refused the token of the login"), response.body!!.message)
        assertFalse(response.body!!.message.contains("Client Secret"), response.body!!.message)
    }

    @Test fun `an exception message containing an address is masked in the response`() {
        // RestClientException embeds the request URI, and the sender mailbox sits in that path.
        stubPlugin()
        stubSendMail().thenThrow(
            GraphMailException(
                """I/O error on POST request for "https://graph.microsoft.com/v1.0/users/geheim@gemeente.nl/sendMail"""",
                statusCode = 500,
            ),
        )

        val response = send()

        val message = response.body!!.message
        assertFalse(message.contains("geheim@gemeente.nl"), "raw address leaked to the admin UI: $message")
        assertTrue(message.contains("g***@gemeente.nl"), "expected a masked address, got: $message")
    }

    @Test fun `shows the message of a 403 GraphMailException`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("Access denied — grant Mail.Send", statusCode = 403))
        val response = send()
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(403, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("Mail.Send"))
    }

    @Test fun `shows the message of a 429 GraphMailException`() {
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("rate limited", statusCode = 429))
        val response = send()
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(429, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("rate limited"), response.body!!.message)
    }

    @Test fun `does not hide the message of a 503 GraphMailException`() {
        // A gateway's 503 used to read only "tijdelijk niet beschikbaar", hiding what failed.
        stubPlugin()
        stubSendMail().thenThrow(GraphMailException("The gateway answered 503", statusCode = 503))
        val response = send()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertFalse(response.body!!.success)
        assertEquals(503, response.body!!.statusCode)
        assertTrue(response.body!!.message.contains("The gateway answered 503"), response.body!!.message)
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
