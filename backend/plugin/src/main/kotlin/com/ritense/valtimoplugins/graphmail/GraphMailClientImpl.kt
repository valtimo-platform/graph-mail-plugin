package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

private const val GRAPH_SCOPE = "https://graph.microsoft.com/.default"
private const val TOKEN_EXPIRY_BUFFER_SECONDS = 60L
private const val MAX_RETRIES = 5
private const val INITIAL_BACKOFF_MS = 500L
private const val BACKOFF_MULTIPLIER = 2.0
// Cap the Retry-After header to limit per-sleep blocking time on the job-executor thread.
// 15s × 5 retries = 75s worst case per send; wall-clock caps (30s/120s) apply on top.
private const val MAX_RETRY_AFTER_SECONDS = 15L
private const val TOKEN_MAX_RETRIES = 3
// Hard wall-clock cap for the entire send operation (including all retries and backoff sleeps).
// Ensures a 429 storm cannot hold an Operaton BPM job-executor thread longer than this limit.
private const val MAX_SEND_WALL_CLOCK_MS = 30_000L
// Longer deadline for the draft+upload flow — large uploads can take tens of seconds.
private const val MAX_DRAFT_SEND_WALL_CLOCK_MS = 120_000L
private const val CHUNK_MAX_RETRIES = 3
// Default token cache capacity — override via constructor parameter.
// Increase if the deployment manages more than 64 distinct Entra app registrations.
private const val DEFAULT_maxCachedTokens = 64

// NOTE (threading): retry backoff uses Thread.sleep(), which blocks the calling thread.
// In Operaton BPM (V13), SERVICE_TASK actions run on the job-executor thread pool.
// Worst case: 429 with Retry-After=15s × 5 attempts = 75s; wall-clock caps enforce the hard limit.
// Size the job executor thread pool accordingly (operaton.bpm.job-executor.core-pool-size),
// or replace with a non-blocking HTTP client (WebClient) in a future release.

/**
 * Implementation of [GraphMailClient].
 *
 * - OAuth2 Client Credentials, per-(tenantId+clientId) cache
 * - ConcurrentHashMap cache + per-key ReentrantLock — cache hits never block,
 *   concurrent misses for the same key collapse into a single Azure call
 * - Exponential backoff with jitter on send (5 attempts) and on token fetch (3)
 * - 429 honours Retry-After (capped at 15s to limit job-executor thread blocking)
 * - 401 invalidates only the affected key, then retries exactly once
 * - PII-aware logging (mailbox + recipients are masked)
 */
class GraphMailClientImpl(
    private val restClient: RestClient,
    private val tokenBaseUrl: String = "https://login.microsoftonline.com",
    private val graphBaseUrl: String = "https://graph.microsoft.com",
    private val maxCachedTokens: Int = DEFAULT_maxCachedTokens,
) : GraphMailClient {

    private val logger = LoggerFactory.getLogger(GraphMailClientImpl::class.java)

    private data class CachedToken(val token: String, val expiresAt: Instant, val createdAt: Instant)

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()
    private val keyLocks = ConcurrentHashMap<String, ReentrantLock>()

    private fun cacheKey(tenantId: String, clientId: String) = "$tenantId:$clientId"

    private fun lockFor(key: String): ReentrantLock =
        keyLocks.computeIfAbsent(key) { ReentrantLock() }.also {
            // Evict lock entries for keys no longer in the token cache so keyLocks doesn't
            // grow unboundedly when many configurations are created/deleted over time.
            if (keyLocks.size > maxCachedTokens + maxCachedTokens / 2) {
                keyLocks.keys
                    .filter { k -> k != key && !tokenCache.containsKey(k) }
                    .toList()
                    .forEach { k -> keyLocks.remove(k) }
            }
        }

    internal fun getAccessToken(tenantId: String, clientId: String, clientSecret: String): String {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        require(clientSecret.isNotBlank()) { "clientSecret must not be blank" }

        val key = cacheKey(tenantId, clientId)

        // Fast path — non-blocking cache hit.
        tokenCache[key]?.let { cached ->
            if (Instant.now().isBefore(cached.expiresAt)) {
                logger.debug("Token cache hit [{}:***]", tenantId)
                return cached.token
            }
        }

        // Slow path — only requests for the same key serialise.
        return lockFor(key).withLock {
            tokenCache[key]?.let { cached ->
                if (Instant.now().isBefore(cached.expiresAt)) {
                    logger.debug("Token cache hit (post-lock) [{}:***]", tenantId)
                    return@withLock cached.token
                }
            }
            fetchAndCacheToken(tenantId, clientId, clientSecret, key)
        }
    }

    override fun invalidateCache(tenantId: String?, clientId: String?) {
        when {
            tenantId != null && clientId != null -> {
                val removed = tokenCache.remove(cacheKey(tenantId, clientId))
                if (removed != null) logger.warn("Token cache cleared for [{}:***]", tenantId)
            }
            tenantId == null && clientId == null -> {
                val count = tokenCache.size
                tokenCache.clear()
                logger.warn("Token cache fully cleared ({} entries)", count)
            }
            else -> logger.warn(
                "invalidateCache called with partial selector — ignored (tenantId={}, clientId={})",
                tenantId != null, clientId != null
            )
        }
    }

    private fun fetchAndCacheToken(tenantId: String, clientId: String, clientSecret: String, key: String): String {
        val url = UriComponentsBuilder
            .fromUriString("$tokenBaseUrl/{tenantId}/oauth2/v2.0/token")
            .build()
            .expand(tenantId)
            .toUriString()

        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("scope", GRAPH_SCOPE)
        }

        val response = postTokenWithRetry(url, form, tenantId)

        // Guard against negative TTL if Azure returns expires_in < buffer.
        val ttl = (response.expiresIn.toLong() - TOKEN_EXPIRY_BUFFER_SECONDS).coerceAtLeast(0L)
        val now = Instant.now()
        evictIfFull()
        tokenCache[key] = CachedToken(response.accessToken, now.plusSeconds(ttl), now)

        logger.info("New token acquired [{}:***] — valid for {}s", tenantId, ttl)
        return response.accessToken
    }

    private fun postTokenWithRetry(url: String, form: LinkedMultiValueMap<String, String>, tenantId: String): TokenResponse {
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            attempt++
            try {
                return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
                    ?: throw GraphMailException("Empty response when fetching access token")
            } catch (ex: HttpClientErrorException) {
                logger.error("Token request rejected ({}) for tenant [{}]", ex.statusCode, tenantId)
                // Do NOT log ex.responseBodyAsString — Azure token responses may echo back the
                // client_secret. The HTTP status (logged above) is sufficient for diagnosis.
                throw GraphMailException(
                    "Azure rejected token request (${ex.statusCode}) — check Client ID and Secret",
                    statusCode = ex.statusCode.value(),
                )
            } catch (ex: HttpServerErrorException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.error("Azure Entra unavailable ({}) after {} token attempts", ex.statusCode, attempt)
                    throw GraphMailException("Azure Entra unavailable (${ex.statusCode})")
                }
                val delay = backoffMs + Random.nextLong(0, (backoffMs / 2).coerceAtLeast(1))
                logger.warn("Token request {} — attempt {}/{}, retrying in {}ms",
                    ex.statusCode, attempt, TOKEN_MAX_RETRIES, delay)
                Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: ResourceAccessException) {
                if (attempt >= TOKEN_MAX_RETRIES) {
                    logger.warn("Token request timed out for tenant [{}] after {} attempts: {}",
                        tenantId, attempt, ex.message)
                    throw GraphMailException("Could not reach Azure Entra (timeout or network error): ${ex.message}")
                }
                val delay = backoffMs + Random.nextLong(0, (backoffMs / 2).coerceAtLeast(1))
                logger.warn("Token request network error — attempt {}/{}, retrying in {}ms: {}",
                    attempt, TOKEN_MAX_RETRIES, delay, ex.message)
                Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    private fun evictIfFull() {
        if (tokenCache.size < maxCachedTokens) return
        // Evict the oldest entry by createdAt — bounded scan, only runs at capacity.
        tokenCache.entries.minByOrNull { it.value.createdAt }?.key?.let { tokenCache.remove(it) }
    }

    // Retry-After can be seconds ("120") or an HTTP date ("Wed, 21 Oct 2025 07:28:00 GMT").
    private fun parseRetryAfter(header: String?): Long {
        if (header == null) return 5L
        header.toLongOrNull()?.let { return it }
        return runCatching {
            val target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            (target.toEpochSecond() - Instant.now().epochSecond).coerceAtLeast(0L)
        }.getOrElse {
            logger.warn("Unparseable Retry-After header '{}' — defaulting to 5s", header)
            5L
        }
    }

    override fun sendMail(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        toRecipients: List<GraphRecipient>,
        ccRecipients: List<GraphRecipient>,
        bccRecipients: List<GraphRecipient>,
        replyToRecipients: List<GraphRecipient>,
        subject: String,
        bodyHtml: String,
        attachments: List<ResolvedAttachment>,
        saveToSentItems: Boolean,
    ) {
        require(toRecipients.isNotEmpty()) { "At least one recipient is required" }

        val recipientCount = toRecipients.size
        logger.info("Sending email — recipients: {}, mailbox: '{}'",
            recipientCount, maskEmail(senderMailbox))

        val useDraftFlow = attachments.any { it.sizeBytes > INLINE_ATTACHMENT_THRESHOLD_BYTES }
            || attachments.sumOf { it.sizeBytes } > INLINE_ATTACHMENT_THRESHOLD_BYTES

        if (useDraftFlow) {
            logger.debug("Using draft+upload flow — {} attachment(s), total {} bytes",
                attachments.size, attachments.sumOf { it.sizeBytes })
            sendViaDraftAndUpload(tenantId, clientId, clientSecret, senderMailbox,
                toRecipients, ccRecipients, bccRecipients, replyToRecipients,
                subject, bodyHtml, attachments, saveToSentItems)
        } else {
            val sendMailUri: URI = UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/sendMail")
                .buildAndExpand(senderMailbox)
                .toUri()
            val payload = buildInlinePayload(subject, bodyHtml, toRecipients, ccRecipients,
                bccRecipients, replyToRecipients, attachments, saveToSentItems)
            sendWithRefreshAndRetry(tenantId, clientId, clientSecret, sendMailUri, payload, senderMailbox)
        }
        logger.info("Email sent successfully — recipients: {}", recipientCount)
    }

    private fun buildInlinePayload(
        subject: String,
        bodyHtml: String,
        toRecipients: List<GraphRecipient>,
        ccRecipients: List<GraphRecipient>,
        bccRecipients: List<GraphRecipient>,
        replyToRecipients: List<GraphRecipient>,
        attachments: List<ResolvedAttachment>,
        saveToSentItems: Boolean,
    ): SendMailRequest {
        val inlineAttachments = attachments.map { a ->
            GraphAttachment(
                name = a.name,
                contentType = a.contentType,
                contentBytes = Base64.getEncoder().encodeToString(a.rawBytes),
            )
        }
        return SendMailRequest(
            message = GraphMessage(
                subject = subject,
                body = GraphBody(contentType = GRAPH_BODY_CONTENT_TYPE_HTML, content = bodyHtml),
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
                replyTo = replyToRecipients,
                attachments = inlineAttachments,
            ),
            saveToSentItems = saveToSentItems,
        )
    }

    private fun sendViaDraftAndUpload(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        toRecipients: List<GraphRecipient>,
        ccRecipients: List<GraphRecipient>,
        bccRecipients: List<GraphRecipient>,
        replyToRecipients: List<GraphRecipient>,
        subject: String,
        bodyHtml: String,
        attachments: List<ResolvedAttachment>,
        saveToSentItems: Boolean,
    ) {
        val deadline = System.currentTimeMillis() + MAX_DRAFT_SEND_WALL_CLOCK_MS

        val draftMessage = GraphMessage(
            subject = subject,
            body = GraphBody(contentType = GRAPH_BODY_CONTENT_TYPE_HTML, content = bodyHtml),
            toRecipients = toRecipients,
            ccRecipients = ccRecipients,
            bccRecipients = bccRecipients,
            replyTo = replyToRecipients,
        )
        val draftId = createDraftWithRetry(tenantId, clientId, clientSecret, senderMailbox, draftMessage, deadline)
        logger.debug("Draft created id={}", draftId)

        try {
            for (attachment in attachments) {
                val uploadUrl = createUploadSession(tenantId, clientId, clientSecret,
                    senderMailbox, draftId, attachment, deadline)
                uploadInChunks(uploadUrl, attachment, deadline)
                logger.debug("Attachment uploaded: name='{}' size={}", attachment.name, attachment.sizeBytes)
            }
            sendDraftWithRetry(tenantId, clientId, clientSecret, senderMailbox, draftId, deadline)
        } catch (ex: Exception) {
            deleteDraftBestEffort(tenantId, clientId, clientSecret, senderMailbox, draftId)
            throw ex
        }
    }

    private fun deleteDraftBestEffort(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        draftId: String,
    ) {
        try {
            val uri: URI = UriComponentsBuilder
                .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}")
                .buildAndExpand(senderMailbox, draftId)
                .toUri()
            val token = getAccessToken(tenantId, clientId, clientSecret)
            restClient.delete()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toBodilessEntity()
            logger.debug("Orphaned draft deleted id={}", draftId)
        } catch (ex: Exception) {
            logger.warn("Failed to delete orphaned draft id={}: {}", draftId, ex.message)
        }
    }

    private fun createDraftWithRetry(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        message: GraphMessage,
        deadline: Long,
    ): String {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages")
            .buildAndExpand(senderMailbox)
            .toUri()

        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > deadline)
                throw GraphMailException("Draft creation timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
            attempt++
            val token = getAccessToken(tenantId, clientId, clientSecret)
            try {
                return restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(DraftMessageResponse::class.java)
                    ?.id
                    ?: throw GraphMailException("Empty response body when creating draft message")
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) throw GraphMailTokenExpiredException(
                            "Token rejected when creating draft (401) — check Mail.ReadWrite permission", ex)
                        invalidateCache(tenantId, clientId)
                        tokenRefreshed = true
                        attempt--
                    }
                    429 -> {
                        if (attempt >= MAX_RETRIES)
                            throw GraphMailException("Rate limited creating draft after $MAX_RETRIES attempts", ex)
                        val wait = (parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                            .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000)
                            .coerceAtMost(deadline - System.currentTimeMillis())
                        if (wait > 0) Thread.sleep(wait)
                    }
                    else -> throw GraphMailException(
                        "Graph API rejected draft creation (${ex.statusCode})", ex,
                        statusCode = ex.statusCode.value())
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES)
                    throw GraphMailException("Graph API unavailable creating draft after $MAX_RETRIES attempts", ex)
                val delay = (backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)))
                    .coerceAtMost(deadline - System.currentTimeMillis())
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    private fun createUploadSession(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        draftId: String,
        attachment: ResolvedAttachment,
        deadline: Long,
    ): String {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/attachments/createUploadSession")
            .buildAndExpand(senderMailbox, draftId)
            .toUri()

        val body = CreateUploadSessionRequest(
            attachmentItem = UploadAttachmentItem(
                name = attachment.name,
                size = attachment.sizeBytes,
                contentType = attachment.contentType,
            )
        )

        if (System.currentTimeMillis() > deadline)
            throw GraphMailException("Upload session creation timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")

        var tokenRefreshed = false

        fun doPost(token: String): String =
            restClient.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(UploadSessionResponse::class.java)
                ?.uploadUrl
                ?: throw GraphMailException("Empty uploadUrl in upload session response")

        val uploadUrl = try {
            doPost(getAccessToken(tenantId, clientId, clientSecret))
        } catch (ex: HttpClientErrorException) {
            if (ex.statusCode.value() == 401 && !tokenRefreshed) {
                tokenRefreshed = true
                invalidateCache(tenantId, clientId)
                doPost(getAccessToken(tenantId, clientId, clientSecret))
            } else {
                throw GraphMailException(
                    "Graph API rejected upload session creation (${ex.statusCode})", ex,
                    statusCode = ex.statusCode.value())
            }
        }

        // Derive expected scheme+host from graphBaseUrl so WireMock tests (http://localhost)
        // pass while production rejects any non-Microsoft https:// domain.
        val expectedScheme = runCatching { java.net.URI.create(graphBaseUrl).scheme }.getOrElse { "https" }
        val expectedHost  = runCatching { java.net.URI.create(graphBaseUrl).host  }.getOrElse { "graph.microsoft.com" }
        val actualScheme  = runCatching { java.net.URI.create(uploadUrl).scheme   }.getOrNull()
        val actualHost    = runCatching { java.net.URI.create(uploadUrl).host     }.getOrNull()
        val microsoftHosts = listOf(".microsoft.com", ".office.com", ".office.net", ".office365.com")
        require(actualScheme == expectedScheme &&
            (actualHost == expectedHost || microsoftHosts.any { actualHost?.endsWith(it) == true })) {
            "Upload URL from Graph API failed domain validation"
        }
        return uploadUrl
    }

    private fun uploadInChunks(uploadUrl: String, attachment: ResolvedAttachment, deadline: Long) {
        val bytes = attachment.rawBytes
        val total = bytes.size.toLong()
        var offset = 0L

        while (offset < total) {
            if (System.currentTimeMillis() > deadline)
                throw GraphMailException("Attachment upload timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")

            val end = minOf(offset + UPLOAD_CHUNK_BYTES - 1, total - 1)
            val chunkLen = (end - offset + 1).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), (end + 1).toInt())

            var chunkAttempt = 0
            var success = false
            while (!success) {
                chunkAttempt++
                try {
                    restClient.put()
                        .uri(URI.create(uploadUrl))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(chunkLen.toLong())
                        .header("Content-Range", "bytes $offset-$end/$total")
                        .body(chunk)
                        .retrieve()
                        .toBodilessEntity()
                    success = true
                } catch (ex: HttpClientErrorException) {
                    throw GraphMailException(
                        "Chunk upload rejected (${ex.statusCode}) at offset $offset", ex,
                        statusCode = ex.statusCode.value())
                } catch (ex: HttpServerErrorException) {
                    if (chunkAttempt >= CHUNK_MAX_RETRIES)
                        throw GraphMailException(
                            "Chunk upload failed after $CHUNK_MAX_RETRIES attempts at offset $offset", ex)
                    val delay = (500L * (1 shl (chunkAttempt - 1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                } catch (ex: ResourceAccessException) {
                    if (chunkAttempt >= CHUNK_MAX_RETRIES)
                        throw GraphMailException(
                            "Chunk upload unreachable after $CHUNK_MAX_RETRIES attempts at offset $offset", ex)
                    val delay = (500L * (1 shl (chunkAttempt - 1)))
                        .coerceAtMost(deadline - System.currentTimeMillis())
                    if (delay > 0) Thread.sleep(delay)
                }
            }
            offset = end + 1
        }
    }

    private fun sendDraftWithRetry(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        senderMailbox: String,
        draftId: String,
        deadline: Long,
    ) {
        val uri: URI = UriComponentsBuilder
            .fromUriString("$graphBaseUrl/v1.0/users/{mailbox}/messages/{id}/send")
            .buildAndExpand(senderMailbox, draftId)
            .toUri()

        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > deadline)
                throw GraphMailException("Draft send timed out after ${MAX_DRAFT_SEND_WALL_CLOCK_MS}ms")
            attempt++
            val token = getAccessToken(tenantId, clientId, clientSecret)
            try {
                restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentLength(0)
                    .retrieve()
                    .toBodilessEntity()
                return
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) throw GraphMailTokenExpiredException(
                            "Token rejected sending draft (401) — check Mail.Send permission", ex)
                        invalidateCache(tenantId, clientId)
                        tokenRefreshed = true
                        attempt--
                    }
                    429 -> {
                        if (attempt >= MAX_RETRIES)
                            throw GraphMailException("Rate limited sending draft after $MAX_RETRIES attempts", ex)
                        val wait = (parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                            .coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000)
                            .coerceAtMost(deadline - System.currentTimeMillis())
                        if (wait > 0) Thread.sleep(wait)
                    }
                    else -> throw GraphMailException(
                        "Graph API rejected draft send (${ex.statusCode})", ex,
                        statusCode = ex.statusCode.value())
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES)
                    throw GraphMailException("Graph API unavailable sending draft after $MAX_RETRIES attempts", ex)
                val delay = (backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)))
                    .coerceAtMost(deadline - System.currentTimeMillis())
                if (delay > 0) Thread.sleep(delay)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }

    private fun sendWithRefreshAndRetry(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        url: URI,
        payload: SendMailRequest,
        mailbox: String,
    ) {
        val callDeadline = System.currentTimeMillis() + MAX_SEND_WALL_CLOCK_MS
        var tokenRefreshed = false
        var attempt = 0
        var backoffMs = INITIAL_BACKOFF_MS

        while (true) {
            if (System.currentTimeMillis() > callDeadline) {
                throw GraphMailException(
                    "Email send timed out — total wall-clock limit of ${MAX_SEND_WALL_CLOCK_MS}ms exceeded"
                )
            }
            attempt++
            val token = getAccessToken(tenantId, clientId, clientSecret)
            try {
                restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
                return
            } catch (ex: HttpClientErrorException) {
                when (ex.statusCode.value()) {
                    401 -> {
                        if (tokenRefreshed) {
                            logger.error("401 Unauthorized after fresh token — Mail.Send permission likely missing")
                            throw GraphMailTokenExpiredException(
                                "Token rejected by Graph API (401) even after refresh — check Mail.Send permission", ex
                            )
                        }
                        logger.warn("401 Unauthorized — invalidating cached token for [{}:***] and retrying once",
                            tenantId)
                        invalidateCache(tenantId, clientId)
                        tokenRefreshed = true
                        attempt-- // Don't burn a retry attempt on the refresh.
                    }
                    429 -> {
                        val retryAfter = parseRetryAfter(ex.responseHeaders?.getFirst("Retry-After"))
                            .coerceAtMost(MAX_RETRY_AFTER_SECONDS)
                        if (attempt >= MAX_RETRIES) {
                            throw GraphMailException("Rate limited after $MAX_RETRIES attempts (429)", ex)
                        }
                        val sleepMs = minOf(retryAfter * 1000, callDeadline - System.currentTimeMillis())
                        logger.warn("429 Rate limited — waiting {}ms (Retry-After={}s, wall-clock cap)",
                            sleepMs, retryAfter)
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                    }
                    else -> {
                        logger.error("Graph API rejected email ({}): mailbox='{}'",
                            ex.statusCode, maskEmail(mailbox))
                        throw GraphMailException(
                            "Graph API rejected email (${ex.statusCode}): check mailbox and Mail.Send permission",
                            ex,
                            statusCode = ex.statusCode.value(),
                        )
                    }
                }
            } catch (ex: HttpServerErrorException) {
                if (attempt >= MAX_RETRIES) {
                    logger.error("Graph API unavailable after {} attempts ({})", MAX_RETRIES, ex.statusCode)
                    throw GraphMailException(
                        "Graph API unavailable after $MAX_RETRIES attempts (${ex.statusCode})", ex
                    )
                }
                // Jitter scales with backoff (up to 20% of current backoff) — better thundering-herd defense.
                val jitter = Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1))
                val delayMs = minOf(backoffMs + jitter, callDeadline - System.currentTimeMillis())
                logger.warn("Graph API {} — attempt {}/{}, waiting {}ms",
                    ex.statusCode, attempt, MAX_RETRIES, delayMs)
                if (delayMs > 0) Thread.sleep(delayMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            } catch (ex: ResourceAccessException) {
                if (attempt >= MAX_RETRIES) {
                    logger.error("Graph API unreachable after {} attempts: {}", MAX_RETRIES, ex.message)
                    throw GraphMailException("Graph API unreachable after $MAX_RETRIES attempts: ${ex.message}", ex)
                }
                val delayMs = minOf(
                    backoffMs + Random.nextLong(0, (backoffMs / 5).coerceAtLeast(1)),
                    callDeadline - System.currentTimeMillis()
                )
                logger.warn("Graph API network error — attempt {}/{}, retrying in {}ms",
                    attempt, MAX_RETRIES, delayMs)
                if (delayMs > 0) Thread.sleep(delayMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
            }
        }
    }
}
