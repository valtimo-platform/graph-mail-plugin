package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.repository.PluginConfigurationRepository
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory

// The one place this expression is written down. The startup check re-evaluates it against the real
// method signatures and AllowedSendersChangeGuardWeavingTest asserts it still matches, so a Valtimo
// change that breaks it surfaces as a failure rather than as a quietly inactive guard.
internal const val UPDATE_PLUGIN_CONFIGURATION_POINTCUT =
    "execution(* com.ritense.plugin.service.PluginService.updatePluginConfiguration(..))"

internal const val GRAPH_MAIL_PLUGIN_KEY = "entra"
internal const val ALLOWED_SENDERS_PROPERTY = "allowedSenders"
internal const val CLIENT_SECRET_PROPERTY = "clientSecret"

/**
 * Requires the client secret to be re-supplied whenever the sender allowlist changes.
 *
 * The allowlist is what bounds which mailboxes the tenant-wide Mail.Send permission can be used for
 * through this plugin. Widening it is a privilege escalation: whoever adds an address can send as
 * that mailbox from then on. Without this guard the only thing standing between an open admin screen
 * and that escalation is a form submit — Valtimo deliberately backfills an empty secret field with
 * the stored value, so the credential itself never has to be produced.
 *
 * ## Why an aspect, and why here
 *
 * [com.ritense.plugin.service.PluginService.updatePluginConfiguration] runs, in order:
 * `updateProperties` → `validateProperties` → `runAllPluginEvents(UPDATE)` → `save`. The backfill
 * happens inside `updateProperties`, which means that by the time any plugin-facing hook runs —
 * including `@PluginEvent(invokedOn = [UPDATE])`, which does run before `save` — the submitted
 * secret and the stored secret are indistinguishable. The information this rule depends on exists
 * only *before* that call. An around-advice on the service method is the earliest supported place
 * that still sees the raw submitted properties.
 *
 * `PluginService` is `@Transactional`, so throwing from here rolls the update back.
 *
 * ## Fragility
 *
 * This binds to a Valtimo-internal signature. If that signature changes the pointcut stops matching
 * and the guard disappears silently — the worst possible failure mode for a security control. That
 * is what [GraphMailGuardStartupCheck] exists to prevent; do not remove one without the other.
 */
@Aspect
class AllowedSendersChangeGuard(
    private val pluginConfigurationRepository: PluginConfigurationRepository,
) {
    private val logger = LoggerFactory.getLogger(AllowedSendersChangeGuard::class.java)

    // Matches both overloads by name. A pointcut on just the 4-argument one would leave a hole:
    // Spring AOP cannot intercept self-invocation, so the 3-argument overload's internal delegation
    // to the 4-argument one passes the proxy by. Matching on name means whichever overload an
    // external caller uses is intercepted exactly once.
    @Around(UPDATE_PLUGIN_CONFIGURATION_POINTCUT)
    fun requireSecretWhenAllowlistChanges(joinPoint: ProceedingJoinPoint): Any? {
        val configurationId = joinPoint.args.firstOrNull { it is PluginConfigurationId } as? PluginConfigurationId
        val submitted = joinPoint.args.lastOrNull { it is ObjectNode } as? ObjectNode

        // Not a shape we recognise — let it through rather than blocking an update we cannot reason
        // about. The startup check is what makes sure this branch is not silently the normal path.
        if (configurationId == null || submitted == null) {
            logger.warn(
                "Could not read the plugin configuration id and submitted properties from an " +
                    "updatePluginConfiguration call — the allowlist guard did not evaluate this update",
            )
            return joinPoint.proceed()
        }

        val stored =
            pluginConfigurationRepository
                .findById(configurationId)
                .orElse(null)
                ?: return joinPoint.proceed()

        // Never touch another plugin's configuration.
        if (stored.pluginDefinition.key != GRAPH_MAIL_PLUGIN_KEY) return joinPoint.proceed()

        val storedAllowlist = normaliseAllowlist(stored.properties?.get(ALLOWED_SENDERS_PROPERTY)?.asText())
        val submittedAllowlist = normaliseAllowlist(submitted.get(ALLOWED_SENDERS_PROPERTY)?.asText())

        if (storedAllowlist == submittedAllowlist) return joinPoint.proceed()

        val secretSupplied =
            submitted
                .get(CLIENT_SECRET_PROPERTY)
                ?.takeIf { !it.isNull }
                ?.asText()
                ?.isNotBlank() == true

        if (!secretSupplied) {
            // The allowlist values themselves stay out of the message: they are email addresses.
            logger.warn(
                "Rejected a change to the sender allowlist of plugin configuration [{}] — " +
                    "the client secret was not supplied",
                configurationId.id,
            )
            throw AllowedSendersChangeRequiresSecretException()
        }

        return joinPoint.proceed()
    }

    // Compared as a set of trimmed, lowercased entries so that reordering or respacing the same
    // addresses is not treated as a change. Reuses the plugin's own parser so this cannot drift
    // from how the allowlist is read at send time.
    private fun normaliseAllowlist(value: String?): Set<String> =
        parseStringListParam(value).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
