package com.ritense.valtimoplugins.graphmail

import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.io.ByteArrayOutputStream

// Prefix for the per-activity pass counter the duplicate guard keys on. Namespaced so it cannot
// collide with process data, and prefixed rather than a single variable so two send-email tasks in
// one process count independently.
internal const val PASS_COUNTER_VARIABLE_PREFIX = "graphMailPass_"

// Used when a delegate is invoked without activity context (custom harnesses, non-standard
// invocation paths). Keeps the key well-formed instead of embedding "null".
private const val NO_ACTIVITY_ID = "unknown-activity"

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
private val EMAIL_HTML_SAFELIST: Safelist =
    Safelist
        .relaxed()
        .addTags("center", "hr")
        .addAttributes(":all", "style", "class", "id", "title", "align", "bgcolor", "valign")
        .addAttributes("table", "border", "cellpadding", "cellspacing", "width")
        .addAttributes("td", "colspan", "rowspan", "width")
        .addAttributes("th", "colspan", "rowspan", "width")
        .addAttributes("img", "width", "height")
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        // No "http" for img src, deliberately. An <img> in a transactional email is fetched by the
        // recipient's mail client the moment the message is opened, so a plaintext fetch tells a
        // third party — and every hop in between — when a citizen read their correspondence, over a
        // connection nobody can vouch for.
        //
        // A logo still works, and both of the ways anyone actually embeds one are untouched:
        //   - "cid:" is the embedded attachment. No external fetch at all, and the right way to put
        //     a logo in an email this plugin sends.
        //   - "https" stays because a self-hosted image is legitimate use of <img>, and blocking it
        //     would break real templates.
        // Only a plain-http source loses its src, so an existing template with an http-hosted logo
        // has to move it to https or cid: — which it wants to do regardless.
        //
        // The honest limit: https is still an external fetch, and an https tracking pixel tracks
        // just as well as an http one. Blocking url() in inline CSS (below) is not a claim that
        // <img> is safe — it is that url() has no legitimate purpose in a transactional email,
        // while <img> does, so the two get different treatment. Rule out every external fetch and
        // you are down to cid: only.
        .addProtocols("img", "src", "https", "cid")
        // relaxed() already permits http for img src, and addProtocols only ever adds, so the
        // removal has to be explicit. Note this applies to img only: an <a href="http://…"> stays,
        // because a link is followed when the recipient chooses to click it, not the moment the
        // message is opened.
        .removeProtocols("img", "src", "http")

private val EMAIL_OUTPUT_SETTINGS =
    org.jsoup.nodes.Document
        .OutputSettings()
        .prettyPrint(false)

// jsoup's Safelist filters attribute *names*, not their values, so allowing `style` above lets
// through exactly the constructs the <style>-block exclusion exists to prevent: url() fetches an
// external resource (a tracking pixel, in GDPR terms), @import pulls in a remote stylesheet, and
// a data: URI in a background sneaks past the deliberate exclusion of data: from img src.
// Literal spelling is not enough: CSS lets a value be written with hex escapes or with comments
// spliced into a keyword, so `background:\75 rl(https://tracker/p.gif)` and `background:u/**/rl(...)`
// both reach a renderer as url() while matching none of the literal patterns. Treat a backslash or
// a comment opener in a style value as hostile in its own right — neither has any business in the
// inline styling of a transactional email.
private val CSS_EXTERNAL_REF_REGEX =
    Regex("""(url\s*\(|@import|expression\s*\(|javascript\s*:|\\|/\*)""", RegexOption.IGNORE_CASE)

private val sanitizerLogger = LoggerFactory.getLogger("com.ritense.valtimoplugins.graphmail.HtmlSanitizer")

private fun sanitizeHtml(html: String): String {
    val cleaned = Jsoup.clean(html, "", EMAIL_HTML_SAFELIST, EMAIL_OUTPUT_SETTINGS)
    val doc = Jsoup.parseBodyFragment(cleaned)
    doc.outputSettings(EMAIL_OUTPUT_SETTINGS)
    doc.select("[style]").forEach { element ->
        val style = element.attr("style")
        if (!CSS_EXTERNAL_REF_REGEX.containsMatchIn(style)) return@forEach
        // Drop the whole attribute rather than filtering declaration by declaration. Splitting on
        // ';' is itself defeatable — a ';' can sit inside a string or an escape — so keeping the
        // "clean" remainder of a value that already contains an evasion attempt means trusting a
        // parse that has just been shown to be the wrong tool. An email that loses its inline
        // styling still renders; one that silently fetches a tracking pixel is the failure.
        sanitizerLogger.debug(
            "Dropped the inline style on <{}>: it contains an external reference or an obfuscation " +
                "construct (escape sequence or comment)",
            element.tagName(),
        )
        element.removeAttr("style")
    }
    return doc.body().html()
}

// Splits a plugin action property into individual string values.
// Supports three formats so process designers can use whichever is most convenient:
//   - single value:        "user@example.com"
//   - comma-separated:     "user1@example.com,user2@example.com"
//   - JSON array string:   ["user1@example.com","user2@example.com"]
internal fun parseStringListParam(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    val trimmed = value.trim()
    if (trimmed.startsWith("[")) {
        return trimmed
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map {
                it
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .trim()
            }.filter { it.isNotBlank() }
    }
    return trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

// Graph's own limit for an attachment name. A longer name is rejected by Graph anyway, but there
// the failure arrives as an opaque 400 halfway through a send.
private const val MAX_ATTACHMENT_NAME_LENGTH = 255

// A bare `type/subtype`, optionally with parameters. Deliberately permissive about the parameters
// (charset, boundary) and strict about the shape.
private val CONTENT_TYPE_REGEX = Regex("""^[a-zA-Z0-9!#$%&'*+.^_`|~-]+/[a-zA-Z0-9!#$%&'*+.^_`|~-]+(\s*;.*)?$""")

private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

private val attachmentLogger = LoggerFactory.getLogger("com.ritense.valtimoplugins.graphmail.AttachmentMetadata")

// A file name reaches the Graph payload and the audit log. Reject what cannot be a file name;
// trim what is merely too long rather than failing a send over it.
private fun sanitizeAttachmentFileName(raw: String): String {
    requireNoControlChars(raw, "attachment fileName")
    val trimmed = raw.trim()
    require(trimmed.isNotBlank()) { "Attachment fileName must not be blank" }
    if (trimmed.length <= MAX_ATTACHMENT_NAME_LENGTH) return trimmed
    // Keep the extension: mail clients pick their handler from it, and a truncated name without
    // one arrives as an unopenable blob.
    val extension = trimmed.substringAfterLast('.', "").take(10)
    val keep = MAX_ATTACHMENT_NAME_LENGTH - extension.length - 1
    return if (extension.isEmpty()) trimmed.take(MAX_ATTACHMENT_NAME_LENGTH) else "${trimmed.take(keep)}.$extension"
}

// An unusable content type is replaced rather than rejected: it is metadata about the file, not
// the file, and refusing to send over it would fail a message whose content is perfectly fine.
private fun sanitizeContentType(raw: String?): String {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isEmpty()) return DEFAULT_CONTENT_TYPE
    if (containsControlChars(candidate) || !CONTENT_TYPE_REGEX.matches(candidate)) {
        attachmentLogger.warn(
            "Ignoring an unusable attachment content type from resource metadata; falling back to {}",
            DEFAULT_CONTENT_TYPE,
        )
        return DEFAULT_CONTENT_TYPE
    }
    return candidate
}

private fun parseRecipients(
    values: List<String>,
    fieldName: String,
): List<GraphRecipient> {
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

// Extension from a MIME type ("application/pdf" -> "pdf"); null for structured subtypes.
private fun mimeExtension(contentType: String?): String? =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
        ?.lowercase()

// Attachment name from the storage metadata; keys come from MetadataType (FILE_NAME.key is "filename").
internal fun resolveAttachmentFileName(metadata: Map<String, Any?>): String {
    val name = (metadata[MetadataType.FILE_NAME.key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val extension = mimeExtension(metadata[MetadataType.CONTENT_TYPE.key] as? String)
    return when {
        // Already carries an extension — use it verbatim.
        name != null && name.contains('.') -> name
        name != null && extension != null -> "$name.$extension"
        name != null -> name
        extension != null -> "attachment.$extension"
        else -> "attachment"
    }
}

@Plugin(
    key = "entra",
    title = "Microsoft Graph Mail Plugin",
    description = "Send emails via Microsoft Graph API with OAuth2 (Client Credentials)",
)
class GraphMailPlugin(
    // Injected, never built here. Valtimo hydrates a fresh GraphMailPlugin per action invocation,
    // so a client constructed in this class would mean a new RestTemplate, a new message converter
    // and — decisively — a brand new connection pool for every single email. That is one full TLS
    // handshake per message and no connection reuse whatsoever. Sharing one pooled client across
    // invocations is the single largest throughput win available to this plugin.
    private val client: GraphMailClient,
    private val resourceStorageService: TemporaryResourceStorageService,
    private val eventPublisher: ApplicationEventPublisher,
    // Shared across all plugin instances by GraphMailAutoConfiguration/GraphMailPluginFactory —
    // see SendIdempotencyGuard's class doc for why this must be injected rather than built here.
    private val sendIdempotencyGuard: SendIdempotencyGuard = SendIdempotencyGuard(),
    // Bounds peak attachment heap independently of the job-executor pool size — see its class doc.
    private val attachmentConcurrencyLimiter: AttachmentConcurrencyLimiter = AttachmentConcurrencyLimiter(),
    // No-op when the host application has no Micrometer — see GraphMailMetrics.
    private val metrics: GraphMailMetrics = GraphMailMetrics(null),
) {
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

    // Strict sender allowlist (deny-by-default): comma-separated full addresses
    // ("noreply@example.com") and/or domain entries ("@example.com"). Every send —
    // including via process data (pv:) — is rejected unless senderMailbox matches an entry.
    // This limits which mailboxes the tenant-wide Mail.Send application permission can
    // actually be used for through this plugin; pair it with an Exchange Online
    // Application Access Policy for enforcement outside the plugin as well.
    @PluginProperty(key = "allowedSenders", secret = false, required = true)
    lateinit var allowedSenders: String

    @PluginProperty(key = "testSenderMailbox", secret = false, required = false)
    var testSenderMailbox: String? = null

    // tokenBaseUrl, graphBaseUrl, connectTimeoutSeconds and readTimeoutSeconds used to live here as
    // @PluginProperty fields. They are now deployment settings under `graph-mail.http` — see
    // GraphMailHttpProperties for why an admin-editable tokenBaseUrl was a way to walk off with the
    // client secret, and why endpoints belong to the deployment rather than to a configuration row.

    // NOTE: SERVICE_TASK_START fires when the service task begins executing, on the
    // Operaton job-executor thread. If the surrounding transaction rolls back and retries
    // (e.g. optimistic lock on other process data written in the same transaction), this action
    // runs again. sendIdempotencyGuard prevents that retry from sending the email a second time
    // — see its class doc for the exact mechanism and its limitation (in-memory, does not survive
    // a JVM restart between the original attempt and a later retry).
    @PluginAction(
        key = "send-email",
        title = "Send email",
        description = "Send an email via Microsoft Graph API",
        activityTypes = [
            ActivityTypeWithEventName.SERVICE_TASK_START,
        ],
    )
    fun sendEmail(
        execution: DelegateExecution,
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
        check(isValidTenantId(tenantId)) {
            "Plugin property 'tenantId' is not a usable tenant identifier — expected a directory " +
                "GUID, a verified domain such as 'contoso.onmicrosoft.com', or 'common' / " +
                "'organizations'. Check the Graph Mail plugin configuration"
        }
        check(::clientId.isInitialized && clientId.isNotBlank()) {
            "Plugin property 'clientId' is not configured — check the Graph Mail plugin configuration"
        }
        check(::clientSecret.isInitialized && clientSecret.isNotBlank()) {
            "Plugin property 'clientSecret' is not configured — check the Graph Mail plugin configuration"
        }
        check(::allowedSenders.isInitialized && allowedSenders.isNotBlank()) {
            "Plugin property 'allowedSenders' is not configured — the sender allowlist is required. " +
                "Add the permitted sender mailboxes (or '@domain' entries) to the Graph Mail plugin configuration"
        }

        val idempotencyKey = idempotencyKeyFor(execution)

        // Cheap early exit for the common case — a sequential retry after the surrounding
        // transaction rolled back — that skips all validation and resource resolution below,
        // not just the network call. ifNotAlreadySent() below is still the atomic, authoritative
        // check: this is purely an optimization, safe to get "wrong" under a race, since a
        // missed race here just means the work below runs once more before that check catches it.
        if (sendIdempotencyGuard.alreadySent(idempotencyKey)) {
            logger.warn(
                "Skipping duplicate send for activity instance [{}] — already sent, surrounding " +
                    "transaction must have rolled back and retried",
                idempotencyKey,
            )
            return
        }

        // Everything below — validation, resolving body/attachments, the actual send, audit
        // logging, event publishing — runs under a per-key lock (see SendIdempotencyGuard): two
        // callers racing for the same key can never both reach client.sendMail, and a failed
        // attempt is never marked sent, so a genuine retry after failure still runs in full.
        val outcome =
            sendIdempotencyGuard.ifNotAlreadySent(idempotencyKey) {
                // Header injection guard — same fields the frontend already validates,
                // re-checked server-side for defense in depth.
                requireNoControlChars(senderMailbox, "senderMailbox")
                requireNoControlChars(subject, "subject")

                require(isValidEmail(senderMailbox)) { "Invalid sender email: '$senderMailbox'" }
                require(isSenderAllowed(senderMailbox, allowedSendersList())) {
                    "Sender '$senderMailbox' is not on the 'allowedSenders' allowlist of this plugin configuration"
                }

                require(subject.isNotBlank()) { "Email subject must not be blank" }
                require(subject.length <= MAX_SUBJECT_LENGTH) {
                    "Email subject exceeds $MAX_SUBJECT_LENGTH characters (${subject.length})"
                }
                require(isValidResourceId(contentId)) {
                    "Invalid contentId: '$contentId' — must not be blank or contain path-traversal sequences"
                }

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

                // Whether attachments are involved is known from the ID list alone, without
                // reading a single byte — which matters, because the permit has to be held BEFORE
                // resolveAttachments() runs. Resolving first and then acquiring would let every
                // job-executor thread allocate its full attachment payload up front and only then
                // queue, so peak heap would still scale with the thread pool and the cap would
                // bound nothing that costs memory.
                val attachmentIdList = parseStringListParam(attachmentIds)

                logger.debug(
                    "Preparing email — to: {} addresses, from: '{}', subject length: {}",
                    toRecipients.size,
                    maskEmail(senderMailbox),
                    subject.length,
                )

                val auditStart = System.currentTimeMillis()
                var attachments: List<ResolvedAttachment> = emptyList()
                try {
                    // Only attachment-carrying sends queue for a slot; plain sends are unbounded
                    // because their memory footprint is negligible.
                    attachmentConcurrencyLimiter.withPermit(attachmentIdList.isNotEmpty()) {
                        attachments = resolveAttachments(attachmentIdList.ifEmpty { null })
                        client.sendMail(
                            credentials =
                                GraphCredentials(
                                    tenantId = tenantId,
                                    clientId = clientId,
                                    clientSecret = clientSecret,
                                ),
                            mail =
                                OutboundMail(
                                    senderMailbox = senderMailbox,
                                    toRecipients = toRecipients,
                                    ccRecipients = ccRecipients,
                                    bccRecipients = bccRecipients,
                                    replyToRecipients = replyToRecipients,
                                    subject = subject,
                                    bodyHtml = safeBodyHtml,
                                    attachments = attachments,
                                    saveToSentItems = true,
                                ),
                        )
                    }
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
                    metrics.recordSend("ok", attachments.isNotEmpty(), durationMs)
                    publishEventSafely(
                        GraphMailEmailSentEvent(
                            senderMailbox = maskEmail(senderMailbox),
                            recipientCount = toRecipients.size,
                            ccCount = ccRecipients.size,
                            bccCount = bccRecipients.size,
                            attachmentCount = attachments.size,
                            durationMs = durationMs,
                        ),
                    )
                } catch (ex: Exception) {
                    val durationMs = System.currentTimeMillis() - auditStart
                    // Spell out the retry verdict. Every failure used to look identical in the logs,
                    // leaving an administrator with no way to tell "wait for this to clear" apart
                    // from "go change something" until the incident showed up.
                    auditLogger.warn(
                        "SEND_FAIL sender={} to={} subject_len={} duration_ms={} verdict={} error={}",
                        maskEmail(senderMailbox),
                        maskEmails(toRecipients.map { it.emailAddress.address }),
                        subject.length,
                        durationMs,
                        retryVerdictOf(ex),
                        maskEmailsInText(ex.message),
                    )
                    // Same vocabulary as the audit log's verdict field, so a dashboard and a log
                    // line name the same failure the same way.
                    metrics.recordSend(
                        retryVerdictOf(ex).substringBefore(" —"),
                        attachmentIdList.isNotEmpty(),
                        durationMs,
                    )
                    publishEventSafely(
                        GraphMailEmailFailedEvent(
                            senderMailbox = maskEmail(senderMailbox),
                            recipientCount = toRecipients.size,
                            reason =
                                (ex.message ?: ex.javaClass.simpleName)
                                    .replace(EMAIL_IN_TEXT_REGEX) { maskEmail(it.value) },
                            durationMs = durationMs,
                        ),
                    )
                    throw ex
                }
            }

        if (outcome == null) {
            logger.warn(
                "Skipping duplicate send for activity instance [{}] — already sent (detected at send time)",
                idempotencyKey,
            )
        }
    }

    // How an administrator reading the audit log should read this failure. Deliberately a log
    // field rather than a different exception type reaching the engine: turning permanent failures
    // into a BpmnError would change process semantics for every existing model, and an uncaught
    // BpmnError degrades into a "no catching boundary event found" incident whose message is less
    // useful than the one thrown here. Routing these through a boundary error event is a process
    // design decision, not something this plugin should impose — see documentation/developer.md.
    private fun retryVerdictOf(ex: Throwable): String =
        when (ex) {
            is IllegalArgumentException, is IllegalStateException ->
                "PERMANENT_INPUT — retrying will fail identically; correct the process data or the plugin configuration"
            // Before GraphMailPermanentException: this one is thrown for a 401 that survived a forced
            // token refresh, which is the single most common "grant the permission" case there is, and
            // it does not extend GraphMailPermanentException — so without its own branch it fell all
            // the way through to UNCLASSIFIED.
            is GraphMailTokenExpiredException ->
                "PERMANENT_REMOTE — the token was rejected even after a refresh; grant Mail.Send as an " +
                    "application permission and give it admin consent"
            is GraphMailPermanentException ->
                "PERMANENT_REMOTE — Graph rejected this permanently; a configuration or permission change is required"
            is GraphMailUnknownOutcomeException ->
                "UNKNOWN — the message MAY have been sent; verify the mailbox before re-running this activity"
            is GraphMailRetryableException ->
                "TRANSIENT — safe to retry; the job executor will reschedule"
            else -> "UNCLASSIFIED"
        }

    // Identifies one *attempt at one activity instance*, which is exactly the granularity the
    // duplicate guard needs: stable across a job-executor retry of the same activity instance
    // (the id is assigned when the execution enters the activity and is persisted with it, so a
    // rolled-back-and-retried attempt reads back the same value), but freshly generated for every
    // new iteration of a loop or multi-instance marker.
    //
    // The previous key, "${execution.id}:${execution.currentActivityId}", was NOT that: a flow
    // that loops back to the same service task reuses both values, so every iteration after the
    // first was silently suppressed as a duplicate for the lifetime of the guard's TTL.
    // Identifies one *pass* over one activity, which is exactly the granularity the duplicate guard
    // needs: the same value when the job executor retries a rolled-back attempt, a different value
    // on every fresh pass of a loop or multi-instance marker.
    //
    // Getting here took two wrong answers, both verified against a real engine in
    // ActivityInstanceIdContractTest:
    //
    //   execution.id + currentActivityId  stable across a retry, but IDENTICAL across loop
    //                                     iterations — every mail after the first was dropped
    //   activityInstanceId                unique per iteration, but CHANGES on every retry
    //                                     (send-email:9 -> :15 -> :20) — a rolled-back send was
    //                                     re-sent, which is the very thing the guard exists to stop
    //
    // Those three fields cannot separate the two situations: in both, execution.id and
    // currentActivityId are equal while activityInstanceId differs. Nothing derived from them
    // works, and neither does a content hash — a dunning process that legitimately sends the same
    // message twice would be silenced.
    //
    // What does work is a value that shares the transaction's fate. This counter is read before it
    // is advanced and written through the execution, so a rollback takes the increment with it and
    // the retry reads the same number, while a committed pass leaves the next one a higher number.
    // The cost is a variable on the process instance, visible in Cockpit and in variable history.
    private fun idempotencyKeyFor(execution: DelegateExecution): String {
        val activityId = execution.currentActivityId ?: NO_ACTIVITY_ID
        val counterVariable = "$PASS_COUNTER_VARIABLE_PREFIX$activityId"

        val passesSoFar = (execution.getVariable(counterVariable) as? Int) ?: 0
        execution.setVariable(counterVariable, passesSoFar + 1)

        return "${execution.id}:$activityId:$passesSoFar"
    }

    // Empty when the property is missing (pre-allowlist configurations) — callers treat
    // an empty list as deny-all. `isInitialized` is only accessible inside this class,
    // so the test-send controller goes through this accessor.
    internal fun allowedSendersList(): List<String> =
        if (::allowedSenders.isInitialized) parseStringListParam(allowedSenders) else emptyList()

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
            // Which name: the metadata keys come from MetadataType, and FILE_NAME.key is "filename" —
            // a literal "fileName" lookup silently misses, leaving every attachment named after its
            // resource UUID with no extension.
            //
            // Whether that name is usable: both values are externally influenced, since an uploaded
            // file names itself. Every other such string in this plugin goes through
            // requireNoControlChars and a length bound; these two did not. Graph JSON-escapes them
            // either way, so the exposure was log injection and confusing Graph errors rather than
            // anything worse — but the gap in an otherwise consistent rule is the point.
            val fileName = sanitizeAttachmentFileName(resolveAttachmentFileName(metadata))
            val contentType = sanitizeContentType(metadata[MetadataType.CONTENT_TYPE.key] as? String)

            val raw =
                resourceStorageService.getResourceContentAsInputStream(resourceId)
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

            logger.debug(
                "Attachment resolved: name='{}', type='{}', size={}",
                fileName,
                contentType,
                rawBytes.size,
            )
            ResolvedAttachment(name = fileName, contentType = contentType, rawBytes = rawBytes)
        }
    }

    private fun resolveBodyContent(contentId: String): String {
        val stream =
            resourceStorageService.getResourceContentAsInputStream(contentId)
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
