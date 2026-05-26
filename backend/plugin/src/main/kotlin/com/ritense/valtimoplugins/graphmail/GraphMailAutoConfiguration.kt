/*
 * Copyright 2015-2025 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestTemplate
import java.time.Duration

// IMPORTANT: this file uses line comments only, on purpose. Kotlin block comments nest, so
// an Ant path pattern (such as the security matcher below, which ends with a double star)
// written inside a KDoc block comment would open an inner comment that is never closed and
// swallows the rest of the file. Keeping all doc text in line comments avoids that trap.
@ConfigurationProperties(prefix = "entra.plugin")
data class GraphMailProperties(
    val tokenBaseUrl: String = "https://login.microsoftonline.com",
    val graphBaseUrl: String = "https://graph.microsoft.com",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 30,
    // Token cache capacity per (tenantId+clientId) pair.
    // Increase when the deployment manages more than 64 distinct Entra app registrations.
    val maxCachedTokens: Int = 64,
)

@AutoConfiguration
@EnableConfigurationProperties(GraphMailProperties::class)
class GraphMailAutoConfiguration {

    private val logger = LoggerFactory.getLogger(GraphMailAutoConfiguration::class.java)

    // Fired once after the full application context is ready.
    // Reminds operators to size the job-executor thread pool correctly: the plugin's
    // retry backoff uses Thread.sleep(), which blocks the calling job-executor thread
    // for up to 30s (regular send) or 120s (upload-session flow for attachments > 3 MB).
    @EventListener(ApplicationReadyEvent::class)
    fun warnOnStartup() {
        logger.warn(
            "[Graph Mail Plugin] IMPORTANT: this plugin blocks Operaton job-executor threads during " +
            "retry backoff (up to 30s per send, 120s for large attachments). " +
            "Set operaton.bpm.job-executor.core-pool-size >= 20 and max-pool-size >= 50 " +
            "to prevent job-executor starvation under load. See documentation/plugin.md for details."
        )
    }

    @Bean
    @ConditionalOnMissingBean(GraphMailClient::class)
    fun graphMailClient(
        restTemplateBuilder: RestTemplateBuilder,
        objectMapper: ObjectMapper,
        properties: GraphMailProperties,
    ): GraphMailClient {
        val restTemplate = restTemplateBuilder
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .readTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
            .build()
            .also { configureJackson(it, objectMapper) }

        return GraphMailClientImpl(
            restTemplate,
            properties.tokenBaseUrl,
            properties.graphBaseUrl,
            properties.maxCachedTokens,
        )
    }

    @Bean
    @ConditionalOnMissingBean(GraphMailPluginFactory::class)
    fun graphMailPluginFactory(
        pluginService: PluginService,
        graphMailClient: GraphMailClient,
        resourceStorageService: TemporaryResourceStorageService,
        eventPublisher: ApplicationEventPublisher,
    ): GraphMailPluginFactory = GraphMailPluginFactory(pluginService, graphMailClient, resourceStorageService, eventPublisher)

    @Bean
    @ConditionalOnMissingBean(GraphMailTestSendController::class)
    fun graphMailTestSendController(
        graphMailClient: GraphMailClient,
        pluginService: PluginService,
        eventPublisher: ApplicationEventPublisher,
    ): GraphMailTestSendController = GraphMailTestSendController(graphMailClient, pluginService, eventPublisher)

    @Order(401)
    @Bean
    @ConditionalOnMissingBean(GraphMailHttpSecurityConfigurer::class)
    fun graphMailHttpSecurityConfigurer(): GraphMailHttpSecurityConfigurer = GraphMailHttpSecurityConfigurer()

    private fun configureJackson(restTemplate: RestTemplate, objectMapper: ObjectMapper) {
        restTemplate.messageConverters.removeIf { it is MappingJackson2HttpMessageConverter }
        restTemplate.messageConverters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
    }
}
