package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Says out loud, on every startup, that the Microsoft endpoint allowlist has been switched off.
 *
 * `graph-mail.http.allow-non-microsoft-endpoints` disables the check that closed a
 * credential-exfiltration path: the client secret is POSTed to `token-base-url` as a form field, so
 * an endpoint that is not Microsoft's is an endpoint that can harvest it. That is precisely why
 * `tokenBaseUrl` was taken out of the admin UI in 1.0.4.
 *
 * The flag itself is legitimate — this repository's own sandbox and the WireMock tests need it —
 * but it logged nothing at all. A deployment could run with it on indefinitely and no signal would
 * ever say so.
 *
 * ## Why a log line and not a refusal to start
 *
 * [GraphMailGuardStartupCheck] refuses to start when its control cannot be applied, and that is
 * right there: a guard that is silently inactive is worse than one that was never claimed. Here the
 * setting is a deliberate, documented choice that legitimate deployments (sandbox, tests) rely on,
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
                "token and Graph traffic is NOT restricted to Microsoft endpoints. The client secret " +
                "is POSTed to token-base-url as a form field, so a non-Microsoft endpoint there can " +
                "harvest it. This setting is for tests and local sandboxes only — remove it from any " +
                "real deployment. Currently token-base-url={} graph-base-url={}",
            properties.tokenBaseUrl,
            properties.graphBaseUrl,
        )
    }
}
