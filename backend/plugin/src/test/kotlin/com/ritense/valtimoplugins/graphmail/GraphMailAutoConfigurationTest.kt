package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestClient

/**
 * Nothing verified this wiring. Ten beans, a `@Qualifier` that exists specifically to dodge a
 * host application's `@Primary` RestClient, and an `AutoConfiguration.imports` entry — a typo in
 * any of them surfaces only in a running GZAC, at which point the plugin simply does not work and
 * the reason is several layers down.
 */
class GraphMailAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GraphMailAutoConfiguration::class.java))
            .withUserConfiguration(HostApplicationStubs::class.java)

    @Test
    fun `the autoconfiguration is registered for Spring Boot to find`() {
        // The imports file is what makes any of this happen at all in a real application.
        val imports =
            javaClass.classLoader
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                ?.readText()

        assertNotNull(imports, "the AutoConfiguration.imports file is missing from the jar")
        assertTrue(
            imports!!.contains(GraphMailAutoConfiguration::class.java.name),
            "GraphMailAutoConfiguration is not listed in AutoConfiguration.imports, so Spring Boot " +
                "will never load it: $imports",
        )
    }

    @Test
    fun `every bean the plugin needs is present`() {
        runner.run { context ->
            assertTrue(context.startupFailure == null, "context failed to start: ${context.startupFailure}")
            listOf(
                GraphMailHttpProperties::class.java,
                GraphTokenCache::class.java,
                SendIdempotencyGuard::class.java,
                AttachmentConcurrencyLimiter::class.java,
                GraphMailClient::class.java,
                GraphMailPluginFactory::class.java,
                GraphMailTestSendController::class.java,
                GraphMailHttpSecurityConfigurer::class.java,
                GraphMailEndpointAllowlistWarning::class.java,
            ).forEach { type ->
                assertTrue(context.getBeanNamesForType(type).isNotEmpty(), "missing bean: ${type.simpleName}")
            }
        }
    }

    @Test
    fun `the Graph client uses the qualified RestClient, not the host application's primary one`() {
        // This is the whole reason the @Qualifier is there. A GZAC application may well define its
        // own @Primary RestClient, and injecting that would silently send Graph traffic through a
        // client with different timeouts, redirect handling and message converters.
        runner.withUserConfiguration(PrimaryRestClientConfiguration::class.java).run { context ->
            assertTrue(context.startupFailure == null, "context failed to start: ${context.startupFailure}")

            val graphClient = context.getBean(GraphMailClient::class.java)
            val injected =
                GraphMailClientImpl::class.java
                    .getDeclaredField("restClient")
                    .apply { isAccessible = true }
                    .get(graphClient)

            assertSame(
                context.getBean("graphMailRestClient"),
                injected,
                "the Graph client was wired to the host application's @Primary RestClient",
            )
        }
    }

    @Test
    fun `the allowlist guard is on by default and can be switched off deliberately`() {
        runner.run { context ->
            assertTrue(
                context.getBeanNamesForType(AllowedSendersChangeGuard::class.java).isNotEmpty(),
                "the guard must be active without any configuration — it is deny-by-default",
            )
            assertTrue(
                context.getBeanNamesForType(GraphMailGuardStartupCheck::class.java).isNotEmpty(),
                "the startup check must be tied to the guard; the guard is worthless if it can " +
                    "disappear silently",
            )
        }

        runner.withPropertyValues("graph-mail.require-secret-for-allowlist-change=false").run { context ->
            assertTrue(
                context.getBeanNamesForType(AllowedSendersChangeGuard::class.java).isEmpty(),
                "switching the guard off must actually remove it",
            )
            assertTrue(
                context.getBeanNamesForType(GraphMailGuardStartupCheck::class.java).isEmpty(),
                "with the guard gone there is nothing for the startup check to verify, and it " +
                    "would fail the boot it exists to protect",
            )
        }
    }

    @Test
    fun `deployment settings reach the beans that use them`() {
        runner
            .withPropertyValues(
                "graph-mail.http.attachment-concurrency=3",
                "graph-mail.http.connect-timeout-seconds=7",
            ).run { context ->
                assertSame(3, context.getBean(AttachmentConcurrencyLimiter::class.java).availablePermits())
                assertSame(7L, context.getBean(GraphMailHttpProperties::class.java).connectTimeoutSeconds)
            }
    }

    @Test
    fun `an invalid endpoint fails the boot rather than being ignored`() {
        runner.withPropertyValues("graph-mail.http.token-base-url=https://evil.example.com").run { context ->
            assertNotNull(
                context.startupFailure,
                "a non-Microsoft token endpoint must stop the application, not be quietly accepted",
            )
        }
    }

    /** The collaborators a real GZAC application supplies. */
    @Configuration(proxyBeanMethods = false)
    class HostApplicationStubs {
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean fun pluginService(): PluginService = mock()

        @Bean fun pluginConfigurationRepository(): PluginConfigurationRepository = mock()

        @Bean fun temporaryResourceStorageService(): TemporaryResourceStorageService = mock()
    }

    /** A host application that defines its own preferred RestClient. */
    @Configuration(proxyBeanMethods = false)
    class PrimaryRestClientConfiguration {
        @Bean
        @Primary
        fun hostApplicationRestClient(): RestClient = RestClient.create()
    }
}
