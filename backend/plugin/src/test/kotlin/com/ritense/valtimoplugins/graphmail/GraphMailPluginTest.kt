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

import com.ritense.plugin.annotation.PluginEvent
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.plugin.domain.EventType
import com.ritense.plugin.service.PluginService
import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.context.ApplicationEventPublisher
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.springframework.http.HttpHeaders

private const val VALID_CONTENT_UUID = "22222222-2222-2222-2222-222222222222"
private const val VALID_UUID = "11111111-1111-1111-1111-111111111111"
private const val LOGIN_ID = "33333333-3333-3333-3333-333333333333"

class GraphMailPluginTest {
    private val mailClient: GraphMailClient = mock()
    private val storage: TemporaryResourceStorageService = mock()
    private val execution: DelegateExecution = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private val graphLogin = GraphMailAuthentication("the login", null) { HttpHeaders().apply { setBearerAuth("token") } }
    private val login: GraphMailLogin = mock { on { resolve(LOGIN_ID) } doReturn graphLogin }
    private val pluginService: PluginService = mock()
    private lateinit var plugin: GraphMailPlugin

    // Stands in for the process instance's variable map.
    private val processVariables = mutableMapOf<String, Any?>()

    @BeforeEach
    fun setUp() {
        processVariables.clear()
        plugin =
            GraphMailPlugin(
                mailClient,
                storage,
                eventPublisher,
                login = login,
            ).apply {
                authenticationPluginConfiguration = LOGIN_ID
                allowedSenders = "@test.nl"
            }
        whenever(execution.id).thenReturn("execution-1")
        whenever(execution.processInstanceId).thenReturn("process-1")
        whenever(execution.currentActivityId).thenReturn("send-email-task")

        // The duplicate guard keys on a pass counter held in a process variable, so the mock has to
        // behave like a real execution: what setVariable writes, getVariable reads back. Stubbing
        // getVariable to a constant would make every test look like a first pass and hide exactly
        // the behaviour these specs are here to pin down.
        whenever(execution.setVariable(any(), any())).thenAnswer { invocation ->
            processVariables[invocation.getArgument(0)] = invocation.getArgument(1)
            null
        }
        whenever(execution.getVariable(any())).thenAnswer { invocation ->
            processVariables[invocation.getArgument<String>(0)]
        }
        // thenAnswer (not thenReturn) — a fresh stream per call, matching a real storage
        // service; thenReturn would hand back the same already-consumed/closed stream on a
        // second sendEmail() call within one test (e.g. the idempotency retry tests below).
        whenever(storage.getResourceContentAsInputStream(VALID_CONTENT_UUID))
            .thenAnswer { ByteArrayInputStream("<p>Test</p>".toByteArray()) }
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

    private fun verifySend() = verify(mailClient).sendMail(any(), any())

    // A job-executor retry re-runs the activity after the transaction rolled back, which undoes the
    // pass-counter increment. Calling send() twice in a row is NOT a retry — that is two committed
    // passes, and the guard is supposed to let both through.
    private fun rollBackTransaction(activityId: String = "send-email-task") {
        val key = "$PASS_COUNTER_VARIABLE_PREFIX$activityId"
        processVariables[key] = ((processVariables[key] as? Int) ?: 1) - 1
    }

    private fun mockBodyHtml(html: String) {
        whenever(storage.getResourceContentAsInputStream(VALID_CONTENT_UUID))
            .thenAnswer { ByteArrayInputStream(html.toByteArray()) }
    }

    // ── Sender mailbox ───────────────────────────────────────────────────────

    @Test fun `uses provided senderMailbox`() {
        val captor = argumentCaptor<OutboundMail>()
        send(mailbox = "afdeling@test.nl")
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals("afdeling@test.nl", captor.firstValue.senderMailbox)
    }

    @Test fun `sends with the login of the selected configuration`() {
        send()
        verify(login).resolve(LOGIN_ID)
        verify(mailClient).sendMail(eq(graphLogin), any())
    }

    @Test fun `a login that can't be used fails the send, audited, without sending`() {
        whenever(login.resolve(LOGIN_ID)).thenThrow(IllegalStateException("'Graph login' has an audience"))

        val ex = assertThrows<IllegalStateException> { send() }

        assertEquals("'Graph login' has an audience", ex.message)
        verify(mailClient, never()).sendMail(any(), any())
        verify(eventPublisher).publishEvent(any<GraphMailEmailFailedEvent>())
    }

    @Test fun `a 1x configuration without a reference sends with its own client secret`() {
        val credentials = GraphCredentials("tenant-1", "client-1", "secret-1")
        whenever(login.resolve(null, credentials)).thenReturn(graphLogin)
        plugin.apply {
            authenticationPluginConfiguration = null
            tenantId = "tenant-1"
            clientId = "client-1"
            clientSecret = "secret-1"
        }

        send()

        verify(login).resolve(null, credentials)
        verify(mailClient).sendMail(eq(graphLogin), any())
    }

    // ── Checks on save and deploy ────────────────────────────────────────────

    @Test fun `a configuration that can supply a token is accepted on save`() {
        plugin.checkConfiguration()

        verify(login).validate(LOGIN_ID, null)
    }

    @Test fun `the Graph API URL is checked on save and goes with the login of a send`() {
        plugin.baseUrl = "https://gateway.example.nl/graph"
        whenever(login.resolve(LOGIN_ID, null, "https://gateway.example.nl/graph")).thenReturn(graphLogin)

        plugin.checkConfiguration()
        send()

        verify(login).validate(LOGIN_ID, null, "https://gateway.example.nl/graph")
        verify(login).resolve(LOGIN_ID, null, "https://gateway.example.nl/graph")
        verify(mailClient).sendMail(eq(graphLogin), any())
    }

    @Test fun `a 1x configuration is checked with its own client secret, not refused`() {
        plugin.apply {
            authenticationPluginConfiguration = null
            tenantId = "tenant-1"
            clientId = "client-1"
            clientSecret = "secret-1"
        }

        plugin.checkConfiguration()

        verify(login).validate(null, GraphCredentials("tenant-1", "client-1", "secret-1"))
    }

    @Test fun `an incomplete client-secret login is handed to the login to name what is missing`() {
        plugin.apply {
            authenticationPluginConfiguration = null
            tenantId = "tenant-1"
        }

        plugin.checkConfiguration()

        verify(login).validate(null, GraphCredentials("tenant-1", "", ""))
    }

    @Test fun `the reference is an optional plain property, so a 1x configuration needs none`() {
        val property =
            GraphMailPlugin::class.java
                .getDeclaredField("authenticationPluginConfiguration")
                .getAnnotation(PluginProperty::class.java)

        assertEquals("authenticationPluginConfiguration", property.key)
        assertFalse(property.required)
        assertFalse(property.secret)
    }

    // Removing them, or making clientSecret non-secret, would leave the secrets stored by 1.x encrypted and
    // unreadable, and store one from a *.pluginconfig.json unencrypted.
    @Test fun `the 1x login properties are still defined, optional, with clientSecret a secret`() {
        val properties =
            listOf("tenantId", "clientId", "clientSecret").associateWith {
                GraphMailPlugin::class.java.getDeclaredField(it).getAnnotation(PluginProperty::class.java)
            }

        properties.forEach { (key, property) ->
            assertEquals(key, property.key)
            assertFalse(property.required, key)
        }
        assertFalse(properties.getValue("tenantId").secret)
        assertFalse(properties.getValue("clientId").secret)
        assertTrue(properties.getValue("clientSecret").secret)
    }

    @Test fun `the checks run when Valtimo creates, deploys or updates a configuration`() {
        val event = GraphMailPlugin::class.java.getMethod("checkConfiguration").getAnnotation(PluginEvent::class.java)

        assertEquals(setOf(EventType.CREATE, EventType.UPDATE), event.invokedOn.toSet())
    }

    @Test fun `the factory hands the plugin the shared login`() {
        val factory =
            GraphMailPluginFactory(
                pluginService,
                mailClient,
                storage,
                eventPublisher,
                SendIdempotencyGuard(),
                AttachmentConcurrencyLimiter(),
                GraphMailMetrics(null),
                login,
            )
        val created = GraphMailPluginFactory::class.java.getDeclaredMethod("create").invoke(factory) as GraphMailPlugin
        created.authenticationPluginConfiguration = LOGIN_ID

        created.checkConfiguration()

        verify(login).validate(LOGIN_ID, null)
    }

    @Test fun `a reference that can't supply a token is refused on save`() {
        whenever(login.validate(LOGIN_ID, null)).thenThrow(IllegalStateException("'Graph login' has no scope"))

        val ex = assertThrows<IllegalStateException> { plugin.checkConfiguration() }

        assertEquals("'Graph login' has no scope", ex.message)
    }

    @Test fun `rejects invalid sender mailbox`() {
        assertThrows<IllegalArgumentException> { send(mailbox = "not-an-email") }
    }

    // ── Sender allowlist (strict, deny-by-default) ──────────────────────────

    @Test fun `rejects sender not on the allowlist`() {
        plugin.allowedSenders = "noreply@gemeente.nl"
        assertThrows<IllegalArgumentException> { send(mailbox = "ceo@gemeente.nl") }
    }

    @Test fun `accepts sender matching a full address entry`() {
        plugin.allowedSenders = "noreply@gemeente.nl, zaken@gemeente.nl"
        send(mailbox = "zaken@gemeente.nl")
        verifySend()
    }

    @Test fun `allowlist matching is case-insensitive`() {
        plugin.allowedSenders = "NoReply@Gemeente.NL"
        send(mailbox = "noreply@gemeente.nl")
        verifySend()
    }

    @Test fun `accepts sender matching a domain entry`() {
        plugin.allowedSenders = "@gemeente.nl"
        send(mailbox = "willekeurig@gemeente.nl")
        verifySend()
    }

    @Test fun `domain entry does not match subdomains`() {
        plugin.allowedSenders = "@gemeente.nl"
        assertThrows<IllegalArgumentException> { send(mailbox = "user@sub.gemeente.nl") }
    }

    @Test fun `rejects send when allowedSenders is blank`() {
        plugin.allowedSenders = "   "
        assertThrows<IllegalStateException> { send() }
    }

    @Test fun `rejects send when allowedSenders is not configured`() {
        // Simulates a pre-allowlist plugin configuration where Valtimo never injected the property.
        val legacyPlugin =
            GraphMailPlugin(
                mailClient,
                storage,
                eventPublisher,
                login = login,
            ).apply {
                authenticationPluginConfiguration = LOGIN_ID
            }
        assertThrows<IllegalStateException> {
            legacyPlugin.sendEmail(
                execution,
                "afzender@test.nl",
                "ontvanger@test.nl",
                null,
                null,
                null,
                "Test",
                VALID_CONTENT_UUID,
                null,
            )
        }
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
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assert(!captor.firstValue.bodyHtml.contains("<script", ignoreCase = true))
    }

    @Test fun `strips iframe and object tags`() {
        mockBodyHtml("""<p>ok</p><iframe src="evil"></iframe><object data="x"></object>""")
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assert(!captor.firstValue.bodyHtml.contains("<iframe", ignoreCase = true))
        assert(!captor.firstValue.bodyHtml.contains("<object", ignoreCase = true))
    }

    @Test fun `neutralises javascript protocol in href`() {
        mockBodyHtml("""<p><a href="javascript:alert(1)">click</a></p>""")
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assert(!captor.firstValue.bodyHtml.contains("javascript:", ignoreCase = true))
    }

    @Test fun `strips inline event handlers without leading whitespace`() {
        mockBodyHtml("""<p><img src="x"onerror="alert(1)" /></p>""")
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assert(!captor.firstValue.bodyHtml.contains("onerror", ignoreCase = true))
    }

    @Test fun `preserves legitimate inline style attributes`() {
        mockBodyHtml("""<p style="color:red">text</p>""")
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assert(captor.firstValue.bodyHtml.contains("style=", ignoreCase = true))
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
        val captor = argumentCaptor<OutboundMail>()
        send(to = "a@t.nl,b@t.nl,c@t.nl")
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(3, captor.firstValue.toRecipients.size)
        assertEquals(
            "a@t.nl",
            captor.firstValue.toRecipients[0]
                .emailAddress.address,
        )
    }

    @Test fun `ignores blank entries in recipient list`() {
        val captor = argumentCaptor<OutboundMail>()
        send(to = "a@t.nl,  ,b@t.nl")
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(2, captor.firstValue.toRecipients.size)
    }

    @Test fun `accepts JSON array string for recipients`() {
        val captor = argumentCaptor<OutboundMail>()
        send(to = """["a@t.nl","b@t.nl"]""")
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(2, captor.firstValue.toRecipients.size)
        assertEquals(
            "a@t.nl",
            captor.firstValue.toRecipients[0]
                .emailAddress.address,
        )
    }

    // ── Attachments ──────────────────────────────────────────────────────────────

    @Test fun `resolves attachments from storage`() {
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf(
                MetadataType.FILE_NAME.key to "doc.pdf",
                MetadataType.CONTENT_TYPE.key to "application/pdf",
            ),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<OutboundMail>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(1, captor.firstValue.attachments.size)
        assertEquals("doc.pdf", captor.firstValue.attachments[0].name)
    }

    @Test fun `a successful send is counted as ok`() {
        val registry = SimpleMeterRegistry()
        pluginWithMetrics(registry).sendEmail(
            execution,
            "afzender@test.nl",
            "jan@test.nl",
            null,
            null,
            null,
            "Onderwerp",
            VALID_CONTENT_UUID,
            null,
        )

        assertEquals(
            1.0,
            registry
                .find("graph.mail.sends")
                .tag("outcome", "ok")
                .counter()
                ?.count(),
        )
    }

    @Test fun `a failed send is counted under the same verdict the audit log uses`() {
        // The verdict is what tells an administrator whether to wait or to go change something.
        // A dashboard that invents its own words for that is a second taxonomy to keep in sync.
        val registry = SimpleMeterRegistry()
        whenever(mailClient.sendMail(any(), any()))
            .thenThrow(GraphMailPermanentException("Graph said no"))

        assertThrows<GraphMailPermanentException> {
            pluginWithMetrics(registry).sendEmail(
                execution,
                "afzender@test.nl",
                "jan@test.nl",
                null,
                null,
                null,
                "Onderwerp",
                VALID_CONTENT_UUID,
                null,
            )
        }

        assertEquals(
            1.0,
            registry
                .find("graph.mail.sends")
                .tag("outcome", "PERMANENT_REMOTE")
                .counter()
                ?.count(),
            "expected a PERMANENT_REMOTE counter, got ${registry.meters.map { it.id }}",
        )
    }

    @Test fun `a certificate failure, also while logging in, is counted under its own verdict`() {
        val registry = SimpleMeterRegistry()
        whenever(login.resolve(LOGIN_ID)).thenThrow(GraphMailCertificateException("could not be loaded"))

        assertThrows<GraphMailCertificateException> {
            pluginWithMetrics(registry).sendEmail(
                execution,
                "afzender@test.nl",
                "jan@test.nl",
                null,
                null,
                null,
                "Onderwerp",
                VALID_CONTENT_UUID,
                null,
            )
        }

        assertEquals(
            1.0,
            registry
                .find("graph.mail.sends")
                .tag("outcome", "CERTIFICATES")
                .counter()
                ?.count(),
            "got ${registry.meters.map { it.id }}",
        )
    }

    private fun pluginWithMetrics(registry: SimpleMeterRegistry) =
        GraphMailPlugin(
            mailClient,
            storage,
            eventPublisher,
            SendIdempotencyGuard(),
            AttachmentConcurrencyLimiter(),
            GraphMailMetrics(registry),
            login,
        ).apply {
            authenticationPluginConfiguration = LOGIN_ID
            allowedSenders = "@test.nl"
        }

    @Test fun `a file name with CRLF is rejected like every other header-bearing field`() {
        // Resource metadata is externally influenced — an uploaded file names itself — and this
        // name reaches both the Graph payload and the audit log. Every other such string in this
        // plugin already goes through requireNoControlChars.
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "invoice\r\nBcc: attacker@evil.nl.pdf", "contentType" to "application/pdf"),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        assertThrows<IllegalArgumentException> { send(attachments = VALID_UUID) }
    }

    @Test fun `an over-long file name is trimmed but keeps its extension`() {
        // Graph rejects a name over 255 characters, but there the failure arrives as an opaque 400
        // halfway through a send. Losing the extension would leave the recipient an unopenable
        // blob, so the trim keeps it.
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "a".repeat(400) + ".pdf", "contentType" to "application/pdf"),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<OutboundMail>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(any(), captor.capture())

        val name = captor.firstValue.attachments[0].name
        assertTrue(name.length <= 255, "name is still ${name.length} characters")
        assertTrue(name.endsWith(".pdf"), "the extension was lost: $name")
    }

    @Test fun `an unusable content type falls back instead of failing the send`() {
        // The content type is metadata about the file, not the file. Refusing to send over it
        // would fail a message whose content is perfectly fine.
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "doc.pdf", "contentType" to "not a content type at all"),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<OutboundMail>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(any(), captor.capture())

        assertEquals("application/octet-stream", captor.firstValue.attachments[0].contentType)
    }

    @Test fun `a content type with parameters is left alone`() {
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "doc.csv", "contentType" to "text/csv; charset=utf-8"),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<OutboundMail>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(any(), captor.capture())

        assertEquals("text/csv; charset=utf-8", captor.firstValue.attachments[0].contentType)
    }

    // Regression: Valtimo stores the name under "filename", not "fileName".
    @Test fun `uses Valtimo filename metadata key, not camelCase fileName`() {
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "brief.pdf", "contentType" to "application/pdf"),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream("data".toByteArray()))

        val captor = argumentCaptor<OutboundMail>()
        send(attachments = VALID_UUID)
        verify(mailClient).sendMail(any(), captor.capture())

        assertEquals("brief.pdf", captor.firstValue.attachments[0].name)
    }

    @Test fun `filename with extension is used verbatim`() {
        assertEquals(
            "doc.pdf",
            resolveAttachmentFileName(
                mapOf(
                    MetadataType.FILE_NAME.key to "doc.pdf",
                    MetadataType.CONTENT_TYPE.key to "application/pdf",
                ),
            ),
        )
    }

    @Test fun `extension is derived from content type when filename has none`() {
        assertEquals(
            "doc.pdf",
            resolveAttachmentFileName(
                mapOf(
                    MetadataType.FILE_NAME.key to "doc",
                    MetadataType.CONTENT_TYPE.key to "application/pdf; charset=utf-8",
                ),
            ),
        )
    }

    @Test fun `structured content type subtype is not used as an extension`() {
        val docx =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(
            "verslag",
            resolveAttachmentFileName(
                mapOf(
                    MetadataType.FILE_NAME.key to "verslag",
                    MetadataType.CONTENT_TYPE.key to docx,
                ),
            ),
        )
    }

    @Test fun `falls back to attachment plus extension when filename is missing`() {
        assertEquals(
            "attachment.png",
            resolveAttachmentFileName(mapOf(MetadataType.CONTENT_TYPE.key to "image/png")),
        )
    }

    @Test fun `falls back to attachment when no metadata is present`() {
        assertEquals("attachment", resolveAttachmentFileName(emptyMap()))
    }

    @Test fun `blank filename metadata falls back`() {
        assertEquals(
            "attachment.pdf",
            resolveAttachmentFileName(
                mapOf(
                    MetadataType.FILE_NAME.key to "   ",
                    MetadataType.CONTENT_TYPE.key to "application/pdf",
                ),
            ),
        )
    }

    @Test fun `empty attachments when ids null`() {
        val captor = argumentCaptor<OutboundMail>()
        send()
        verify(mailClient).sendMail(any(), captor.capture())
        assertEquals(0, captor.firstValue.attachments.size)
    }

    @Test fun `accepts attachment up to 25 MB`() {
        val bigBytes = ByteArray(MAX_SINGLE_ATTACHMENT_BYTES.toInt())
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf(
                MetadataType.FILE_NAME.key to "large.bin",
                MetadataType.CONTENT_TYPE.key to "application/octet-stream",
            ),
        )
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
            mapOf(
                MetadataType.FILE_NAME.key to "big.bin",
                MetadataType.CONTENT_TYPE.key to "application/octet-stream",
            ),
        )
        whenever(storage.getResourceContentAsInputStream(VALID_UUID))
            .thenReturn(ByteArrayInputStream(oversized))
        assertThrows<IllegalArgumentException> { send(attachments = VALID_UUID) }
    }

    @Test fun `no attachment bytes are read when no send slot is available`() {
        // The whole point of the cap is peak heap. Resolving attachments first and only then
        // queueing for a permit would have every thread allocate its full payload before waiting,
        // so the limit would bound concurrency without bounding memory — the thing it exists for.
        val limiter = AttachmentConcurrencyLimiter(permits = 1, acquireTimeoutMs = 100)
        val gatedPlugin =
            GraphMailPlugin(
                mailClient,
                storage,
                eventPublisher,
                SendIdempotencyGuard(),
                limiter,
                login = login,
            ).apply {
                authenticationPluginConfiguration = LOGIN_ID
                allowedSenders = "@test.nl"
            }
        whenever(storage.getResourceMetadata(VALID_UUID)).thenReturn(
            mapOf("filename" to "a.bin", "contentType" to "application/octet-stream"),
        )

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hog =
            Thread {
                limiter.withPermit(hasAttachments = true) {
                    holding.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        hog.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS), "permit holder did not start")

        assertThrows<GraphMailRetryableException> {
            gatedPlugin.sendEmail(
                execution,
                "afzender@test.nl",
                "ontvanger@test.nl",
                null,
                null,
                null,
                "Test",
                VALID_CONTENT_UUID,
                VALID_UUID,
            )
        }

        // Never touched the attachment content — no payload was allocated while queueing.
        verify(storage, times(0)).getResourceContentAsInputStream(VALID_UUID)
        verify(mailClient, times(0)).sendMail(any(), any())

        release.countDown()
        hog.join(5_000)
    }

    // ── Error propagation ────────────────────────────────────────────────────────

    @Test fun `propagates GraphMailException from mailClient`() {
        whenever(mailClient.sendMail(any(), any())).doThrow(GraphMailException("error"))
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
        whenever(mailClient.sendMail(any(), any())).doThrow(GraphMailException("send failed"))
        whenever(eventPublisher.publishEvent(any<GraphMailEmailFailedEvent>()))
            .doThrow(RuntimeException("listener failed"))
        val ex = assertThrows<GraphMailException> { send() }
        assert(ex.message == "send failed")
    }

    // ── Idempotency (retry after transaction rollback) ─────────────────────────────

    @Test fun `retrying after a rolled-back successful send does not send twice`() {
        // sendEmail succeeds, the surrounding Operaton transaction then rolls back for an unrelated
        // reason, and the job executor re-runs the activity. The rollback takes the pass-counter
        // increment with it, so the retry produces the same key.
        send()
        rollBackTransaction()
        send()

        verify(mailClient, times(1)).sendMail(any(), any())
    }

    @Test fun `retrying after a rolled-back successful send does not publish a second event`() {
        // A skipped duplicate is not a new send — publishing GraphMailEmailSentEvent again would
        // mislead any listener that counts emails sent.
        send()
        rollBackTransaction()
        send()

        verify(eventPublisher, times(1)).publishEvent(any<GraphMailEmailSentEvent>())
    }

    @Test fun `two callers landing on the same key cannot both send`() {
        // Covers the rare race the cheap alreadySent() pre-check can miss: two callers reach
        // ifNotAlreadySent for the same key at (almost) the same time. The per-key lock inside
        // SendIdempotencyGuard must serialise them so only one actually calls mailClient.sendMail.
        //
        // The counter is pinned so both callers derive the same key. Letting them each read and
        // advance it would hand them different keys and test nothing: Operaton never runs one
        // execution on two threads at once, so concurrent key derivation is not a real scenario —
        // what is real is two attempts that resolve to the same key reaching the guard together.
        whenever(execution.getVariable(any())).thenReturn(0)

        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        whenever(mailClient.sendMail(any(), any())).thenAnswer {
            startedLatch.countDown()
            releaseLatch.await()
        }

        // Bounded (not just "wait forever"): a hung wait here would otherwise stall CI instead
        // of failing this test. Any exception thrown on a caller's thread is captured rather than
        // silently swallowed by the thread's default handler — a caller failing unexpectedly
        // (e.g. a lock left in a broken state) must fail this test, not disappear.
        val firstError = AtomicReference<Throwable?>()
        val secondError = AtomicReference<Throwable?>()
        val firstCallerThread = Thread { runCatching { send() }.onFailure { firstError.set(it) } }
        firstCallerThread.start()
        assertTrue(
            startedLatch.await(5, TimeUnit.SECONDS),
            "first caller never reached sendMail — it should have entered within 5s",
        )

        // Second caller races in while the first is still in flight (before markSent runs) — on
        // its own thread, since it legitimately blocks on the per-key lock until the first
        // caller either fails or marks the key sent.
        val secondCallerThread = Thread { runCatching { send() }.onFailure { secondError.set(it) } }
        secondCallerThread.start()

        releaseLatch.countDown()
        firstCallerThread.join(5_000)
        secondCallerThread.join(5_000)
        assertTrue(!firstCallerThread.isAlive, "first caller thread did not finish within 5s")
        assertTrue(!secondCallerThread.isAlive, "second caller thread did not finish within 5s")
        assertEquals(null, firstError.get(), "first caller threw unexpectedly")
        assertEquals(null, secondError.get(), "second caller threw unexpectedly")

        verify(mailClient, times(1)).sendMail(any(), any())
    }

    // ── HTML sanitisation of inline style values ─────────────────────────────

    @Test fun `an inline style url is stripped but the rest of the styling survives`() {
        // <style> blocks are excluded precisely because CSS url() fetches an external resource — a
        // tracking pixel in GDPR terms. Allowing the style *attribute* let the same thing straight
        // back in, since jsoup's Safelist filters attribute names and not their values.
        mockBodyHtml("""<div style="color:#333;background:url(https://tracker.example/p.png)">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        val body = captor.firstValue.bodyHtml
        assertFalse(body.contains("tracker.example"), "external url() survived sanitisation: $body")
        // The whole style attribute goes, not just the offending declaration: splitting a value on
        // ';' to keep the "clean" part means trusting a parse that the evasion has already beaten.
        assertFalse(body.contains("style="), "the hostile style attribute should be dropped: $body")
    }

    @Test fun `an inline style data uri background is stripped`() {
        mockBodyHtml("""<div style="background:url(data:image/svg+xml;base64,AAAA)">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        assertFalse(captor.firstValue.bodyHtml.contains("data:image"))
    }

    @Test fun `a hex-escaped url in an inline style is stripped`() {
        // CSS lets url() be spelled with escapes, so a literal-text filter misses it while the
        // renderer still fetches the resource.
        mockBodyHtml("""<div style="color:red;background:\75 rl(https://tracker.example/p.gif)">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        assertFalse(captor.firstValue.bodyHtml.contains("tracker.example"))
    }

    @Test fun `a comment-spliced url in an inline style is stripped`() {
        mockBodyHtml("""<div style="background:u/**/rl(https://tracker.example/p.gif)">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        assertFalse(captor.firstValue.bodyHtml.contains("tracker.example"))
    }

    @Test fun `a clean inline style is left completely alone`() {
        mockBodyHtml("""<div style="color:#333;font-weight:bold">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        val body = captor.firstValue.bodyHtml
        assertTrue(body.contains("color:#333"), "clean styling must survive: $body")
        assertTrue(body.contains("font-weight:bold"), "clean styling must survive: $body")
    }

    @Test fun `an inline style import is stripped`() {
        mockBodyHtml("""<div style="@import url(https://evil.example/x.css);color:red">Hi</div>""")
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        val body = captor.firstValue.bodyHtml
        assertFalse(body.contains("evil.example"))
        assertFalse(body.contains("style="), "the hostile style attribute should be dropped: $body")
    }

    @Test fun `a second loop iteration over the same activity is not treated as a duplicate`() {
        // A committed pass advances the counter, so the next pass over the same task gets a new key.
        // ActivityInstanceIdContractTest verifies against a real engine that this is what the
        // counter actually does.
        send()

        send()

        verify(mailClient, times(2)).sendMail(any(), any())
    }

    @Test fun `a retry after a rolled-back attempt is suppressed`() {
        // The guard's whole reason for existing. A rollback takes the counter increment with it, so
        // the retry reads the same number and produces the same key.
        send()
        val counterAfterFirstSend = processVariables["${PASS_COUNTER_VARIABLE_PREFIX}send-email-task"]

        // Simulate the transaction rolling back: the increment is undone.
        processVariables["${PASS_COUNTER_VARIABLE_PREFIX}send-email-task"] =
            (counterAfterFirstSend as Int) - 1
        send()

        verify(mailClient, times(1)).sendMail(any(), any())
    }

    @Test fun `two send-email tasks in one process count independently`() {
        // The counter is per activity, so a second task must not inherit the first task's number.
        send()

        whenever(execution.currentActivityId).thenReturn("send-email-task-2")
        send()

        verify(mailClient, times(2)).sendMail(any(), any())
        assertEquals(1, processVariables["${PASS_COUNTER_VARIABLE_PREFIX}send-email-task"])
        assertEquals(1, processVariables["${PASS_COUNTER_VARIABLE_PREFIX}send-email-task-2"])
    }

    @Test fun `the counter is namespaced so it cannot collide with process data`() {
        send()

        assertTrue(
            processVariables.keys.all { it.startsWith(PASS_COUNTER_VARIABLE_PREFIX) },
            "the plugin wrote an unexpected process variable: ${processVariables.keys}",
        )
    }

    @Test fun `a failed send is not marked as sent, so a genuine retry after failure still sends`() {
        var callCount = 0
        whenever(mailClient.sendMail(any(), any())).thenAnswer {
            callCount++
            if (callCount == 1) throw GraphMailException("transient failure")
        }
        assertThrows<GraphMailException> { send() }
        send()
        verify(mailClient, times(2)).sendMail(any(), any())
    }

    // ── img src protocols ──────────────────────────────────────────────────

    @Test fun `an http image source is stripped while https and cid survive`() {
        // The CSS rules above block url() because it fetches an external resource on open. A plain
        // <img> is the same fetch by a shorter route, so http — the one variant that is both
        // trackable and unprotected in transit — goes too. https and cid: stay: a self-hosted logo
        // and an embedded attachment are what <img> is legitimately for in a transactional email.
        mockBodyHtml(
            """
            <p>x</p>
            <img src="http://tracker.example/p.gif">
            <img src="https://eigen-domein.nl/logo.png">
            <img src="cid:logo123">
            """.trimIndent(),
        )
        val captor = argumentCaptor<OutboundMail>()

        send()

        verify(mailClient).sendMail(any(), captor.capture())
        val body = captor.firstValue.bodyHtml
        assertFalse(body.contains("tracker.example"), "http image survived sanitisation: $body")
        assertTrue(body.contains("https://eigen-domein.nl/logo.png"), "https image was dropped: $body")
        assertTrue(body.contains("cid:logo123"), "cid image was dropped: $body")
    }
}
