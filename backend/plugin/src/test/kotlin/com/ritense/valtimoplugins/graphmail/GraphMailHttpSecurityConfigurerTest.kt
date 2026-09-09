package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.service.PluginService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

private const val TEST_SEND_BODY =
    """{"pluginConfigurationId":"11111111-1111-1111-1111-111111111111","recipient":"a@b.nl","senderMailbox":"c@b.nl"}"""

// This plugin module has no application entry point of its own (that lives in :backend:app),
// but @WebMvcTest requires a @SpringBootConfiguration discoverable by upward package search.
// This minimal marker satisfies that without pulling in the real (DB-backed) application.
@SpringBootConfiguration
private class GraphMailHttpSecurityConfigurerTestApplication

// GraphMailHttpSecurityConfigurer.hasAuthority("ROLE_ADMIN") is the only line of code
// protecting /test-send from sending real email using production credentials as any
// authenticated user — it had no test coverage before this file. A refactor that silently
// weakens or drops the matcher would previously have passed CI unnoticed.
@WebMvcTest(controllers = [GraphMailTestSendController::class])
@Import(GraphMailHttpSecurityConfigurerTest.TestSecurityConfig::class)
class GraphMailHttpSecurityConfigurerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var mailClient: GraphMailClient

    @MockitoBean
    private lateinit var pluginService: PluginService

    @MockitoBean
    private lateinit var eventPublisher: ApplicationEventPublisher

    @Test fun `rejects test-send for a user without ROLE_ADMIN`() {
        mockMvc
            .post("/api/v1/plugin/entra/test-send") {
                contentType = MediaType.APPLICATION_JSON
                content = TEST_SEND_BODY
                with(user("bob").authorities(SimpleGrantedAuthority("ROLE_USER")))
                with(csrf())
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test fun `lets an admin user through the security gate`() {
        // pluginService is unmocked for this id, so createInstance returns null and the
        // controller replies 404 — proving the request passed the security filter and
        // reached the controller, which is what this test is verifying.
        mockMvc
            .post("/api/v1/plugin/entra/test-send") {
                contentType = MediaType.APPLICATION_JSON
                content = TEST_SEND_BODY
                with(user("admin").authorities(SimpleGrantedAuthority("ROLE_ADMIN")))
                with(csrf())
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test fun `rejects test-send for an unauthenticated request`() {
        // No AuthenticationEntryPoint configured on this minimal test chain, so an
        // unauthenticated request is denied as 403 rather than a 401 challenge — either way,
        // the request never reaches the controller.
        mockMvc
            .post("/api/v1/plugin/entra/test-send") {
                contentType = MediaType.APPLICATION_JSON
                content = TEST_SEND_BODY
                with(csrf())
            }.andExpect {
                status { isForbidden() }
            }
    }

    // Minimal SecurityFilterChain wiring GraphMailHttpSecurityConfigurer the same way
    // Valtimo's core composes all HttpSecurityConfigurer beans onto one shared HttpSecurity
    // in the real application, without pulling in the full (DB-backed) application context.
    @TestConfiguration
    class TestSecurityConfig {
        @Bean
        fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
            GraphMailHttpSecurityConfigurer().configure(http)
            http.authorizeHttpRequests { it.anyRequest().authenticated() }
            return http.build()
        }
    }
}
