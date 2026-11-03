package com.ritense.valtimoplugins.graphmail

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment

internal const val REQUIRE_SECRET_FOR_ALLOWLIST_CHANGE = "graph-mail.require-secret-for-allowlist-change"

/**
 * Says on startup that `graph-mail.require-secret-for-allowlist-change` no longer does anything.
 *
 * Up to 1.0 it switched a guard that required the client secret to be re-entered when the sender allowlist changed.
 * The secret now lives in the Http Client Authentication configuration, so that guard is gone (see the release notes
 * of 1.1.0). A deployment that set the property relied on it, or switched it off deliberately; either way an ignored
 * setting must not look like an active control.
 */
class GraphMailRemovedSettingWarning(
    private val environment: Environment,
) {
    private val logger = LoggerFactory.getLogger(GraphMailRemovedSettingWarning::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun warnWhenSet() {
        val value = environment.getProperty(REQUIRE_SECRET_FOR_ALLOWLIST_CHANGE) ?: return
        logger.warn(
            "[Graph Mail Plugin] {}={} is set, but this setting was removed in Graph Mail 1.1.0 and is ignored: " +
                "changing the allowed senders no longer requires the client secret. Remove the setting, and limit " +
                "the mailboxes in Microsoft 365 instead (an Exchange Online Application Access Policy); see " +
                "'Wie bij het secret kan' in documentation/handleiding.md.",
            REQUIRE_SECRET_FOR_ALLOWLIST_CHANGE,
            value,
        )
    }
}
