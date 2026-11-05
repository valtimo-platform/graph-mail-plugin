# Developer documentatie

Bouwen, lokaal testen, installeren en de technische aandachtspunten van de Graph Mail-plugin.

Voor het inrichten van de plugin via de beheerinterface: zie [handleiding.md](handleiding.md).

## Prerequisites

- Java 17+
- Node.js >= 20, < 23
- Valtimo 13.x
- Http Client Authentication 3.1.1 of hoger, als Graph Mail zich daarmee aanmeldt (aanbevolen;
  backend en frontend zelf toevoegen, Graph Mail brengt het niet mee)
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
`login.microsoftonline.com` als `graph.microsoft.com` nabootst, met daarvoor een **gateway die een
clientcertificaat eist** (nginx, `https://localhost:8449`). Daarmee kun je de plugin end-to-end
draaien zonder App Registration, zoals bij een organisatie met zo'n gateway.

Graph Mail meldt zich aan via `http-client-authentication` 3.1.1. Zolang die versie niet op Maven
Central en npm staat:

1. Backend: draai `./gradlew :backend:plugin:publishToMavenLocal` in de repository van die plugin. Graph
   Mail zoekt eerst in `mavenLocal()`; verwijder
   `~/.m2/repository/com/ritense/valtimoplugins/http-client-authentication/3.1.1` weer zodra 3.1.1
   is uitgebracht.
2. Frontend: bouw daar de library (`npm run build` in `frontend`) en installeer hem hier zonder de
   `package.json` te wijzigen: `npm install --no-save <pad naar die repository>/frontend/dist/plugin`
   in `frontend`.

```shell
./gradlew :backend:app:bootRun
```

`bootRun` start de docker-compose stack (inclusief de mock en de gateway) en zet de applicatie op
`http://localhost:8080`. Bij het opstarten worden deze pluginconfiguraties aangemaakt — zie
`backend/app/src/main/resources/config/plugin/graph-mail.pluginconfig.json`:

| Configuratie | Wat het laat zien |
| --- | --- |
| **Graph Mail (dummy)** | Meldt zich aan met *Graph login via gateway* en verstuurt via de gateway. |
| **Graph login via gateway** (Http Client Authentication) | Token exchange zonder audience naar het token endpoint achter de gateway, met het clientcertificaat dat de gateway accepteert en het CA-certificaat van de gateway. |
| **Graph login without client certificate** | Geen clientcertificaat: de gateway weigert het aanmelden met *No required SSL certificate was sent*; de melding noemt de configuratie en het certificaat. |
| **Graph login, untrusted client certificate** | Haalt het token rechtstreeks bij de mock (`http://127.0.0.1:1080`), met een certificaat van een andere CA: de gateway weigert de Graph-verzoeken met *The SSL certificate error*. |
| **Microsoft direct** | Token exchange zonder certificaten naar `login.microsoftonline.com`, voor een echte tenant (zie hieronder). |

Kies in *Graph Mail (dummy)* bij **Http Client Authentication-configuratie** een andere en verstuur
een testmail om de foutmeldingen te zien. Het log van de gateway toont per verzoek het certificaat en
de uitkomst: `docker logs plugin-docker-compose-graph-mail-gateway`.

> De token endpoints wijzen naar `127.0.0.1`, niet naar `localhost`. Het aanmelden gaat via de
> `RestClient.Builder` van de applicatie, en daarop herschrijft Valtimo's docker-filter
> (`valtimo.docker.filter`, hier met de eigen poort 8080) `localhost` naar `host.docker.internal`.
> Hetzelfde geldt voor elke applicatie waarvan `valtimo.docker.filter.ports` de eigen poort bevat.

De certificaten staan in `backend/app/imports/mtls-gateway/certs` (wegwerpcertificaten, alleen voor
de sandbox). Nieuwe maken, inclusief de pluginconfiguraties: `backend/app/imports/mtls-gateway/generate-certs.sh`.

Alleen de mock en de gateway starten:

```shell
docker compose -f backend/app/docker-compose.yml up -d mockserver mtls-gateway
```

> De sandbox deelt containernamen en poorten met die van andere plugins (zoals
> `http-client-authentication-plugin`); er kan er één tegelijk draaien. Alleen de gateway heeft een
> eigen naam en poort (8449).

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

De pluginconfiguratie en `application.yml` lezen omgevingsvariabelen met een fallback naar de
dummy-waarden, dus je kunt zonder codewijziging naar echt Azure omschakelen (ook via
`.env.properties`):

```shell
export GRAPH_MAIL_TENANT_ID=<tenant-uuid>
export GRAPH_MAIL_CLIENT_ID=<client-uuid>
export GRAPH_MAIL_CLIENT_SECRET=<secret>
export GRAPH_MAIL_SENDER=noreply@jouwdomein.nl
export GRAPH_MAIL_ALLOWED_SENDERS=@jouwdomein.nl
export GRAPH_MAIL_SANDBOX_HTTP_CLIENT_AUTHENTICATION=9a3e2b10-5c4d-4e8f-a1b2-c3d4e5f60005   # Microsoft direct
export GRAPH_MAIL_SANDBOX_GRAPH_BASE_URL=https://graph.microsoft.com
export GRAPH_MAIL_SANDBOX_ALLOW_NON_MICROSOFT=false
```

De eerste drie gaan naar de configuratie *Microsoft direct*. `GRAPH_MAIL_TOKEN_BASE_URL` en
`GRAPH_MAIL_BASE_URL` van eerdere versies doen niets meer.

## Installatie in je Valtimo-project

### Backend

Voeg de volgende dependency toe aan je `build.gradle.kts`:

```kotlin
implementation("com.ritense.valtimoplugins:graph-mail:1.1.0")
// Om aan te melden via een Http Client Authentication-configuratie (aanbevolen):
implementation("com.ritense.valtimoplugins:http-client-authentication:3.1.1")
```

Graph Mail heeft `http-client-authentication` als `compileOnly`: het brengt die plugin niet mee, zodat
een upgrade van Graph Mail nooit een plugin toevoegt of de versie van een bestaande verandert (andere
plugins kunnen ervan afhangen, en twee bibliotheken met dezelfde plugin-key houden de applicatie
tegen bij het starten). Configuraties die zich nog met een eigen client secret aanmelden (verouderd)
hebben het niet nodig. Kies je een Http Client Authentication-configuratie terwijl de plugin
ontbreekt of ouder is dan 3.1.1, dan weigert Graph Mail die bij het opslaan met een melding die dat
zegt. Had je applicatie al een eerdere versie van die plugin, lees dan diens release notes vanaf die
versie (zie de [release notes](release-notes.md#110)).

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
    graph-base-url: https://graph.microsoft.com         # default; per configuratie te overschrijven met baseUrl
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

En `@valtimo-plugins/http-client-authentication` 3.1.1 of hoger, om de configuratie waarmee Graph
Mail zich aanmeldt in het beheerscherm te kunnen invullen (eerdere versies eisen een audience).

Voeg beide modules en specificaties toe aan je `AppModule`:

```typescript
import { NgModule } from '@angular/core';
import { PLUGINS_TOKEN } from '@valtimo/plugin';
import { GraphMailPluginModule, graphMailPluginSpecification } from '@valtimo-plugins/graph-mail';
import {
  HttpClientAuthenticationPluginModule,
  httpClientAuthenticationPluginSpecification,
} from '@valtimo-plugins/http-client-authentication';

@NgModule({
  imports: [
    // ... andere imports
    GraphMailPluginModule,
    HttpClientAuthenticationPluginModule,
  ],
  providers: [
    {
      provide: PLUGINS_TOKEN,
      useValue: [graphMailPluginSpecification, httpClientAuthenticationPluginSpecification],
    },
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
| `authenticationPluginConfiguration` | Id van de Http Client Authentication-configuratie waarmee Graph Mail zich aanmeldt en waarvan hij de certificaten gebruikt. Zie [Aanmelden via Http Client Authentication](#aanmelden-via-http-client-authentication) | Aanbevolen; anders de drie hieronder |
| `tenantId`, `clientId`, `clientSecret` | **Verouderd.** Het aanmelden van 1.0, alleen gebruikt als `authenticationPluginConfiguration` leeg is. Zie [Verouderd: aanmelden met client secret](#verouderd-aanmelden-met-client-secret) | Alle drie, zonder verwijzing |
| `baseUrl` | Graph API-URL, voor als Graph Mail niet rechtstreeks met Microsoft praat, bijv. via een gateway: `https://gateway.gemeente.nl/graph`. Alleen samen met `authenticationPluginConfiguration`; leeg: `graph-mail.http.graph-base-url`. Zie [Het Graph-endpoint](#het-graph-endpoint-deployment-instelling-met-een-graph-api-url-per-configuratie) | Nee |
| `allowedSenders` | Whitelist van toegestane afzenders: kommagescheiden volledige adressen (`noreply@gemeente.nl`) en/of domein-entries (`@gemeente.nl`) | Ja |
| `testSenderMailbox` | Standaard afzenderadres voor de test-send functie | Nee |

Een configuratie heeft één van beide logins nodig; zonder wordt ze bij opslaan en deploy geweigerd. De standaard Graph-endpoints en de timeouts zijn **geen** pluginproperty. Ze staan onder `graph-mail.http` in
`application.yml`; alleen de Graph API-URL kan per configuratie (`baseUrl`) — zie
[Het Graph-endpoint](#het-graph-endpoint-deployment-instelling-met-een-graph-api-url-per-configuratie) hieronder voor de reden.

### Afzender-whitelist (`allowedSenders`)

De plugin hanteert **deny-by-default**: elke verzending wordt geweigerd tenzij het (eventueel via
een procesvariabele aangeleverde) `senderMailbox`-adres op de whitelist voorkomt. Matching is
hoofdletterongevoelig; een domein-entry (`@gemeente.nl`) staat het hele domein toe maar géén
subdomeinen. De whitelist geldt ook voor het test-send endpoint.

> **Migratie:** pluginconfiguraties die vóór de introductie van `allowedSenders` zijn aangemaakt,
> weigeren na de upgrade elke verzending totdat de whitelist eenmalig is ingevuld en opgeslagen.

#### Wie de whitelist mag wijzigen

Wie pluginconfiguraties mag beheren, kan de whitelist wijzigen; de plugin vraagt daar geen secret
voor (in 1.x wel). Wie dat recht heeft, kan ook de Http Client Authentication-configuratie wijzigen,
dus een controle in Graph Mail zou weinig begrenzen. De grens ligt daarom buiten de plugin: een
Exchange Online-scope (hieronder) en de netwerkmaatregelen uit de
[handleiding](handleiding.md#wie-bij-het-secret-kan).

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
controle, omdat hij ook beschermt wanneer het client secret buiten de plugin om wordt misbruikt, en
wanneer iemand met beheerrechten de whitelist verruimt.

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
| `PERMANENT_REMOTE` | De overkant weigert dit permanent en dat verandert niet vanzelf: het token endpoint (aanmelden geweigerd), Graph zelf (bijv. 401 token geweigerd, 403 zonder `Mail.Send`, 404 onbekende mailbox), of de uitgaande proxy (407, zie *Uitgaande proxy*). Vereist een configuratie- of permissiewijziging. |
| `UNKNOWN` | Transportfout na verzending; de mail is mogelijk wél verstuurd. Verifieer voordat je opnieuw uitvoert. |
| `CERTIFICATES` | De certificaten van de Http Client Authentication-configuratie zijn niet te laden, of de gateway weigerde ze — in TLS (ook bij het aanmelden), of met een HTTP-fout die niet van Microsoft kwam. Het verzoek heeft Microsoft niet bereikt. De melding noemt de configuratie. |
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

Het client secret staat in de Http Client Authentication-configuratie, als Valtimo secret property
(`@PluginProperty(secret = true)`): het wordt AES-versleuteld opgeslagen in de database en nooit teruggestuurd naar de frontend. De
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

### Het Graph-endpoint: deployment-instelling, met een Graph API-URL per configuratie

`graphBaseUrl` was eerder per pluginconfiguratie instelbaar vanuit de beheer-UI. Dat gaf een
SSRF-primitief, en de hostcontrole op de upload-URL leidde haar verwachte host áf uit `graphBaseUrl`
— waardoor die controle precies zo sterk was als de waarde die een beheerder had ingevuld.

Het staat nu onder `graph-mail.http.graph-base-url` en wordt bij het opstarten gevalideerd tegen een
vaste allowlist van Microsoft Graph-endpoints (inclusief de sovereign clouds). Een afwijkende waarde
laat de applicatie falen bij opstarten met een leesbare melding.

Sinds 1.2.0 kan een Graph Mail-configuratie daarnaast een eigen Graph API-URL zetten (`baseUrl`),
zonder `application.yml`: voor als Graph Mail niet rechtstreeks met Microsoft praat, zoals via een
gateway met een eigen URL of een stub in een testomgeving (wel https). Dat mag alleen samen met een Http Client
Authentication-configuratie: wie die beheert, kan het token endpoint toch al overal heen laten wijzen,
dus de property voegt geen nieuw pad toe voor het secret. Met het verouderde client-secret-aanmelden
wordt ze geweigerd, omdat dat token van Microsoft komt en alleen naar Microsoft hoort te gaan.

- **Controle** (`isValidBaseUrl`, bij opslaan, deployen en per verzending): https, een host,
  geen user info, query of fragment. Geen allowlist: zo'n URL heeft per definitie een eigen host.
  Dubbele slashes in het pad en een slash aan het eind worden weggehaald.
- **Doorgifte.** `GraphMailLogin` zet de URL op `GraphMailAuthentication.graphBaseUrl`;
  `GraphMailClientImpl` gebruikt die voor die verzending in plaats van `graph-base-url`. Zo blijft
  `GraphMailClient` ongewijzigd.
- **Uploadhosts.** Naast de uploadhosts van de cloud van `graph-base-url` accepteert de client dan
  een upload-URL op precies de origin van die URL (scheme, host en poort), voor een gateway die de
  upload-URL naar zichzelf herschrijft.
- **Het beheerscherm** toont het veld alleen met een gekozen Http Client Authentication-configuratie,
  controleert hetzelfde als de backend, en slaat het leeg op als je overstapt op het client secret
  (Valtimo overschrijft niet-geheime properties met wat binnenkomt).
- **Sleutel `baseUrl`**, zoals bij andere plugins (Xential, Suwinet, Public Task). Niet `graphBaseUrl`:
  configuraties van 1.0.3 en ouder kunnen die nog bewaard hebben, en die wordt sinds 1.0.4 genegeerd.
  Dezelfde sleutel zou die oude waarden weer laten meetellen.

Het token endpoint van een Http Client Authentication-configuratie staat in die configuratie en wordt
niet gecontroleerd. Wie pluginconfiguraties mag beheren, kan het dus naar een eigen host laten wijzen;
dat begrens je in het netwerk, zie de [handleiding](handleiding.md#wie-bij-het-secret-kan). Alleen het
verouderde aanmelden met client secret gebruikt `graph-mail.http.token-base-url`; dat wordt, als
voorheen, bij het opstarten gecontroleerd tegen de Microsoft-loginhosts, omdat het secret er als
formulierveld naartoe gaat.

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

> De proxy is bewust een deployment-instelling en geen pluginproperty: hij ziet elk Graph-verzoek,
> met het token.

> **Het aanmelden gaat niet via deze proxy.** Het token-verzoek doet Http Client Authentication, met
> de HTTP-client en timeouts van Valtimo (standaard 180 seconden leestimeout,
> `valtimo.http.rest-client.read-timeout`, voor alle REST-clients van Valtimo). Heeft het aanmelden
> een proxy nodig, stel die dan op JVM-niveau in. Een token endpoint dat blijft hangen, houdt een
> job-executor-thread zo lang vast als die timeout.

### Aanmelden via Http Client Authentication

Graph Mail meldt zich bij Microsoft aan via de Http Client Authentication-configuratie in
`authenticationPluginConfiguration`, van elk authenticatietype. Rechtstreeks bij Microsoft is dat
**Token exchange zonder audience**: die doet het client-credentials-verzoek naar het token endpoint
van Entra (met het clientcertificaat, als er een is) en bewaart het token tot kort voordat het
verloopt. Via een gateway kan het ook een ander type zijn, bijvoorbeeld *Header* of *None* als de
gateway zelf bij Microsoft aanmeldt. Graph Mail gebruikt daarvan
`HttpClientAuthenticator.authenticationHeaders()` (per verzoek, álle headers die het type geeft) en
`sslContext()` (de certificaten), op zijn eigen verbinding: proxy, timeouts, geen redirects en
HTTP/1.1 blijven die van `graph-mail.http`. `applyAuth()` wordt bewust niet gebruikt, die zou de
request factory vervangen. Inrichten vanuit het beheerscherm: zie de [handleiding](handleiding.md).

- **Wat Graph Mail controleert** (`GraphMailLogin`), bij opslaan, bij deployen en per verzending: dat
  de verwijzing een bestaande Http Client Authentication-configuratie is. Niet het authenticatietype,
  niet waar het token endpoint naartoe wijst, en niet welke resource de scope noemt (zie
  [handleiding](handleiding.md#wie-bij-het-secret-kan)). Een verkeerd type, endpoint of scope geeft
  een aanmeldfout of een Graph-401 die de configuratie noemt.
- **Een verwijzing, geen getypeerde property.** De property is een gewone string met het id. Valtimo
  13.48 kan bij een property van een categorie-type niet zeggen wat er mis is met de keuze, en faalt
  bij een optionele als die leeg is — en optioneel moet ze zijn, voor configuraties met client secret. De sleutel bevat `PluginConfiguration`, zodat Valtimo's
  export de Graph Mail-configuratie ná de Http Client Authentication-configuratie zet.
- **Volgorde in `*.pluginconfig.json`:** de Http Client Authentication-configuratie in **hetzelfde
  bestand, vóór** de Graph Mail-configuratie. De volgorde tussen bestanden is niet betrouwbaar (in een
  jar die van de entries).
- **Http Client Authentication is `compileOnly`.** Alleen `HttpClientAuthenticatorCalls` in
  `GraphMailLogin.kt` raakt een type van die plugin aan, en pas nadat
  `httpClientAuthenticationProblem()` heeft vastgesteld dat de plugin er is en `authenticationHeaders()`
  en `sslContext()` heeft. Zonder de plugin laden de andere klassen gewoon, en werkt het aanmelden met
  client secret.
- **Aanmeldfouten** worden ingedeeld op de cause chain van Http Client Authentication: met
  certificaten wordt een TLS-weigering `CERTIFICATES`; 408, 429, 5xx en netwerkfouten zijn tijdelijk en worden binnen
  het wachtbudget opnieuw geprobeerd (er is dan nog niets verstuurd); de rest is permanent, met de
  melding wat je in de configuratie moet controleren. De body van een foutantwoord komt nooit in een
  melding: een token endpoint kan het formulier, met het secret, terugsturen. Http Client
  Authentication haalt de tokenverzoeken ook buiten Valtimo's request-logging.
- **Een Graph-401** wordt niet met een nieuw token herhaald: Http Client Authentication houdt haar
  token in haar eigen cache (`GraphMailAuthentication.rejected` geeft `false`). Een ingetrokken token
  blijft tot het verloopt (~1 uur) in gebruik.

### Verouderd: aanmelden met client secret

Een configuratie zonder `authenticationPluginConfiguration` meldt zich aan zoals in 1.0: met haar
eigen `tenantId`, `clientId` en `clientSecret`, via een client-credentials-verzoek naar
`graph-mail.http.token-base-url` (`GraphMailClientSecretLogin`). Zo blijven configuraties van 1.0 —
opgeslagen of in een `*.pluginconfig.json` — na een upgrade werken, en start de applicatie. Het
verdwijnt in een volgende major versie; Graph Mail logt het als verouderd bij elke opslag en deploy
(met een `*.pluginconfig.json` dus bij elke start) en eenmaal per configuratie bij de eerste
verzending.

- **De drie properties bestaan nog**, optioneel. `clientSecret` blijft een secret-property: Valtimo
  versleutelt en ontsleutelt alleen properties die de plugin definieert, dus zonder die definitie zijn
  de secrets van 1.0 onleesbaar en wordt een secret uit een `*.pluginconfig.json` onversleuteld
  opgeslagen.
- **Controles als in 1.0**: alle drie ingevuld, en een `tenantId` die de token-URL niet kan
  veranderen (`isValidTenantId`). Een verwijzing gaat altijd voor; staan `tenantId` of `clientId` er
  dan nog, dan logt Graph Mail dat ze genegeerd worden.
- **Eén tokenverzoek per poging.** Het verzoek gaat over de gedeelde Graph-client, dus met de proxy en
  timeouts van `graph-mail.http`. 408, 429, 5xx en netwerkfouten zijn tijdelijk en worden, zoals bij
  Http Client Authentication, binnen het wachtbudget van de verzending opnieuw geprobeerd; andere
  4xx zijn permanent. Een foutantwoord komt zonder body in de melding en in de cause.
- **Een Graph-401** haalt het geweigerde token uit `GraphTokenCache` (`GraphMailAuthentication.rejected`
  geeft `true`), en `GraphMailClientImpl` probeert het verzoek eenmaal opnieuw met een nieuw token, zoals
  in 1.0. Een 401 is geweigerd vóór verwerking, dus dat is ook bij een verzending veilig. Een tweede
  401 is permanent.
- **Het beheerscherm** toont de drie velden zolang er geen Http Client Authentication-configuratie
  gekozen is. Het stuurt `tenantId` en `clientId` altijd mee terug: Valtimo overschrijft
  niet-geheime properties bij een update met wat er binnenkomt, ook met niets. Kies je een
  configuratie, dan worden ze leeggemaakt. Een opgeslagen secret kan het scherm niet wissen (Valtimo
  bewaart een secret dat leeg binnenkomt); Graph Mail negeert het dan.

### Clientcertificaat via een gateway (mTLS)

Staan er certificaten in de Http Client Authentication-configuratie, dan gebruikt Graph Mail ze voor
al zijn verkeer; het aanmelden doet Http Client Authentication er zelf mee.

- **Vertrouwen.** Staat er een servercertificaat in, dan vertrouwt Graph Mail voor de Graph-verzoeken
  en uploads **alleen** dat, niet meer de standaard-CA's van de JVM. Het aanmelden vertrouwt daarnaast
  de standaard-CA's. Een gateway die het aanmelden en Graph doorlaat maar niet de uploadhosts
  (`*.office.com` e.d.), laat bijlagen vanaf 3 MB op een certificaatfout stuklopen.
- **Endpoints.** Een transparante gateway laat de Microsoft-URL's ongemoeid; dan verandert er niets
  aan `graph-mail.http`. Een gateway met een eigen URL zet je in de Graph Mail-configuratie
  (`baseUrl`, zie *[Het Graph-endpoint](#het-graph-endpoint-deployment-instelling-met-een-graph-api-url-per-configuratie)*); `graph-base-url`
  blijft een Microsoft-host.
- **Bijlagen.** Bijlagen van 3 MB of groter gaan in stukken van 3,2 MB; de gateway moet verzoeken
  van die grootte doorlaten (nginx staat standaard 1 MB toe: `client_max_body_size`).
- **Het certificaat komt met de hele keten.** De JDK biedt een clientcertificaat alleen aan als de
  gateway de uitgever ervan noemt; zet tussenliggende certificaten dus mee in de PEM.
- **Fouten** worden `verdict=CERTIFICATES`, met een melding die de configuratie noemt. Dat geldt voor
  een weigering in TLS en voor een HTTP-weigering op een Graph-verzoek die niet van Microsoft komt
  (status 400, 401 of 403 zonder Graph-headers of -foutbody; nginx weigert een ontbrekend of
  onbekend certificaat met een 400). Een TLS-weigering die de client ziet, is geen `UNKNOWN`: de
  gateway heeft het verzoek niet verwerkt. Eén grens: met TLS 1.3 kan een gateway die het certificaat
  weigert de verbinding sluiten voordat zijn melding (alert) aankomt. Dan is dat niet te
  onderscheiden van een verbinding die na het versturen wegviel; bij een verzending blijft dat
  `UNKNOWN`, met in de melding de configuratie als plek om te kijken. De controle zit in een
  interceptor op de client van de certificaten, dus vóór de retry-logica en voor elk verzoek. Een
  HTTP-weigering bij het aanmelden is een aanmeldfout met de hint om het certificaat en de gateway te
  controleren: daar is niet te zien of de gateway of Microsoft weigerde.
- **Verbindingen.** Per certificaatset is er een eigen client met een eigen connection pool (maximaal
  16 sets tegelijk). Http Client Authentication levert voor dezelfde certificaten steeds hetzelfde
  `SSLContext`, dus verbindingen worden hergebruikt. Een applicatie die zelf `graphMailRestClient`
  definieert, krijgt voor certificaatclients de instellingen van de plugin.

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

De plugin gebruikt één gedeelde, gepoolde HTTP-client (de JDK `HttpClient`) voor alle verzendingen
zonder certificaten (met certificaten: zie *Clientcertificaat via een gateway (mTLS)*).
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
| Aanmelden bij het token endpoint (Http Client Authentication), daarbovenop | `valtimo.http.rest-client.read-timeout` (standaard 180 seconden) |

De twee onderste grenzen zijn geen wachttijd maar werk: het overzetten van een bijlage van 25 MiB
duurt nu eenmaal. Alleen de bovenste was de bron van de engine-brede stilstand, en die is nu
begrensd.

> **Let op (`queue-capacity`):** bij een thread-pool-executor worden threads bóven `core-pool-size`
> pas aangemaakt wanneer de wachtrij vol is. Staat `queue-capacity` hoog, dan blijft de pool in de
> praktijk op `core-pool-size` steken en doet `max-pool-size` niets. Houd `queue-capacity` daarom
> klein als je op de extra threads wilt kunnen leunen, en stem het totale aantal threads af op de
> database-connectiepool.

### Token caching

Het token cachet Http Client Authentication, per configuratie (de sleutel bevat token endpoint,
client ID, een hash van het secret, scope en certificaten), tot kort voordat het verloopt.
Gelijktijdige aanvragen voor dezelfde configuratie wachten op één aanroep. Graph Mail vraagt per
verzoek de `Authorization`-header.

Alleen het verouderde aanmelden met client secret gebruikt Graph Mails eigen `GraphTokenCache` (een
gedeelde bean; de sleutel is tenant, client ID en een hash van het secret).

### Metrics

Heeft de applicatie Micrometer op het classpath — GZAC levert Actuator mee — dan publiceert de plugin
meters voor verzendingen, hun duur en de vrije bijlage-slots:

| Meter | Type | Betekenis |
| --- | --- | --- |
| `graph.mail.sends` | counter | Verzendingen, met een `outcome`-tag die hetzelfde vocabulaire gebruikt als het `verdict`-veld |
| `graph.mail.send.duration` | timer | Duur van een verzending |
| `graph.mail.attachment.permits.available` | gauge | Vrije slots in de bijlage-limiter |
| `graph.mail.token.cache.size` | gauge | Tokens in de cache van het verouderde aanmelden met client secret |

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

Via de pluginconfiguratiepagina kan een testmail worden verstuurd om te verifiëren dat het
aanmelden via de Http Client Authentication-configuratie en de machtigingen werken. Dit vereist de
rol `ROLE_ADMIN`. De testmail wordt niet opgeslagen in Verzonden items en de afzender moet op de `allowedSenders`-whitelist staan.
