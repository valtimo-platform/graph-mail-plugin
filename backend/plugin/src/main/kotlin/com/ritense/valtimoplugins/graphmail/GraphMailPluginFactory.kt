package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.PluginFactory
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.springframework.context.ApplicationEventPublisher

/**
 * Every collaborator here is a singleton that outlives the plugin instances this factory creates.
 * That is the point: Valtimo builds a fresh [GraphMailPlugin] per action invocation, so anything the
 * plugin owns itself is thrown away after one email — which is what previously made the HTTP client
 * (and its connection pool) useless, and would make the idempotency guard blind to earlier attempts.
 */
class GraphMailPluginFactory(
    pluginService: PluginService,
    private val graphMailClient: GraphMailClient,
    private val resourceStorageService: TemporaryResourceStorageService,
    private val eventPublisher: ApplicationEventPublisher,
    private val sendIdempotencyGuard: SendIdempotencyGuard,
    private val attachmentConcurrencyLimiter: AttachmentConcurrencyLimiter,
    private val metrics: GraphMailMetrics,
) : PluginFactory<GraphMailPlugin>(pluginService) {
    override fun create(): GraphMailPlugin =
        GraphMailPlugin(
            graphMailClient,
            resourceStorageService,
            eventPublisher,
            sendIdempotencyGuard,
            attachmentConcurrencyLimiter,
            metrics,
        )
}
