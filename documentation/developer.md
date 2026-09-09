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
implementation("com.ritense.valtimoplugins:graph-mail:1.0.4")
```

Vul daarna het bestaande `operaton`-blok in je `application.yml` aan, en voeg het `graph-mail`-blok
toe:

```yaml
operaton:
  bpm:
    job-execution:
      core-pool-size: 20
      max-pool-size: 50
      queue-capacity: 10

graph-mail:
  http:
    token-base-url: https://login.microsoftonline.com   # default
    graph-base-url: https://graph.microsoft.com         # default
    connect-timeout-seconds: 10
    read-timeout-seconds: 30
    attachment-concurrency: 8                # max gelijktijdige verzendingen mét bijlagen
    attachment-acquire-timeout-seconds: 30
    # proxy-host: proxy.intern.gemeente.nl   # alleen bij een eigen proxy, zie Uitgaande proxy
    # proxy-port: 8080
    # non-proxy-hosts: "localhost|*.intern.gemeente.nl"
    # allow-non-microsoft-endpoints: true    # UITSLUITEND voor tests / lokale sandbox
```

> **De sleutel is `job-execution`, niet `job-executor`.** De Operaton-starter bindt
> `JobExecutionProperty` onder die naam, en Spring negeert een onbekende sleutel zonder
> foutmelding. Een typefout hier laat de engine dus stilzwijgend op zijn standaarden van 3 en 10
> draaien. Controleer bij het opstarten op
> `STARTER-SB040 Setting up jobExecutor with corePoolSize=20, maxPoolSize:50`; staan daar 3 en 10,
> dan is je instelling niet aangekomen.
>
> Staat er al een `operaton:`-sleutel in het bestand, voeg deze instellingen dan tóe aan dat blok.
> Een tweede `operaton:` op het hoogste niveau is ongeldige YAML en laat de applicatie crashen met
> `found duplicate key operaton`.

> **Verplicht:** zonder voldoende job-executor threads kan de applicatie vastlopen wanneer de Graph
> API rate-limiteert. Zie [Job executor thread-blokkering](#job-executor-thread-blokkering).

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

Maak een pluginconfiguratie aan via **Admin → Plugins → Graph Mail Plugin**.

| Eigenschap | Beschrijving | Verplicht |
| --- | --- | --- |
| `tenantId` | Azure Directory (tenant) ID | Ja |
| `clientId` | Azure Application (client) ID | Ja |
| `clientSecret` | Client secret van de App Registration (versleuteld opgeslagen) | Ja |
| `allowedSenders` | Whitelist van toegestane afzenders: kommagescheiden volledige adressen (`noreply@gemeente.nl`) en/of domein-entries (`@gemeente.nl`) | Ja |
| `testSenderMailbox` | Standaard afzenderadres voor de test-send functie | Nee |

De endpoints en timeouts zijn **geen** pluginproperty meer. Ze staan onder `graph-mail.http` in
`application.yml` — zie *Endpoints zijn deployment-instellingen* hieronder voor de reden.

### Afzender-whitelist (`allowedSenders`)

De plugin hanteert **deny-by-default**: elke verzending wordt geweigerd tenzij het (eventueel via
een procesvariabele aangeleverde) `senderMailbox`-adres op de whitelist voorkomt. Matching is
hoofdletterongevoelig; een domein-entry (`@gemeente.nl`) staat het hele domein toe maar géén
subdomeinen. De whitelist geldt ook voor het test-send endpoint.

> **Migratie:** pluginconfiguraties die vóór de introductie van `allowedSenders` zijn aangemaakt,
> weigeren na de upgrade elke verzending totdat de whitelist eenmalig is ingevuld en opgeslagen.

#### Wijzigen vereist het client secret

De whitelist bepaalt namens welke mailboxen de tenant-brede `Mail.Send`-machtiging via deze plugin
gebruikt mag worden. Een adres toevoegen is daarmee feitelijk een rechtenuitbreiding: wie dat doet,
kan vanaf dat moment als die mailbox mailen. Daarom is een gewijzigde whitelist alleen op te slaan
wanneer het `clientSecret` in diezelfde request opnieuw wordt meegegeven — beheerschermtoegang
alleen is niet genoeg, je moet de credential ook daadwerkelijk bezitten.

Blijft de whitelist ongewijzigd, dan verandert er niets: het secretveld mag leeg blijven en Valtimo
behoudt de opgeslagen waarde. Herordenen of anders spatiëren van dezelfde adressen telt niet als
wijziging; hoofdletters, dubbele vermeldingen en de bracket-notatie (`["a@x.nl","b@x.nl"]`, die de
backend-parser ook accepteert) evenmin. Verwijderen telt wél als wijziging — versmallen is op
zichzelf geen escalatie, maar zo kan "verwijderen en opnieuw toevoegen" geen omweg worden.

Dit wordt server-side afgedwongen (`AllowedSendersChangeGuard`), dus ook een directe
`PUT /api/v1/plugin/configuration/{id}` die de frontend omzeilt krijgt een `400`. De controle grijpt
in vóórdat Valtimo een leeg secretveld aanvult met de opgeslagen waarde — daarna is niet meer vast
te stellen óf het secret is meegegeven.

> **Uitschakelen** kan met `graph-mail.require-secret-for-allowlist-change: false`. Dat is een reële
> verzwakking en daarom een expliciete, zichtbare keuze. De plugin weigert op te starten wanneer de
> controle aan staat maar niet toegepast kan worden (bijvoorbeeld doordat een Valtimo-upgrade de
> onderliggende signatuur wijzigde) — een beveiligingscontrole die stilletjes wegvalt is erger dan
> een die nooit beloofd is.

#### Hoe fijnmazig, naast een Exchange Online-scope?

De Exchange Online-scope (een Application Access Policy of RBAC for Applications) en de whitelist
beantwoorden **verschillende vragen**:

| | Exchange Online-scope | `allowedSenders` |
| --- | --- | --- |
| Vraag | Welke mailboxen mag deze *app registration* überhaupt aanraken? | Als welke mailbox mag *deze pluginconfiguratie* verzenden? |
| Bereik | Per app registration | Per pluginconfiguratie |
| Beheerd door | Exchange Online-beheerder | Beheerder van de pluginconfiguratie |

Die tweede vraag kan een scope niet uitdrukken: twee pluginconfiguraties op dezelfde app
registration zijn voor die scope identiek. Wil je één configuratie beperken tot
`invordering@gemeente.nl` en een andere tot `noreply@gemeente.nl`, dan is de whitelist de enige
plek waar dat kan.

| Situatie | `allowedSenders` |
| --- | --- |
| Scope ingesteld, geverifieerd én aantoonbaar sluitend | Domein-entry, bijv. `@gemeente.nl` |
| Geen scope, niet te verifiëren vanuit jouw rol, of twijfel | Volledige adressen per mailbox |

Verifiëren doe je altijd tégen een concrete mailbox — anders test je of de rol bestaat, niet of de
grens werkt:

| Mechanisme | Verificatie |
| --- | --- |
| Application Access Policy | `Test-ApplicationAccessPolicy -AppId "<client-id>" -Identity "<mailbox>"` |
| RBAC for Applications | `Test-ServicePrincipalAuthorization -Identity "<app>" -Resource "<mailbox>"` — zonder `-Resource` wordt de scope-controle niet uitgevoerd |

> **Let op bij RBAC — een scope alléén is niet sluitend.** Exchange-RBAC en
> Entra-applicatiemachtigingen zijn losse grants: RBAC-rollen werken volgens Microsoft *in
> aanvulling op* wat je in Entra ID toekent, niet als beperking daarop. Houdt dezelfde service
> principal daarnaast nog een tenantbrede `Mail.Send`-applicatiemachtiging in Entra ID, dan blijft
> die de verzending autoriseren buiten de RBAC-scope om.
>
> Dat is een stille valkuil: `Test-ServicePrincipalAuthorization` kan `InScope: False` melden voor
> een mailbox terwijl een Graph-`sendMail` naar diezelfde mailbox gewoon slaagt. **Wil je op RBAC
> kunnen leunen, trek dan de tenantbrede `Mail.Send`-applicatiemachtiging in Entra ID in** en houd
> alleen de gescopete Exchange-rol over.

Ook met een correct ingestelde scope blijft de whitelist zinvol: de scope is niet zichtbaar vanuit
Valtimo, hij wordt door een ander team beheerd en kan verschuiven zonder dat dat hier opvalt, en een
afzender buiten de lijst wordt lokaal geweigerd vóór de tokenaanvraag in plaats van als Graph-`403`
midden in het proces. Omgekeerd maakt de whitelist de scope niet overbodig: die blijft de primaire
controle, omdat hij ook beschermt wanneer het client secret buiten de plugin om wordt misbruikt.

## Actie: send-email

| Parameter | Beschrijving | Verplicht |
| --- | --- | --- |
| `senderMailbox` | E-mailadres van de afzender — moet op de `allowedSenders`-whitelist staan | Ja |
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

De weergavenaam die de ontvanger ziet, is de Display Name die is ingesteld op de afzendermailbox in
Microsoft 365. De plugin kan die niet overschrijven (het `from`-veld wordt niet gezet). Pas de
weergavenaam aan via het Microsoft 365 Admin Center.

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

De actie vuurt op `SERVICE_TASK_START`. Als de Operaton-transactie na een geslaagde verzending
alsnog terugdraait (bijvoorbeeld door een optimistic lock op andere procesdata) en de activiteit
opnieuw uitvoert, is de e-mail bij Graph al onomkeerbaar geaccepteerd. De plugin herkent die
herhaling zelf en slaat de tweede Graph-aanroep over; in het procesmodel hoef je daar niets voor in
te richten.

De bescherming bestaat uit twee delen die elk een tegengestelde eigenschap moeten hebben. Dat
onderscheid is bepalend en verklaart waarom de opzet op het eerste gezicht omslachtig oogt.

**De sleutel** — waaraan een verzending herkend wordt — is `execution.id : activityId : volgnummer`.
Het volgnummer komt uit een procesvariabele (`graphMailPass_<activityId>`) en is daarmee **met opzet
transactioneel**: draait de transactie terug, dan draait het volgnummer mee terug en berekent de
retry exact dezelfde sleutel. Committeert een iteratie van een loop, dan begint de volgende bij een
hoger nummer en dus bij een andere sleutel.

**De markering** — of die sleutel al verstuurd is — ligt in het geheugen en is **met opzet niet
transactioneel**. Deelde zij het lot van de transactie, dan verdween zij bij de rollback en was zij
voor de volgende poging nooit zichtbaar. Om die reden is een procesvariabele ongeschikt als
markering, terwijl zij als onderdeel van de sleutel juist het gewenste gedrag oplevert.

> **Let op:** de plugin schrijft daarmee een variabele op elke procesinstantie die mail verstuurt.
> Die is zichtbaar in Cockpit en in de variabelenhistorie. De naam is genamespaced per activity,
> zodat twee send-email-taken in één proces onafhankelijk tellen.

De alternatieven voor die sleutel zijn getoetst tegen een draaiende engine, in
`ActivityInstanceIdContractTest`:

| Sleutel | Stabiel over retry? | Uniek per loop-iteratie? |
| --- | --- | --- |
| `execution.id` + `currentActivityId` | ja | **nee** — elke volgende mail in een loop werd weggegooid |
| `activityInstanceId` | **nee** — krijgt per poging een nieuw nummer | ja |
| teller in procesvariabele | ja | ja |

Geen van de engine-eigen velden kan de twee situaties scheiden: bij zowel een retry als een nieuwe
loop-iteratie zijn `execution.id` en `currentActivityId` gelijk, terwijl `activityInstanceId` in
beide gevallen verschilt. Een hash over de mailinhoud is evenmin bruikbaar — een aanmaningsproces
dat bewust twee keer dezelfde mail stuurt, zou dan stilzwijgend worden ingeslikt.

### Wanneer de guard de verzending niet meer herkent

De markering leeft in het geheugen van één JVM en verloopt na 30 minuten. Daaruit volgen drie
situaties waarin een retry een al verzonden mail niet als duplicaat ziet en hem opnieuw verstuurt —
zonder foutmelding, want technisch gaat er niets mis:

| Situatie | Gevolg | Wat je eraan doet |
| --- | --- | --- |
| **Multi-node deployment** | De job-executor is cluster-breed. Wordt de retry door een andere node opgepakt dan de node die verstuurde, dan kent die de markering niet. | Deduplicatie aan ontvangerskant, of accepteer dat de bescherming best-effort is. Er is geen instelling die dit binnen de plugin oplost. |
| **Retry later dan 30 minuten** | De markering is dan al verlopen. Zie de waarschuwing bij `failedJobRetryTimeCycle` hieronder. | Houd de totale duur van je retry-cyclus onder 30 minuten. |
| **Herstart tussen verzending en retry** | De markering is met de JVM verdwenen. | Deduplicatie aan ontvangerskant. |

Bij één node, een retry-cyclus binnen het plafond en geen herstart — het gangbare geval — werkt de
bescherming zoals beschreven.

De TTL van 30 minuten is een constante in `InMemorySentMarkerStore`; er is géén property om hem te
wijzigen. Heb je een langere retry-cyclus nodig, dan vervang je de store-bean — die is geregistreerd
met `@ConditionalOnMissingBean`, dus een eigen bean in je applicatie wint:

```kotlin
@Bean
fun sentMarkerStore(): SentMarkerStore = InMemorySentMarkerStore(entryTtlMs = 2 * 60 * 60 * 1000L) // 2 uur
```

Een langere TTL houdt de markeringen navenant langer in geheugen, en lost alleen de tweede rij
hierboven op: multi-node en een herstart blijven buiten bereik van elke TTL-waarde.

### Multi-node: een eigen markeringsopslag

Waar de markeringen leven zit achter `SentMarkerStore`, juist omdat de standaard in-memory is en dus
per JVM geldt. Draai je meerdere nodes en heb je de garantie echt nodig, dan lever je een eigen
implementatie:

```kotlin
@Bean
fun sentMarkerStore(): SentMarkerStore = JdbcSentMarkerStore(dataSource)
```

> **Eén eis, en die is bepalend:** de opslag moet **buiten de omliggende transactie** committen —
> bijvoorbeeld in een `REQUIRES_NEW`-scope. Daar draait de hele guard om. De storing waar hij voor
> bestaat is een transactie die terugrolt *nadat* Graph het bericht al heeft geaccepteerd; een
> markering die het lot van die transactie deelt rolt mee terug en is voor de volgende poging nooit
> zichtbaar. Een gewone `@Transactional`-schrijfactie is daarmee niet beter dan een procesvariabele.

De plugin levert bewust géén JDBC-implementatie mee: dat betekent een tabel, een changelog en een
schemawijziging bij elke afnemer, en die keuze hoort bij de beheerder van de omgeving te liggen. De
vergrendeling per sleutel blijft in `SendIdempotencyGuard`, dus een eigen store hoeft alleen
markeringen op te slaan en terug te lezen, en moet thread-safe zijn.

### Transportfouten worden bewust niet opnieuw geprobeerd

Een netwerkfout of read-timeout op de verzendaanroep zelf (`sendMail`, `messages/{id}/send`) zegt
niets over of Graph het bericht al heeft geaccepteerd. De plugin probeert die aanroep daarom **niet**
automatisch opnieuw en meldt de fout als `GraphMailUnknownOutcomeException` — beter één onzekere
verzending dan een gegarandeerde dubbele mail bij de ontvanger. Conceptaanmaak, het aanhangen van
een bijlage aan een concept en het aanmaken van een upload-sessie zijn wél herhaalbaar en worden wel
opnieuw geprobeerd.

Controleer bij deze fout de mailbox voordat de activity opnieuw wordt uitgevoerd. In de auditlog is
dit herkenbaar aan `verdict=UNKNOWN`.

### Foutclassificatie in de auditlog

Elke mislukte verzending logt een `verdict`-veld dat aangeeft wat de beheerder moet doen:

| Verdict | Betekenis |
| --- | --- |
| `PERMANENT_INPUT` | Invoer- of configuratiefout; opnieuw proberen faalt identiek. Corrigeer de procesdata of de pluginconfiguratie. |
| `PERMANENT_REMOTE` | De overkant weigert dit permanent en dat verandert niet vanzelf: Graph zelf (bijv. 403 zonder `Mail.Send`, 404 onbekende mailbox), of de uitgaande proxy (407, zie *Uitgaande proxy*). Vereist een configuratie- of permissiewijziging. |
| `UNKNOWN` | Transportfout na verzending; de mail is mogelijk wél verstuurd. Verifieer voordat je opnieuw uitvoert. |
| `TRANSIENT` | Tijdelijk (429/5xx, of een netwerkfout op een herhaalbare stap zoals conceptaanmaak, het aanmaken van een upload-sessie, of een verbinding die nooit tot stand kwam); de job-executor probeert het opnieuw. Een transportfout op `sendMail` of `messages/{id}/send` nádat het verzoek verstuurd is valt hier **niet** onder — die is `UNKNOWN`. |

Deze classificatie zit bewust in de logging en niet in een `BpmnError`: het omzetten van permanente
fouten naar een BPMN-fout zou de procesafhandeling van elk bestaand model wijzigen, en een
niet-afgevangen `BpmnError` degradeert tot een incident met de melding "no catching boundary event
found" — minder bruikbaar dan de fout die de plugin nu gooit. Wil je permanente fouten in het
procesmodel afvangen, gebruik dan een `failedJobRetryTimeCycle` in combinatie met een
incident-handler.

### HTML-body sanitisatie

De HTML-body wordt vóór verzending gesanitiseerd met jsoup. Toegestaan: opmaaktags, tabellen, inline
`style`-attributen, `<img>` met `https`- of `cid`-bronnen. Verwijderd: `<style>`-blokken, `<script>`,
iframes, `data:` URI's, JavaScript-eventattributen.

Ook binnen toegestane inline `style`-attributen worden `url(...)`, `@import`, `expression(...)` en
`javascript:` weggefilterd — anders zou een `style="background:url(https://tracker/pixel.png)"`
alsnog een externe request (tracking pixel) veroorzaken, precies waarvoor `<style>`-blokken geweerd
worden. Het hele `style`-attribuut vervalt bij zo'n treffer, niet alleen de betreffende declaratie:
een waarde die al een ontwijkingspoging bevat, laat zich niet betrouwbaar in een schoon en een vuil
deel splitsen.

`<img src="http://...">` wordt sinds 1.0.4 eveneens geweerd. Een afbeelding in een e-mail wordt
opgehaald zodra de ontvanger het bericht opent, dus een `http`-bron vertelt een derde partij wanneer
een burger zijn correspondentie las, over een verbinding die niemand kan garanderen.

Logo's blijven ongewijzigd werken; beide gangbare manieren om er een mee te sturen zijn ongemoeid:

| Bron | Resultaat |
| --- | --- |
| `<img src="cid:logo">` | werkt — logo als bijlage, ingesloten. Geen externe request; de aangewezen route. |
| `<img src="https://gemeente.nl/logo.png">` | werkt — logo op de eigen server. |
| `<img src="http://gemeente.nl/logo.png">` | `src` wordt verwijderd. |

Een bestaand sjabloon met een logo op `http://` moet dus naar `https://` of naar `cid:`.

De grens daarvan is beperkt: ook een `https`-afbeelding is een externe request en kan als tracking
pixel dienen. Het onderscheid met `url()` in inline CSS is dat die in transactionele post geen
legitiem doel dient, terwijl `<img>` dat wel heeft. Wie elke externe request wil uitsluiten, gebruikt
uitsluitend `cid:`-bronnen. Een `<a href="http://...">` blijft toegestaan: een link wordt pas gevolgd
wanneer de ontvanger erop klikt.

Blijft de body na sanitisatie leeg, dan gooit de plugin een fout. Controleer in dat geval de HTML die
op het opgegeven `contentId` is opgeslagen.

### Limieten

| Limiet | Waarde |
| --- | --- |
| Max ontvangers per veld (To / Cc / Bcc) | 100 |
| Max ontvangers totaal (To + Cc + Bcc) | 200 |
| Max onderwerpregel | 255 tekens |
| Max body-grootte | 5 MiB |
| Max bijlagen | 5 |
| Max grootte per bijlage | 25 MiB |
| Max totale bijlagegrootte | 25 MiB |

> De body-limiet van 5 MiB ligt boven wat Graph in één verzoek accepteert (4 MiB). Een body tussen 4
> en 5 MiB komt dus door de validatie van de plugin; de verzending gaat daarna via een concept, waar
> die grens niet geldt.

De constanten staan in `GraphMailPlugin.kt` en `GraphMailModels.kt`. Wijzig je er een, werk dan ook
[Grenzen](handleiding.md#grenzen) in de handleiding bij — dat is dezelfde set limieten in
functionele bewoordingen.

### Secret management

Het `clientSecret` is een Valtimo secret property (`@PluginProperty(secret = true)`): het wordt
AES-versleuteld opgeslagen in de database en nooit teruggestuurd naar de frontend. De
encryptiesleutel komt uit `valtimo.plugin.encryption-secret` en moet exact 16, 24 of 32 bytes lang
zijn. Zet die sleutel **nooit** in de repository of in een gecommit configuratiebestand — lever hem
aan via een environment variable of een secret store:

```yaml
valtimo:
  plugin:
    encryption-secret: ${VALTIMO_PLUGIN_ENCRYPTION_SECRET}
```

Wie deze sleutel én een databasedump bezit, kan alle plugin-secrets ontsleutelen. Roteer het client
secret in Azure periodiek en behandel de encryptiesleutel met hetzelfde beveiligingsniveau als de
secrets zelf.

### Endpoints zijn deployment-instellingen, geen pluginproperties

`tokenBaseUrl` en `graphBaseUrl` waren eerder per pluginconfiguratie instelbaar vanuit de beheer-UI.
Dat was een exfiltratiepad voor het client secret: dat secret wordt als formulierveld naar
`tokenBaseUrl` gePOST, dus wie pluginconfiguraties mocht beheren kon het naar een eigen host laten
sturen. `graphBaseUrl` gaf daarnaast een SSRF-primitief, en de hostcontrole op de upload-URL leidde
haar verwachte host áf uit `graphBaseUrl` — waardoor die controle precies zo sterk was als de waarde
die een beheerder had ingevuld.

Deze instellingen staan nu onder `graph-mail.http` en worden bij het opstarten gevalideerd tegen een
vaste allowlist van Microsoft-endpoints (inclusief de sovereign clouds). Een afwijkende waarde laat
de applicatie falen bij opstarten met een leesbare melding.

> **Migratie:** bestaande pluginconfiguraties met een `tokenBaseUrl`- of `graphBaseUrl`-waarde
> negeren die waarde na deze upgrade. Stond er een niet-standaard endpoint in, dan kun je dat
> **niet** ongewijzigd overzetten: `graph-mail.http` accepteert alleen endpoints uit de allowlist, en
> een afwijkende waarde laat de applicatie falen bij opstarten. `connectTimeoutSeconds` en
> `readTimeoutSeconds` kunnen wél ongewijzigd mee als `connect-timeout-seconds` en
> `read-timeout-seconds`.

### Uploadhosts worden per cloud gescheiden

Gaat een bijlage via een upload-sessie, dan levert Graph daarvoor een URL op een eigen opslag- of
SharePoint-domein — dat per cloud verschilt. De plugin controleert die host tegen de lijst die hoort
bij de cloud waar `graph-base-url` naar wijst, niet tegen één gedeelde lijst. Een US Gov-omgeving
accepteert dus geen commerciële uploadhost en andersom.

Tot en met 1.0.3 bestond die lijst alleen uit commerciële hosts, terwijl de configuratie een
sovereign endpoint wél accepteerde. Kleine mails werkten daardoor, en de eerste bijlage die een
upload-sessie nodig had faalde permanent op hostvalidatie.

> **Kanttekening.** De commerciële lijst draait in productie. De sovereign lijsten volgen Microsofts
> gepubliceerde clouddomeinen maar zijn niet tegen een levende sovereign tenant beproefd. Loopt een
> upload toch stuk op `Upload URL returned by the Graph API failed host validation`, zet dan
> `logging.level.com.ritense.valtimoplugins.graphmail=DEBUG`: de geweigerde host komt daar in de log
> te staan. De host blijft bewust uit de foutmelding zelf, omdat die uit een extern antwoord komt en
> in logs en beheerdersmeldingen terechtkomt.

### Uitgaande proxy

Loopt het verkeer naar Microsoft via een forward proxy — bij overheidsorganisaties vrijwel altijd —
dan hoef je in de regel niets in te stellen: zonder `proxy-host` gebruikt de plugin de proxy die de
JVM al kent via `-Dhttps.proxyHost` en `-Dhttps.proxyPort`. Bij het opstarten logt de plugin welke
proxy hij gebruikt, of dat hij er geen heeft. Controleer die regel als verzendingen falen met een
connectiefout: zo'n fout wordt als *transient* geclassificeerd, dus de job-executor blijft het
proberen en de melding ziet eruit als een tijdelijke storing.

Stel `proxy-host` en `proxy-port` alleen in wanneer deze plugin een ándere proxy nodig heeft dan de
rest van de applicatie. `non-proxy-hosts` volgt dezelfde notatie als `http.nonProxyHosts`:
pipe-gescheiden patronen waarin `*` voor een willekeurige reeks tekens staat. Een komma in plaats van
een pipe, of `non-proxy-hosts` zonder `proxy-host`, laat de applicatie bij opstarten falen — beide
zouden anders stil niets doen en het verkeer alsnog door de proxy sturen.

> **Een proxy die authenticatie eist wordt niet ondersteund.** De HTTP-client wordt zonder
> `Authenticator` gebouwd, dus de plugin kan geen proxy-credentials aanbieden. De proxy antwoordt dan
> met `407` en de verzending faalt als `verdict=PERMANENT_REMOTE`, met een melding die naar de proxy
> wijst in plaats van naar Graph. Die classificatie is bewust permanent: de proxy weigert elke poging
> identiek tot iemand de configuratie aanpast.

> De proxy is bewust een deployment-instelling en geen pluginproperty. Al het verkeer naar het
> token-endpoint loopt erdoorheen, inclusief het formulier waarin het client secret wordt gePOST —
> instelbaar maken vanuit de beheer-UI zou hetzelfde exfiltratiepad heropenen dat hierboven is
> gedicht door `tokenBaseUrl` daar weg te halen.

### Geheugengebruik bij bijlagen

Een verzending met bijlagen houdt de volledige inhoud in het heap-geheugen. Zonder rem zou het aantal
gelijktijdige verzendingen gelijk zijn aan de grootte van de job-executor thread-pool, wat bij de
aanbevolen `max-pool-size: 50` neerkomt op meerdere gigabytes en dus een `OutOfMemoryError`.
`graph-mail.http.attachment-concurrency` begrenst dit los van de thread-pool; verzendingen zónder
bijlagen worden niet begrensd. Wordt binnen `attachment-acquire-timeout-seconds` geen slot vrij, dan
faalt de verzending als *transient* en probeert de job-executor het later opnieuw.

Bijlagen en de body worden volledig in geheugen gehouden zolang een verzending loopt; er wordt niet
naar schijf gestreamd. Twee verschillende grenzen bepalen daarom de piek:

| Soort verzending | Piek per verzending | Hoeveel er tegelijk kunnen | Deelplafond |
| --- | --- | --- | --- |
| Mét bijlagen | ≈ 33 MiB (25 MiB bijlagen + 5 MiB body + 3,2 MiB chunk-buffer) | `graph-mail.http.attachment-concurrency` (default **8**) | ≈ 265 MiB |
| Zónder bijlagen | ≈ 5 MiB (body) | `operaton.bpm.job-execution.max-pool-size` (aanbevolen **50**) | ≈ 250 MiB |

> **Reken met `attachment-concurrency`, niet met `max-pool-size`.** Het zijn de verzendingen mét
> bijlagen die het geheugen opeten, en juist die worden begrensd door de limiter — niet door de
> thread-pool. Verhoog je `attachment-concurrency`, dan schaalt de bovenste rij één op één mee.

### Connection pooling

De plugin gebruikt één gedeelde, gepoolde HTTP-client (de JDK `HttpClient`) voor alle verzendingen.
Eerder werd per plugin-actie-invocatie een nieuwe `RestTemplate` opgebouwd — Valtimo hydrateert
namelijk een nieuwe plugin-instantie per actie — waardoor er per e-mail een volledige TLS-handshake
nodig was en verbindingen nooit hergebruikt werden. Er is bewust geen afhankelijkheid van Apache
HttpClient5 toegevoegd, zodat de plugin niets hoeft aan te nemen over de HTTP-bibliotheken op het
classpath van de omringende applicatie. De client staat expliciet op HTTP/1.1: de JDK-client kiest
standaard HTTP/2, en dat zou het wire-protocol wijzigen als bijeffect van een performance-refactor.

### Rate limiting test-send

Het test-send endpoint staat maximaal 1 verzoek per gebruiker per 10 seconden toe en vereist
`ROLE_ADMIN`. De teller wordt in geheugen bijgehouden per JVM-instantie; bij een multi-node
deployment geldt de limiet per node afzonderlijk. De afzender van de testmail moet — net als bij de
`send-email`-actie — op de `allowedSenders`-whitelist staan.

### Job executor thread-blokkering

De retry-backoff gebruikt `Thread.sleep()`, maar **alleen binnen een wachtbudget van 2 seconden per
aanroep**. Zodra een volgende wachtperiode dat budget zou overschrijden, geeft de plugin de thread op
en meldt de fout als *transient*, zodat de job-executor het later opnieuw inplant zónder een thread
vast te houden.

Dat verschil is wezenlijk. Graph throttlet mail per mailbox stevig, en eerder honoreerde de plugin
een `Retry-After` van 15 seconden vijf keer achter elkaar. Alle job-executor threads lagen dan
tegelijk te slapen en de engine verwerkte géén enkele job meer — ook niets dat met e-mail te maken
had. Eén externe API die de hele engine platlegt is een ernstiger storing dan een verzending die
later slaagt.

Korte haperingen worden nog steeds in de aanroep zelf opgevangen; één backoff van 500 ms uitzitten is
goedkoper dan een job herplannen.

> **Configureer een `failedJobRetryTimeCycle`** op de send-email service task, bijvoorbeeld
> `R5/PT2M`. Zonder dat valt de plugin terug op de standaard retry-instelling van de engine, die voor
> throttling meestal te kort is.

> **Let op — er zit een plafond aan die cyclus.** De duplicaatbescherming vergeet een verzending na
> 30 minuten. Duurt je retry-cyclus langer, dan is de markering verlopen op het moment dat de laatste
> pogingen binnenkomen, en wordt een al verzonden mail opnieuw verstuurd. Reken de totale duur uit,
> niet alleen het interval:
>
> | Cyclus | Totale duur | Veilig |
> | --- | --- | --- |
> | `R5/PT2M` | 10 minuten | ja |
> | `R3/PT5M` | 15 minuten | ja |
> | `R5/PT10M` | 50 minuten | **nee** — laatste retries vallen buiten de bescherming |
> | `R3/PT1H` | 3 uur | **nee** |
>
> Heb je een lange cyclus nodig, vervang dan de `SentMarkerStore`-bean door een exemplaar met een
> ruimere `entryTtlMs` — zie *Wanneer de guard de verzending niet meer herkent*. Doe je dat niet, dan
> kies je impliciet voor langere retries ten koste van de duplicaatbescherming.

Resterende maximale blokkeertijden per verzending:

| Situatie | Maximale blokkeertijd |
| --- | --- |
| Wachten op een nieuwe poging (backoff, `Retry-After`) | 2 seconden totaal per aanroep |
| Reguliere verzending, inclusief netwerktijd | 30 seconden |
| Verzending via een concept (upload-sessie of losse POSTs) | 120 seconden |

De twee onderste grenzen zijn geen wachttijd maar werk: het overzetten van een bijlage van 25 MiB
duurt nu eenmaal. Alleen de bovenste was de bron van de engine-brede stilstand, en die is nu
begrensd.

> **Let op (`queue-capacity`):** bij een thread-pool-executor worden threads bóven `core-pool-size`
> pas aangemaakt wanneer de wachtrij vol is. Staat `queue-capacity` hoog, dan blijft de pool in de
> praktijk op `core-pool-size` steken en doet `max-pool-size` niets. Houd `queue-capacity` daarom
> klein als je op de extra threads wilt kunnen leunen, en stem het totale aantal threads af op de
> database-connectiepool.

### Token caching

Access tokens worden gecached met een marge van 60 seconden voor de expiry. Cache-hits blokkeren
niet; gelijktijdige misses voor dezelfde key vallen samen in één Azure-aanroep. Bij een 401 wordt
alleen de betrokken key geïnvalideerd en exact één keer opnieuw geprobeerd. De cache is standaard
begrensd op 64 entries.

De cachesleutel bevat naast `tenantId` en `clientId` een hash van het client secret. Zonder dat kon
een configuratie met een verkeerd secret meeliften op het token dat een andere configuratie voor
dezelfde tenant had opgehaald.

### Metrics

Heeft de applicatie Micrometer op het classpath — GZAC levert Actuator mee — dan publiceert de plugin
meters voor verzendingen, hun duur, de vrije bijlage-slots en de tokencache:

| Meter | Type | Betekenis |
| --- | --- | --- |
| `graph.mail.sends` | counter | Verzendingen, met een `outcome`-tag die hetzelfde vocabulaire gebruikt als het `verdict`-veld |
| `graph.mail.send.duration` | timer | Duur van een verzending |
| `graph.mail.attachment.slots.available` | gauge | Vrije slots in de bijlage-limiter |
| `graph.mail.token.cache.size` | gauge | Aantal entries in de tokencache |

De afhankelijkheid is `compileOnly` met `@ConditionalOnClass`: zonder Micrometer start de plugin
gewoon en publiceert hij niets.

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
gemaskeerde e-mailadressen en bij een fout het `verdict`-veld. Elk Graph-verzoek draagt bovendien een
`client-request-id`; bij een fout staan dat id en Graphs eigen `request-id` in de auditlog — de twee
waarden waar Microsoft Support als eerste om vraagt.

Je kunt die logger apart configureren:

```xml
<logger name="entra.plugin.audit" level="INFO" additivity="false">
```

## Test-send

Via de pluginconfiguratiepagina kan een testmail worden verstuurd om te verifiëren dat de
Azure-credentials correct zijn geconfigureerd. Dit vereist de rol `ROLE_ADMIN`. De testmail wordt
niet opgeslagen in Verzonden items en de afzender moet op de `allowedSenders`-whitelist staan.
