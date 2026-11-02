package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.security.MessageDigest
import java.time.Instant

private const val TOKEN_EXPIRY_BUFFER_SECONDS = 60L
private const val BEARER = "Bearer "

// Statuses of the token endpoint that may clear by themselves.
private val RETRYABLE_TOKEN_STATUSES = setOf(408, 429)

/**
 * The deprecated 1.x login: the client-credentials request with the tenantId, clientId and clientSecret of the Graph
 * Mail configuration itself, to `graph-mail.http.token-base-url`. Kept so configurations from 1.x keep sending after
 * an upgrade; [GraphMailLogin] only uses it for a configuration that selects no Http Client Authentication
 * configuration.
 *
 * One token request per call, without retries of its own: [GraphMailClientImpl] retries a
 * [GraphMailRetryableException] from the login like a network error, within the deadline of the send. Tokens are kept
 * in the shared [GraphTokenCache]; a token Graph refuses is dropped from it ([GraphMailAuthentication.rejected]), and
 * [GraphMailClientImpl] tries the request once more with a fresh one, as in 1.0.
 */
class GraphMailClientSecretLogin(
    private val restClient: RestClient = RestClient.create(),
    private val tokenBaseUrl: String = "https://login.microsoftonline.com",
    graphBaseUrl: String = "https://graph.microsoft.com",
    private val tokenCache: GraphTokenCache = GraphTokenCache(),
) {
    private val logger = LoggerFactory.getLogger(GraphMailClientSecretLogin::class.java)

    // The client-credentials scope has to name the cloud instance the token will be used against: a
    // US Gov or China tenant would otherwise ask Entra for a resource that does not exist in its cloud.
    private val graphScope: String = "${graphBaseUrl.trimEnd('/')}/.default"

    /** Checks the three properties on save and deploy, without network calls. */
    fun validate(credentials: GraphCredentials) {
        check(credentials.tenantId.isNotBlank()) {
            "Plugin property 'tenantId' is not configured — check the Graph Mail plugin configuration"
        }
        check(isValidTenantId(credentials.tenantId)) {
            "Plugin property 'tenantId' is not a usable tenant identifier — expected a directory " +
                "GUID, a verified domain such as 'contoso.onmicrosoft.com', or 'common' / " +
                "'organizations'. Check the Graph Mail plugin configuration"
        }
        check(credentials.clientId.isNotBlank()) {
            "Plugin property 'clientId' is not configured — check the Graph Mail plugin configuration"
        }
        check(credentials.clientSecret.isNotBlank()) {
            "Plugin property 'clientSecret' is not configured — check the Graph Mail plugin configuration"
        }
    }

    /** The login for one send. */
    fun resolve(credentials: GraphCredentials): GraphMailAuthentication {
        validate(credentials)
        val key = cacheKey(credentials)
        return GraphMailAuthentication(
            source = "the client secret of this Graph Mail configuration (tenant ${credentials.tenantId})",
            sslContext = null,
            // Always a fresh token next: this one is dropped, or a concurrent send already replaced it.
            rejected = { authorization ->
                tokenCache.invalidateIfMatches(key, authorization.removePrefix(BEARER))
                true
            },
        ) { BEARER + tokenCache.getOrFetch(key) { fetchToken(credentials) } }
    }

    // A hash of clientSecret is part of the cache key — not just tenantId+clientId — so a
    // configuration with a wrong or stale secret can never ride on a token that a *different*
    // (correct) secret already fetched and cached for the same tenant/client. tenantId and clientId
    // are visible in the admin UI, so they alone must not be enough to obtain a working token.
    private fun cacheKey(credentials: GraphCredentials) =
        "${credentials.tenantId}:${credentials.clientId}:${sha256(credentials.clientSecret)}"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fetchToken(credentials: GraphCredentials): Pair<String, Instant> {
        val (tenantId, clientId, clientSecret) = credentials
        // encode() before expand() percent-encodes the substituted value, so a tenantId carrying a
        // structural character can't add a path segment or start a query string.
        val url =
            UriComponentsBuilder
                .fromUriString("$tokenBaseUrl/{tenantId}/oauth2/v2.0/token")
                .encode()
                .build()
                .expand(tenantId)
                .toUriString()

        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "client_credentials")
                add("client_id", clientId)
                add("client_secret", clientSecret)
                add("scope", graphScope)
            }

        val response =
            try {
                restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
                    ?: throw GraphMailException("Empty response when fetching access token")
            } catch (ex: RestClientResponseException) {
                throw tokenRequestRejected(withoutBody(ex), tenantId)
            } catch (ex: RestClientException) {
                logger.warn("Token request for tenant [{}] failed: {}", tenantId, maskEmailsInText(ex.message))
                throw GraphMailRetryableException(
                    "Could not reach Azure Entra for the token request (timeout or network error): " +
                        "${maskEmailsInText(ex.message)}",
                    ex,
                )
            }

        // Guard against negative TTL if Azure returns expires_in < buffer.
        val ttl = (response.expiresIn.toLong() - TOKEN_EXPIRY_BUFFER_SECONDS).coerceAtLeast(0L)
        logger.info("New token acquired [{}:***] — valid for {}s", tenantId, ttl)
        return response.accessToken to Instant.now().plusSeconds(ttl)
    }

    // Entra throttles (429), times out (408) and has outages (5xx) like any other service: those
    // clear by themselves, so they don't send an administrator hunting a credential problem.
    private fun tokenRequestRejected(
        ex: RestClientResponseException,
        tenantId: String,
    ): GraphMailException {
        val status = ex.statusCode.value()
        if (status in RETRYABLE_TOKEN_STATUSES || status >= 500) {
            logger.warn("Token request for tenant [{}] answered {}", tenantId, status)
            return GraphMailRetryableException(
                "Azure Entra did not answer the token request for tenant [$tenantId] (HTTP $status)",
                ex,
                statusCode = status,
            )
        }
        logger.error("Token request rejected ({}) for tenant [{}]", status, tenantId)
        return GraphMailPermanentException(
            "Azure rejected the token request (HTTP $status) — check tenantId, clientId and clientSecret of this " +
                "Graph Mail configuration, and that the app registration and its secret have not expired",
            ex,
            statusCode = status,
        )
    }

    // An Entra error response may echo the form, client secret included: keep its status and
    // headers, never its body — not in a message, and not in a cause that a stack trace prints.
    private fun withoutBody(ex: RestClientResponseException): RestClientResponseException =
        HttpClientErrorException(ex.statusCode, ex.statusText, ex.responseHeaders ?: HttpHeaders(), null, null)
}
