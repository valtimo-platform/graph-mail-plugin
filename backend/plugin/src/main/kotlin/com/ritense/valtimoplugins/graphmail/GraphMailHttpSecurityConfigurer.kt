package com.ritense.valtimoplugins.graphmail

import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity

class GraphMailHttpSecurityConfigurer : HttpSecurityConfigurer {

    override fun configure(http: HttpSecurity) {
        http.authorizeHttpRequests { requests ->
            requests.requestMatchers(HttpMethod.POST, "/api/v1/plugin/entra/test-send")
                .hasAuthority("ROLE_ADMIN")
        }
    }
}
