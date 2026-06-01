package com.ritense.valtimoplugins.graphmail

data class GraphMailTestSendRequest(
    val pluginConfigurationId: String,
    val recipient: String,
    val senderMailbox: String,
)
