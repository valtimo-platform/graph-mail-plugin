/*
 * Copyright 2015-2025 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate

class GraphMailClientTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var client: GraphMailClientImpl

    private val token = "test-token-123"
    private val mailbox = "noreply@test.nl"
    private val tenantId = "tenant-A"
    private val clientId = "client-A"
    private val clientSecret = "secret-A"
    private val tokenPath = ".*/oauth2/v2.0/token"
    private val mailPath = ".*/sendMail"

    private fun tokenJson(t: String = token, exp: Int = 3600) =
        """{"access_token":"$t","token_type":"Bearer","expires_in":$exp}"""

    private fun stubToken(t: String = token, exp: Int = 3600) {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(okJson(tokenJson(t, exp))))
    }

    private fun recipients(vararg addresses: String) =
        addresses.map { GraphRecipient(GraphEmailAddress(address = it)) }

    private fun sendBasic(saveToSentItems: Boolean = true) =
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "Test", "<p>Test</p>", emptyList(), saveToSentItems)

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        val mapper = ObjectMapper().registerKotlinModule()
        val rest = RestTemplate().apply {
            messageConverters.removeIf { it is MappingJackson2HttpMessageConverter }
            messageConverters.add(0, MappingJackson2HttpMessageConverter(mapper))
        }

        client = GraphMailClientImpl(RestClient.create(rest), wireMock.baseUrl(), wireMock.baseUrl())
    }

    @AfterEach
    fun tearDown() = wireMock.stop()

    // ── Token ──────────────────────────────────────────────────────────────

    @Test fun `fetches token`() {
        stubToken()
        assertEquals(token, client.getAccessToken("t", "c", "s"))
    }

    @Test fun `sends correct credentials`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath))
            .withRequestBody(containing("client_id=myid"))
            .withRequestBody(containing("client_secret=mysecret"))
            .willReturn(okJson(tokenJson())))
        client.getAccessToken("t", "myid", "mysecret")
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `caches token on second call`() {
        stubToken()
        client.getAccessToken("t", "c", "s")
        client.getAccessToken("t", "c", "s")
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `fetches new token after cache expiry`() {
        stubToken(exp = 59) // < TOKEN_EXPIRY_BUFFER_SECONDS so each call refreshes
        client.getAccessToken("t", "c", "s")
        client.getAccessToken("t", "c", "s")
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `different tenants use separate cache entries`() {
        stubToken()
        client.getAccessToken("tenant1", "c", "s")
        client.getAccessToken("tenant2", "c", "s")
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `throws on 400 token request`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(aResponse().withStatus(400)))
        assertThrows(GraphMailException::class.java) { client.getAccessToken("t", "c", "s") }
    }

    @Test fun `retries token on 503 then succeeds`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).inScenario("token-5xx")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(503))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(tokenPath)).inScenario("token-5xx")
            .whenScenarioStateIs("ok").willReturn(okJson(tokenJson())))
        assertEquals(token, client.getAccessToken("t", "c", "s"))
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `gives up on token after MAX retries on 503`() {
        wireMock.stubFor(post(urlPathMatching(tokenPath)).willReturn(aResponse().withStatus(503)))
        assertThrows(GraphMailException::class.java) { client.getAccessToken("t", "c", "s") }
    }

    @Test fun `invalidateCache key-scoped only clears that key`() {
        stubToken()
        client.getAccessToken("tenant1", "client1", "s")
        client.getAccessToken("tenant2", "client2", "s")
        client.invalidateCache("tenant1", "client1")
        client.getAccessToken("tenant1", "client1", "s")
        client.getAccessToken("tenant2", "client2", "s")
        // tenant1 fetched twice (initial + post-invalidate); tenant2 fetched once total
        wireMock.verify(3, postRequestedFor(urlPathMatching(tokenPath)))
    }

    @Test fun `invalidateCache full flush clears everything`() {
        stubToken()
        client.getAccessToken("tenant1", "c", "s")
        client.getAccessToken("tenant2", "c", "s")
        client.invalidateCache()
        client.getAccessToken("tenant1", "c", "s")
        client.getAccessToken("tenant2", "c", "s")
        wireMock.verify(4, postRequestedFor(urlPathMatching(tokenPath)))
    }

    // ── SendMail success ────────────────────────────────────────────────────

    @Test fun `sends mail successfully — fetches token first`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath))
            .withHeader("Authorization", equalTo("Bearer $token"))
            .willReturn(aResponse().withStatus(202)))
        sendBasic()
        wireMock.verify(1, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `saveToSentItems false in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic(saveToSentItems = false)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""saveToSentItems":false"""))
    }

    @Test fun `saveToSentItems true in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic(saveToSentItems = true)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""saveToSentItems":true"""))
    }

    // ── JSON structure ───────────────────────────────────────────────────────

    @Test fun `3 recipients exact in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("a@t.nl", "b@t.nl", "c@t.nl"), emptyList(), emptyList(), emptyList(),
            "Sub", "<p>B</p>", emptyList(), true)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""address":"a@t.nl""""))
        assertTrue(body.contains(""""address":"b@t.nl""""))
        assertTrue(body.contains(""""address":"c@t.nl""""))
    }

    @Test fun `CC and BCC present in JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("to@t.nl"), recipients("cc@t.nl"), recipients("bcc@t.nl"), emptyList(),
            "T", "<p>B</p>", emptyList(), true)
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertTrue(body.contains(""""ccRecipients"""))
        assertTrue(body.contains(""""bccRecipients"""))
    }

    @Test fun `empty ccRecipients omitted from JSON`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        sendBasic()
        val body = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0].bodyAsString
        assertFalse(body.contains(""""ccRecipients"""))
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test fun `401 once triggers refresh and retry, succeeds`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("401-then-ok")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(401))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("401-then-ok")
            .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(202)))
        sendBasic()
        // First send 401 → cache invalidated → fresh token fetched → retry sends 202
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `401 twice throws GraphMailTokenExpiredException`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(401)))
        assertThrows(GraphMailTokenExpiredException::class.java) { sendBasic() }
    }

    @Test fun `403 throws with status`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(403)))
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        assertTrue(ex.message!!.contains("403"))
    }

    @Test fun `429 rate limit retries`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("rl")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("rl")
            .whenScenarioStateIs("ok")
            .willReturn(aResponse().withStatus(202)))
        sendBasic()
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `429 exhausts all MAX_RETRIES on sendMail`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")))
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        assertTrue(ex.message!!.contains("Rate limited") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `three consecutive 429s on sendMail then success`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("3x-429")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("r1"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("3x-429")
            .whenScenarioStateIs("r1")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("r2"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("3x-429")
            .whenScenarioStateIs("r2")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("3x-429")
            .whenScenarioStateIs("ok")
            .willReturn(aResponse().withStatus(202)))
        sendBasic()
        wireMock.verify(4, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `retry on 503 succeeds on 2nd attempt`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("r")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(503)).willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(mailPath)).inScenario("r")
            .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(202)))
        sendBasic()
        wireMock.verify(2, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `fails after 5 retries on 503`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(503)))
        val ex = assertThrows(GraphMailException::class.java) { sendBasic() }
        assertTrue(ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(mailPath)))
    }

    @Test fun `empty recipients throws IllegalArgumentException`() {
        stubToken()
        assertThrows(IllegalArgumentException::class.java) {
            client.sendMail(tenantId, clientId, clientSecret, mailbox, emptyList(),
                emptyList(), emptyList(), emptyList(), "T", "<p>B</p>", emptyList(), true)
        }
    }

    @Test fun `mailbox with special chars is URL-encoded in path`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        // + sign in mailbox local part should not break the URI
        client.sendMail(tenantId, clientId, clientSecret, "user+tag@test.nl",
            recipients("jan@t.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", emptyList(), true)
        val req = wireMock.findAll(postRequestedFor(urlPathMatching(mailPath)))[0]
        assertTrue(req.url.contains("user%2Btag%40test.nl") || req.url.contains("user+tag@test.nl"),
            "Expected encoded or original mailbox in path, got: ${req.url}")
    }

    // ── Attachment routing ────────────────────────────────────────────────────

    private fun resolvedAttachment(name: String, sizeBytes: Int): ResolvedAttachment =
        ResolvedAttachment(name = name, contentType = "application/octet-stream",
            rawBytes = ByteArray(sizeBytes))

    private val draftPath = ".*/messages$"
    private val uploadSessionPath = ".*/attachments/createUploadSession$"
    private val sendDraftPath = ".*/messages/.*/send$"

    private fun stubDraftCreate(draftId: String = "draft-1") {
        wireMock.stubFor(post(urlPathMatching(draftPath))
            .willReturn(okJson("""{"id":"$draftId"}""")))
    }

    private fun stubUploadSession(uploadUrl: String) {
        wireMock.stubFor(post(urlPathMatching(uploadSessionPath))
            .willReturn(okJson("""{"uploadUrl":"$uploadUrl"}""")))
    }

    private fun stubSendDraft() {
        wireMock.stubFor(post(urlPathMatching(sendDraftPath))
            .willReturn(aResponse().withStatus(202)))
    }

    @Test fun `small attachment stays on inline sendMail path`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(mailPath)).willReturn(aResponse().withStatus(202)))
        val attachment = resolvedAttachment("small.pdf", INLINE_ATTACHMENT_THRESHOLD_BYTES.toInt())
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", listOf(attachment), true)
        wireMock.verify(1, postRequestedFor(urlPathMatching(mailPath)))
        wireMock.verify(0, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `large attachment uses draft upload path`() {
        stubToken()
        val uploadUrl = "${wireMock.baseUrl()}/upload/session-abc"
        stubDraftCreate("draft-42")
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        val attachment = resolvedAttachment("large.bin", (INLINE_ATTACHMENT_THRESHOLD_BYTES + 1).toInt())
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", listOf(attachment), true)

        wireMock.verify(0, postRequestedFor(urlPathMatching(mailPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(draftPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(uploadSessionPath)))
        wireMock.verify(1, putRequestedFor(anyUrl()))
        wireMock.verify(1, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `chunk upload sets Content-Range header correctly`() {
        stubToken()
        // attachment slightly larger than one chunk — expect 2 PUT calls
        val chunkSize = UPLOAD_CHUNK_BYTES.toInt()
        val totalSize = chunkSize + 1
        val uploadUrl = "${wireMock.baseUrl()}/upload/range-test"
        stubDraftCreate()
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", listOf(resolvedAttachment("f.bin", totalSize)), true)

        val puts = wireMock.findAll(putRequestedFor(anyUrl()))
        assertEquals(2, puts.size)
        val firstRange = puts[0].getHeader("Content-Range")
        // first chunk: bytes 0-(chunkSize-1)/totalSize
        assertEquals("bytes 0-${chunkSize - 1}/$totalSize", firstRange)
        val secondRange = puts[1].getHeader("Content-Range")
        assertEquals("bytes $chunkSize-${totalSize - 1}/$totalSize", secondRange)
    }

    // ── Draft flow: createDraft error handling ────────────────────────────────

    private fun sendLarge(attachment: ResolvedAttachment = resolvedAttachment("f.bin", (INLINE_ATTACHMENT_THRESHOLD_BYTES + 1).toInt())) =
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", listOf(attachment), true)

    @Test fun `401 on createDraft triggers cache invalidate and retry`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("draft-401")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(401))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("draft-401")
            .whenScenarioStateIs("ok").willReturn(okJson("""{"id":"draft-1"}""")))
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        // initial token + post-invalidate fresh token
        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `401 twice on createDraft throws GraphMailTokenExpiredException`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(draftPath)).willReturn(aResponse().withStatus(401)))

        assertThrows(GraphMailTokenExpiredException::class.java) { sendLarge() }
    }

    @Test fun `429 on createDraft retries and succeeds`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("draft-429")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("draft-429")
            .whenScenarioStateIs("ok").willReturn(okJson("""{"id":"draft-1"}""")))
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `429 exhausts all attempts on createDraft`() {
        stubToken()
        wireMock.stubFor(post(urlPathMatching(draftPath))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")))
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("Rate limited creating draft") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(draftPath)))
    }

    @Test fun `429 on createDraft with RFC 1123 Retry-After parses and retries`() {
        stubToken()
        // Past date → parseRetryAfter returns 0 → no sleep → fast test
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("rfc1123")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "Thu, 01 Jan 1970 00:00:00 GMT"))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(draftPath)).inScenario("rfc1123")
            .whenScenarioStateIs("ok").willReturn(okJson("""{"id":"draft-1"}""")))
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(draftPath)))
    }

    // ── Draft flow: chunk upload error handling ───────────────────────────────

    @Test fun `chunk upload retries on 503 and succeeds`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-retry"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(urlPathMatching(".*/upload/chunk-retry")).inScenario("chunk-5xx")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(503))
            .willSetStateTo("ok"))
        wireMock.stubFor(put(urlPathMatching(".*/upload/chunk-retry")).inScenario("chunk-5xx")
            .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        sendLarge()

        wireMock.verify(2, putRequestedFor(urlPathMatching(".*/upload/chunk-retry")))
    }

    @Test fun `429 on chunk upload throws immediately without retry`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl = "${wireMock.baseUrl()}/upload/chunk-429"
        stubUploadSession(uploadUrl)
        wireMock.stubFor(put(urlPathMatching(".*/upload/chunk-429"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")))
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("429"))
        wireMock.verify(1, putRequestedFor(urlPathMatching(".*/upload/chunk-429")))
    }

    // ── Draft flow: sendDraft error handling ──────────────────────────────────

    @Test fun `401 on sendDraft triggers cache invalidate and retry`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(post(urlPathMatching(sendDraftPath)).inScenario("send-401")
            .whenScenarioStateIs("Started").willReturn(aResponse().withStatus(401))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(sendDraftPath)).inScenario("send-401")
            .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(202)))

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(tokenPath)))
        wireMock.verify(2, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `429 on sendDraft retries and succeeds`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(post(urlPathMatching(sendDraftPath)).inScenario("send-429")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
            .willSetStateTo("ok"))
        wireMock.stubFor(post(urlPathMatching(sendDraftPath)).inScenario("send-429")
            .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(202)))

        sendLarge()

        wireMock.verify(2, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    @Test fun `429 exhausts all attempts on sendDraft`() {
        stubToken()
        stubDraftCreate()
        stubUploadSession("${wireMock.baseUrl()}/upload/s1")
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        wireMock.stubFor(post(urlPathMatching(sendDraftPath))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")))
        val ex = assertThrows(GraphMailException::class.java) { sendLarge() }
        assertTrue(ex.message!!.contains("Rate limited sending draft") && ex.message!!.contains("5 attempts"))
        wireMock.verify(5, postRequestedFor(urlPathMatching(sendDraftPath)))
    }

    // ── Draft flow: multiple attachments ──────────────────────────────────────

    @Test fun `two large attachments create two upload sessions and one draft send`() {
        stubToken()
        stubDraftCreate()
        val uploadUrl1 = "${wireMock.baseUrl()}/upload/session-1"
        val uploadUrl2 = "${wireMock.baseUrl()}/upload/session-2"
        wireMock.stubFor(post(urlPathMatching(uploadSessionPath)).inScenario("two-sessions")
            .whenScenarioStateIs("Started")
            .willReturn(okJson("""{"uploadUrl":"$uploadUrl1"}"""))
            .willSetStateTo("second"))
        wireMock.stubFor(post(urlPathMatching(uploadSessionPath)).inScenario("two-sessions")
            .whenScenarioStateIs("second")
            .willReturn(okJson("""{"uploadUrl":"$uploadUrl2"}""")))
        wireMock.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(200)))
        stubSendDraft()

        val attachment = resolvedAttachment("f.bin", (INLINE_ATTACHMENT_THRESHOLD_BYTES + 1).toInt())
        client.sendMail(tenantId, clientId, clientSecret, mailbox,
            recipients("jan@test.nl"), emptyList(), emptyList(), emptyList(),
            "T", "<p>B</p>", listOf(attachment, attachment), true)

        wireMock.verify(2, postRequestedFor(urlPathMatching(uploadSessionPath)))
        wireMock.verify(1, postRequestedFor(urlPathMatching(sendDraftPath)))
    }
}
