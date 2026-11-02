package com.ritense.valtimoplugins.graphmail

/**
 * Contract for the Graph Mail client.
 *
 * The login comes from the caller ([GraphMailAuthentication]): the Http Client Authentication plugin fetches and
 * caches the token, the client asks it per request and sends with its certificates.
 *
 * Separated interface enables easy mocking in tests with any framework
 * (Mockito, MockK, Spring Test) without coupling to the HTTP implementation.
 */
interface GraphMailClient {
    fun sendMail(
        authentication: GraphMailAuthentication,
        mail: OutboundMail,
    )
}
