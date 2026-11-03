package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val RATE_LIMIT_INTERVAL_MS = 10_000L // max 1 test-send per 10s per user
private const val RATE_LIMIT_STORE_MAX_ENTRIES = 1_000

@RestController
@RequestMapping("/api/v1/plugin/entra")
class GraphMailTestSendController(
    private val graphMailClient: GraphMailClient,
    // Creates the plugin instance of the configuration, as the process action gets it.
    private val pluginService: PluginService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(GraphMailTestSendController::class.java)

    private val rateLimitStore = ConcurrentHashMap<String, AtomicLong>()

    // CAS-based check — atomically read and update in one step.
    private fun isRateLimited(username: String): Boolean {
        val now = System.currentTimeMillis()
        evictStaleRateLimitEntriesIfNeeded(now)
        val tracker = rateLimitStore.computeIfAbsent(username) { AtomicLong(0) }
        val prev = tracker.get()
        if (now - prev < RATE_LIMIT_INTERVAL_MS) return true
        return !tracker.compareAndSet(prev, now)
    }

    // rateLimitStore only grows (one entry per distinct username that has ever called
    // test-send) and never shrinks on its own. Once it gets large, sweep out entries whose
    // last request already fell outside the rate-limit window — they're not being rate-limited
    // by anything anymore — so a long-running instance doesn't leak memory one admin at a time.
    private fun evictStaleRateLimitEntriesIfNeeded(now: Long) {
        if (rateLimitStore.size < RATE_LIMIT_STORE_MAX_ENTRIES) return
        rateLimitStore.entries.removeIf { now - it.value.get() > RATE_LIMIT_INTERVAL_MS }
    }

    // Admin-only: this endpoint sends real email using production credentials.
    // Access is enforced at the HTTP security layer via GraphMailHttpSecurityConfigurer.
    @PostMapping("/test-send")
    fun testSend(
        @RequestBody request: GraphMailTestSendRequest,
        authentication: Authentication,
    ): ResponseEntity<GraphMailTestSendResponse> {
        if (!isValidUuid(request.pluginConfigurationId)) {
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(false, "Ongeldig pluginConfigurationId — verwacht UUID-formaat", 400),
            )
        }
        if (!isValidEmail(request.recipient)) {
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(false, "Ongeldig ontvanger e-mailadres", 400),
            )
        }

        if (isRateLimited(authentication.name)) {
            logger.warn("Test send rate limited — user: {}", authentication.name)
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                GraphMailTestSendResponse(
                    false,
                    "Te veel verzoeken — wacht 10 seconden voor de volgende testmail",
                    429,
                ),
            )
        }

        val configIdStr = request.pluginConfigurationId
        val plugin: GraphMailPlugin? =
            try {
                pluginService.createInstance(
                    PluginConfigurationId.existingId(UUID.fromString(configIdStr)),
                ) as? GraphMailPlugin
            } catch (ex: Exception) {
                logger.warn("Plugin configuration not found or failed to load for id: {}", configIdStr, ex)
                null
            }
        if (plugin == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                GraphMailTestSendResponse(false, "Plugin configuratie niet gevonden", 404),
            )
        }

        // The frontend always sends senderMailbox from the test-send form.
        // plugin.testSenderMailbox (stored as a @PluginProperty) acts as a fallback
        // so direct API callers or future integrations can omit the field.
        val testSender =
            request.senderMailbox
                .trim()
                .ifBlank { plugin.testSenderMailbox?.trim() ?: "" }

        if (testSender.isEmpty() || !isValidEmail(testSender)) {
            logger.warn("Test send rejected — invalid senderMailbox in request")
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(
                    false,
                    "Ongeldig afzender e-mailadres — vul een geldig e-mailadres in als afzender",
                    400,
                ),
            )
        }

        // The sender allowlist applies to test sends too — an admin must not be able to
        // send as an arbitrary tenant mailbox either. Empty list = deny-all (strict).
        val allowlist = plugin.allowedSendersList()
        if (allowlist.isEmpty()) {
            logger.warn(
                "Test send rejected — 'allowedSenders' is not configured for plugin configuration {}",
                configIdStr,
            )
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(
                    false,
                    "De pluginconfiguratie heeft geen 'allowedSenders' (afzender-whitelist) — " +
                        "vul de toegestane afzendermailboxen in en sla de configuratie op",
                    400,
                ),
            )
        }
        if (!isSenderAllowed(testSender, allowlist)) {
            logger.warn("Test send rejected — sender {} not on the allowedSenders allowlist", maskEmail(testSender))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                GraphMailTestSendResponse(
                    false,
                    "Afzender staat niet op de 'allowedSenders' whitelist van deze pluginconfiguratie",
                    403,
                ),
            )
        }

        logger.info(
            "Test send requested — recipient: {}, mailbox: {}",
            maskEmail(request.recipient),
            maskEmail(testSender),
        )

        // The login of the configuration, as on the process action path. One that can't be used is a
        // configuration problem, reported with its own message. Anything else (the certificates, creating the
        // Http Client Authentication instance) is reported like a failed send, not as an unhandled 500.
        val login =
            try {
                plugin.authentication()
            } catch (ex: IllegalStateException) {
                logger.warn("Test send rejected — login: {}", ex.message)
                return ResponseEntity
                    .badRequest()
                    .body(GraphMailTestSendResponse(false, "Aanmelden niet mogelijk — ${ex.message}", 400))
            } catch (ex: Exception) {
                return failure(ex)
            }

        val sendStart = System.currentTimeMillis()
        return try {
            graphMailClient.sendMail(
                authentication = login,
                mail =
                    OutboundMail(
                        senderMailbox = testSender,
                        toRecipients = listOf(GraphRecipient(GraphEmailAddress(address = request.recipient))),
                        subject = "Testmail — Microsoft Graph Mail Plugin",
                        bodyHtml = buildTestMailBody(testSender),
                        saveToSentItems = false,
                    ),
            )
            val durationMs = System.currentTimeMillis() - sendStart
            logger.info("Test send successful — recipient: {}", maskEmail(request.recipient))
            eventPublisher.publishEvent(
                GraphMailEmailSentEvent(
                    senderMailbox = maskEmail(testSender),
                    recipientCount = 1,
                    ccCount = 0,
                    bccCount = 0,
                    attachmentCount = 0,
                    durationMs = durationMs,
                ),
            )
            ResponseEntity.ok(
                GraphMailTestSendResponse(
                    success = true,
                    message = "Testmail succesvol verzonden naar ${request.recipient}",
                    statusCode = 202,
                ),
            )
        } catch (ex: Exception) {
            failure(ex)
        }
    }

    // A Graph Mail failure says what to check itself (the login, the certificates, the permission); anything else gets
    // its status. Masked: the message can carry mailbox addresses, and it goes straight into the admin UI.
    private fun failure(ex: Exception): ResponseEntity<GraphMailTestSendResponse> {
        val rawStatus = (ex as? GraphMailException)?.statusCode ?: 500
        val statusCode = if (rawStatus in 100..599) rawStatus else 500
        // Deliberately not an HTTP 401 for an upstream 401: that is Graph or the token endpoint rejecting
        // *our* login, not the admin's session — and a 401 on the wire makes the frontend's auth
        // interceptor log the administrator out. The upstream status stays visible in the body.
        val httpStatus = if (statusCode == 401) 502 else statusCode
        val message =
            when (ex) {
                is GraphMailCertificateException -> "Certificaten — ${maskEmailsInText(ex.message)}"
                is GraphMailException -> "Verzenden mislukt ($statusCode) — ${maskEmailsInText(ex.message)}"
                else -> "Fout $statusCode: ${maskEmailsInText(ex.message) ?: "Onbekende fout"}"
            }
        // Deliberately not passing `ex` itself: its message (and the URIs inside it) can carry
        // the sender mailbox, and a logged throwable prints that message alongside the stack
        // trace — undoing the masking applied to the response.
        logger.warn(
            "Test send failed — status: {}, type: {}, cause: {}",
            statusCode,
            ex.javaClass.simpleName,
            maskEmailsInText(ex.message),
        )
        return ResponseEntity
            .status(httpStatus)
            .body(GraphMailTestSendResponse(false, message, statusCode))
    }
}
