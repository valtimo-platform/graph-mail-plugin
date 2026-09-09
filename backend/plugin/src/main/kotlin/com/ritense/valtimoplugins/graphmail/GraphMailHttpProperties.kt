package com.ritense.valtimoplugins.graphmail

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

// Azure Entra and Graph endpoints per cloud instance. Commercial is what a Dutch government
// deployment uses, but hard-coding only that would silently break a sovereign-cloud tenant.
private val TOKEN_HOSTS =
    setOf(
        "login.microsoftonline.com",
        "login.microsoftonline.us",
        "login.partner.microsoftonline.cn",
    )

private val GRAPH_HOSTS =
    setOf(
        "graph.microsoft.com",
        "graph.microsoft.us",
        "dod-graph.microsoft.us",
        "microsoftgraph.chinacloudapi.cn",
    )

// Hosts that may serve an attachment upload session, per cloud instance. Graph does not serve the
// upload itself: createUploadSession hands back a URL on the cloud's own storage/SharePoint domain,
// and those domains differ per cloud. Keeping them separate per cloud rather than in one flat list
// is deliberate — a US Gov deployment has no business accepting a commercial upload host, and a
// single list would let it.
//
// Each entry is a suffix beginning with '.', so the match cannot be fooled by a host that merely
// ends in the same letters (see validateUploadUrl).
//
// The commercial set is the one this plugin has always used and is exercised in production. The
// sovereign sets follow Microsoft's published cloud endpoint domains but have NOT been verified
// against a live sovereign tenant. A host that is missing here now surfaces on DEBUG from
// GraphMailClientImpl.validateUploadUrl rather than failing anonymously, so a gap is reportable.
private val COMMERCIAL_UPLOAD_HOSTS =
    setOf(".microsoft.com", ".office.com", ".office.net", ".office365.com", ".sharepoint.com")

private val US_GOV_UPLOAD_HOSTS =
    setOf(".microsoft.us", ".office365.us", ".sharepoint.us", ".sharepoint-mil.us")

private val CHINA_UPLOAD_HOSTS =
    setOf(".chinacloudapi.cn", ".sharepoint.cn", ".partner.microsoftonline.cn")

private val UPLOAD_HOSTS_PER_CLOUD =
    mapOf(
        "graph.microsoft.com" to COMMERCIAL_UPLOAD_HOSTS,
        "graph.microsoft.us" to US_GOV_UPLOAD_HOSTS,
        "dod-graph.microsoft.us" to US_GOV_UPLOAD_HOSTS,
        "microsoftgraph.chinacloudapi.cn" to CHINA_UPLOAD_HOSTS,
    )

/**
 * Deployment-level HTTP settings for the Graph Mail plugin.
 *
 * These used to be `@PluginProperty` fields on [GraphMailPlugin], editable per plugin configuration
 * from the admin UI. That was a credential-exfiltration path: the client secret is POSTed to
 * `tokenBaseUrl` as a form field, so anyone who could edit a plugin configuration could point that
 * at a host they control and harvest it. `graphBaseUrl` was an SSRF primitive on top of that, and
 * the upload-URL host check derived its expected host *from* `graphBaseUrl` — so the check was only
 * ever as strong as the value an administrator typed in.
 *
 * Moving them here puts them under deployment control (application.yml / environment variables,
 * changed by whoever operates the platform) and lets them be validated once, at startup, against a
 * fixed allowlist. They are also genuinely deployment concerns rather than per-configuration ones:
 * two plugin configurations in the same GZAC instance have no reason to talk to different clouds.
 */
@ConfigurationProperties("graph-mail.http")
data class GraphMailHttpProperties(
    val tokenBaseUrl: String = "https://login.microsoftonline.com",
    val graphBaseUrl: String = "https://graph.microsoft.com",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 30,
    /**
     * Caps how many sends may hold attachment bytes in memory at once. Peak attachment heap is
     * roughly this value × [MAX_TOTAL_ATTACHMENT_BYTES] × a small copying factor — deliberately
     * decoupled from the job-executor pool size, which would otherwise set it.
     */
    val attachmentConcurrency: Int = 8,
    /** How long a send waits for an attachment slot before giving up and letting the engine retry. */
    val attachmentAcquireTimeoutSeconds: Long = 30,
    /**
     * Escape hatch for tests and local sandboxes pointing at WireMock or a proxy. Disables the
     * Microsoft endpoint allowlist below — never enable it in a production deployment.
     */
    val allowNonMicrosoftEndpoints: Boolean = false,
    /**
     * Outbound proxy for Graph and token traffic. Leave unset to use whatever the JVM is already
     * configured with (`-Dhttps.proxyHost` and friends) — that covers the common case, where the
     * whole application shares one egress proxy.
     *
     * Set it only when this plugin needs a different proxy than the rest of the application.
     * Deliberately a deployment setting and not a plugin property: a proxy sees the token request,
     * client secret and all, so letting it be set from the admin UI would reopen the credential
     * exfiltration path that moving [tokenBaseUrl] out of that UI closed.
     */
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    /**
     * Pipe-separated host patterns that bypass the proxy, e.g. `localhost|*.intern.example.nl`.
     *
     * Only applies alongside [proxyHost]; without one the JVM's own `http.nonProxyHosts` decides,
     * and setting this would do nothing at all — which [init] rejects rather than ignores.
     */
    val nonProxyHosts: String? = null,
) {
    init {
        require(connectTimeoutSeconds in 1..120) {
            "graph-mail.http.connect-timeout-seconds must be between 1 and 120 (got $connectTimeoutSeconds). " +
                "A value of 0 means 'wait forever' in some request factories, which is the opposite " +
                "of what a timeout is for."
        }
        require(readTimeoutSeconds in 1..300) {
            "graph-mail.http.read-timeout-seconds must be between 1 and 300 (got $readTimeoutSeconds)"
        }
        require(attachmentConcurrency in 1..256) {
            "graph-mail.http.attachment-concurrency must be between 1 and 256 (got $attachmentConcurrency)"
        }
        require(attachmentAcquireTimeoutSeconds in 1..600) {
            "graph-mail.http.attachment-acquire-timeout-seconds must be between 1 and 600 " +
                "(got $attachmentAcquireTimeoutSeconds)"
        }
        if (proxyHost != null) {
            require(proxyHost.isNotBlank()) {
                "graph-mail.http.proxy-host must not be blank. Remove the property to fall back to " +
                    "the JVM's own proxy settings."
            }
            require(proxyHost.none { it.isWhitespace() || it.isISOControl() }) {
                "graph-mail.http.proxy-host must not contain whitespace or control characters " +
                    "(got '$proxyHost')."
            }
            // ':' belongs here with the other structural characters: a host of the form
            // "proxy.example.nl:8080" clears the scheme and path checks, and then
            // InetSocketAddress.createUnresolved() happily builds an address whose hostname
            // contains a colon. That only surfaces as a DNS failure at connect time, long after
            // the misconfiguration could have been reported here.
            require(!proxyHost.contains("://") && !proxyHost.contains('/') && !proxyHost.contains(':')) {
                "graph-mail.http.proxy-host must be a bare hostname without scheme, port or path " +
                    "(got '$proxyHost'). Use proxy-port for the port."
            }
            requireNotNull(proxyPort) {
                "graph-mail.http.proxy-port is required when proxy-host is set."
            }
            require(proxyPort in 1..65535) {
                "graph-mail.http.proxy-port must be between 1 and 65535 (got $proxyPort)."
            }
        }
        if (nonProxyHosts != null) {
            // A bypass list that silently matches nothing is worse than no bypass list at all:
            // intranet and loopback traffic then goes through the proxy while the configuration
            // reads as though it does not. Both mistakes below fail exactly that way, so both are
            // rejected here rather than discovered in a packet capture.
            requireNotNull(proxyHost) {
                "graph-mail.http.non-proxy-hosts only applies to an explicitly configured proxy, " +
                    "but graph-mail.http.proxy-host is not set. Remove it, or set proxy-host too. " +
                    "(Without proxy-host the JVM's own http.nonProxyHosts governs the bypass.)"
            }
            require(nonProxyHosts.none { it == ',' }) {
                "graph-mail.http.non-proxy-hosts is pipe-separated, not comma-separated " +
                    "(got '$nonProxyHosts'). A comma becomes part of the pattern, so the entry " +
                    "matches nothing and the bypass silently does nothing."
            }
            // Whitespace *around* an entry is fine — BypassingProxySelector trims each one — but
            // whitespace inside one becomes part of the pattern and stops it from ever matching.
            val entries = nonProxyHosts.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            require(entries.isNotEmpty()) {
                "graph-mail.http.non-proxy-hosts contains no usable entry (got '$nonProxyHosts')."
            }
            require(entries.none { entry -> entry.any { it.isWhitespace() || it.isISOControl() } }) {
                "graph-mail.http.non-proxy-hosts entries must not contain whitespace or control " +
                    "characters (got '$nonProxyHosts'). Whitespace inside an entry becomes part of " +
                    "the pattern and stops it from matching."
            }
        }
        if (!allowNonMicrosoftEndpoints) {
            requireMicrosoftEndpoint(tokenBaseUrl, TOKEN_HOSTS, "graph-mail.http.token-base-url")
            requireMicrosoftEndpoint(graphBaseUrl, GRAPH_HOSTS, "graph-mail.http.graph-base-url")
        }
    }

    /**
     * True when [graphBaseUrl] points at a real Microsoft Graph host. The upload-URL check uses this
     * to decide how strict it can be: against production Graph it demands a Microsoft host, while a
     * WireMock sandbox is allowed to hand back its own host. Safe to derive from configuration only
     * because [init] has already validated that configuration.
     */
    fun isProductionGraphEndpoint(): Boolean = runCatching { URI.create(graphBaseUrl).host }.getOrNull() in GRAPH_HOSTS

    /**
     * The upload-session hosts acceptable for the configured cloud, or null when [graphBaseUrl] is
     * not a real Graph endpoint — a WireMock or sandbox endpoint legitimately serves the upload from
     * its own host, and [GraphMailClientImpl] pins it to exactly that host instead.
     *
     * Returning the cloud's own set rather than a boolean is what fixes the gap this replaced: the
     * old boolean said only "must be a Microsoft host" and was then checked against a commercial-only
     * list, so every sovereign-cloud deployment failed host validation on the first attachment over
     * 2 MiB — while accepting the sovereign Graph endpoint itself without complaint.
     */
    fun uploadHostSuffixes(): Set<String>? {
        val host =
            runCatching { URI.create(graphBaseUrl).host }
                .getOrNull()
        return UPLOAD_HOSTS_PER_CLOUD[host]
    }
}

private fun requireMicrosoftEndpoint(
    url: String,
    allowed: Set<String>,
    property: String,
) {
    val uri = runCatching { URI.create(url) }.getOrNull()
    require(uri?.scheme == "https" && uri.host in allowed) {
        "$property must be an https URL on one of $allowed (got '$url'). " +
            "Set graph-mail.http.allow-non-microsoft-endpoints=true only in tests or a local sandbox."
    }
}
