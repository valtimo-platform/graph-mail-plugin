package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import com.ritense.valtimoplugins.httpclientauthentication.HttpClientAuthenticator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClientResponseException
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext

internal const val AUTHENTICATION_PLUGIN_CONFIGURATION_PROPERTY = "authenticationPluginConfiguration"

// How messages name the field: its label in the screen, and its key in *.pluginconfig.json.
internal const val AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD =
    "'Http Client Authentication configuration' ($AUTHENTICATION_PLUGIN_CONFIGURATION_PROPERTY)"

// A configuration with neither login.
internal const val NO_LOGIN_MESSAGE =
    "This Graph Mail configuration has no login: select an Http Client Authentication configuration in the field " +
        "$AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD, or fill in tenantId, clientId and clientSecret " +
        "(deprecated). See the release notes of Graph Mail 1.1.0."

// Logged for a configuration that still logs in with its own client secret.
internal const val DEPRECATED_LOGIN_MESSAGE =
    "logs in with its own tenantId, clientId and clientSecret. That login is deprecated and will be removed in a " +
        "future major version: select an Http Client Authentication configuration in the field " +
        "$AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD instead. See the migration steps in the release notes of Graph " +
        "Mail 1.1.0."

internal const val MINIMUM_HTTP_CLIENT_AUTHENTICATION_VERSION = "3.1.1"

private const val AUTHENTICATOR_CLASS = "com.ritense.valtimoplugins.httpclientauthentication.HttpClientAuthenticator"

// The methods of HttpClientAuthenticator Graph Mail calls; older versions of the plugin don't have them.
private val AUTHENTICATOR_METHODS = listOf("authenticationHeaders", "sslContext")

private const val HTTP_CLIENT_AUTHENTICATION_KEY = "http-client-authentication"
private const val TOKEN_EXCHANGE = "TOKEN_EXCHANGE"

// Statuses of the token endpoint that may clear by themselves.
private val RETRYABLE_LOGIN_STATUSES = setOf(408, 429)

/**
 * How one send logs in: the `Authorization` header to send, fetched per request ([authorization]; the token is cached
 * by the login), and the certificates for the connection, null without them. [source] names the configuration they
 * come from, for messages. [rejected] is called with a header Graph refused (401), so a login that caches its own
 * token can drop it; it returns whether the next [authorization] fetches a fresh token, which the client then tries
 * once. The Http Client Authentication plugin keeps its cache to itself, so its login returns false.
 */
class GraphMailAuthentication(
    val source: String,
    val sslContext: SSLContext?,
    val rejected: (String) -> Boolean = { false },
    val authorization: () -> String,
)

/**
 * Why the Http Client Authentication plugin can't be used, or null when it can. Graph Mail has it as a compileOnly
 * dependency: an application that only has configurations with the deprecated client-secret login doesn't need it, so
 * upgrading Graph Mail doesn't add it, or change the version of one the application already has.
 */
internal fun httpClientAuthenticationProblem(
    classLoader: ClassLoader? = GraphMailLogin::class.java.classLoader,
): String? {
    val authenticator =
        try {
            Class.forName(AUTHENTICATOR_CLASS, false, classLoader)
        } catch (ex: ClassNotFoundException) {
            return "the Http Client Authentication plugin is not installed. Add " +
                "com.ritense.valtimoplugins:http-client-authentication " +
                "$MINIMUM_HTTP_CLIENT_AUTHENTICATION_VERSION or later to the application"
        }
    val methods = authenticator.methods.map { it.name }
    return if (methods.containsAll(AUTHENTICATOR_METHODS)) {
        null
    } else {
        "the installed Http Client Authentication plugin is too old. Graph Mail needs " +
            "$MINIMUM_HTTP_CLIENT_AUTHENTICATION_VERSION or later"
    }
}

/**
 * The login of a Graph Mail configuration. Two kinds:
 * - the `authenticationPluginConfiguration` reference: an Http Client Authentication configuration of type Token
 *   exchange without audience, which does the client-credentials request to Microsoft Entra ID (with its client
 *   certificate, if any) and supplies the certificates for the Graph calls. Used whenever it is filled in.
 * - otherwise the deprecated 1.x login, with the configuration's own tenantId, clientId and clientSecret
 *   ([GraphMailClientSecretLogin]), so configurations from 1.x keep sending after an upgrade. Logged as deprecated.
 *
 * Only checks that the Http Client Authentication configuration can supply a token, not where its token endpoint
 * points: limiting where the secret can go is left to the network. The checks read the stored properties, so Graph
 * Mail only needs the [HttpClientAuthenticator] interface, not the plugin class (which the Http Client Authentication
 * plugin generates at startup). That interface is only touched in [HttpClientAuthenticatorCalls], after
 * [httpClientAuthenticationProblem] found it.
 */
class GraphMailLogin(
    private val pluginService: PluginService,
    private val clientSecretLogin: GraphMailClientSecretLogin = GraphMailClientSecretLogin(),
    // Null when the Http Client Authentication plugin can be used.
    private val httpClientAuthenticationProblem: String? = httpClientAuthenticationProblem(),
) {
    private val logger = LoggerFactory.getLogger(GraphMailLogin::class.java)

    // The client-secret logins already logged as deprecated by a send, so a send doesn't log it every time.
    private val deprecationLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Checks the login on save and deploy, without network calls or loading certificates. [credentials] are the
     * deprecated client-secret login, null when none of its properties is filled in.
     */
    fun validate(
        reference: String?,
        credentials: GraphCredentials? = null,
    ) {
        if (reference.isNullOrBlank()) {
            checkNotNull(credentials) { NO_LOGIN_MESSAGE }
            clientSecretLogin.validate(credentials)
            // On every save and deploy: with a *.pluginconfig.json that is every start, where it is seen.
            logger.warn(
                "[Graph Mail Plugin] A Graph Mail configuration (tenant {}) {}",
                credentials.tenantId,
                DEPRECATED_LOGIN_MESSAGE,
            )
            return
        }
        configurationOf(reference)
        // Not for a clientSecret alone: Valtimo keeps a stored secret when an update leaves it empty, so the screen
        // can't remove it after a migration. It is ignored all the same.
        if (credentials != null && (credentials.tenantId.isNotBlank() || credentials.clientId.isNotBlank())) {
            logger.warn(
                "[Graph Mail Plugin] A Graph Mail configuration selects an Http Client Authentication configuration " +
                    "and also has tenantId or clientId. The client-secret login is ignored; remove them.",
            )
        }
    }

    /** The login for one send. */
    fun resolve(
        reference: String?,
        credentials: GraphCredentials? = null,
    ): GraphMailAuthentication {
        if (reference.isNullOrBlank()) {
            checkNotNull(credentials) { NO_LOGIN_MESSAGE }
            if (deprecationLogged.add("${credentials.tenantId}:${credentials.clientId}")) {
                logger.warn(
                    "[Graph Mail Plugin] A Graph Mail configuration (tenant {}) {}",
                    credentials.tenantId,
                    DEPRECATED_LOGIN_MESSAGE,
                )
            }
            return clientSecretLogin.resolve(credentials)
        }
        val configuration = configurationOf(reference)
        val source = describe(configuration)
        val authenticator = pluginService.createInstance(configuration)
        val sslContext =
            try {
                HttpClientAuthenticatorCalls.sslContext(authenticator)
            } catch (ex: Exception) {
                throw GraphMailCertificateException(
                    "The certificates of $source could not be loaded: ${ex.message ?: ex.javaClass.simpleName}. " +
                        "Check the certificates in that configuration.",
                    ex,
                )
            }
        val tokenEndpointHost = tokenEndpointHostOf(configuration.properties)
        return GraphMailAuthentication(source, sslContext) {
            authorization(authenticator, source, tokenEndpointHost, sslContext != null)
        }
    }

    private fun configurationOf(reference: String): PluginConfiguration {
        val value = reference.trim()
        httpClientAuthenticationProblem?.let { problem ->
            throw IllegalStateException(
                "The field $AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD is filled in, but $problem.",
            )
        }
        check(isValidUuid(value)) {
            "The field $AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD ('$value') is not a plugin configuration id"
        }
        val configuration =
            pluginService.findPluginConfiguration(PluginConfigurationId.existingId(UUID.fromString(value)))
                ?: throw IllegalStateException(
                    "The Http Client Authentication configuration selected in " +
                        "$AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD ($value) does not exist. Select an existing one.",
                )
        check(configuration.pluginDefinition.key == HTTP_CLIENT_AUTHENTICATION_KEY) {
            "The plugin configuration '${configuration.title}' (${configuration.id.id}) selected in " +
                "$AUTHENTICATION_PLUGIN_CONFIGURATION_FIELD is a '${configuration.pluginDefinition.key}' " +
                "configuration. Select an Http Client Authentication configuration."
        }
        checkTokenLogin(configuration.properties, describe(configuration))
        return configuration
    }

    // What the Microsoft login needs; the rest (endpoint, client ID, secret) Http Client Authentication checks itself.
    private fun checkTokenLogin(
        properties: ObjectNode?,
        source: String,
    ) {
        val type = textOf(properties, "authenticationType")
        check(type == TOKEN_EXCHANGE) {
            "$source has authentication type ${type ?: "(none)"}. Graph Mail needs type Token exchange " +
                "($TOKEN_EXCHANGE) with the token endpoint, client ID, client secret and scope of the app " +
                "registration in Microsoft Entra ID."
        }
        check(textOf(properties, "audience") == null) {
            "$source has an audience. Leave Audience empty: Microsoft Entra ID only does the client credentials " +
                "request, not a token exchange."
        }
        check(textOf(properties, "scope") != null) {
            "$source has no scope. Fill in Scope, e.g. https://graph.microsoft.com/.default."
        }
    }

    private fun authorization(
        authenticator: Any,
        source: String,
        tokenEndpointHost: String,
        withCertificates: Boolean,
    ): String {
        val headers =
            try {
                HttpClientAuthenticatorCalls.authenticationHeaders(authenticator)
            } catch (ex: Exception) {
                throw loginFailure(ex, source, tokenEndpointHost, withCertificates)
            }
        return checkNotNull(headers.getFirst(HttpHeaders.AUTHORIZATION)) { "$source supplied no Authorization header" }
    }

    // Classified on the cause chain: http auth wraps the failure of its token request. Never the message of an HTTP
    // error, which may carry the response body, and a token endpoint may echo the form with the secret.
    private fun loginFailure(
        ex: Exception,
        source: String,
        host: String,
        withCertificates: Boolean,
    ): GraphMailException {
        // Only with certificates: without them, a TLS failure is a network failure, as at the Graph step.
        certificateRejectionIn(ex)?.takeIf { withCertificates }?.let { tls ->
            return GraphMailCertificateException(
                "TLS with the token endpoint $host failed, using the certificates of $source: ${tls.message}. The " +
                    "login did not reach Microsoft. $CHECK_CERTIFICATES",
                ex,
            )
        }
        val causes = causesOf(ex)
        val status =
            causes
                .filterIsInstance<RestClientResponseException>()
                .firstOrNull()
                ?.statusCode
                ?.value()
        val certificates = if (withCertificates) ", and its client certificate and the gateway" else ""
        val hint = if (withCertificates) " (${certificateHint(source)})" else ""
        val ioFailure = causes.firstOrNull { it is IOException }
        if (status == null && ioFailure != null) {
            return GraphMailRetryableException(
                "The token endpoint $host could not be reached for the login of $source: " +
                    "${maskEmailsInText(ioFailure.message)}$hint",
                ex,
            )
        }
        if (status != null && (status in RETRYABLE_LOGIN_STATUSES || status >= 500)) {
            return GraphMailRetryableException(
                "The token endpoint $host did not answer the login of $source (HTTP $status); the job executor " +
                    "will retry.$hint",
                ex,
                statusCode = status,
            )
        }
        if (status == null) {
            return GraphMailPermanentException(
                "The login of $source failed: ${maskEmailsInText(ex.message)}. Check that configuration$certificates.",
                ex,
            )
        }
        return GraphMailPermanentException(
            "The token endpoint $host refused the login of $source (HTTP $status): check the tenant in the token " +
                "endpoint, the client ID, the client secret (expired?) and the scope there$certificates.",
            ex,
            statusCode = status,
        )
    }

    private fun tokenEndpointHostOf(properties: ObjectNode?): String =
        textOf(properties, "tokenEndpoint")?.let { runCatching { URI.create(it).host }.getOrNull() } ?: "(unknown)"

    private fun textOf(
        properties: ObjectNode?,
        name: String,
    ): String? =
        properties
            ?.get(name)
            ?.takeUnless { it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun describe(configuration: PluginConfiguration) =
        "Http Client Authentication configuration '${configuration.title}' (${configuration.id.id})"
}

// The only code that touches a type of the Http Client Authentication plugin. That plugin is compileOnly, so this is only
// loaded once a configuration selects one, after httpClientAuthenticationProblem has found it.
private object HttpClientAuthenticatorCalls {
    fun sslContext(authenticator: Any): SSLContext? = (authenticator as HttpClientAuthenticator).sslContext()

    fun authenticationHeaders(authenticator: Any): HttpHeaders =
        (authenticator as HttpClientAuthenticator).authenticationHeaders()
}
