package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.domain.PluginDefinition
import com.ritense.plugin.service.PluginService
import com.ritense.valtimoplugins.httpclientauthentication.HttpClientAuthenticator
import com.ritense.valtimoplugins.httpclientauthentication.exception.TokenExchangeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.net.ConnectException
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

private const val LOGIN_ID = "6f0c4a8e-0000-0000-0000-000000000001"
private const val SOURCE = "Http Client Authentication configuration 'Graph login' ($LOGIN_ID)"
private const val ENTRA_LOGIN =
    """{
        "authenticationType": "TOKEN_EXCHANGE",
        "tokenEndpoint": "https://login.microsoftonline.com/tenant/oauth2/v2.0/token",
        "clientId": "client",
        "scope": "https://graph.microsoft.com/.default"
    }"""

class GraphMailLoginTest {
    private val pluginService: PluginService = mock()
    private val clientSecretLogin: GraphMailClientSecretLogin = mock()
    private val login = GraphMailLogin(pluginService, clientSecretLogin)
    private val sslContext: SSLContext = SSLContext.getInstance("TLS")
    private val authenticator: HttpClientAuthenticator =
        mock {
            on { sslContext() } doReturn sslContext
            on { authenticationHeaders() } doReturn HttpHeaders().apply { setBearerAuth("token-1") }
        }
    private val configuration: PluginConfiguration = mock()

    @BeforeEach
    fun setUp() {
        stored(ENTRA_LOGIN)
        whenever(configuration.id).thenReturn(PluginConfigurationId.existingId(UUID.fromString(LOGIN_ID)))
        whenever(configuration.title).thenReturn("Graph login")
        whenever(pluginService.findPluginConfiguration(eq(configuration.id))).thenReturn(configuration)
        whenever(pluginService.createInstance(configuration)).thenReturn(authenticator)
    }

    private fun stored(
        properties: String,
        plugin: String = "http-client-authentication",
    ) {
        val definition: PluginDefinition = mock { on { key } doReturn plugin }
        whenever(configuration.pluginDefinition).thenReturn(definition)
        whenever(configuration.properties).thenReturn(ObjectMapper().readTree(properties) as ObjectNode)
    }

    private fun withProperty(
        name: String,
        value: String,
    ) = ENTRA_LOGIN.replace("\"clientId\"", "\"$name\": \"$value\", \"clientId\"")

    private fun refusal(
        reference: String? = LOGIN_ID,
        credentials: GraphCredentials? = null,
    ): String = assertThrows<IllegalStateException> { login.validate(reference, credentials) }.message!!

    // ── The reference ───────────────────────────────────────────────────

    @Test fun `the login of an Http Client Authentication configuration is its headers and certificates`() {
        val authentication = login.resolve(LOGIN_ID)

        assertEquals(SOURCE, authentication.source)
        assertSame(sslContext, authentication.sslContext)
        assertEquals("Bearer token-1", authentication.headers().getFirst(HttpHeaders.AUTHORIZATION))
    }

    @Test fun `without certificates the login has none`() {
        whenever(authenticator.sslContext()).thenReturn(null)

        assertNull(login.resolve(LOGIN_ID).sslContext)
    }

    @Test fun `no reference and no client secret is no login, naming both`() {
        listOf(null, "", "  ").forEach { reference ->
            val message = refusal(reference)
            assertTrue(message.contains("has no login"), message)
            assertTrue(message.contains(AUTHENTICATION_PLUGIN_CONFIGURATION_PROPERTY), message)
            assertTrue(message.contains("tenantId, clientId and clientSecret (deprecated)"), message)
        }
    }

    // ── The deprecated client-secret login of 1.x ───────────────────────

    private val credentials = GraphCredentials("tenant", "client", "secret")

    @Test fun `without a reference the client secret of the configuration is checked`() {
        listOf(null, "", "  ").forEach { reference -> login.validate(reference, credentials) }

        verify(clientSecretLogin, times(3)).validate(credentials)
        verify(pluginService, never()).findPluginConfiguration(any<PluginConfigurationId>())
    }

    @Test fun `without a reference a send logs in with the client secret of the configuration`() {
        val clientSecretAuthentication = GraphMailAuthentication("the client secret", null) { HttpHeaders().apply { setBearerAuth("legacy") } }
        whenever(clientSecretLogin.resolve(credentials)).thenReturn(clientSecretAuthentication)

        assertSame(clientSecretAuthentication, login.resolve(null, credentials))
        verify(pluginService, never()).createInstance(any<PluginConfiguration>())
    }

    @Test fun `an incomplete client secret is refused like the client secret login refuses it`() {
        whenever(
            clientSecretLogin.validate(credentials),
        ).thenThrow(IllegalStateException("'clientSecret' is not configured"))

        assertEquals("'clientSecret' is not configured", refusal(null, credentials))
    }

    @Test fun `with a reference the client secret is ignored`() {
        login.validate(LOGIN_ID, credentials)
        val authentication = login.resolve(LOGIN_ID, credentials)

        assertEquals(SOURCE, authentication.source)
        verify(clientSecretLogin, never()).validate(any())
        verify(clientSecretLogin, never()).resolve(any())
    }

    // ── Http Client Authentication is compileOnly ───────────────────────

    @Test fun `a reference without the Http Client Authentication plugin says to add it`() {
        val withoutPlugin = GraphMailLogin(pluginService, clientSecretLogin, "the plugin is not installed")

        val message = assertThrows<IllegalStateException> { withoutPlugin.validate(LOGIN_ID) }.message!!

        assertTrue(message.contains("is filled in, but the plugin is not installed"), message)
        verify(pluginService, never()).findPluginConfiguration(any<PluginConfigurationId>())
    }

    @Test fun `the client secret login works without the Http Client Authentication plugin`() {
        val withoutPlugin = GraphMailLogin(pluginService, clientSecretLogin, "the plugin is not installed")

        withoutPlugin.validate(null, credentials)

        verify(clientSecretLogin).validate(credentials)
    }

    @Test fun `the plugin is found on the classpath, and named with its version when it is missing`() {
        assertNull(httpClientAuthenticationProblem())

        val withoutPlugin = object : ClassLoader(null) {}
        val message = httpClientAuthenticationProblem(withoutPlugin)!!

        assertTrue(message.contains("not installed"), message)
        assertTrue(message.contains("http-client-authentication $MINIMUM_HTTP_CLIENT_AUTHENTICATION_VERSION"), message)
    }

    @Test fun `a reference that is not an id is refused`() {
        assertTrue(refusal("gateway").contains("'gateway'"))
    }

    @Test fun `a reference to a configuration that does not exist is refused`() {
        val other = "6f0c4a8e-0000-0000-0000-000000000099"

        val message = refusal(other)

        assertTrue(message.contains(other) && message.contains("does not exist"), message)
    }

    @Test fun `a configuration of another plugin is refused, by name`() {
        stored(ENTRA_LOGIN, plugin = "smartdocuments")

        val message = refusal()

        assertTrue(message.contains("'Graph login'") && message.contains("'smartdocuments'"), message)
    }

    @Test fun `every authentication type is accepted`() {
        listOf(
            """{"authenticationType": "NONE"}""",
            """{"authenticationType": "BEARER"}""",
            """{"authenticationType": "HEADER", "authHeaderName": "X-Api-Key"}""",
            """{"authenticationType": "SAML_TOKEN", "tokenEndpoint": "https://sts.example.nl/token"}""",
            withProperty("audience", "graph"),
            ENTRA_LOGIN.replace("https://graph.microsoft.com/.default", ""),
        ).forEach {
            stored(it)

            login.validate(LOGIN_ID)
            login.resolve(LOGIN_ID)
        }
    }

    @Test fun `a send repeats the checks, without creating the instance when they fail`() {
        val noReference = assertThrows<IllegalStateException> { login.resolve(null) }
        stored(ENTRA_LOGIN, plugin = "smartdocuments")
        val otherPlugin = assertThrows<IllegalStateException> { login.resolve(LOGIN_ID) }

        assertTrue(noReference.message!!.contains("has no login"), noReference.message)
        assertTrue(otherPlugin.message!!.contains("'smartdocuments'"), otherPlugin.message)
        verify(pluginService, never()).createInstance(any<PluginConfiguration>())
    }

    @Test fun `the token endpoint may point anywhere`() {
        // Where the secret may go is limited in the network, not here (story decision 4).
        stored(ENTRA_LOGIN.replace("login.microsoftonline.com", "gateway.example.nl"))

        login.validate(LOGIN_ID)
    }

    @Test fun `checking neither creates the instance nor loads the certificates`() {
        login.validate(LOGIN_ID)

        verify(pluginService, never()).createInstance(any<PluginConfiguration>())
    }

    @Test fun `certificates that can't be loaded are a certificate error naming the configuration`() {
        whenever(authenticator.sslContext()).thenThrow(IllegalStateException("No certificates are configured"))

        val ex = assertThrows<GraphMailCertificateException> { login.resolve(LOGIN_ID) }

        assertTrue(ex.message!!.contains(SOURCE) && ex.message!!.contains("No certificates are configured"), ex.message)
    }

    // ── A failing token request ─────────────────────────────────────────
    //
    // As http auth fails it: a TokenExchangeException around the RestClientException of its token request.

    private fun loginFailure(
        cause: Throwable,
        withCertificates: Boolean = true,
    ): GraphMailException {
        if (!withCertificates) whenever(authenticator.sslContext()).thenReturn(null)
        doThrow(
            TokenExchangeException("Could not obtain a token", cause),
        ).whenever(authenticator).authenticationHeaders()
        val headers = login.resolve(LOGIN_ID).headers
        return assertThrows<GraphMailException> { headers() }
    }

    private fun httpError(status: HttpStatus) =
        if (status.is5xxServerError) {
            HttpServerErrorException.create(status, status.reasonPhrase, HttpHeaders(), ByteArray(0), null)
        } else {
            HttpClientErrorException.create(status, status.reasonPhrase, HttpHeaders(), ByteArray(0), null)
        }

    @Test fun `a refused login is permanent and says what to check where`() {
        val ex = loginFailure(httpError(HttpStatus.UNAUTHORIZED))

        assertTrue(ex is GraphMailPermanentException, ex.javaClass.simpleName)
        assertEquals(401, ex.statusCode)
        assertTrue(
            ex.message!!.contains("login.microsoftonline.com refused the login of $SOURCE (HTTP 401)"),
            ex.message,
        )
        assertTrue(ex.message!!.contains("client secret"), ex.message)
        assertTrue(ex.message!!.contains("client certificate and the gateway"), ex.message)
    }

    @Test fun `without certificates a refused login doesn't send anyone to the gateway`() {
        val ex = loginFailure(httpError(HttpStatus.BAD_REQUEST), withCertificates = false)

        assertFalse(ex.message!!.contains("gateway"), ex.message)
    }

    @Test fun `a refused login never repeats the response, which may echo the secret`() {
        val echo =
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders(),
                "client_id=client&client_secret=s3cret".toByteArray(),
                null,
            )

        val ex = loginFailure(echo)

        assertFalse(ex.message!!.contains("s3cret"), ex.message)
    }

    @Test fun `a throttled or failing token endpoint is retryable`() {
        listOf(
            HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.REQUEST_TIMEOUT,
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.SERVICE_UNAVAILABLE,
        ).forEach {
            val ex = loginFailure(httpError(it))

            assertTrue(ex is GraphMailRetryableException, "$it: ${ex.javaClass.simpleName}")
            assertEquals(it.value(), ex.statusCode)
            assertTrue(ex.message!!.contains("certificates of $SOURCE"), ex.message)
        }
    }

    @Test fun `an unreachable token endpoint is retryable, with the certificate hint`() {
        val ex =
            loginFailure(ResourceAccessException("I/O error on POST request", ConnectException("Connection refused")))

        assertTrue(ex is GraphMailRetryableException, ex.javaClass.simpleName)
        assertTrue(ex.message!!.contains("could not be reached"), ex.message)
        assertTrue(ex.message!!.contains("Connection refused"), ex.message)
        assertTrue(ex.message!!.contains("certificates of $SOURCE"), ex.message)
    }

    @Test fun `without certificates an unreachable token endpoint gives no certificate hint`() {
        val ex =
            loginFailure(
                ResourceAccessException("I/O error on POST request", ConnectException("Connection refused")),
                withCertificates = false,
            )

        assertTrue(ex is GraphMailRetryableException, ex.javaClass.simpleName)
        assertFalse(ex.message!!.contains("certificates of"), ex.message)
    }

    @Test fun `the login supplies every header of the configuration, also without Authorization`() {
        val apiKey = HttpHeaders().apply { set("X-Api-Key", "key-1") }
        whenever(authenticator.authenticationHeaders()).thenReturn(apiKey)

        assertEquals(apiKey, login.resolve(LOGIN_ID).headers())
    }

    @Test fun `addresses in a login failure are masked`() {
        val unreachable = loginFailure(ResourceAccessException("I/O error", ConnectException("geheim@gemeente.nl")))
        doThrow(IllegalArgumentException("geheim@gemeente.nl")).whenever(authenticator).authenticationHeaders()
        val headers = login.resolve(LOGIN_ID).headers
        val other = assertThrows<GraphMailPermanentException> { headers() }

        listOf(unreachable, other).forEach {
            assertFalse(it.message!!.contains("geheim@gemeente.nl"), it.message)
            assertTrue(it.message!!.contains("g***@gemeente.nl"), it.message)
        }
    }

    @Test fun `a TLS rejection at the token step is a certificate error`() {
        val tls = SSLHandshakeException("Received fatal alert: bad_certificate")

        val ex = loginFailure(ResourceAccessException("I/O error on POST request", tls))

        assertTrue(ex is GraphMailCertificateException, ex.javaClass.simpleName)
        assertTrue(ex.message!!.contains(SOURCE) && ex.message!!.contains("bad_certificate"), ex.message)
        assertTrue(ex.message!!.contains("did not reach Microsoft"), ex.message)
    }

    @Test fun `without certificates a TLS failure at the token step is not blamed on certificates`() {
        val tls = SSLHandshakeException("PKIX path building failed")

        val ex = loginFailure(ResourceAccessException("I/O error on POST request", tls), withCertificates = false)

        assertTrue(ex is GraphMailRetryableException, ex.javaClass.simpleName)
        assertTrue(ex.message!!.contains("PKIX"), ex.message)
    }

    @Test fun `a connection dropped during the handshake is a network failure, not a certificate error`() {
        val dropped = SSLHandshakeException("Remote host terminated the handshake")

        val ex = loginFailure(ResourceAccessException("I/O error", IOException("wrapped", dropped)))

        assertTrue(ex is GraphMailRetryableException, ex.javaClass.simpleName)
    }

    @Test fun `any other failure is permanent and keeps its message`() {
        whenever(authenticator.authenticationHeaders())
            .thenThrow(
                IllegalArgumentException("Authentication type TOKEN_EXCHANGE requires the properties: clientSecret"),
            )
        val headers = login.resolve(LOGIN_ID).headers

        val ex = assertThrows<GraphMailPermanentException> { headers() }

        assertTrue(ex.message!!.contains("requires the properties: clientSecret"), ex.message)
        assertTrue(ex.message!!.contains(SOURCE), ex.message)
        assertTrue(ex.message!!.contains("client certificate and the gateway"), ex.message)
    }
}
