package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import java.time.Duration

// IMPORTANT: this file uses line comments only, on purpose. Kotlin block comments nest, so
// an Ant path pattern (such as the security matcher below, which ends with a double star)
// written inside a KDoc block comment would open an inner comment that is never closed and
// swallows the rest of the file. Keeping all doc text in line comments avoids that trap.
@AutoConfiguration
class GraphMailAutoConfiguration {

    private val logger = LoggerFactory.getLogger(GraphMailAutoConfiguration::class.java)

    // Fired once after the full application context is ready.
    // Reminds operators to size the job-executor thread pool correctly: the plugin's
    // retry backoff uses Thread.sleep(), which blocks the calling job-executor thread
    // for up to 30s (regular send) or 120s (upload-session flow for attachments > 2 MB).
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
    ): GraphMailClient {
        val restTemplate = restTemplateBuilder
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()
            .also { configureJackson(it, objectMapper) }

        return GraphMailClientImpl(RestClient.create(restTemplate))
    }

    @Bean
    @ConditionalOnMissingBean(GraphMailPluginFactory::class)
    fun graphMailPluginFactory(
        pluginService: PluginService,
        restTemplateBuilder: RestTemplateBuilder,
        objectMapper: ObjectMapper,
        resourceStorageService: TemporaryResourceStorageService,
        eventPublisher: ApplicationEventPublisher,
    ): GraphMailPluginFactory = GraphMailPluginFactory(
        pluginService, restTemplateBuilder, objectMapper, resourceStorageService, eventPublisher
    )

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
