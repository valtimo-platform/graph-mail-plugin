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

internal fun isValidEmail(value: String) =
    value.length <= 254 && !value.contains("..") && EMAIL_REGEX.matches(value)

// Header injection guard: any CR or LF inside a header-bearing field is rejected.
// Defense-in-depth — the Graph API JSON encodes them anyway, but the same fields are
// echoed in logs and could feed downstream systems with weaker handling.
internal fun containsControlChars(value: String?): Boolean =
    value != null && value.any { it == '\r' || it == '\n' }

internal fun requireNoControlChars(value: String?, fieldName: String) {
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

internal fun maskEmails(addresses: Collection<String>): String =
    addresses.joinToString(", ") { maskEmail(it) }

// Matches any email-like token in a freeform string — used to mask PII in error messages
// before they reach Spring Application Events / audit logs. Pattern kept in sync with EMAIL_REGEX.
internal val EMAIL_IN_TEXT_REGEX =
    Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9][a-zA-Z0-9.\\-]*\\.[a-zA-Z]{2,}")
