package com.ritense.valtimoplugins.graphmail

data class GraphMailEmailSentEvent(
    val senderMailbox: String,
    val recipientCount: Int,
    val ccCount: Int,
    val bccCount: Int,
    val attachmentCount: Int,
    val durationMs: Long,
)
