package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.domain.PluginDefinition
import com.ritense.plugin.repository.PluginConfigurationRepository
import org.aspectj.lang.ProceedingJoinPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

class AllowedSendersChangeGuardTest {
    private val objectMapper = ObjectMapper()
    private val repository: PluginConfigurationRepository = mock()
    private val guard = AllowedSendersChangeGuard(repository)

    private val configurationId = PluginConfigurationId.existingId(UUID.randomUUID())

    private fun properties(
        allowedSenders: String?,
        clientSecret: String? = null,
    ): ObjectNode =
        objectMapper.createObjectNode().apply {
            allowedSenders?.let { put(ALLOWED_SENDERS_PROPERTY, it) }
            clientSecret?.let { put(CLIENT_SECRET_PROPERTY, it) }
        }

    private fun storedConfiguration(
        allowedSenders: String?,
        pluginKey: String = GRAPH_MAIL_PLUGIN_KEY,
    ): PluginConfiguration {
        val definition: PluginDefinition = mock()
        whenever(definition.key).thenReturn(pluginKey)
        val configuration: PluginConfiguration = mock()
        whenever(configuration.pluginDefinition).thenReturn(definition)
        whenever(configuration.properties).thenReturn(properties(allowedSenders))
        return configuration
    }

    private fun joinPoint(vararg args: Any?): ProceedingJoinPoint {
        val jp: ProceedingJoinPoint = mock()
        whenever(jp.args).thenReturn(arrayOf(*args))
        whenever(jp.proceed()).thenReturn("proceeded")
        return jp
    }

    private fun stubStored(configuration: PluginConfiguration?) {
        whenever(repository.findById(any())).thenReturn(Optional.ofNullable(configuration))
    }

    @Test fun `an unchanged allowlist saves without the secret`() {
        stubStored(storedConfiguration("noreply@test.nl,@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl,@test.nl"))

        assertEquals("proceeded", guard.requireSecretWhenAllowlistChanges(jp))
        verify(jp).proceed()
    }

    @Test fun `reordering and respacing the same entries is not a change`() {
        // Otherwise an admin who never touched the field would be forced to re-enter the secret
        // just because the form round-tripped the value differently.
        stubStored(storedConfiguration("noreply@test.nl, @test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("@TEST.NL,  noreply@Test.nl "))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `a repeated entry is not a change`() {
        // The frontend normaliser deduplicates for exactly this reason; if it did not, the form
        // would demand a secret for a list the backend considers untouched.
        stubStored(storedConfiguration("noreply@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl,noreply@test.nl"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `trailing and repeated separators are not a change`() {
        stubStored(storedConfiguration("noreply@test.nl,@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl,,@test.nl,"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `a changed allowlist without the secret is rejected`() {
        stubStored(storedConfiguration("noreply@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl,ceo@test.nl"))

        assertThrows(AllowedSendersChangeRequiresSecretException::class.java) {
            guard.requireSecretWhenAllowlistChanges(jp)
        }
        // The update must never reach Valtimo — reaching it would persist the widened allowlist.
        verify(jp, never()).proceed()
    }

    @Test fun `a changed allowlist with a blank secret is rejected`() {
        stubStored(storedConfiguration("noreply@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("ceo@test.nl", clientSecret = "   "))

        assertThrows(AllowedSendersChangeRequiresSecretException::class.java) {
            guard.requireSecretWhenAllowlistChanges(jp)
        }
        verify(jp, never()).proceed()
    }

    @Test fun `a changed allowlist with the secret supplied is allowed`() {
        stubStored(storedConfiguration("noreply@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("ceo@test.nl", clientSecret = "s3cret"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `removing an entry also counts as a change`() {
        // Narrowing is not itself an escalation, but treating it as one keeps the rule simple and
        // stops "remove then re-add" from being a way around it.
        stubStored(storedConfiguration("noreply@test.nl,ceo@test.nl"))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl"))

        assertThrows(AllowedSendersChangeRequiresSecretException::class.java) {
            guard.requireSecretWhenAllowlistChanges(jp)
        }
    }

    @Test fun `filling in the allowlist for the first time requires the secret`() {
        stubStored(storedConfiguration(null))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl"))

        assertThrows(AllowedSendersChangeRequiresSecretException::class.java) {
            guard.requireSecretWhenAllowlistChanges(jp)
        }
    }

    @Test fun `the bracketed list form is the same list as the comma-separated one`() {
        // parseStringListParam accepts the JSON-array form, so the guard must treat it as equal —
        // and the frontend normaliser mirrors this, or the two disagree about what changed.
        stubStored(storedConfiguration("""["noreply@test.nl","@test.nl"]"""))
        val jp = joinPoint(configurationId, null, "title", properties("noreply@test.nl,@test.nl"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `another plugin's configuration is never touched`() {
        // The pointcut matches every plugin's update; the guard must apply to this plugin only.
        stubStored(storedConfiguration("anything", pluginKey = "some-other-plugin"))
        val jp = joinPoint(configurationId, null, "title", properties("totally-different"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `the three-argument overload is read correctly`() {
        // updatePluginConfiguration(id, title, properties) — no newId argument.
        stubStored(storedConfiguration("noreply@test.nl"))
        val jp = joinPoint(configurationId, "title", properties("ceo@test.nl"))

        assertThrows(AllowedSendersChangeRequiresSecretException::class.java) {
            guard.requireSecretWhenAllowlistChanges(jp)
        }
        verify(jp, never()).proceed()
    }

    @Test fun `an unrecognised call shape is let through`() {
        val jp = joinPoint("not-an-id")

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }

    @Test fun `a configuration that no longer exists is let through`() {
        stubStored(null)
        val jp = joinPoint(configurationId, null, "title", properties("ceo@test.nl"))

        guard.requireSecretWhenAllowlistChanges(jp)

        verify(jp).proceed()
    }
}
