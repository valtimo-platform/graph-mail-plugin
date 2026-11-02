package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.ritense.plugin.service.PluginService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.web.client.RestClient

private const val TENANT = "00000000-0000-0000-0000-000000000001"
private const val TOKEN_PATH = "/$TENANT/oauth2/v2.0/token"

// The deprecated client-secret login of 1.x: a configuration from 1.x, with only tenantId, clientId and clientSecret,
// must keep sending after an upgrade — also in an application without the Http Client Authentication plugin.
class GraphMailClientSecretLoginTest {
    private lateinit var wireMock: WireMockServer
    private lateinit var restClient: RestClient
    private lateinit var clientSecretLogin: GraphMailClientSecretLogin
    private lateinit var login: GraphMailLogin
    private lateinit var client: GraphMailClientImpl

    private val credentials = GraphCredentials(TENANT, "client-1", "secret-1")

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        val properties =
            GraphMailHttpProperties(
                tokenBaseUrl = wireMock.baseUrl(),
                graphBaseUrl = wireMock.baseUrl(),
                allowNonMicrosoftEndpoints = true,
            )
        // Built as GraphMailAutoConfiguration builds the shared Graph client, which the token request goes over.
        restClient = GraphMailAutoConfiguration().graphRestClient(ObjectMapper().registerKotlinModule(), properties)
        clientSecretLogin =
            GraphMailClientSecretLogin(restClient, properties.tokenBaseUrl, properties.graphBaseUrl, GraphTokenCache())
        val pluginService: PluginService = mock()
        login = GraphMailLogin(pluginService, clientSecretLogin, httpClientAuthenticationProblem = "not installed")
        client =
            GraphMailClientImpl(
                restClient = restClient,
                graphBaseUrl = wireMock.baseUrl(),
                uploadHostSuffixes = null,
                certificateRestClients = { error("no certificates expected") },
            )
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    private fun tokenResponds(vararg tokens: String) {
        tokens.forEachIndexed { index, token ->
            wireMock.stubFor(
                post(urlPathEqualTo(TOKEN_PATH))
                    .inScenario("tokens")
                    .whenScenarioStateIs(if (index == 0) "Started" else "token-$index")
                    .willSetStateTo("token-${index + 1}")
                    .willReturn(okJson("""{"access_token":"$token","token_type":"Bearer","expires_in":3600}""")),
            )
        }
    }

    private fun graphAccepts() =
        wireMock.stubFor(post(urlPathMatching(".*/sendMail")).willReturn(aResponse().withStatus(202)))

    private fun send() =
        client.sendMail(
            login.resolve(null, credentials),
            OutboundMail(
                senderMailbox = "noreply@test.nl",
                toRecipients = listOf(GraphRecipient(GraphEmailAddress(address = "jan@test.nl"))),
                subject = "Test",
                bodyHtml = "<p>Test</p>",
            ),
        )

    @Test fun `a 1x configuration sends with its own client secret, without the Http Client Authentication plugin`() {
        tokenResponds("token-1")
        graphAccepts()

        send()

        wireMock.verify(
            postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=client-1"))
                .withRequestBody(containing("client_secret=secret-1"))
                .withRequestBody(
                    containing(
                        "scope=" + java.net.URLEncoder.encode("${wireMock.baseUrl()}/.default", "UTF-8"),
                    ),
                ),
        )
        wireMock.verify(
            postRequestedFor(urlPathEqualTo("/v1.0/users/noreply@test.nl/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-1")),
        )
    }

    @Test fun `the token is cached across sends`() {
        tokenResponds("token-1")
        graphAccepts()

        send()
        send()

        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)))
    }

    @Test fun `a token Graph refuses is dropped and the send is tried once more with a fresh one, as in 1x`() {
        tokenResponds("token-1", "token-2")
        wireMock.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(aResponse().withStatus(401)),
        )
        wireMock.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-2"))
                .willReturn(aResponse().withStatus(202)),
        )

        send()

        wireMock.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(".*/sendMail")))
    }

    @Test fun `a fresh token Graph refuses too is permanent, and the next send fetches a new one`() {
        tokenResponds("token-1", "token-2", "token-3")
        wireMock.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-3"))
                .willReturn(aResponse().withStatus(202)),
        )
        wireMock.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(aResponse().withStatus(401)),
        )
        wireMock.stubFor(
            post(urlPathMatching(".*/sendMail"))
                .withHeader("Authorization", equalTo("Bearer token-2"))
                .willReturn(aResponse().withStatus(401)),
        )

        assertThrows<GraphMailPermanentException> { send() }
        send()

        wireMock.verify(3, postRequestedFor(urlPathEqualTo(TOKEN_PATH)))
    }

    @Test fun `a refused token request is permanent, names the properties, and never carries the response body`() {
        wireMock.stubFor(
            post(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(401).withBody("""{"error":"invalid_client","echo":"secret-1"}""")),
        )

        val ex = assertThrows<GraphMailPermanentException> { login.resolve(null, credentials).authorization() }

        assertEquals(401, ex.statusCode)
        assertTrue(ex.message!!.contains("check tenantId, clientId and clientSecret"), ex.message)
        generateSequence<Throwable>(ex) { it.cause }.forEach {
            assertFalse(it.message.orEmpty().contains("secret-1"), it.message)
        }
    }

    @Test fun `throttling and outages of the token endpoint are retryable`() {
        listOf(429, 503).forEach { status ->
            wireMock.resetAll()
            wireMock.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)))

            val ex = assertThrows<GraphMailRetryableException> { login.resolve(null, credentials).authorization() }

            assertEquals(status, ex.statusCode)
        }
    }

    @Test fun `the properties are checked like 1x did, before any request`() {
        val refusals =
            listOf(
                GraphCredentials("", "client-1", "secret-1") to "'tenantId' is not configured",
                GraphCredentials("tenant/../other", "client-1", "secret-1") to "'tenantId' is not a usable tenant",
                GraphCredentials(TENANT, " ", "secret-1") to "'clientId' is not configured",
                GraphCredentials(TENANT, "client-1", "") to "'clientSecret' is not configured",
            )

        refusals.forEach { (invalid, expected) ->
            val message = assertThrows<IllegalStateException> { login.validate(null, invalid) }.message!!
            assertTrue(message.contains(expected), message)
        }
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)))
    }

    @Test fun `the credentials never print the secret`() {
        assertFalse(credentials.toString().contains("secret-1"))
    }
}
