package com.ritense.valtimoplugins.graphmail

internal val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal val EMAIL_REGEX =
    Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9][a-zA-Z0-9.\\-]*\\.[a-zA-Z]{2,}$")

internal fun isValidUuid(value: String) = UUID_REGEX.matches(value)

// Resource IDs in Valtimo's TemporaryResourceStorageService are not always UUIDs —
// some implementations use numeric or composite formats. We only block obvious
// path-traversal patterns; UUID validation would be too restrictive.
internal fun isValidResourceId(value: String): Boolean {
    if (value.isBlank()) return false
    if (containsControlChars(value)) return false
    if (value.contains("../") || value.contains("..\\")) return false
    return true
}

internal fun isValidEmail(value: String) = value.length <= 254 && !value.contains("..") && EMAIL_REGEX.matches(value)

// Characters that would change the shape of the token request rather than name a tenant.
// tenantId is interpolated into the token URL path (/{tenantId}/oauth2/v2.0/token), so a '/'
// adds a path segment, '?' starts a query string and '#' truncates the path.
private val TENANT_STRUCTURAL_CHARS = charArrayOf('/', '?', '#', '\\', '@')

/**
 * Deliberately *not* a UUID check, even though the admin UI asks for one. Entra accepts three
 * shapes for the tenant segment: a directory GUID, a verified domain name such as
 * `contoso.onmicrosoft.com`, and the aliases `common` / `organizations` / `consumers`. Demanding a
 * GUID here would reject configurations that work today.
 *
 * What this does rule out is a value that cannot be a tenant identifier at all and would instead
 * reshape the request — the host is pinned by the endpoint allowlist, so this is input hygiene and
 * a readable error rather than a way out of the tenant, but a blank or structurally broken value
 * currently only surfaces as a bare 400 from Entra whose message points at the wrong thing.
 */
internal fun isValidTenantId(value: String): Boolean {
    if (value.isBlank() || value.length > 253) return false
    if (containsControlChars(value)) return false
    if (value.any { it.isWhitespace() }) return false
    return value.none { it in TENANT_STRUCTURAL_CHARS }
}

// Sender allowlist check (deny-by-default). An entry is either a full address
// ("noreply@example.com") or a domain entry starting with '@' ("@example.com").
// Matching is case-insensitive. An empty allowlist permits nothing.
// The domain match anchors on the sender's own '@' separator, so "@example.com"
// does not match "user@sub.example.com".
internal fun isSenderAllowed(
    sender: String,
    allowlist: List<String>,
): Boolean {
    val normalizedSender = sender.trim().lowercase()
    val senderAt = normalizedSender.indexOf('@')
    if (senderAt <= 0) return false
    return allowlist.any { entry ->
        val normalizedEntry = entry.trim().lowercase()
        when {
            normalizedEntry.length < 2 -> false
            normalizedEntry.startsWith("@") ->
                normalizedSender.substring(senderAt) == normalizedEntry
            else -> normalizedSender == normalizedEntry
        }
    }
}

// Header injection guard: any CR or LF inside a header-bearing field is rejected.
// Defense-in-depth — the Graph API JSON encodes them anyway, but the same fields are
// echoed in logs and could feed downstream systems with weaker handling.
internal fun containsControlChars(value: String?): Boolean = value != null && value.any { it == '\r' || it == '\n' }

internal fun requireNoControlChars(
    value: String?,
    fieldName: String,
) {
    require(!containsControlChars(value)) {
        "Field '$fieldName' must not contain CR or LF characters"
    }
}

// Mask the local part of an email for logs: `foo.bar@example.com` -> `f***@example.com`.
// Reduces PII exposure when DEBUG logging is briefly enabled in production.
internal fun maskEmail(address: String): String {
    val at = address.indexOf('@')
    if (at <= 0) return "***"
    val local = address.substring(0, at)
    val domain = address.substring(at)
    val first = local.firstOrNull()?.toString() ?: ""
    return "$first***$domain"
}

internal fun maskEmails(addresses: Collection<String>): String = addresses.joinToString(", ") { maskEmail(it) }

// Convenience wrapper for the common "mask any address inside a free-form message" case.
internal fun maskEmailsInText(text: String?): String? = text?.replace(EMAIL_IN_TEXT_REGEX) { maskEmail(it.value) }

// Matches any email-like token in a freeform string — used to mask PII in error messages
// before they reach Spring Application Events / audit logs. Pattern kept in sync with EMAIL_REGEX.
internal val EMAIL_IN_TEXT_REGEX =
    Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9][a-zA-Z0-9.\\-]*\\.[a-zA-Z]{2,}")
