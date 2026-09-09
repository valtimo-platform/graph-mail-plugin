package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

const val GRAPH_BODY_CONTENT_TYPE_HTML = "HTML"
const val GRAPH_BODY_CONTENT_TYPE_TEXT = "Text"

// Graph's hard minimum for attachment upload sessions; smaller files must use a plain POST.
const val UPLOAD_SESSION_MIN_BYTES = 3L * 1024L * 1024L

// Graph rejects write requests over 4 MiB with HTTP 413 (base64-encoded size).
const val MAX_GRAPH_WRITE_BYTES = 4L * 1024L * 1024L

// Headroom inside MAX_GRAPH_WRITE_BYTES for the JSON envelope; generous on purpose.
const val GRAPH_ENVELOPE_OVERHEAD_BYTES = 64L * 1024L

// Encoded length of `n` raw bytes in base64, including padding.
fun base64Length(n: Long): Long = 4L * ((n + 2L) / 3L)

// Hard limits enforced before any API call — raised to 25 MB now that upload sessions are supported.
const val MAX_SINGLE_ATTACHMENT_BYTES = 25L * 1024L * 1024L
const val MAX_TOTAL_ATTACHMENT_BYTES = 25L * 1024L * 1024L
const val MAX_ATTACHMENTS = 5

// Upload-session chunk size — must be a non-zero multiple of 320 KiB (327,680 bytes).
// 327,680 × 10 = 3,276,800 bytes (3,200 KiB). Stays under 4 MB and is exactly divisible.
const val UPLOAD_CHUNK_BYTES = 327_680L * 10L

// Internal holder — keeps raw bytes until sendMail decides inline vs. upload-session path.
// Not a data class: ByteArray equality is reference-based, which would cause surprising behaviour.
class ResolvedAttachment(
    val name: String,
    val contentType: String,
    val rawBytes: ByteArray,
) {
    val sizeBytes: Long get() = rawBytes.size.toLong()
}

// Azure Entra app registration credentials for OAuth2 Client Credentials — grouped so
// GraphMailClient callers pass one value instead of three positional strings repeated
// across the interface, the implementation, and every internal retry/draft-flow method.
data class GraphCredentials(
    val tenantId: String,
    val clientId: String,
    val clientSecret: String,
) {
    // Kotlin's generated toString() would otherwise print clientSecret in plaintext — override
    // it so an accidental debug log statement, or a failed test assertion printing this object,
    // can never leak the secret.
    override fun toString(): String = "GraphCredentials(tenantId=$tenantId, clientId=$clientId, clientSecret=***)"
}

// Everything needed to send one email via Graph, grouped for the same reason as
// [GraphCredentials]. Optional recipient lists and saveToSentItems default so callers only
// need to name the fields that matter for their call site.
data class OutboundMail(
    val senderMailbox: String,
    val toRecipients: List<GraphRecipient>,
    val ccRecipients: List<GraphRecipient> = emptyList(),
    val bccRecipients: List<GraphRecipient> = emptyList(),
    val replyToRecipients: List<GraphRecipient> = emptyList(),
    val subject: String,
    val bodyHtml: String,
    val attachments: List<ResolvedAttachment> = emptyList(),
    val saveToSentItems: Boolean = true,
)

internal data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Int,
) {
    // Same reasoning as GraphCredentials.toString() — accessToken is a live, directly usable
    // bearer credential for the token's full lifetime. Kotlin's generated toString() would
    // print it in plaintext if this object ever reached a debug log line or a failed assertion.
    override fun toString(): String = "TokenResponse(accessToken=***, tokenType=$tokenType, expiresIn=$expiresIn)"
}

data class SendMailRequest(
    @JsonProperty("message") val message: GraphMessage,
    @JsonProperty("saveToSentItems") val saveToSentItems: Boolean,
)

data class GraphMessage(
    @JsonProperty("subject") val subject: String,
    @JsonProperty("body") val body: GraphBody,
    @JsonProperty("toRecipients") val toRecipients: List<GraphRecipient>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("ccRecipients") val ccRecipients: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("bccRecipients") val bccRecipients: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("replyTo") val replyTo: List<GraphRecipient> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("attachments") val attachments: List<GraphAttachment> = emptyList(),
    // No `from`: the sender comes from the /users/{mailbox} path on every call, so the field was
    // never populated and Graph would reject a value that disagreed with the path anyway.
)

data class GraphBody(
    @JsonProperty("contentType") val contentType: String,
    @JsonProperty("content") val content: String,
)

data class GraphRecipient(
    @JsonProperty("emailAddress") val emailAddress: GraphEmailAddress,
)

data class GraphEmailAddress(
    @JsonProperty("address") val address: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("name") val name: String? = null,
)

data class GraphAttachment(
    @JsonProperty("@odata.type") val odataType: String = "#microsoft.graph.fileAttachment",
    @JsonProperty("name") val name: String,
    @JsonProperty("contentType") val contentType: String,
    @JsonProperty("contentBytes") val contentBytes: String,
)

// ── Upload-session models ─────────────────────────────────────────────────────

internal data class DraftMessageResponse(
    @JsonProperty("id") val id: String,
)

data class CreateUploadSessionRequest(
    @JsonProperty("AttachmentItem") val attachmentItem: UploadAttachmentItem,
)

data class UploadAttachmentItem(
    @JsonProperty("attachmentType") val attachmentType: String = "file",
    @JsonProperty("name") val name: String,
    @JsonProperty("size") val size: Long,
    @JsonProperty("contentType") val contentType: String,
)

// Returned by each chunk PUT while an upload is still in progress. The final chunk returns the
// created attachment instead, with no nextExpectedRanges — hence the nullable field.
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class UploadChunkResponse(
    @JsonProperty("nextExpectedRanges") val nextExpectedRanges: List<String>? = null,
)

internal data class UploadSessionResponse(
    @JsonProperty("uploadUrl") val uploadUrl: String,
    @JsonProperty("expirationDateTime") val expirationDateTime: String? = null,
)
