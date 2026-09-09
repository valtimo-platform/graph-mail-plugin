package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

// IMPORTANT: this file uses line comments only, on purpose. Kotlin block comments nest, so
// an Ant path pattern (such as the security matcher below, which ends with a double star)
// written inside a KDoc block comment would open an inner comment that is never closed and
// swallows the rest of the file. Keeping all doc text in line comments avoids that trap.
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(GraphMailHttpProperties::class)
class GraphMailAutoConfiguration {
    private val logger = LoggerFactory.getLogger(GraphMailAutoConfiguration::class.java)

    // Fired once after the full application context is ready.
    //
    // Reminds operators to size the job-executor thread pool. Retry backoff itself is no longer
    // the concern it once was: MAX_IN_CALL_WAIT_MS caps in-call sleeping at 2s, after which the
    // send is handed back to the job executor rather than holding its thread. What still occupies
    // a thread is the work itself — the Graph round-trip, and for attachments above 2 MiB the
    // chunked upload of the whole payload. A pool sized for a handful of jobs starves on that.
    //
    // The property is `operaton.bpm.job-execution` (not `job-executor`): the Operaton starter
    // binds JobExecutionProperty under that name, and Spring drops an unknown key silently — a
    // misspelled setting leaves the engine on its 3/10 defaults with no warning at all.
    @EventListener(ApplicationReadyEvent::class)
    fun warnOnStartup() {
        logger.warn(
            "[Graph Mail Plugin] IMPORTANT: sending occupies an Operaton job-executor thread for " +
                "the duration of the Graph call, and for attachments above 2 MiB for the duration " +
                "of the chunked upload. " +
                "Set operaton.bpm.job-execution.core-pool-size >= 20 and max-pool-size >= 50 " +
                "(note: job-execution, not job-executor — an unknown key is ignored silently) " +
                "to prevent job-executor starvation under load, and configure a " +
                "failedJobRetryTimeCycle on the send-email service task. " +
                "See documentation/developer.md for details.",
        )
    }

    // ONE pooled client for the whole application, shared by the plugin action path and the
    // test-send endpoint alike.
    //
    // The JDK's own HttpClient is used rather than Apache HttpClient5 on purpose: it pools
    // connections out of the box and ships with the JVM, so the plugin does not have to assume
    // anything about which HTTP library the surrounding GZAC application happens to put on the
    // classpath. RestTemplateBuilder.build() would fall back to an unpooled
    // SimpleClientHttpRequestFactory when no third-party client is present — and, built per plugin
    // instance as it was before, there was nothing to pool in the first place.
    @Bean
    @Qualifier("graphMailRestClient")
    @ConditionalOnMissingBean(name = ["graphMailRestClient"])
    fun graphMailRestClient(
        objectMapper: ObjectMapper,
        properties: GraphMailHttpProperties,
    ): RestClient {
        val httpClient = graphHttpClient(properties)

        val requestFactory =
            JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
            }

        return RestClient
            .builder()
            .requestFactory(requestFactory)
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
            }.build()
    }

    // Extracted so a test can assert on the client itself: the regression this guards against was a
    // missing ProxySelector, and that is only visible on the HttpClient — not on the RestClient
    // built around it.
    internal fun graphHttpClient(properties: GraphMailHttpProperties): HttpClient {
        val proxySelector = proxySelectorFor(properties)
        logProxyInUse(proxySelector, properties)
        return HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            // Pinned to HTTP/1.1 deliberately. The JDK client defaults to HTTP/2, which would make
            // adding pooling change the wire protocol as a side effect — the RestTemplate this
            // replaced spoke HTTP/1.1. Connection reuse comes from keep-alive and works the same on
            // 1.1, while HTTP/2 adds a variable that egress proxies in government networks do not
            // always handle.
            .version(HttpClient.Version.HTTP_1_1)
            // Egress in a government network almost always runs through a forward proxy. The JDK
            // client installs no ProxySelector of its own — not even when https.proxyHost is set —
            // so without this every send fails at connect, and neverLeftTheClient() reports it as
            // transient: the job executor then retries forever on something that can never succeed.
            // The RestTemplate this replaced went through HttpURLConnection, which honoured those
            // JVM properties, so leaving it out was a silent regression.
            .proxy(proxySelector)
            .build()
    }

    // An explicitly configured proxy wins; otherwise fall back to whatever the JVM already knows.
    internal fun proxySelectorFor(properties: GraphMailHttpProperties): ProxySelector {
        val host = properties.proxyHost
        if (host == null) {
            // ProxySelector.getDefault() is nullable — ProxySelector.setDefault(null) is a public
            // API, and this plugin runs inside somebody else's application. Passing null to
            // HttpClient.Builder.proxy() throws, which fails bean creation and stops the whole
            // application from starting over a proxy setting.
            return ProxySelector.getDefault() ?: HttpClient.Builder.NO_PROXY
        }

        // proxyPort is guaranteed non-null here: GraphMailHttpProperties rejects a host without one.
        val port = requireNotNull(properties.proxyPort)
        val proxy = ProxySelector.of(InetSocketAddress.createUnresolved(host, port))
        val bypass = properties.nonProxyHosts
        return if (bypass.isNullOrBlank()) proxy else BypassingProxySelector(proxy, bypass)
    }

    // Logged because a proxy that is silently absent, or silently different from what the operator
    // intended, is indistinguishable from a network outage in the logs — and documentation/developer.md
    // sends administrators here first when sends fail at connect.
    //
    // Asks the selector what it would actually do with the Graph endpoint, rather than reading
    // https.proxyHost back out of the system properties. Those two can disagree: the host
    // application may have installed its own ProxySelector.setDefault(...), or set only
    // http.proxyHost. A diagnostic line that reports "no proxy" while the client is in fact
    // proxying sends the reader somewhere else entirely, which is worse than printing nothing.
    private fun logProxyInUse(
        selector: ProxySelector,
        properties: GraphMailHttpProperties,
    ) {
        val resolved =
            runCatching {
                selector
                    .select(URI.create(properties.graphBaseUrl))
                    .firstOrNull { it.type() != Proxy.Type.DIRECT }
            }.getOrNull()

        val source = if (properties.proxyHost != null) "graph-mail.http.proxy-host" else "the JVM's proxy settings"
        if (resolved == null) {
            logger.info(
                "[Graph Mail Plugin] No outbound proxy for {}; connecting to Graph directly.",
                properties.graphBaseUrl,
            )
        } else {
            logger.info(
                "[Graph Mail Plugin] Graph traffic to {} goes through proxy {} (from {}).",
                properties.graphBaseUrl,
                resolved.address(),
                source,
            )
        }
    }

    // Requires the client secret to be re-entered whenever the sender allowlist changes — the
    // allowlist bounds which mailboxes Mail.Send may be used for, so widening it is a privilege
    // escalation. See the class doc for why this has to be an aspect and not a plugin event.
    //
    // Guarded by a property so an operator can switch it off deliberately if it ever blocks them,
    // rather than being tempted to patch it out. Off is a real reduction in protection, so it has
    // to be an explicit, visible choice.
    @Bean
    @ConditionalOnProperty(
        prefix = "graph-mail",
        name = ["require-secret-for-allowlist-change"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(AllowedSendersChangeGuard::class)
    fun allowedSendersChangeGuard(
        pluginConfigurationRepository: PluginConfigurationRepository,
    ): AllowedSendersChangeGuard = AllowedSendersChangeGuard(pluginConfigurationRepository)

    // Deliberately tied to the same condition as the guard: when the guard is on, its absence must
    // fail startup; when an operator has switched it off, there is nothing to verify.
    @Bean
    @ConditionalOnProperty(
        prefix = "graph-mail",
        name = ["require-secret-for-allowlist-change"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(GraphMailGuardStartupCheck::class)
    fun graphMailGuardStartupCheck(pluginService: PluginService): GraphMailGuardStartupCheck =
        GraphMailGuardStartupCheck(pluginService)

    // Registered as its own bean rather than a listener on this class, mirroring
    // GraphMailGuardStartupCheck: this class is instantiated directly in tests and must stay
    // no-arg. See the class doc for why a loud line, and not a refusal to start.
    @Bean
    @ConditionalOnMissingBean(GraphMailEndpointAllowlistWarning::class)
    fun graphMailEndpointAllowlistWarning(properties: GraphMailHttpProperties): GraphMailEndpointAllowlistWarning =
        GraphMailEndpointAllowlistWarning(properties)

    // Single shared instance — see GraphTokenCache's class doc for why the cache must be a
    // bean rather than something each GraphMailClientImpl owns: Valtimo hydrates a fresh
    // GraphMailPlugin per action invocation, so an instance-owned cache never accumulated hits.
    @Bean
    @ConditionalOnMissingBean(GraphTokenCache::class)
    fun graphTokenCache(): GraphTokenCache = GraphTokenCache()

    // Single shared instance for the same reason as graphTokenCache() above: a fresh
    // GraphMailPlugin per action invocation means an instance-owned guard would never see the
    // marker left by an earlier attempt of the same activity. See SendIdempotencyGuard's class
    // doc for what failure mode this does and does not protect against.
    // Where "already sent" is remembered. The default is in-memory and therefore per-JVM: on a
    // multi-node GZAC a retry picked up by another node has never heard of the marker, so the guard
    // offers no protection there. Registering it separately means a deployment that needs the
    // guarantee can supply a durable store — one committing outside the surrounding transaction —
    // without replacing the guard's locking and eviction as well. See SentMarkerStore.
    @Bean
    @ConditionalOnMissingBean(SentMarkerStore::class)
    fun sentMarkerStore(): SentMarkerStore = InMemorySentMarkerStore()

    @Bean
    @ConditionalOnMissingBean(SendIdempotencyGuard::class)
    fun sendIdempotencyGuard(sentMarkerStore: SentMarkerStore): SendIdempotencyGuard =
        SendIdempotencyGuard(sentMarkerStore)

    // Must be a single instance to mean anything — a per-invocation limiter would hand every
    // caller its own full set of permits and cap nothing at all.
    @Bean
    @ConditionalOnMissingBean(AttachmentConcurrencyLimiter::class)
    fun attachmentConcurrencyLimiter(properties: GraphMailHttpProperties): AttachmentConcurrencyLimiter =
        AttachmentConcurrencyLimiter(
            permits = properties.attachmentConcurrency,
            acquireTimeoutMs = properties.attachmentAcquireTimeoutSeconds * 1000,
        )

    @Bean
    @ConditionalOnMissingBean(GraphMailClient::class)
    fun graphMailClient(
        // Qualified rather than resolved by type or parameter name: this plugin runs inside a host
        // application that may well define its own RestClient, and a @Primary one there would
        // otherwise be injected here — silently sending Graph traffic through a client with
        // different timeouts, redirect handling and converters.
        @Qualifier("graphMailRestClient") graphMailRestClient: RestClient,
        graphTokenCache: GraphTokenCache,
        properties: GraphMailHttpProperties,
    ): GraphMailClient =
        GraphMailClientImpl(
            restClient = graphMailRestClient,
            tokenBaseUrl = properties.tokenBaseUrl,
            graphBaseUrl = properties.graphBaseUrl,
            tokenCache = graphTokenCache,
            uploadHostSuffixes = properties.uploadHostSuffixes(),
        )

    // ObjectProvider rather than a nullable parameter: it resolves lazily and to nothing at all
    // when Micrometer is absent, which is exactly the "metrics are optional" contract. The
    // @ConditionalOnClass guard is what keeps this method from being loaded at all in that case —
    // its signature mentions MeterRegistry, and a missing class on a bean method signature is a
    // NoClassDefFoundError at context startup, not a quietly skipped bean.
    @Bean
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry::class)
    @ConditionalOnMissingBean(GraphMailMetrics::class)
    fun graphMailMetrics(
        meterRegistry: ObjectProvider<io.micrometer.core.instrument.MeterRegistry>,
        attachmentConcurrencyLimiter: AttachmentConcurrencyLimiter,
        graphTokenCache: GraphTokenCache,
    ): GraphMailMetrics =
        GraphMailMetrics(meterRegistry.getIfAvailable()).also {
            it.bindAttachmentLimiter(attachmentConcurrencyLimiter)
            it.bindTokenCache(graphTokenCache)
        }

    // The fallback for a host application without Micrometer on the classpath. Every method on it
    // is a no-op, so the plugin behaves identically minus the meters.
    @Bean
    @ConditionalOnMissingBean(GraphMailMetrics::class)
    fun graphMailMetricsDisabled(): GraphMailMetrics = GraphMailMetrics(null)

    @Bean
    @ConditionalOnMissingBean(GraphMailPluginFactory::class)
    fun graphMailPluginFactory(
        pluginService: PluginService,
        graphMailClient: GraphMailClient,
        resourceStorageService: TemporaryResourceStorageService,
        eventPublisher: ApplicationEventPublisher,
        sendIdempotencyGuard: SendIdempotencyGuard,
        attachmentConcurrencyLimiter: AttachmentConcurrencyLimiter,
        metrics: GraphMailMetrics,
    ): GraphMailPluginFactory =
        GraphMailPluginFactory(
            pluginService,
            graphMailClient,
            resourceStorageService,
            eventPublisher,
            sendIdempotencyGuard,
            attachmentConcurrencyLimiter,
            metrics,
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
}
