package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.ConcurrentLruCache
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * The [RestClient]s for client certificates, one per [SSLContext].
 *
 * Keyed by identity: the Http Client Authentication plugin hands out the same instance for the same certificates, so
 * their connections are pooled like those of the default client. Bounded, so certificates that change often can't
 * grow it without limit; an evicted client is not shut down — a send may still be using it — but released by the
 * garbage collector once nothing refers to it.
 */
class GraphMailCertificateRestClients(
    create: (SSLContext) -> RestClient,
) {
    private val clients = ConcurrentLruCache(MAX_CERTIFICATE_SETS, create)

    fun forSslContext(sslContext: SSLContext): RestClient = clients.get(sslContext)
}

private const val MAX_CERTIFICATE_SETS = 16

internal const val CHECK_CERTIFICATES =
    "Check the client certificate (with its chain) and the server certificate (CA) in that configuration."

// Statuses a gateway answers a missing or untrusted client certificate with: nginx uses 400 (it reports its
// internal 495/496 as 400), others 401 or 403.
private val CERTIFICATE_REJECTION_STATUSES = setOf(400, 401, 403)

// Headers only Microsoft Graph sets. Deliberately not WWW-Authenticate: Bearer or x-azure-ref: gateways send those too.
private val GRAPH_RESPONSE_HEADERS = listOf("request-id", "client-request-id")

// The messages of a TLS failure that rejects a certificate: a TLS alert from the server, or an
// untrusted server certificate (PKIX).
private val CERTIFICATE_REJECTION_MESSAGE =
    Regex(
        "certificate_required|bad_certificate|unknown_ca|certificate_unknown|certificate_expired|" +
            "certificate_revoked|unsupported_certificate|handshake_failure|access_denied|PKIX",
    )

private val HTML_TITLE = Regex("<title>([^<]{1,200})</title>", RegexOption.IGNORE_CASE)

private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}+")

private val errorBodyReader = ObjectMapper()

/**
 * The TLS failure in [ex] that rejects a certificate: one that names a TLS alert or PKIX, or is caused by a
 * CertificateException (also with TLS 1.3, where the server's verdict arrives after the client finished its
 * handshake). Not every handshake failure: a connection dropped mid-handshake ("Remote host terminated the
 * handshake") is a network failure, and keeps its normal retry.
 */
internal fun certificateRejectionIn(ex: Throwable): SSLException? =
    causesOf(ex).filterIsInstance<SSLException>().firstOrNull {
        CERTIFICATE_REJECTION_MESSAGE.containsMatchIn(it.message.orEmpty()) || it.cause is CertificateException
    }

// With TLS 1.3 a gateway that rejects the certificates may close the connection before its alert reaches the client,
// which then can't tell that from a connection lost after sending. Where to look when it keeps happening.
internal fun certificateHint(source: String) =
    "sent with the certificates of $source: if this keeps happening, check them — a gateway that rejects them may " +
        "also just close the connection"

/**
 * On the [RestClient] of a certificate set: turns the gateway's rejection of the certificates into a
 * [GraphMailCertificateException] that names their configuration ([source]), before the retry
 * logic of [GraphMailClientImpl] sees the failure. Without it, that logic would read the failure as
 * Microsoft's — "check the mailbox", or for a send "may have been sent" — while the request never reached Microsoft.
 *
 * A gateway rejects a certificate in one of two ways:
 * - in TLS: the request fails in [ClientHttpRequestExecution.execute] with an SSLException that names
 *   a TLS alert or PKIX, or is caused by a CertificateException ([certificateRejectionIn]). Failures while reading the
 *   response body happen later and keep their normal handling, so an unknown outcome is never reported as "not sent";
 * - at HTTP level, as nginx does with a 400: an error status in [CERTIFICATE_REJECTION_STATUSES]
 *   without Microsoft's headers or error body. A Microsoft response passes, buffered.
 *
 * The token request is not made here but by the Http Client Authentication plugin; [GraphMailLogin] classifies it.
 */
internal class CertificateRejectionInterceptor(
    private val source: String,
    private val graphBaseUrl: String,
) : ClientHttpRequestInterceptor {
    private val logger = LoggerFactory.getLogger(CertificateRejectionInterceptor::class.java)

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val response =
            try {
                execution.execute(request, body)
            } catch (ex: IOException) {
                throw tlsRejection(ex, stepOf(request)) ?: IOException("${ex.message} (${certificateHint(source)})", ex)
            }
        if (response.statusCode.value() !in CERTIFICATE_REJECTION_STATUSES) return response

        val content = response.use { it.body.readAllBytes() }
        val buffered = BufferedResponse(response.statusCode, response.statusText, response.headers, content)
        if (fromMicrosoft(response.headers, content)) return buffered
        throw gatewayRejection(stepOf(request), response.statusCode.value(), content)
    }

    private fun tlsRejection(
        ex: IOException,
        step: String,
    ): GraphMailCertificateException? {
        val tlsFailure = certificateRejectionIn(ex) ?: return null
        logger.warn(
            "TLS with the gateway failed during {} using the certificates of {}: {}",
            step,
            source,
            tlsFailure.message,
        )
        return GraphMailCertificateException(
            "TLS with the server failed during $step, using the certificates of $source: ${tlsFailure.message}. " +
                "The request did not reach Microsoft. $CHECK_CERTIFICATES",
            ex,
        )
    }

    private fun gatewayRejection(
        step: String,
        status: Int,
        content: ByteArray,
    ): GraphMailCertificateException {
        // Not the body itself: a gateway may echo the request. An HTML title (nginx: "400 No required
        // SSL certificate was sent") says what is wrong without that risk.
        val detail =
            HTML_TITLE
                .find(content.toString(Charsets.UTF_8))
                ?.groupValues
                ?.get(1)
                // The gateway's text goes into a log line: no line breaks or other control characters.
                ?.replace(CONTROL_CHARACTERS, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { " (\"$it\")" }
                .orEmpty()
        logger.warn("The gateway rejected {} ({}){} using the certificates of {}", step, status, detail, source)
        return GraphMailCertificateException(
            "The gateway (not Microsoft) rejected $step (HTTP $status)$detail — most likely the client " +
                "certificate of $source. $CHECK_CERTIFICATES Check any other requirements of the gateway too. The " +
                "request did not reach Microsoft.",
        )
    }

    private fun fromMicrosoft(
        headers: HttpHeaders,
        content: ByteArray,
    ): Boolean {
        val error = runCatching { errorBodyReader.readTree(content) }.getOrNull()
        return GRAPH_RESPONSE_HEADERS.any { headers.getFirst(it) != null } || error?.get("error")?.get("code") != null
    }

    // Names the step without the path, which holds the mailbox.
    private fun stepOf(request: HttpRequest): String =
        if (request.uri.toString().startsWith("${graphBaseUrl.trimEnd('/')}/v1.0/")) {
            "the Graph request ${request.method} …/${request.uri.path.substringAfterLast('/')}"
        } else {
            "the attachment upload"
        }

    private class BufferedResponse(
        private val status: HttpStatusCode,
        private val text: String,
        private val responseHeaders: HttpHeaders,
        private val content: ByteArray,
    ) : ClientHttpResponse {
        override fun getStatusCode(): HttpStatusCode = status

        override fun getStatusText(): String = text

        override fun getHeaders(): HttpHeaders = responseHeaders

        override fun getBody(): InputStream = ByteArrayInputStream(content)

        override fun close() = Unit
    }
}
