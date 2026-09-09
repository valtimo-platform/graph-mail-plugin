package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.AspectJExpressionPointcut

/**
 * Proves that the guard's pointcut still selects the real Valtimo methods.
 *
 * [AllowedSendersChangeGuardTest] calls the advice method directly, which verifies its logic but
 * says nothing about whether the advice would ever run. The failure this class exists for is the
 * opposite one: the logic stays correct while a Valtimo upgrade renames or reshapes
 * `PluginService.updatePluginConfiguration`, the pointcut silently stops matching, and the allowlist
 * becomes editable without the client secret with no test going red.
 *
 * Evaluating the expression against the actual compiled signatures is what turns that into a build
 * failure. It runs against whatever Valtimo version is on the classpath, so it breaks at upgrade
 * time rather than in production.
 */
class AllowedSendersChangeGuardWeavingTest {
    private val pointcut =
        AspectJExpressionPointcut().apply {
            expression = UPDATE_PLUGIN_CONFIGURATION_POINTCUT
        }

    private val updateMethods =
        PluginService::class.java.methods.filter { it.name == "updatePluginConfiguration" }

    @Test fun `Valtimo still exposes the method the guard binds to`() {
        assertTrue(
            updateMethods.isNotEmpty(),
            "PluginService has no updatePluginConfiguration method — the API the guard depends on " +
                "has changed and the guard is inert",
        )
    }

    @Test fun `the pointcut matches every updatePluginConfiguration overload`() {
        // Every overload, not just the one the REST resource happens to call today: Spring AOP
        // cannot intercept self-invocation, so an unmatched overload entered from outside would
        // delegate internally and slip past the proxy entirely.
        val unmatched = updateMethods.filterNot { pointcut.matches(it, PluginService::class.java) }

        assertTrue(
            unmatched.isEmpty(),
            "the guard's pointcut does not match ${unmatched.map { it.toGenericString() }} — " +
                "updates through those overloads would bypass the allowlist check",
        )
    }

    @Test fun `every matched overload carries what the advice needs to read`() {
        // "At least one" would be the same mistake the startup check used to make: a matched
        // overload the advice cannot read still gets advised, still hits the permissive escape
        // branch, and still persists an allowlist change unchecked. The ObjectNode is the only
        // place where "was the secret supplied" is answerable, and the id is what identifies the
        // configuration to compare against.
        val unreadable =
            updateMethods
                .filter { pointcut.matches(it, PluginService::class.java) }
                .filterNot { method ->
                    method.parameterTypes.any { ObjectNode::class.java.isAssignableFrom(it) } &&
                        method.parameterTypes.any {
                            PluginConfigurationId::class.java.isAssignableFrom(it)
                        }
                }

        assertTrue(
            unreadable.isEmpty(),
            "the pointcut matches ${unreadable.map { it.toGenericString() }}, which the advice " +
                "cannot inspect — updates through those would bypass the allowlist check",
        )
    }

    @Test fun `the pointcut does not match unrelated PluginService methods`() {
        // Too broad is its own failure: advising reads would add overhead and risk blocking calls
        // the guard was never meant to see.
        val unrelated =
            PluginService::class.java.methods.filter {
                it.name == "getPluginConfiguration" || it.name == "createPluginConfiguration"
            }

        val wronglyMatched = unrelated.filter { pointcut.matches(it, PluginService::class.java) }

        assertTrue(
            wronglyMatched.isEmpty(),
            "the pointcut also matches ${wronglyMatched.map { it.name }}, which it should not",
        )
    }
}
