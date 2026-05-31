package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.PluginFactory
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.ApplicationEventPublisher

class GraphMailPluginFactory(
    pluginService: PluginService,
    private val restTemplateBuilder: RestTemplateBuilder,
    private val objectMapper: ObjectMapper,
    private val resourceStorageService: TemporaryResourceStorageService,
    private val eventPublisher: ApplicationEventPublisher,
) : PluginFactory<GraphMailPlugin>(pluginService) {

    override fun create(): GraphMailPlugin =
        GraphMailPlugin(restTemplateBuilder, objectMapper, resourceStorageService, eventPublisher)
}
