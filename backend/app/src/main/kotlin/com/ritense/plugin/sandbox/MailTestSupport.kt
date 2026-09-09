/*
 * Copyright 2015-2022 Ritense BV, the Netherlands.
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

package com.ritense.plugin.sandbox

import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.annotation.ProcessBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Testondersteuning voor de Graph Mail plugin in de sandbox.
 *
 * De `send-email`-actie neemt geen HTML aan maar een resource-id in
 * [TemporaryResourceStorageService]. Zonder een stap die die opslag vult is de actie in een
 * procesmodel niet te beproeven — vandaar deze bean. Hij is bedoeld voor de sandbox en hoort
 * niet in productiecode thuis.
 *
 * Aanroepen vanuit BPMN via `${mailTest.<methode>(execution)}`.
 *
 * [ProcessBean] is niet optioneel. Valtimo geeft Operaton alleen de beans die die annotatie
 * dragen; alle andere zijn onbereikbaar vanuit een expressie, hoe correct de bean verder ook
 * geregistreerd is. Zonder deze annotatie faalt elke procesinstantie op
 * `Unknown property used in expression: ${mailTest.storeBody(execution)}. Cause: Cannot
 * resolve identifier 'mailTest'`.
 */
@ProcessBean
@Component("mailTest")
class MailTestSupport(
    private val storage: TemporaryResourceStorageService,
) {
    /**
     * Zet de mailbody in tijdelijke opslag en schrijft de resource-id weg als `bodyContentId`.
     *
     * Houdt daarnaast `mailPass` bij: het volgnummer van deze verzending binnen de
     * procesinstantie. Dat nummer komt terug in het onderwerp, zodat je bij een loop (T8) aan de
     * ontvangen mails kunt zien welke iteratie welke mail stuurde.
     */
    fun storeBody(execution: DelegateExecution) {
        normaliseHerhalingen(execution)

        val pass = ((execution.getVariable(VAR_PASS) as? Number)?.toInt() ?: 0) + 1
        execution.setVariable(VAR_PASS, pass)

        val subject =
            (execution.getVariable(VAR_SUBJECT) as? String)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_SUBJECT
        val bodyText =
            (execution.getVariable(VAR_BODY_TEXT) as? String)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_BODY_TEXT

        execution.setVariable(VAR_SUBJECT_RESOLVED, "$subject (#$pass)")

        val html = renderHtml(bodyText, pass, execution.processInstanceId)
        val id =
            storage.store(
                html.byteInputStream(StandardCharsets.UTF_8),
                mapOf(META_FILE_NAME to "body.html", META_CONTENT_TYPE to "text/html"),
            )
        execution.setVariable(VAR_BODY_CONTENT_ID, id)

        logger.info { "Testmail-body opgeslagen als resource $id (verzending #$pass)" }
    }

    /**
     * Genereert een bijlage van `attachmentKb` kilobytes en schrijft de resource-id weg als
     * `attachmentResourceIds`. Bij 0 of leeg blijft die variabele een lege string, wat de plugin
     * leest als "geen bijlagen".
     *
     * Bedoeld voor T5: met een waarde onder 2048 blijft de plugin op het inline-pad, daarboven
     * schakelt hij over op concept plus upload-sessie en is `Mail.ReadWrite` vereist.
     */
    fun storeAttachment(execution: DelegateExecution) {
        val kb = (execution.getVariable(VAR_ATTACHMENT_KB) as? Number)?.toInt() ?: 0
        if (kb <= 0) {
            execution.setVariable(VAR_ATTACHMENT_IDS, "")
            return
        }
        require(kb <= MAX_ATTACHMENT_KB) {
            "attachmentKb $kb is groter dan de sandboxlimiet van $MAX_ATTACHMENT_KB KB"
        }

        val filler = ByteArray(kb * 1024) { FILLER_BYTE }
        val id =
            storage.store(
                filler.inputStream(),
                mapOf(META_FILE_NAME to "testbijlage-${kb}kb.bin", META_CONTENT_TYPE to "application/octet-stream"),
            )
        execution.setVariable(VAR_ATTACHMENT_IDS, id)

        logger.info { "Testbijlage van $kb KB opgeslagen als resource $id" }
    }

    /**
     * Gooit bij de eerste passage van een procesinstantie, en slaagt daarna.
     *
     * Dit is de motor achter T7: staat de send-email-taak op `asyncBefore` en volgt deze taak er
     * in dezelfde transactie op, dan is de mail al onomkeerbaar bij Graph geaccepteerd wanneer de
     * transactie terugdraait. De job-executor voert de send-email-job opnieuw uit en de
     * duplicaatbescherming hoort de tweede Graph-aanroep over te slaan.
     *
     * De administratie staat bewust in geheugen en niet in een procesvariabele: die zou met de
     * transactie mee terugrollen, waardoor elke poging opnieuw de eerste zou zijn en het proces
     * nooit voorbij deze taak kwam.
     */
    fun failOnce(execution: DelegateExecution) {
        val key = execution.processInstanceId
        if (alreadyFailed.add(key)) {
            logger.info { "Forceer transactie-rollback voor procesinstantie $key (testgeval T7)" }
            throw IllegalStateException(
                "Opzettelijke fout om een transactie-rollback te forceren (testgeval T7). " +
                    "De volgende poging van deze procesinstantie slaagt.",
            )
        }
        logger.info { "Tweede passage voor procesinstantie $key — deze taak slaagt nu" }
    }

    /**
     * Maakt van `herhalingen` een geheel getal en schrijft het terug.
     *
     * Het formulierveld is een `number`, dus een gebruiker kan er `3.5` in zetten. De gateway
     * vergelijkt `mailPass < herhalingen`, en `mailPass` telt met hele stappen — bij 3,5 loopt hij
     * dus door tot 4 en verstuurt het proces vier mails in plaats van de bedoelde drie. Afkappen
     * naar beneden maakt de uitkomst gelijk aan wat er in het veld staat.
     */
    private fun normaliseHerhalingen(execution: DelegateExecution) {
        val raw = execution.getVariable(VAR_HERHALINGEN) as? Number ?: return
        val whole = raw.toInt().coerceAtLeast(1)
        if (raw.toDouble() != whole.toDouble()) {
            logger.info { "herhalingen $raw afgekapt naar $whole — de gateway telt in hele verzendingen" }
        }
        execution.setVariable(VAR_HERHALINGEN, whole)
    }

    private fun renderHtml(
        bodyText: String,
        pass: Int,
        processInstanceId: String,
    ): String =
        """
        <html>
          <body style="font-family: sans-serif;">
            <p>${escapeHtml(bodyText)}</p>
            <hr>
            <p style="color:#666; font-size: 12px;">
              Verzending #$pass &middot; procesinstantie $processInstanceId<br>
              Verstuurd door de Graph Mail plugin vanuit de sandbox.
            </p>
          </body>
        </html>
        """.trimIndent()

    // De body gaat als HTML naar Graph, dus vrije invoer uit het startformulier moet ge-escaped
    // worden. De plugin saneert de HTML zelf ook nog, maar dat ontslaat de producent er niet van
    // om geen kapotte markup aan te leveren.
    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        private val logger = KotlinLogging.logger {}

        // Groeit met een procesinstantie-id per uitgevoerde retrytest en wordt nooit
        // opgeschoond. Bewust zo gelaten: opruimen na de geslaagde tweede passage zou de
        // administratie kunnen wissen terwijl de transactie daarna alsnog terugdraait, en dan
        // faalt de volgende poging opnieuw in plaats van te slagen. Een handvol strings per
        // testrun in een sandbox weegt niet op tegen dat risico.
        private val alreadyFailed = ConcurrentHashMap.newKeySet<String>()

        private const val VAR_PASS = "mailPass"
        private const val VAR_HERHALINGEN = "herhalingen"
        private const val VAR_SUBJECT = "subject"
        private const val VAR_SUBJECT_RESOLVED = "subjectResolved"
        private const val VAR_BODY_TEXT = "bodyText"
        private const val VAR_BODY_CONTENT_ID = "bodyContentId"
        private const val VAR_ATTACHMENT_KB = "attachmentKb"
        private const val VAR_ATTACHMENT_IDS = "attachmentResourceIds"

        private const val META_FILE_NAME = "fileName"
        private const val META_CONTENT_TYPE = "contentType"

        private const val DEFAULT_SUBJECT = "Testmail Graph Mail plugin"
        private const val DEFAULT_BODY_TEXT = "Dit is een testbericht vanuit de sandbox."

        // Ruim boven de 2 MiB-drempel zodat het upload-sessiepad te beproeven is, maar begrensd
        // zodat een typefout in het formulier niet het geheugen opeet.
        private const val MAX_ATTACHMENT_KB = 8 * 1024

        private const val FILLER_BYTE: Byte = 0x41 // 'A'
    }
}
