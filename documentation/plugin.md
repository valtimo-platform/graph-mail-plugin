# Graph Mail Plugin Documentatie

Verstuur e-mails via de Microsoft Graph API met OAuth2 (Client Credentials flow).

## Vereisten

Een **Azure App Registration** met de volgende instellingen:

- Applicatiemachtiging: `Mail.Send` (niet delegated) — vereist voor alle e-mailverzendingen
- Applicatiemachtiging: `Mail.ReadWrite` (niet delegated) — vereist voor bijlagen groter dan 3 MB; de plugin maakt dan eerst een conceptbericht aan via de Graph API upload-sessie flow
- Een client secret aangemaakt onder *Certificates & secrets*

> **Let op:** zonder `Mail.ReadWrite` mislukt het versturen van bijlagen groter dan 3 MB met een 403-fout. Voor e-mails zonder bijlagen of met bijlagen tot 3 MB is alleen `Mail.Send` voldoende.

## Pluginconfiguratie

Maak een pluginconfiguratie aan in Valtimo via **Admin → Plugins → Graph Mail Plugin**.

| Eigenschap | Beschrijving | Verplicht |
|------------|-------------|-----------|
| `tenantId` | Azure Directory (tenant) ID | Ja |
| `clientId` | Azure Application (client) ID | Ja |
| `clientSecret` | Client secret van de App Registration | Ja |
| `testSenderMailbox` | Standaard afzenderadres voor de test-send functie | Nee |

## Actie: send-email

Verstuur een e-mail vanuit een BPMN-serviceTask.

| Parameter | Beschrijving | Verplicht |
|-----------|-------------|-----------|
| `senderMailbox` | E-mailadres van de afzender | Ja |
| `recipients` | Ontvangers — enkelvoudig adres, kommalijst of JSON-array | Ja |
| `cc` | CC-ontvangers | Nee |
| `bcc` | BCC-ontvangers | Nee |
| `replyTo` | Reply-To adressen | Nee |
| `subject` | Onderwerp van de e-mail | Ja |
| `contentId` | Resource-ID van de HTML-body in tijdelijke opslag | Ja |
| `attachmentIds` | Resource-ID('s) van bijlagen in tijdelijke opslag | Nee |

## Aandachtspunten

**Weergavenaam afzender**
De weergavenaam die de ontvanger ziet, is de Display Name die is ingesteld op de afzendermailbox in Microsoft 365. De plugin heeft geen mogelijkheid om de weergavenaam te overschrijven. Pas de gewenste weergavenaam aan via het Microsoft 365 Admin Center.

**Opslaan in Verzonden items**
E-mails verzonden via de `send-email` actie worden opgeslagen in de Sent Items van de afzendermailbox. E-mails verstuurd via de test-send functie op de configuratiepagina worden *niet* opgeslagen.

**Bijlagen — twee verzendpaden**
Bijlagen van 3 MB of kleiner worden inline (base64) meegestuurd in de sendMail-aanroep. Bijlagen groter dan 3 MB worden automatisch via een Graph API upload-sessie verstuurd (concept → chunked upload → verzenden). Bij de upload-sessie is het verzendtijdstip het moment van de definitieve verzendaanroep, niet het moment van conceptaanmaak.

**Dubbele verzending bij transactieretry**
De plugin-actie vuurt op `SERVICE_TASK_START`. Als de Operaton-transactie terugdraait en opnieuw start (bijvoorbeeld bij een optimistic lock conflict), kan de e-mail meer dan één keer worden verzonden. Mitigatie: sla een idempotency-token op als procesvariabele en dedupliceer aan de ontvangerskant.

**HTML-body sanitisatie**
De HTML-body wordt automatisch gesanitiseerd via jsoup vóór verzending. Toegestaan: opmaaktags, tabellen, inline `style`-attributen, `<img>` met http/https/cid-bronnen. Verwijderd: `<style>`-blokken, `<script>`, iframes, `data:` URI's, JavaScript-eventattributen. Als de body na sanitisatie leeg is, gooit de plugin een fout — controleer de HTML-inhoud die is opgeslagen op het opgegeven `contentId`.

**Limieten**

| Limiet | Waarde |
|--------|--------|
| Max ontvangers per veld (To / Cc / Bcc) | 100 |
| Max ontvangers totaal (To + Cc + Bcc) | 200 |
| Max onderwerpregel | 255 tekens |
| Max body-grootte | 5 MB |
| Max bijlagen | 5 |
| Max grootte per bijlage | 25 MB |
| Max totale bijlagegrootte | 25 MB |

**Rate limiting test-send**
Het test-send endpoint staat maximaal 1 verzoek per gebruiker per 10 seconden toe. De teller wordt in geheugen bijgehouden per JVM-instantie. Bij een multi-node deployment geldt de limiet per node afzonderlijk.

**Job executor thread-blokkering — verplichte configuratie**

De retry-backoff gebruikt `Thread.sleep()`, waardoor de aanroepende Operaton job-executor thread geblokkeerd wordt tijdens het wachten op een nieuwe poging. Maximale blokkeerttijden per verzending:

| Situatie | Maximale blokkeerttijd |
|----------|----------------------|
| Reguliere verzending (geen grote bijlagen) | 30 seconden |
| Verzending via upload-sessie (bijlage > 3 MB) | 120 seconden |
| 429 rate-limit sleep per poging (max) | 15 seconden |

Als meerdere processen tegelijk e-mails versturen terwijl de Graph API rate-limiteert, kunnen alle job-executor threads tegelijkertijd geblokkeerd worden. Dit stopt de verwerking van alle andere Operaton-taken in de applicatie.

**Minimum vereiste configuratie — voeg dit toe aan `application.yml`:**

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

Bij minder dan 20 threads loop je een reëel risico op een vastgelopen job-executor onder normale productielast. De plugin logt een waarschuwing bij opstarten als herinnering.

## Test-send

Via de pluginconfiguratiepagina in Valtimo kan een testmail worden verstuurd om te verifiëren dat de Azure-credentials correct zijn geconfigureerd. Dit vereist de rol `ROLE_ADMIN`.
