package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.SSLContext

private const val STORE_PASSWORD = "changeit"
private const val SOURCE =
    "Http Client Authentication configuration 'Graph login' (6f0c4a8e-0000-0000-0000-000000000001)"
private const val NGINX_NO_CERTIFICATE =
    "<html><head><title>400 No required SSL certificate was sent</title></head><body></body></html>"

/**
 * Graph Mail with a client certificate (mutual TLS), against WireMock over HTTPS: the client is
 * built exactly like GraphMailAutoConfiguration builds it, so what is tested is the client that
 * ships — the JDK client's TLS behaviour is the point here.
 */
class GraphMailClientMtlsTest {
    @TempDir
    lateinit var tempDir: Path

    private val servers = mutableListOf<WireMockServer>()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val mailbox = "noreply@test.nl"

    @AfterEach
    fun tearDown() = servers.forEach { it.stop() }

    // A gateway that requires a client certificate signed by ca.pem.
    private fun gateway(): WireMockServer = httpsServer(requireClientCertificate = true)

    // An https server that does not ask for a client certificate.
    private fun plainTlsServer(): WireMockServer = httpsServer(requireClientCertificate = false)

    private fun httpsServer(requireClientCertificate: Boolean): WireMockServer {
        val keystore = store("server.p12") { keyStore("server", "server.pem", "server-key.pem") }
        var config: WireMockConfiguration =
            wireMockConfig()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystore.toString())
                .keystorePassword(STORE_PASSWORD)
                .keyManagerPassword(STORE_PASSWORD)
                .keystoreType("PKCS12")
        if (requireClientCertificate) {
            val truststore = store("trust.p12") { trustStore("ca.pem") }
            config =
                config
                    .needClientAuth(true)
                    .trustStorePath(truststore.toString())
                    .trustStorePassword(STORE_PASSWORD)
                    .trustStoreType("PKCS12")
        }
        return WireMockServer(config).also {
            it.start()
            servers += it
        }
    }

    private fun store(
        name: String,
        build: () -> KeyStore,
    ): Path {
        val path = tempDir.resolve(name)
        Files.newOutputStream(path).use { build().store(it, STORE_PASSWORD.toCharArray()) }
        return path
    }

    private fun pem(
        certificate: String,
        key: String? = null,
    ): PemSslStoreDetails =
        PemSslStoreDetails.forCertificate("classpath:mtls/$certificate").let {
            if (key == null) it else it.withPrivateKey("classpath:mtls/$key")
        }

    private fun keyStore(
        alias: String,
        certificate: String,
        key: String,
    ): KeyStore = PemSslStoreBundle(pem(certificate, key).withAlias(alias).withPassword(STORE_PASSWORD), null).keyStore

    private fun trustStore(certificate: String): KeyStore = PemSslStoreBundle(null, pem(certificate)).trustStore

    // What HttpClientAuthenticator.sslContext() hands Graph Mail: a client certificate (or none), and
    // the server certificates to trust.
    private fun sslContext(
        clientCertificate: String? = "client.pem",
        clientKey: String = "client-key.pem",
        trusted: String = "ca.pem",
    ): SSLContext =
        SslBundle
            .of(PemSslStoreBundle(clientCertificate?.let { pem(it, clientKey) }, pem(trusted)))
            .createSslContext()

    private fun properties(graphServer: WireMockServer) =
        GraphMailHttpProperties(graphBaseUrl = graphServer.baseUrl(), allowNonMicrosoftEndpoints = true)

    // The login of an Http Client Authentication configuration with these certificates; its token request is that
    // plugin's own (tested there, and classified by GraphMailLogin).
    private fun login(sslContext: SSLContext?) = GraphMailAuthentication(SOURCE, sslContext) { "Bearer token-1" }

    // Sends as the plugin does with the client GraphMailAutoConfiguration builds.
    private fun send(
        properties: GraphMailHttpProperties,
        mail: OutboundMail,
        sslContext: SSLContext? = sslContext(),
    ) {
        val autoConfiguration = GraphMailAutoConfiguration()
        autoConfiguration
            .graphMailClient(
                autoConfiguration.graphMailRestClient(objectMapper, properties),
                properties,
                autoConfiguration.graphMailCertificateRestClients(objectMapper, properties),
            ).sendMail(login(sslContext), mail)
    }

    private fun nginxRejection(): ResponseDefinitionBuilder =
        aResponse().withStatus(400).withHeader("Content-Type", "text/html").withBody(NGINX_NO_CERTIFICATE)

    private fun mail(attachments: List<ResolvedAttachment> = emptyList()) =
        OutboundMail(
            senderMailbox = mailbox,
            toRecipients = listOf(GraphRecipient(GraphEmailAddress(address = "jan@test.nl"))),
            subject = "Test",
            bodyHtml = "<p>Test</p>",
            attachments = attachments,
        )

    private fun largeAttachment() =
        ResolvedAttachment("large.bin", "application/octet-stream", ByteArray(UPLOAD_SESSION_MIN_BYTES.toInt()))

    private fun stubDraftFlow(server: WireMockServer) {
        server.stubFor(post(urlPathMatching(".*/messages$")).willReturn(okJson("""{"id":"draft-1"}""")))
        server.stubFor(
            post(urlPathMatching(".*/attachments/createUploadSession$"))
                .willReturn(okJson("""{"uploadUrl":"${server.baseUrl()}/upload/session-1"}""")),
        )
        server.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        server.stubFor(post(urlPathMatching(".*/messages/.*/send$")).willReturn(aResponse().withStatus(202)))
    }

    private fun assertNamesSource(ex: GraphMailCertificateException) {
        assertTrue(ex.message!!.contains(SOURCE), ex.message)
        assertTrue(ex.message!!.contains("did not reach Microsoft"), ex.message)
    }

    // ── With the right certificate ────────────────────────────────────────

    @Test fun `the inline send goes through a gateway that requires the client certificate`() {
        val gateway = gateway()
        gateway.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(aResponse().withStatus(202)))

        send(properties(gateway), mail())

        gateway.verify(
            1,
            postRequestedFor(urlPathMatching(".*/sendMail")).withHeader("Authorization", equalTo("Bearer token-1")),
        )
    }

    @Test fun `the draft and upload flow goes through the gateway with the client certificate`() {
        val gateway = gateway()
        stubDraftFlow(gateway)

        send(properties(gateway), mail(listOf(largeAttachment())))

        gateway.verify(1, postRequestedFor(urlPathMatching(".*/attachments/createUploadSession$")))
        gateway.verify(1, putRequestedFor(anyUrl()))
        gateway.verify(1, postRequestedFor(urlPathMatching(".*/messages/.*/send$")))
    }

    // ── Rejected in TLS ───────────────────────────────────────────────────

    // A gateway that rejects in TLS sends an alert, but may close before the client has read it; the
    // client then sees a reset, which is a network failure and retried as one. Over a few sends the
    // alert gets through. Never allowed: an unknown outcome ("may have been sent", which would fail
    // the test as an unexpected exception), or a failure that doesn't name the certificates.
    private fun rejectedInTls(send: () -> Unit): GraphMailCertificateException {
        repeat(5) {
            try {
                send()
                fail<Unit>("the send succeeded")
            } catch (ex: GraphMailCertificateException) {
                return ex
            } catch (ex: GraphMailRetryableException) {
                assertTrue(ex.message!!.contains(SOURCE), ex.message)
            }
        }
        return fail("the certificate rejection did not get through in 5 sends")
    }

    @Test fun `a client certificate of another CA is rejected in TLS, naming the configuration`() {
        val gateway = gateway()

        val ex =
            rejectedInTls {
                send(properties(gateway), mail(), sslContext("untrusted-client.pem", "untrusted-client-key.pem"))
            }

        assertNamesSource(ex)
    }

    @Test fun `an untrusted gateway certificate is reported against the configuration`() {
        val gateway = gateway()

        val ex =
            assertThrows<GraphMailCertificateException> {
                send(properties(gateway), mail(), sslContext(trusted = "untrusted-client.pem"))
            }

        assertNamesSource(ex)
    }

    // Where an inline send would otherwise report "may have been sent", and the draft flow's retry
    // budget would hand back a retryable failure without the TLS cause.

    @Test fun `a TLS rejection of the inline send is a certificate error, not an unknown outcome`() {
        val gateway = gateway()

        val ex = rejectedInTls { send(properties(gateway), mail(), sslContext(clientCertificate = null)) }

        assertNamesSource(ex)
        assertTrue(ex.message!!.contains("…/sendMail"), ex.message)
    }

    @Test fun `a TLS rejection in the draft flow is a certificate error, not a retry that lost its cause`() {
        val gateway = gateway()

        val ex =
            rejectedInTls {
                send(properties(gateway), mail(listOf(largeAttachment())), sslContext(clientCertificate = null))
            }

        assertNamesSource(ex)
        assertTrue(ex.message!!.contains("…/messages"), ex.message)
    }

    @Test fun `a connection dropped during the handshake is a network failure, retried as before`() {
        // Accepts the connection, then closes it before the handshake completes.
        val server = java.net.ServerSocket(0)
        val accepts =
            Thread {
                runCatching {
                    while (true) {
                        server.accept().use { it.getInputStream().read(ByteArray(64)) }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val url = "https://localhost:${server.localPort}"
            val properties = GraphMailHttpProperties(graphBaseUrl = url, allowNonMicrosoftEndpoints = true)

            assertThrows<GraphMailRetryableException> { send(properties, mail()) }
        } finally {
            server.close()
            accepts.join(1000)
        }
    }

    @Test fun `a retry budget that runs out still says what failed`() {
        val lost = java.io.IOException("Remote host terminated the handshake (sent with the certificates of $SOURCE)")

        val ex =
            assertThrows<GraphMailRetryableException> {
                WaitBudget(
                    totalMs = 10,
                ).sleepOrHandBack(100, "Retrying", lost)
            }

        assertSame(lost, ex.cause)
        assertTrue(ex.message!!.contains(SOURCE), ex.message)
    }

    @Test fun `any other IO failure with certificates keeps its cause and says where to look`() {
        val interceptor = CertificateRejectionInterceptor(SOURCE, "https://graph.example")
        val request =
            org.springframework.http.client
                .SimpleClientHttpRequestFactory()
                .createRequest(
                    java.net.URI.create("https://graph.example/v1.0/users/x/sendMail"),
                    org.springframework.http.HttpMethod.POST,
                )
        val lost = java.io.IOException("HTTP/1.1 header parser received no bytes")

        val ex =
            assertThrows<java.io.IOException> {
                interceptor.intercept(request, ByteArray(0)) { _, _ -> throw lost }
            }

        assertSame(lost, ex.cause)
        assertTrue(ex.message!!.contains(SOURCE), ex.message)
    }

    // ── Rejected at HTTP level, as nginx does ────────────────────────────

    @Test fun `a gateway 400 on the inline send is a certificate error`() {
        val server = plainTlsServer()
        server.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(nginxRejection()))

        val ex = assertThrows<GraphMailCertificateException> { send(properties(server), mail()) }

        assertNamesSource(ex)
        assertTrue(ex.message!!.contains("400 No required SSL certificate was sent"), ex.message)
        assertTrue(ex.message!!.contains("gateway (not Microsoft)"), ex.message)
    }

    @Test fun `the title of a gateway rejection can't break a log line`() {
        val server = plainTlsServer()
        server.stubFor(
            post(urlPathMatching(".*/sendMail")).willReturn(
                aResponse().withStatus(400).withBody("<html><title>400 Bad\r\nINFO forged line</title></html>"),
            ),
        )

        val ex = assertThrows<GraphMailCertificateException> { send(properties(server), mail()) }

        assertTrue(ex.message!!.contains("(\"400 Bad INFO forged line\")"), ex.message)
        assertFalse(ex.message!!.contains("\n"), ex.message)
    }

    @Test fun `a Graph 400 with certificates keeps the Graph message`() {
        val server = plainTlsServer()
        server.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("request-id", "graph-1")
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"error":{"code":"ErrorInvalidRecipients","message":"x"}}"""),
                ),
        )

        val ex = assertThrows<GraphMailPermanentException> { send(properties(server), mail()) }

        assertTrue(ex.message!!.contains("Graph rejected the request payload"), ex.message)
    }

    @Test fun `a gateway 401 on a Graph call is a certificate error`() {
        val server = plainTlsServer()
        server.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(aResponse().withStatus(401)))

        assertThrows<GraphMailCertificateException> { send(properties(server), mail()) }

        server.verify(1, postRequestedFor(urlPathMatching(".*/sendMail")))
    }

    @Test fun `a Microsoft 401 on a Graph call is the refused token, not the certificates`() {
        val server = plainTlsServer()
        server.stubFor(
            post(
                urlPathMatching(".*/sendMail"),
            ).willReturn(aResponse().withStatus(401).withHeader("request-id", "graph-1")),
        )

        val ex = assertThrows<GraphMailPermanentException> { send(properties(server), mail()) }

        assertTrue(ex.message!!.contains("Graph refused the token of $SOURCE"), ex.message)
    }

    @Test fun `a gateway 403 on a Graph call is a certificate error`() {
        val server = plainTlsServer()
        server.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(aResponse().withStatus(403)))

        assertThrows<GraphMailCertificateException> { send(properties(server), mail()) }
    }

    @Test fun `a Graph error body without Graph's headers is still Graph's`() {
        val server = plainTlsServer()
        server.stubFor(
            post(urlPathMatching(".*/sendMail")).willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"error":{"code":"ErrorInvalidRecipients","message":"x"}}"""),
            ),
        )

        assertThrows<GraphMailPermanentException> { send(properties(server), mail()) }
    }

    @Test fun `a TLS failure caused by a certificate verdict is a rejection, whatever its message`() {
        val verdict =
            javax.net.ssl.SSLHandshakeException("neutral").apply {
                initCause(java.security.cert.CertificateException())
            }

        assertSame(verdict, certificateRejectionIn(java.io.IOException("wrapped", verdict)))
    }

    @Test fun `a gateway 400 on a chunk upload is a certificate error`() {
        val server = plainTlsServer()
        stubDraftFlow(server)
        server.stubFor(put(anyUrl()).willReturn(nginxRejection()))

        val ex =
            assertThrows<GraphMailCertificateException> {
                send(properties(server), mail(listOf(largeAttachment())))
            }

        assertTrue(ex.message!!.contains("attachment upload"), ex.message)
    }

    // ── Without certificates ─────────────────────────────────────────────

    @Test fun `without certificates the shared client sends, and a gateway-style 400 is Graph's`() {
        val server = plainTlsServer()
        server.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(nginxRejection()))
        val properties = properties(server)
        // The shared client, with only the test CA added to what it trusts; no certificate client.
        val client =
            GraphMailClientImpl(
                restClient =
                    GraphMailAutoConfiguration().graphRestClient(
                        objectMapper,
                        properties,
                        sslContext(clientCertificate = null),
                    ),
                graphBaseUrl = properties.graphBaseUrl,
                certificateRestClients = { fail("a send without certificates used a certificate client") },
            )

        val ex = assertThrows<GraphMailPermanentException> { client.sendMail(login(null), mail()) }

        assertTrue(ex.message!!.contains("Graph API rejected email send (400"), ex.message)
    }

    // ── One client per certificate set ───────────────────────────────────

    @Test fun `the same SSLContext reuses its client, another one gets its own`() {
        var created = 0
        val clients =
            GraphMailCertificateRestClients {
                created++
                RestClient.create()
            }
        val first = sslContext()

        assertSame(clients.forSslContext(first), clients.forSslContext(first))
        clients.forSslContext(sslContext())

        assertEquals(2, created)
    }
}
