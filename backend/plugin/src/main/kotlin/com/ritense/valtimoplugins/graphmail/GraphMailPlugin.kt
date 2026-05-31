package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.resource.service.TemporaryResourceStorageService
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.time.Duration

private const val MAX_RECIPIENTS_PER_FIELD = 100
private const val MAX_RECIPIENTS_TOTAL = 200
private const val MAX_SUBJECT_LENGTH = 255
private const val MAX_BODY_CONTENT_BYTES = 5 * 1_048_576
// MAX_ATTACHMENTS / MAX_SINGLE_ATTACHMENT_BYTES / MAX_TOTAL_ATTACHMENT_BYTES live in
// GraphMailModels.kt — keeping a single source of truth (also referenced by the tests).

// jsoup allowlist tuned for transactional email: relaxed (formatting, tables, images),
// inline `style` attributes for layout. <style> blocks are excluded: CSS url()/@import
// can trigger external requests (GDPR tracking pixels) and load malicious stylesheets.
// data: URIs excluded from img src: SVG+script payload, SEG/DLP bypass. Use cid: or https:.
private val EMAIL_HTML_SAFELIST: Safelist = Safelist.relaxed()
    .addTags("center", "hr")
    .addAttributes(":all", "style", "class", "id", "title", "align", "bgcolor", "valign")
    .addAttributes("table", "border", "cellpadding", "cellspacing", "width")
    .addAttributes("td", "colspan", "rowspan", "width")
    .addAttributes("th", "colspan", "rowspan", "width")
    .addAttributes("img", "width", "height")
    .addProtocols("a", "href", "http", "https", "mailto", "tel")
    .addProtocols("img", "src", "http", "https", "cid")

private val EMAIL_OUTPUT_SETTINGS = org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)

private fun sanitizeHtml(html: String): String =
    Jsoup.clean(html, "", EMAIL_HTML_SAFELIST, EMAIL_OUTPUT_SETTINGS)

// Splits a plugin action property into individual string values.
// Supports three formats so process designers can use whichever is most convenient:
//   - single value:        "user@example.com"
//   - comma-separated:     "user1@example.com,user2@example.com"
//   - JSON array string:   ["user1@example.com","user2@example.com"]
internal fun parseStringListParam(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    val trimmed = value.trim()
    if (trimmed.startsWith("[")) {
        return trimmed.removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"").removeSurrounding("'").trim() }
            .filter { it.isNotBlank() }
    }
    return trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

private fun parseRecipients(values: List<String>, fieldName: String): List<GraphRecipient> {
    if (values.isEmpty()) return emptyList()
    require(values.size <= MAX_RECIPIENTS_PER_FIELD) {
        "Too many addresses in '$fieldName': ${values.size} (max $MAX_RECIPIENTS_PER_FIELD)"
    }
    return values.map { address ->
        requireNoControlChars(address, fieldName)
        require(isValidEmail(address)) { "Invalid email address in '$fieldName': '$address'" }
        GraphRecipient(GraphEmailAddress(address = address))
    }
}

@Plugin(
    key = "entra",
    title = "Microsoft Graph Mail Plugin",
    description = "Send emails via Microsoft Graph API with OAuth2 (Client Credentials)",
)
class GraphMailPlugin(
    private val restTemplateBuilder: RestTemplateBuilder,
    private val objectMapper: ObjectMapper,
    private val resourceStorageService: TemporaryResourceStorageService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // Only set by the internal test constructor — overrides the lazy-built client.
    private var clientOverride: GraphMailClient? = null

    // Initialized lazily so that @PluginProperty values (tokenBaseUrl, graphBaseUrl,
    // connectTimeoutSeconds, readTimeoutSeconds) are already injected by Valtimo before
    // the first sendEmail() call triggers this block.
    private val client: GraphMailClient by lazy {
        clientOverride ?: run {
            val rt = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build()
            rt.messageConverters.removeIf { it is MappingJackson2HttpMessageConverter }
            rt.messageConverters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
            GraphMailClientImpl(RestClient.create(rt), tokenBaseUrl, graphBaseUrl)
        }
    }

    // Unit-test backdoor: avoids needing a real RestTemplateBuilder / ObjectMapper in tests.
    internal constructor(
        graphMailClient: GraphMailClient,
        resourceStorageService: TemporaryResourceStorageService,
        eventPublisher: ApplicationEventPublisher,
    ) : this(RestTemplateBuilder(), ObjectMapper(), resourceStorageService, eventPublisher) {
        this.clientOverride = graphMailClient
    }

    private val logger = LoggerFactory.getLogger(GraphMailPlugin::class.java)

    // Dedicated audit logger — configure appenders/log levels separately in logback.xml if needed.
    // Example filter: <logger name="entra.plugin.audit" level="INFO" additivity="false">
    private val auditLogger = LoggerFactory.getLogger("entra.plugin.audit")

    @PluginProperty(key = "tenantId", secret = false, required = true)
    lateinit var tenantId: String

    @PluginProperty(key = "clientId", secret = false, required = true)
    lateinit var clientId: String

    @PluginProperty(key = "clientSecret", secret = true, required = true)
    lateinit var clientSecret: String

    @PluginProperty(key = "testSenderMailbox", secret = false, required = false)
    var testSenderMailbox: String? = null

    @PluginProperty(key = "tokenBaseUrl", secret = false, required = false)
    var tokenBaseUrl: String = "https://login.microsoftonline.com"

    @PluginProperty(key = "graphBaseUrl", secret = false, required = false)
    var graphBaseUrl: String = "https://graph.microsoft.com"

    @PluginProperty(key = "connectTimeoutSeconds", secret = false, required = false)
    var connectTimeoutSeconds: Long = 10

    @PluginProperty(key = "readTimeoutSeconds", secret = false, required = false)
    var readTimeoutSeconds: Long = 30

    // NOTE: USER_TASK_CREATE fires when a user task is committed to the database.
    // If the surrounding Operaton transaction rolls back and retries (e.g. optimistic lock),
    // the email may be sent again before the task appears in the UI. Accept this risk, or
    // use an idempotency token stored in a process variable to deduplicate on the receiver side.
    @PluginAction(
        key = "send-email",
        title = "Send email",
        description = "Send an email via Microsoft Graph API",
        activityTypes = [
            ActivityTypeWithEventName.SERVICE_TASK_START,
        ],
    )
    fun sendEmail(
        @Suppress("UNUSED_PARAMETER") execution: DelegateExecution,
        @PluginActionProperty senderMailbox: String,
        @PluginActionProperty recipients: String,
        @PluginActionProperty cc: String?,
        @PluginActionProperty bcc: String?,
        @PluginActionProperty replyTo: String?,
        @PluginActionProperty subject: String,
        @PluginActionProperty contentId: String,
        @PluginActionProperty attachmentIds: String?,
    ) {
        // Guard against misconfigured or partially-migrated plugin instances where Valtimo
        // failed to inject one of the required properties — gives a clear diagnostic instead
        // of an opaque UninitializedPropertyAccessException from the lateinit field.
        check(::tenantId.isInitialized && tenantId.isNotBlank()) {
            "Plugin property 'tenantId' is not configured — check the Graph Mail plugin configuration"
        }
        check(::clientId.isInitialized && clientId.isNotBlank()) {
            "Plugin property 'clientId' is not configured — check the Graph Mail plugin configuration"
        }
        check(::clientSecret.isInitialized && clientSecret.isNotBlank()) {
            "Plugin property 'clientSecret' is not configured — check the Graph Mail plugin configuration"
        }

        // Header injection guard — same fields the frontend already validates,
        // re-checked server-side for defense in depth.
        requireNoControlChars(senderMailbox, "senderMailbox")
        requireNoControlChars(subject, "subject")

        require(isValidEmail(senderMailbox)) { "Invalid sender email: '$senderMailbox'" }

        require(subject.isNotBlank()) { "Email subject must not be blank" }
        require(subject.length <= MAX_SUBJECT_LENGTH) {
            "Email subject exceeds $MAX_SUBJECT_LENGTH characters (${subject.length})"
        }
        require(isValidResourceId(contentId)) { "Invalid contentId: '$contentId' — must not be blank or contain path-traversal sequences" }

        val toRecipients = parseRecipients(parseStringListParam(recipients), "recipients")
        val ccRecipients = parseRecipients(parseStringListParam(cc), "cc")
        val bccRecipients = parseRecipients(parseStringListParam(bcc), "bcc")
        val replyToRecipients = parseRecipients(parseStringListParam(replyTo), "replyTo")

        // replyTo addresses are not delivery recipients — they do not receive the message.
        val totalRecipients = toRecipients.size + ccRecipients.size + bccRecipients.size
        require(totalRecipients <= MAX_RECIPIENTS_TOTAL) {
            "Total addresses across to/cc/bcc exceed $MAX_RECIPIENTS_TOTAL (got $totalRecipients)"
        }
        require(toRecipients.isNotEmpty()) { "At least one recipient (To) is required" }

        val bodyHtml = resolveBodyContent(contentId)
        val safeBodyHtml = sanitizeHtml(bodyHtml)
        require(safeBodyHtml.isNotBlank()) {
            "Email body became empty after sanitisation — check the HTML content stored at '$contentId'"
        }

        val attachments = resolveAttachments(parseStringListParam(attachmentIds).ifEmpty { null })

        logger.debug("Preparing email — to: {} addresses, from: '{}', subject length: {}",
            toRecipients.size, maskEmail(senderMailbox), subject.length)

        val auditStart = System.currentTimeMillis()
        try {
            client.sendMail(
                tenantId = tenantId,
                clientId = clientId,
                clientSecret = clientSecret,
                senderMailbox = senderMailbox,
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
                replyToRecipients = replyToRecipients,
                subject = subject,
                bodyHtml = safeBodyHtml,
                attachments = attachments,
                saveToSentItems = true,
            )
            val durationMs = System.currentTimeMillis() - auditStart
            auditLogger.info(
                "SEND_OK sender={} to={} cc={} bcc={} subject_len={} attachments={} duration_ms={}",
                maskEmail(senderMailbox),
                maskEmails(toRecipients.map { it.emailAddress.address }),
                maskEmails(ccRecipients.map { it.emailAddress.address }),
                maskEmails(bccRecipients.map { it.emailAddress.address }),
                subject.length,
                attachments.size,
                durationMs,
            )
            publishEventSafely(GraphMailEmailSentEvent(
                senderMailbox = maskEmail(senderMailbox),
                recipientCount = toRecipients.size,
                ccCount = ccRecipients.size,
                bccCount = bccRecipients.size,
                attachmentCount = attachments.size,
                durationMs = durationMs,
            ))
        } catch (ex: Exception) {
            val durationMs = System.currentTimeMillis() - auditStart
            auditLogger.warn(
                "SEND_FAIL sender={} to={} subject_len={} duration_ms={} error={}",
                maskEmail(senderMailbox),
                maskEmails(toRecipients.map { it.emailAddress.address }),
                subject.length,
                durationMs,
                ex.message,
            )
            publishEventSafely(GraphMailEmailFailedEvent(
                senderMailbox = maskEmail(senderMailbox),
                recipientCount = toRecipients.size,
                reason = (ex.message ?: ex.javaClass.simpleName)
                    .replace(EMAIL_IN_TEXT_REGEX) { maskEmail(it.value) },
                durationMs = durationMs,
            ))
            throw ex
        }
    }

    private fun publishEventSafely(event: Any) {
        try {
            eventPublisher.publishEvent(event)
        } catch (ex: Exception) {
            logger.warn("Event listener threw for {} — email result unaffected", event::class.simpleName, ex)
        }
    }

    private fun resolveAttachments(attachmentIds: List<String>?): List<ResolvedAttachment> {
        if (attachmentIds.isNullOrEmpty()) return emptyList()

        val ids = attachmentIds.map { it.trim() }.filter { it.isNotBlank() }

        require(ids.size <= MAX_ATTACHMENTS) {
            "Too many attachments: ${ids.size} (max $MAX_ATTACHMENTS)"
        }

        var totalBytes = 0L
        return ids.map { resourceId ->
            require(isValidResourceId(resourceId)) {
                "Invalid attachment ID: '$resourceId' — must not be blank or contain path-traversal sequences"
            }

            val metadata = resourceStorageService.getResourceMetadata(resourceId)
            val fileName = metadata["fileName"] as? String ?: resourceId
            val contentType = metadata["contentType"] as? String ?: "application/octet-stream"

            val raw = resourceStorageService.getResourceContentAsInputStream(resourceId)
                ?: throw GraphMailException("Attachment '$resourceId' not found in temporary storage")

            // Read with a hard cap so a single oversized blob doesn't blow up the heap.
            val rawBytes = raw.use { it.readNBytesCapped(MAX_SINGLE_ATTACHMENT_BYTES + 1L) }
            require(rawBytes.size <= MAX_SINGLE_ATTACHMENT_BYTES) {
                "Attachment '$fileName' exceeds ${MAX_SINGLE_ATTACHMENT_BYTES / (1024 * 1024)} MB " +
                "(${rawBytes.size} bytes)."
            }
            totalBytes += rawBytes.size
            require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) {
                "Total attachment size exceeds ${MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024)} MB ($totalBytes bytes)."
            }

            logger.debug("Attachment resolved: name='{}', type='{}', size={}",
                fileName, contentType, rawBytes.size)
            ResolvedAttachment(name = fileName, contentType = contentType, rawBytes = rawBytes)
        }
    }

    private fun resolveBodyContent(contentId: String): String {
        val stream = resourceStorageService.getResourceContentAsInputStream(contentId)
            ?: throw GraphMailException("Body content '$contentId' not found in temporary storage")
        val bytes = stream.use { it.readNBytesCapped(MAX_BODY_CONTENT_BYTES + 1L) }
        require(bytes.size <= MAX_BODY_CONTENT_BYTES) {
            "Body content '$contentId' exceeds maximum allowed size of $MAX_BODY_CONTENT_BYTES bytes"
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

// Reads up to `cap + 1` bytes from an InputStream — the extra byte lets callers detect
// an overflow via `bytes.size > cap` without reading the entire oversized input into memory.
private fun java.io.InputStream.readNBytesCapped(cap: Long): ByteArray {
    val limit = cap + 1
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (total < limit) {
        val toRead = minOf(buffer.size.toLong(), limit - total).toInt()
        val n = read(buffer, 0, toRead)
        if (n == -1) break
        total += n
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
