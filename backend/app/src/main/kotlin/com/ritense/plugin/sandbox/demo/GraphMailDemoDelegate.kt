/*
 * Copyright 2026 Ritense BV, the Netherlands.
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

package com.ritense.plugin.sandbox.demo

import com.ritense.processdocument.service.ValueResolverDelegateService
import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.annotation.ProcessBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * Glue for the `graph-mail` demo case in `config/case/graph-mail/1-0-0`. Sandbox-app scaffolding
 * only — the plugin itself does not need it.
 *
 * It exists because the Graph Mail plugin takes its body and attachments as temporary resource
 * storage IDs rather than as process variables, and Valtimo ships no BPMN-callable bean that puts
 * content *into* that storage (`ResourceStorageDelegate` only reads and deletes). Without this the
 * demo processes could not produce a `contentId`.
 *
 * Registered under the bean name `graphMailDemo`, which is how the BPMN expressions address it.
 * `@ProcessBean` is what makes it addressable at all: Valtimo replaces Operaton's expression
 * manager with one backed by a whitelist (`OperatonWhitelistedBeansPlugin`), so a plain
 * `@Component` resolves to "Cannot resolve identifier" inside a BPMN expression.
 */
@ProcessBean
@Component("graphMailDemo")
class GraphMailDemoDelegate(
    private val storageService: TemporaryResourceStorageService,
    private val valueResolverDelegateService: ValueResolverDelegateService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Writes `doc:/bodyHtml` to temporary resource storage and returns the resource ID, which the
     * BPMN captures in the `contentId` process variable.
     */
    fun storeBody(execution: DelegateExecution): String {
        val bodyHtml = resolve(execution, "doc:/bodyHtml") as? String ?: DEFAULT_BODY_HTML
        val contentId = store(bodyHtml.toByteArray(Charsets.UTF_8), "email-body.html", "text/html")
        logger.debug { "Stored demo email body as resource '$contentId' (${bodyHtml.length} chars)" }
        return contentId
    }

    /**
     * Generates the attachments configured on the case and returns their resource IDs as a comma
     * separated string — one of the three formats the plugin's action property accepts.
     *
     * The filler file is sized from `doc:/attachments/largeFileSizeKb` so a single case can drive
     * either send path: at or below 2 MB the plugin embeds the attachment in the `sendMail` body,
     * above it the plugin creates a draft and uploads in chunks.
     */
    fun storeAttachments(execution: DelegateExecution): String {
        val ids = mutableListOf<String>()

        if (resolve(execution, "doc:/attachments/includeSmallFile") == true) {
            ids += store(SMALL_ATTACHMENT_TEXT.toByteArray(Charsets.UTF_8), "voorwaarden.txt", "text/plain")
        }

        val largeFileSizeKb = (resolve(execution, "doc:/attachments/largeFileSizeKb") as? Number)?.toInt() ?: 0
        if (largeFileSizeKb > 0) {
            ids += store(filler(largeFileSizeKb * 1024), "grote-bijlage.txt", "text/plain")
        }

        logger.debug { "Generated ${ids.size} demo attachment(s)" }
        return ids.joinToString(",")
    }

    /**
     * Records the outcome on the case. The plugin action returns nothing, so anything the case
     * should show has to be written by the process (or derived from `GraphMailEmailSentEvent`).
     */
    fun recordDelivery(
        execution: DelegateExecution,
        status: String,
    ) {
        valueResolverDelegateService.handleValue(
            execution,
            "doc:/delivery",
            mapOf(
                "status" to status,
                "sentAt" to Instant.now().toString(),
                "contentId" to (execution.getVariable("contentId") as? String).orEmpty(),
                "attachmentIds" to (execution.getVariable("attachmentIds") as? String).orEmpty(),
            ),
        )
    }

    // Optional document properties resolve to null when absent; a failure to resolve should not
    // sink the demo process, so fall back to the same null and let the caller pick a default.
    private fun resolve(
        execution: DelegateExecution,
        key: String,
    ): Any? =
        runCatching { valueResolverDelegateService.resolveValue(execution, key) }
            .onFailure { logger.debug(it) { "Could not resolve '$key' for the demo process" } }
            .getOrNull()

    private fun store(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): String =
        storageService.store(
            ByteArrayInputStream(bytes),
            // MetadataType.FILE_NAME.key is "filename", not "fileName" — writing the wrong key
            // leaves the plugin naming every attachment after its bare resource UUID.
            mapOf(
                MetadataType.FILE_NAME.key to fileName,
                MetadataType.CONTENT_TYPE.key to contentType,
            ),
        )

    private fun filler(sizeBytes: Int): ByteArray {
        val line = FILLER_LINE.toByteArray(Charsets.UTF_8)
        val out = ByteArray(sizeBytes)
        var offset = 0
        while (offset < sizeBytes) {
            val length = minOf(line.size, sizeBytes - offset)
            line.copyInto(out, offset, 0, length)
            offset += length
        }
        return out
    }

    private companion object {
        const val DEFAULT_BODY_HTML = "<html><body><p>Demo mail via Microsoft Graph.</p></body></html>"

        const val SMALL_ATTACHMENT_TEXT =
            "Voorwaarden\n" +
                "===========\n\n" +
                "Dit is een demo-bijlage van de Graph Mail plugin. Hij blijft ruim onder de\n" +
                "drempel van 2 MB en gaat daarom inline mee in het sendMail-verzoek.\n"

        const val FILLER_LINE =
            "Vulregel voor de grote demo-bijlage van de Graph Mail plugin. " +
                "Boven 2 MB schakelt de plugin over op de upload-sessie.\n"
    }
}
