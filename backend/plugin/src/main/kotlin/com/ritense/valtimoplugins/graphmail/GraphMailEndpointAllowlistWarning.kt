package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Says out loud, on every startup, that the Microsoft endpoint allowlist has been switched off.
 *
 * `graph-mail.http.allow-non-microsoft-endpoints` disables the check that `graph-base-url` and
 * `token-base-url` are Microsoft hosts. Every Graph request carries the bearer token, and the
 * upload-URL host check takes its expected host from that endpoint, so a host that is not
 * Microsoft's receives both. The deprecated client-secret login POSTs the client secret to
 * `token-base-url` as a form field, so a non-Microsoft host there can harvest it.
 *
 * The flag itself is legitimate — this repository's own sandbox and the WireMock tests need it —
 * but it logged nothing at all. A deployment could run with it on indefinitely and no signal would
 * ever say so.
 *
 * ## Why a log line and not a refusal to start
 *
 * The setting is a deliberate, documented choice that legitimate deployments (sandbox, tests) rely on,
 * so refusing to start would break the very use case the flag exists for. Logging it at ERROR on
 * every startup puts it in front of whoever reads the logs without taking the escape hatch away.
 */
class GraphMailEndpointAllowlistWarning(
    private val properties: GraphMailHttpProperties,
) {
    private val logger = LoggerFactory.getLogger(GraphMailEndpointAllowlistWarning::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun warnWhenDisabled() {
        if (!properties.allowNonMicrosoftEndpoints) return
        logger.error(
            "[Graph Mail Plugin] SECURITY: graph-mail.http.allow-non-microsoft-endpoints is TRUE, so " +
                "Graph and token traffic is NOT restricted to Microsoft endpoints. Every Graph request " +
                "carries the bearer token, so a non-Microsoft graph-base-url receives it, and the " +
                "deprecated client-secret login POSTs the client secret to token-base-url. This setting " +
                "is for tests and local sandboxes only — remove it from any real deployment. Currently " +
                "graph-base-url={} token-base-url={}",
            properties.graphBaseUrl,
            properties.tokenBaseUrl,
        )
    }
}
