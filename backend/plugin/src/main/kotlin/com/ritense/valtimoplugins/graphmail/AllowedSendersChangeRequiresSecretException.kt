package com.ritense.valtimoplugins.graphmail

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Raised when a plugin configuration update changes the sender allowlist without re-supplying the
 * client secret. Annotated so it surfaces as a 400 with a readable message rather than a 500.
 *
 * The message deliberately names no addresses — the allowlist is made of them, and this text reaches
 * logs and the admin UI.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class AllowedSendersChangeRequiresSecretException :
    RuntimeException(
        "Changing the sender allowlist requires the client secret to be entered again. " +
            "The allowlist controls which mailboxes this plugin may send as, so widening it is " +
            "only permitted by someone who holds the credential.",
    )
