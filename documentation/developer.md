# Developer documentatie

Bouwen, lokaal testen, installeren en de technische aandachtspunten van de Graph Mail-plugin.

Voor het inrichten van de plugin via de beheerinterface: zie [handleiding.md](handleiding.md).

## Prerequisites

- Java 17+
- Node.js >= 20, < 23
- Valtimo 13.x
- Azure App Registration met een client secret (*Certificates & secrets*) en de
  applicatiemachtigingen:
  - `Mail.Send` — vereist voor alle e-mailverzendingen
  - `Mail.ReadWrite` — vereist zodra de plugin een concept moet aanmaken: bij een losse bijlage
    van 3 MB of groter, óf wanneer alle bijlagen samen niet meer in één Graph-verzoek passen
    (ruwweg 3 MB totaal, zie [Bijlagen](#bijlagen--twee-verzendpaden))

Beide machtigingen zijn *applicatiemachtigingen* (niet delegated) en vereisen beheerdersconsent
van een tenant-/Entra-beheerder.

> Zonder `Mail.ReadWrite` mislukt de conceptaanmaak met een **403** zodra de plugin die route
> nodig heeft. Voor mail zonder bijlagen — of waarbij alle bijlagen samen in één verzoek
> passen — volstaat `Mail.Send`.

## Plugin development

De broncode staat in:

- Backend: `backend/plugin/src/`
- Frontend: `frontend/projects/plugin/src/`

Zie ook de Valtimo-documentatie over
[Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition).

De plugin registreert zich onder de key `entra`, met één actie `send-email` op
`SERVICE_TASK_START`.

## Build

### Backend

```shell
cd backend
./gradlew build
```

Tests uitvoeren:

```shell
./gradlew test
```

### Frontend

```shell
cd frontend
npm install
npm run build
```

De bibliotheek los bouwen:

```shell
npx ng build @valtimo-plugins/graph-mail
```

## Lokaal testen zonder Azure-tenant

De ontwikkel-stack bevat een **dummy Microsoft Graph** (MockServer, poort 1080) die zowel
`login.microsoftonline.com` als `graph.microsoft.com` nabootst. Daarmee kun je de plugin
end-to-end draaien zonder App Registration.

```shell
./gradlew :backend:app:bootRun
```

`bootRun` start de docker-compose stack (inclusief de mock) en zet de applicatie op
`http://localhost:8080`. Bij het opstarten wordt automatisch een pluginconfiguratie
**"Graph Mail (dummy)"** aangemaakt die naar de mock wijst — zie
`backend/app/src/main/resources/config/plugin/graph-mail.pluginconfig.json`.

Alleen de mock starten:

```shell
docker compose -f backend/app/docker-compose.yml up -d mockserver
```

De stubs staan in `backend/app/imports/mockserver/initializerJson.json` en dekken het token-
endpoint, de inline `sendMail` en de volledige conceptflow (concept → bijlage toevoegen met een
gewone POST → createUploadSession → chunked PUT → verzenden → concept opruimen).

### De demo-dossierdefinitie

De sandbox-app deployt een dossierdefinitie **Graph Mail** (`config/case/graph-mail/1-0-0`) met
twee startbare processen. Beide zetten de HTML-body eerst in de temporary resource storage — de
plugin leest `contentId` daaruit, niet uit een procesvariabele — en schrijven na afloop het
resultaat terug op het dossier (tab *Summary*).

| Proces | Wat het laat zien |
| --- | --- |
| `graph-mail-send-email` | Basisverzending: afzender, to/cc/bcc/reply-to als komma-gescheiden lijst, onderwerp en HTML-body. De standaard-body bevat bewust een `<script>`-tag: die hoort door de sanitizer verwijderd te zijn in wat er daadwerkelijk verstuurd wordt. |
| `graph-mail-send-email-with-attachments` | Genereert bijlagen in de temporary resource storage en geeft de resource-ID's komma-gescheiden door. De plugin kiest de route **per bijlage**. Met de standaardwaarden (`grote-bijlage.txt` van 3072 KB) gaat die ene bijlage via een upload-sessie en wordt `voorwaarden.txt` met een gewone POST aan hetzelfde concept gehangen. Zet het formaat op 1024 KB om beide bijlagen in één `sendMail` te krijgen. |

Start een zaak via **Dossiers → Graph Mail → Nieuw**, of via de proceslijst.

De verzendtaak staat op `asyncBefore` met één poging (`R1/PT10S`) in plaats van de standaard
drie. De plugin heeft geen idempotency-guard, dus een automatische retry kan dezelfde mail
nogmaals afleveren; één poging en daarna een incident is voor een demo het eerlijkere gedrag.

Het aanmaken van demo-content in de temporary resource storage gebeurt door
`GraphMailDemoDelegate` (bean `graphMailDemo`) in de sandbox-app. Valtimo biedt daar zelf geen
BPMN-aanroepbare bean voor — `ResourceStorageDelegate` kan alleen lezen en verwijderen. Die
klasse hoort dus bij de demo, niet bij de plugin.

### Verifiëren wat er verstuurd is

De mock accepteert alles en gooit het weg, dus inspecteer het request-log om te zien wat de
plugin daadwerkelijk heeft verstuurd — ontvangers, de gesanitiseerde HTML-body en de
**bestandsnamen van de bijlagen**:

```shell
backend/app/imports/mockserver/inspect-last-mail.py          # laatste verzending
backend/app/imports/mockserver/inspect-last-mail.py --all    # alle verzendingen
```

Het script schrijft de HTML-body en de bijlagen naar een tijdelijke map, zodat je de mail in
een browser kunt openen en de bijlagen kunt controleren.

> **Let op:** gebruik `PUT /mockserver/clear?type=LOG` om het request-log leeg te maken.
> `PUT /mockserver/reset` wist óók de expectations, waardoor de mock 404's gaat teruggeven
> totdat de container herstart wordt (`MOCKSERVER_WATCH_INITIALIZATION_JSON=false`).

### Tegen een echte tenant aan testen

De pluginconfiguratie leest omgevingsvariabelen met een fallback naar de dummy-waarden, dus je
kunt zonder codewijziging naar echt Azure omschakelen:

```shell
export GRAPH_MAIL_TENANT_ID=<tenant-uuid>
export GRAPH_MAIL_CLIENT_ID=<client-uuid>
export GRAPH_MAIL_CLIENT_SECRET=<secret>
export GRAPH_MAIL_SENDER=noreply@jouwdomein.nl
export GRAPH_MAIL_TOKEN_BASE_URL=https://login.microsoftonline.com
export GRAPH_MAIL_BASE_URL=https://graph.microsoft.com
```

## Installatie in je Valtimo-project

### Backend

Voeg de volgende dependency toe aan je `build.gradle.kts`:

```kotlin
implementation("com.ritense.valtimoplugins:graph-mail:1.0.3")
```

Voeg de volgende configuratie toe aan je `application.yml`:

```yaml
operaton:
  bpm:
    job-executor:
      core-pool-size: 20
      max-pool-size: 50
```

> **Verplicht:** zonder voldoende job-executor threads kan de applicatie vastlopen als de Graph
> API rate-limiteert. Zie [Job executor thread-blokkering](#job-executor-thread-blokkering) voor
> details.

### Frontend

```shell
npm install @valtimo-plugins/graph-mail
```

Voeg de module en specificatie toe aan je `AppModule`:

```typescript
import { NgModule } from '@angular/core';
import { PLUGINS_TOKEN } from '@valtimo/plugin';
import { GraphMailPluginModule, graphMailPluginSpecification } from '@valtimo-plugins/graph-mail';

@NgModule({
  imports: [
    // ... andere imports
    GraphMailPluginModule,
  ],
  providers: [
    { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification] },
  ],
})
export class AppModule {}
```

> Als je meerdere plugins registreert, combineer je de `useValue`-arrays of gebruik je `multi: true`:
> ```typescript
> { provide: PLUGINS_TOKEN, useValue: [graphMailPluginSpecification, anderePluginSpecification] }
> ```

## Pluginconfiguratie — eigenschappen

| Eigenschap | Beschrijving | Verplicht | Default |
| --- | --- | --- | --- |
| `tenantId` | Azure Directory (tenant) ID | Ja | — |
| `clientId` | Azure Application (client) ID | Ja | — |
| `clientSecret` | Client secret van de App Registration (versleuteld opgeslagen) | Ja | — |
| `testSenderMailbox` | Standaard afzenderadres voor de test-send functie | Nee | — |
| `tokenBaseUrl` | Basis-URL van het Entra token-endpoint | Nee | `https://login.microsoftonline.com` |
| `graphBaseUrl` | Basis-URL van de Graph API | Nee | `https://graph.microsoft.com` |
| `connectTimeoutSeconds` | Connect-timeout | Nee | `10` |
| `readTimeoutSeconds` | Read-timeout | Nee | `30` |

De laatste vier staan **niet** in het beheerscherm. Ze zijn bedoeld voor tests en voor
afwijkende clouds (bijv. een sovereign cloud) en kun je alleen zetten via plugin-autodeployment
(`**/*.pluginconfig.json`) of de plugin-configuratie-API.

> Het test-send endpoint gebruikt een aparte client-bean met de *standaard* base-URL's. Zet je
> `tokenBaseUrl`/`graphBaseUrl` af van de default, dan praat de testmail met een andere host dan
> de `send-email`-actie.

## Actie: send-email

| Parameter | Beschrijving | Verplicht |
| --- | --- | --- |
| `senderMailbox` | E-mailadres van de afzender | Ja |
| `recipients` | Ontvangers — enkel adres, kommalijst of JSON-array | Ja |
| `cc` | CC-ontvangers | Nee |
| `bcc` | BCC-ontvangers | Nee |
| `replyTo` | Reply-To adressen | Nee |
| `subject` | Onderwerp | Ja |
| `contentId` | Resource-ID van de HTML-body in temporary resource storage | Ja |
| `attachmentIds` | Resource-ID('s) van bijlagen in temporary resource storage | Nee |

Alle velden accepteren de `pv:`-prefix om de waarde uit een procesvariabele te halen.

## Aandachtspunten

### Weergavenaam afzender

De weergavenaam die de ontvanger ziet, is de Display Name die is ingesteld op de
afzendermailbox in Microsoft 365. De plugin kan die niet overschrijven (het `from`-veld wordt
niet gezet). Pas de weergavenaam aan via het Microsoft 365 Admin Center.

### Opslaan in Verzonden items

E-mails uit de `send-email` actie worden opgeslagen in de Sent Items van de afzendermailbox
(`saveToSentItems = true`). E-mails uit de test-send functie worden dat *niet*.

### Bijlagen — twee verzendpaden

De route wordt **per bijlage** bepaald, op basis van twee harde grenzen van de Graph API:

- een upload-sessie geldt alleen voor bestanden van **3 MiB of groter** — daaronder weigert
  `createUploadSession` met `ErrorAttachmentSizeShouldNotBeLessThanMinimumSize`;
- een schrijfverzoek mag maximaal **4 MiB** groot zijn, en base64 maakt content 4/3 keer zo groot.

Daaruit volgt:

1. **Alles in één `sendMail`** — geen enkele bijlage is 3 MiB of groter én body plus
   base64-bijlagen passen samen binnen de 4 MiB. Eén verzoek, `Mail.Send` volstaat.
2. **Via een concept** — in alle andere gevallen. De plugin maakt een concept aan en hangt
   daar elke bijlage afzonderlijk aan: bestanden onder 3 MiB met een gewone
   `POST .../messages/{id}/attachments`, bestanden van 3 MiB en groter via een upload-sessie
   met chunked upload (3200 KiB per chunk). Daarna wordt het concept verzonden. Mislukt dat
   halverwege, dan wordt het concept best-effort opgeruimd.

Een kleine bijlage gaat dus nooit door een upload-sessie, ook niet als er een grote bijlage in
hetzelfde bericht zit.

Bij de upload-sessie is het verzendtijdstip het moment van de definitieve verzendaanroep, niet
het moment van conceptaanmaak.

### Bestandsnamen van bijlagen

De naam van een bijlage komt uit de metadata van de temporary resource storage, uitgelezen via
`MetadataType.FILE_NAME.key` (= `"filename"`) en `MetadataType.CONTENT_TYPE.key`. Gebruik altijd
de `MetadataType`-enum en geen string-literals: de key is `filename` en niet `fileName`, en een
mis-lezing levert stil een bijlage op die vernoemd is naar het resource-UUID, zonder extensie.

Ontbreekt de extensie in de opgeslagen naam, dan wordt die afgeleid uit het content-type
(`application/pdf` → `.pdf`). Samengestelde subtypes (zoals die van Office-formaten) leveren
geen extensie op in plaats van een onzinnige suffix.

### Dubbele verzending bij transactieretry

De actie vuurt op `SERVICE_TASK_START`. Als de Operaton-transactie terugdraait en opnieuw start
(bijvoorbeeld bij een optimistic lock conflict), draait de actie opnieuw en kan de e-mail meer
dan één keer verstuurd worden. Dit is geaccepteerd gedrag (at-least-once); er is geen
idempotency-guard. Een procesvariabele werkt daar níet als guard — die rolt mee terug met de
retry. Mitigatie: een idempotency-token meesturen en aan de ontvangerskant dedupliceren.

### HTML-body sanitisatie

De HTML-body wordt vóór verzending gesanitiseerd met jsoup.

Toegestaan: opmaaktags, tabellen, inline `style`-attributen, `<img>` met http/https/cid-bronnen.
Verwijderd: `<style>`-blokken, `<script>`, iframes, `data:` URI's en JavaScript-eventattributen.

`<style>`-blokken zijn bewust uitgesloten: CSS `url()`/`@import` kan externe requests triggeren
(tracking pixels) en kwaadaardige stylesheets laden. `data:` URI's zijn uitgesloten vanwege
SVG-met-script payloads en het omzeilen van mailscanners — gebruik `cid:` of `https:`.

Is de body na sanitisatie leeg, dan gooit de plugin een fout. Controleer dan de HTML die op het
opgegeven `contentId` is opgeslagen.

### Limieten

| Limiet | Waarde |
| --- | --- |
| Max ontvangers per veld (To / Cc / Bcc) | 100 |
| Max ontvangers totaal (To + Cc + Bcc) | 200 |
| Max onderwerpregel | 255 tekens |
| Max body-grootte | 5 MB |
| Max bijlagen | 5 |
| Max grootte per bijlage | 25 MB |
| Max totale bijlagegrootte | 25 MB |

> De body-limiet van 5 MB ligt boven wat Graph accepteert: het request-payload maximum voor
> `sendMail` en `createDraft` is circa 4 MB. Een body tussen 4 en 5 MB komt dus door de
> validatie van de plugin en wordt daarna door Graph geweigerd.

De constanten staan in `GraphMailPlugin.kt` en `GraphMailModels.kt`. Wijzig je er een, werk dan
ook [Grenzen](handleiding.md#grenzen) in de handleiding bij — dat is dezelfde set limieten in
functionele bewoordingen.

### Rate limiting test-send

Het test-send endpoint staat maximaal 1 verzoek per gebruiker per 10 seconden toe en vereist
`ROLE_ADMIN`. De teller wordt in geheugen bijgehouden per JVM-instantie; bij een multi-node
deployment geldt de limiet per node afzonderlijk.

### Job executor thread-blokkering

De retry-backoff gebruikt `Thread.sleep()`, waardoor de aanroepende Operaton job-executor thread
geblokkeerd wordt tijdens het wachten op een nieuwe poging. Maximale blokkeertijden per
verzending:

| Situatie | Maximale blokkeertijd |
| --- | --- |
| Reguliere verzending (geen grote bijlagen) | 30 seconden |
| Verzending via een concept (bijlagen passen niet in één verzoek) | 120 seconden |
| 429 rate-limit sleep per poging (max) | 15 seconden |

Als meerdere processen tegelijk e-mails versturen terwijl de Graph API rate-limiteert, kunnen
alle job-executor threads tegelijkertijd geblokkeerd worden. Dat stopt de verwerking van alle
andere Operaton-taken in de applicatie. Daarom is de thread-pool configuratie uit
[Installatie in je Valtimo-project](#installatie-in-je-valtimo-project) verplicht. Bij minder dan
20 threads loop je een reëel risico op een vastgelopen job-executor onder normale productielast.
De plugin logt bij opstarten een waarschuwing als herinnering.

> **Let op (queue-size):** bij een thread-pool-executor worden threads bóven `core-pool-size`
> pas aangemaakt wanneer de wachtrij vol is. Staat `queue-size` hoog, dan blijft de pool in de
> praktijk op `core-pool-size` steken en doet `max-pool-size` niets. Houd `queue-size` daarom
> klein als je op de extra threads wilt kunnen leunen, en stem het totale aantal threads af op
> de database-connectiepool.

### Token caching

Access tokens worden gecached per `tenantId:clientId`-combinatie, met een marge van 60 seconden
voor de expiry. Cache-hits blokkeren niet; gelijktijdige misses voor dezelfde key vallen samen in
één Azure-aanroep. Bij een 401 wordt alleen de betrokken key geïnvalideerd en exact één keer
opnieuw geprobeerd. De cache is standaard begrensd op 64 entries.

### Events

De plugin publiceert Spring application events die je kunt gebruiken voor eigen monitoring:

| Event | Wanneer |
| --- | --- |
| `GraphMailEmailSentEvent` | Na een geslaagde verzending |
| `GraphMailEmailFailedEvent` | Na een mislukte verzending (de fout wordt daarna doorgegooid) |

E-mailadressen in deze events zijn gemaskeerd. Een exception uit een listener wordt gelogd maar
beïnvloedt het verzendresultaat niet.

### Audit logging

Elke verzending wordt gelogd op de logger `entra.plugin.audit` (`SEND_OK` / `SEND_FAIL`), met
gemaskeerde e-mailadressen. Je kunt die logger apart configureren:

```xml
<logger name="entra.plugin.audit" level="INFO" additivity="false">
```
