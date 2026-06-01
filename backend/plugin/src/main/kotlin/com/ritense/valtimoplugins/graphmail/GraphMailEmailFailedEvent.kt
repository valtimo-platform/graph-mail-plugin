package com.ritense.valtimoplugins.graphmail

data class GraphMailEmailFailedEvent(
    val senderMailbox: String,
    val recipientCount: Int,
    val reason: String,
    val durationMs: Long,
)
