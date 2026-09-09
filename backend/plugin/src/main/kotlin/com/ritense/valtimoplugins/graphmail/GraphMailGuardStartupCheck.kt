package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import org.slf4j.LoggerFactory
import org.springframework.aop.Advisor
import org.springframework.aop.PointcutAdvisor
import org.springframework.aop.aspectj.AbstractAspectJAdvice
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.lang.reflect.Method

/**
 * Fails startup unless [AllowedSendersChangeGuard]'s advice is demonstrably applied to every
 * `updatePluginConfiguration` method on the live [PluginService] proxy.
 *
 * The guard's pointcut binds to a Valtimo-internal signature. Should Valtimo rename or reshape that
 * method, or stop proxying the bean, the pointcut stops matching — no error, no warning, and the
 * sender allowlist becomes editable without the client secret again while the documentation and
 * release notes keep asserting otherwise. A security control that disappears quietly on an upgrade
 * is worse than one that was never claimed.
 *
 * ## Why this asks the proxy rather than the container
 *
 * An earlier version of this check only asserted `AopUtils.isAopProxy(pluginService)`. That proves
 * nothing: `PluginService` is `@Transactional`, so it is *always* an AOP proxy whether or not this
 * guard is registered. The check passed in exactly the scenario it was written to catch.
 *
 * So it now walks the proxy's own advisor chain and requires an advisor that (a) comes from
 * [AllowedSendersChangeGuard] and (b) whose pointcut actually matches each of the target's
 * `updatePluginConfiguration` methods. Nothing short of that distinguishes "the advice is applied"
 * from "some advice is applied".
 *
 * Refusing to start is deliberate: an application that will not boot gets fixed, while a warning in
 * a startup log does not.
 */
class GraphMailGuardStartupCheck(
    private val pluginService: PluginService,
) {
    private val logger = LoggerFactory.getLogger(GraphMailGuardStartupCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun verifyAllowedSendersGuardIsActive() {
        val target = AopUtils.getTargetClass(pluginService)

        // Every overload of the name, not only the ones the guard understands. The pointcut matches
        // by name, so an overload the advice cannot read is still advised — and takes its
        // proceed() escape branch, passing an allowlist change through unchecked. Filtering to
        // "the ones carrying an ObjectNode" here would mean the check silently ignores exactly the
        // overload that creates the hole.
        val updateMethods = target.methods.filter { it.name == "updatePluginConfiguration" }

        if (updateMethods.isEmpty()) {
            fail(
                "${target.name} has no updatePluginConfiguration method at all. " +
                    "The Valtimo API this guard depends on has changed.",
            )
        }

        val uninspectable = updateMethods.filterNot(::isInspectableByGuard)
        if (uninspectable.isNotEmpty()) {
            fail(
                "the guard cannot read the arguments of " +
                    "${uninspectable.joinToString { it.toGenericString() }}. It needs both a " +
                    "PluginConfigurationId and an ObjectNode of submitted properties to decide " +
                    "whether the allowlist changed and whether the secret was supplied; without " +
                    "them the advice waves the update through.",
            )
        }

        val advised =
            pluginService as? Advised
                ?: fail("PluginService is not an Advised proxy, so the guard's advice cannot be applied.")

        val guardAdvisors = advised.advisors.filter(::belongsToGuard)
        if (guardAdvisors.isEmpty()) {
            fail(
                "no advisor from ${AllowedSendersChangeGuard::class.java.simpleName} is present on " +
                    "the PluginService proxy. The aspect bean is missing or was not woven.",
            )
        }

        // Registered is not the same as matching: an advisor whose pointcut no longer selects the
        // method contributes nothing.
        val unmatched =
            updateMethods.filterNot { method ->
                guardAdvisors.any { advisor ->
                    (advisor as? PointcutAdvisor)?.pointcut?.methodMatcher?.matches(method, target) == true
                }
            }
        if (unmatched.isNotEmpty()) {
            fail(
                "the guard's pointcut does not match ${unmatched.joinToString { it.toGenericString() }}. " +
                    "Those updates would bypass the check.",
            )
        }

        logger.info(
            "[Graph Mail Plugin] Sender-allowlist guard verified on {} update method(s): changing " +
                "'allowedSenders' requires the client secret to be supplied again.",
            updateMethods.size,
        )
    }

    // The advice reads the configuration id and the submitted properties out of the join point's
    // arguments. An overload missing either is one it cannot evaluate.
    private fun isInspectableByGuard(method: Method): Boolean =
        method.parameterTypes.any { PluginConfigurationId::class.java.isAssignableFrom(it) } &&
            method.parameterTypes.any { ObjectNode::class.java.isAssignableFrom(it) }

    private fun belongsToGuard(advisor: Advisor): Boolean {
        val advice = advisor.advice
        return advice is AbstractAspectJAdvice &&
            advice.aspectJAdviceMethod.declaringClass == AllowedSendersChangeGuard::class.java
    }

    private fun fail(reason: String): Nothing =
        throw IllegalStateException(
            "[Graph Mail Plugin] The sender-allowlist guard is enabled but cannot be applied: " +
                "$reason Changing the allowlist would no longer require the client secret. " +
                "Refusing to start rather than running with a security control that is silently " +
                "inactive — see AllowedSendersChangeGuard. Set " +
                "graph-mail.require-secret-for-allowlist-change=false to disable it deliberately.",
        )
}
