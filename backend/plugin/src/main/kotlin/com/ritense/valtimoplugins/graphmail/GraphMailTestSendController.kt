package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.service.PluginService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val RATE_LIMIT_INTERVAL_MS = 10_000L  // max 1 test-send per 10s per user

@RestController
@RequestMapping("/api/v1/plugin/entra")
class GraphMailTestSendController(
    private val graphMailClient: GraphMailClient,
    // PluginService hydrates the plugin instance with decrypted @PluginProperty(secret=true) values.
    // Reading clientSecret directly from PluginConfigurationRepository would yield AES ciphertext,
    // not the actual secret — causing every test-send to fail with a misleading 401.
    private val pluginService: PluginService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    private val logger = LoggerFactory.getLogger(GraphMailTestSendController::class.java)

    private val rateLimitStore = ConcurrentHashMap<String, AtomicLong>()

    // CAS-based check — atomically read and update in one step.
    private fun isRateLimited(username: String): Boolean {
        val now = System.currentTimeMillis()
        val tracker = rateLimitStore.computeIfAbsent(username) { AtomicLong(0) }
        val prev = tracker.get()
        if (now - prev < RATE_LIMIT_INTERVAL_MS) return true
        return !tracker.compareAndSet(prev, now)
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
                GraphMailTestSendResponse(false, "Ongeldig pluginConfigurationId — verwacht UUID-formaat", 400)
            )
        }
        if (!isValidEmail(request.recipient)) {
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(false, "Ongeldig ontvanger e-mailadres", 400)
            )
        }

        if (isRateLimited(authentication.name)) {
            logger.warn("Test send rate limited — user: {}", authentication.name)
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                GraphMailTestSendResponse(false, "Te veel verzoeken — wacht 10 seconden voor de volgende testmail", 429)
            )
        }

        val configIdStr = request.pluginConfigurationId
        val plugin: GraphMailPlugin? = try {
            pluginService.createInstance(PluginConfigurationId.existingId(UUID.fromString(configIdStr))) as? GraphMailPlugin
        } catch (ex: Exception) {
            logger.warn("Plugin configuration not found or failed to load for id: {}", configIdStr, ex)
            null
        }
        if (plugin == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                GraphMailTestSendResponse(false, "Plugin configuratie niet gevonden", 404)
            )
        }

        // The frontend always sends senderMailbox from the test-send form.
        // plugin.testSenderMailbox (stored as a @PluginProperty) acts as a fallback
        // so direct API callers or future integrations can omit the field.
        val testSender = request.senderMailbox.trim()
            .ifBlank { plugin.testSenderMailbox?.trim() ?: "" }

        if (testSender.isEmpty() || !isValidEmail(testSender)) {
            logger.warn("Test send rejected — invalid senderMailbox in request")
            return ResponseEntity.badRequest().body(
                GraphMailTestSendResponse(
                    false,
                    "Ongeldig afzender e-mailadres — vul een geldig e-mailadres in als afzender",
                    400
                )
            )
        }

        logger.info(
            "Test send requested — recipient: {}, mailbox: {}",
            maskEmail(request.recipient), maskEmail(testSender)
        )

        val sendStart = System.currentTimeMillis()
        return try {
            graphMailClient.sendMail(
                tenantId = plugin.tenantId,
                clientId = plugin.clientId,
                clientSecret = plugin.clientSecret,
                senderMailbox = testSender,
                toRecipients = listOf(GraphRecipient(GraphEmailAddress(address = request.recipient))),
                ccRecipients = emptyList(),
                bccRecipients = emptyList(),
                replyToRecipients = emptyList(),
                subject = "Testmail — Microsoft Graph Mail Plugin",
                bodyHtml = buildTestBody(testSender),
                attachments = emptyList(),
                saveToSentItems = false,
            )
            val durationMs = System.currentTimeMillis() - sendStart
            logger.info("Test send successful — recipient: {}", maskEmail(request.recipient))
            eventPublisher.publishEvent(
                GraphMailEmailSentEvent(
                    senderMailbox = testSender,
                    recipientCount = 1,
                    ccCount = 0,
                    bccCount = 0,
                    attachmentCount = 0,
                    durationMs = durationMs,
                )
            )
            ResponseEntity.ok(
                GraphMailTestSendResponse(
                    success = true,
                    message = "Testmail succesvol verzonden naar ${request.recipient}",
                    statusCode = 202,
                )
            )
        } catch (ex: GraphMailTokenExpiredException) {
            val message = "Authenticatie mislukt (401) — token geweigerd door Graph API, controleer Client Secret"
            logger.warn("Test send failed — {}", message)
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(GraphMailTestSendResponse(false, message, 401))
        } catch (ex: Exception) {
            val rawStatus = when (ex) {
                is GraphMailException      -> ex.statusCode
                is HttpClientErrorException  -> ex.statusCode.value()
                is HttpServerErrorException  -> ex.statusCode.value()
                else                         -> 500
            }
            val statusCode = if (rawStatus in 100..599) rawStatus else 500
            val message = when (statusCode) {
                400  -> "Ongeldige aanvraag (400) — controleer Tenant ID en Client ID"
                401  -> "Authenticatie mislukt (401) — controleer Tenant ID, Client ID en Client Secret"
                403  -> "Toegang geweigerd (403) — controleer of Mail.Send is toegekend in de Azure App Registration"
                429  -> "Te veel verzoeken (429) — probeer het over een moment opnieuw"
                503, 502, 504 -> "Azure / Graph API tijdelijk niet beschikbaar ($statusCode) — probeer het later opnieuw"
                else -> "Fout $statusCode: ${ex.message ?: "Onbekende fout"}"
            }
            logger.warn("Test send failed — status: {}", statusCode, ex)
            ResponseEntity.status(statusCode)
                .body(GraphMailTestSendResponse(false, message, statusCode))
        }
    }

    private fun buildTestBody(sender: String): String {
        val escapedSender = org.springframework.web.util.HtmlUtils.htmlEscape(sender)
        return """
            <html>
            <body style="font-family: Arial, sans-serif; color: #333; padding: 32px; max-width: 600px;">
              <div style="background: #003d82; padding: 20px 24px; border-radius: 6px 6px 0 0;">
                <h2 style="color: #fff; margin: 0; font-size: 18px">Testmail — Microsoft Graph Mail Plugin</h2>
              </div>
              <div style="border: 1px solid #ddd; border-top: none; padding: 24px; border-radius: 0 0 6px 6px;">
                <p>Dit is een <strong>testmail</strong> om te valideren dat de e-mailconfiguratie correct werkt.</p>
                <table style="border-collapse: collapse; width: 100%; margin: 16px 0;">
                  <tr style="background: #f5f5f5;">
                    <td style="padding: 8px 12px; font-weight: bold; width: 140px; border: 1px solid #e0e0e0">Naam</td>
                    <td style="padding: 8px 12px; border: 1px solid #e0e0e0">Pietje van Patje</td>
                  </tr>
                  <tr>
                    <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">E-mailadres</td>
                    <td style="padding: 8px 12px; border: 1px solid #e0e0e0">pietje@patje.nl</td>
                  </tr>
                  <tr style="background: #f5f5f5;">
                    <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">Zaaknummer</td>
                    <td style="padding: 8px 12px; border: 1px solid #e0e0e0">ZAK-2025-00001</td>
                  </tr>
                  <tr>
                    <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">Status</td>
                    <td style="padding: 8px 12px; border: 1px solid #e0e0e0">In behandeling</td>
                  </tr>
                </table>
                <p style="color: #666; font-size: 13px; margin-top: 24px;">
                  Als u dit bericht heeft ontvangen, zijn de credentials correct geconfigureerd en werkt
                  de verbinding met Microsoft Graph API.
                </p>
              </div>
              <p style="font-size: 11px; color: #aaa; margin-top: 16px; text-align: center;">
                Verzonden via Microsoft Graph API &middot; Graph Mail Plugin configuratietest &middot; ${'$'}escapedSender
              </p>
            </body>
            </html>
        """.trimIndent()
    }
}
