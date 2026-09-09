# Testcase Graph Mail

Procesmodellen en formulier om de `send-email`-actie in de sandbox te beproeven. Hoort bij het
testdraaiboek; de nummering T1 t/m T8 verwijst daarnaar.

## Waarom dit bestaat

De `send-email`-actie neemt geen HTML aan maar een **resource-id in
`TemporaryResourceStorageService`**. Hetzelfde geldt voor bijlagen. Zonder een stap die die opslag
vult is de actie in een procesmodel niet te beproeven — je krijgt dan
`Body content '...' not found in temporary storage`.

`MailTestSupport` (`backend/app/src/main/kotlin/com/ritense/plugin/sandbox/`) vult die opslag en
zet `bodyContentId` en `attachmentResourceIds` klaar als procesvariabelen.

## Eenmalige koppelstap

De process-links staan hier als `.json.template` en worden **niet** automatisch uitgerold. Reden:
een process-link verwijst naar de UUID van een pluginconfiguratie, en die bestaat pas nadat je de
configuratie in het beheerscherm hebt aangemaakt. Een gefixeerde UUID meeleveren zou niet werken.

1. Maak de pluginconfiguratie aan via **Admin → Plugins → Microsoft Graph Mail Plugin**
   (tenant-id, client-id, client secret en `allowedSenders`).
2. Koppel de `send-email`-taak aan de actie. Twee wegen:
   - **Via het beheerscherm** (aanbevolen): Admin → Processen → `Graph Mail Test` → koppel de taak
     `send-email` aan de actie *Send email* en vul de eigenschappen in volgens de tabel hieronder.
   - **Via configuratie**: hernoem het template naar `*.process-link.json`, vervang
     `VUL-HIER-DE-UUID-VAN-JE-PLUGINCONFIGURATIE-IN` door de UUID uit de URL van je
     pluginconfiguratie, en herstart.

### Koppeling van de actie-eigenschappen

| Actie-eigenschap | Waarde |
|---|---|
| `senderMailbox` | `pv:senderMailbox` |
| `recipients` | `pv:recipients` |
| `cc` | `pv:cc` |
| `bcc` | `pv:bcc` |
| `replyTo` | `pv:replyTo` |
| `subject` | `pv:subjectResolved` — bevat het volgnummer van de verzending |
| `contentId` | `pv:bodyContentId` |
| `attachmentIds` | `pv:attachmentResourceIds` |

Doe dit voor **beide** processen; ze hebben allebei een taak met id `send-email`.

## Het formulier schrijft procesvariabelen, geen documentvelden

De velden in `graph-mail-test-start.form.json` dragen allemaal het prefix `pv:` — `pv:senderMailbox`,
`pv:recipients`, enzovoort. Dat is geen stijlkeuze maar een vereiste: Valtimo schrijft een veld
zonder prefix naar het **document**, en een veld met `pv:` naar een **procesvariabele**
(`FormIoFormDefinition` onderscheidt `processVarName` en `documentJsonPointer`).

Zowel de process-links als `MailTestSupport` lezen procesvariabelen. Haal je het prefix weg, dan
komt de invoer in het document terecht, blijven de procesvariabelen leeg en faalt `send-email` op:

```
NullPointerException: Parameter specified as non-null is null:
method ...GraphMailPlugin.sendEmail, parameter senderMailbox
```

De document-definitie houdt dezelfde velden aan zodat de zaak een geldig schema heeft; het formulier
vult ze niet.

## De twee processen

### `graph-mail-test-process` — T4, T5, T8

```
start ─▶ body opslaan ─▶ bijlage genereren ─▶ send-email ─▶ ◇ nog een mail?
             ▲                                                  │ ja
             └──────────────────────────────────────────────────┘
```

Startformulier-velden die het gedrag sturen:

- **Bijlage (KB)** — `0` is geen bijlage. Onder `2048` blijft de plugin op het inline-pad; daarboven
  gaat hij via concept plus upload-sessie en is `Mail.ReadWrite` vereist. Zo beproef je T5 zonder
  bestanden te hoeven zoeken.
- **Aantal verzendingen** — meer dan `1` laat het proces terugkeren naar dezelfde `send-email`-taak.
  Dat is T8: elke iteratie hoort te verzenden, geen enkele mag als duplicaat worden onderdrukt.

De taak staat op `asyncBefore` met `failedJobRetryTimeCycle` `R5/PT2M` — tien minuten in totaal, ruim
onder de dertig minuten die de duplicaatbescherming onthoudt.

### `graph-mail-retry-test-process` — T7

```
start ─▶ body opslaan ─▶ bijlage genereren ─▶ send-email ─▶ rollback forceren ─▶ eind
                                              (asyncBefore)   (géén async)
```

`send-email` staat op `asyncBefore`, de taak erna bewust **niet**. Beide draaien dus in één
transactie. `mailTest.failOnce(...)` gooit bij de eerste passage, waardoor de transactie terugdraait
terwijl Graph de mail al onomkeerbaar heeft geaccepteerd. De job-executor voert `send-email` opnieuw
uit en de duplicaatbescherming hoort de tweede Graph-aanroep over te slaan.

**Wat je moet zien:** precies één ontvangen mail, en in de log

```
Skipping duplicate send for activity instance [...] — already sent
```

Komen er twee mails aan, dan werkt de bescherming niet in jouw opstelling — controleer of je op één
node draait.

De teller in `failOnce` staat bewust in geheugen en niet in een procesvariabele: die zou met de
transactie mee terugrollen, waardoor elke poging opnieuw de eerste zou zijn en het proces nooit
voorbij die taak kwam. Hij telt per procesinstantie, dus elke nieuwe start faalt weer eenmalig.

## Wat deze testcase níét beproeft: de HTML-sanitisatie

`storeBody` zet de **Berichttekst** door een `escapeHtml` voordat het de HTML samenstelt:

```kotlin
<p>${escapeHtml(bodyText)}</p>
```

Markup die je in dat veld typt komt dus als leesbare tekst in de mail aan — je ziet de tag staan,
maar het is nooit een element geweest en de sanitizer van de plugin krijgt hem niet te zien. De
testmail-knop in het beheerscherm gebruikt een vaste body, dus ook daarlangs komt er geen ruwe HTML
binnen.

Dat is opzet: de scaffolding is de *producent* van de body, en een producent hoort geen kapotte
markup af te leveren. Dat de plugin daarnaast saneert is een tweede lijn, geen excuus om zelf slordig
te zijn.

Gevolg: **de sanitizer is met T1 t/m T8 niet te beproeven.** Wie dat gedrag wil zien, kijkt naar de
unit tests in `backend/plugin/src/test/.../GraphMailPluginTest.kt` — `an http image source is
stripped while https and cid survive` en de testgevallen rond inline `style`-attributen, inclusief de
ontwijkingen met CSS-escapes en comment-splicing.

Wil je toch handmatig met ruwe HTML testen, dan moet je die zelf in `TemporaryResourceStorageService`
zetten en het resulterende resource-id als `contentId` aan de actie meegeven, buiten dit
procesmodel om.

## Overnemen naar je eigen project

Kopieer de map `config/case/graph-mail-test/` en `MailTestSupport.kt`. De bean heeft alleen
`TemporaryResourceStorageService` nodig en hangt verder nergens aan vast. Pas de casedefinitie aan
als `graph-mail-test` bij jou al bestaat.

Twee dingen aan `MailTestSupport.kt` moeten kloppen, anders is de bean onbereikbaar vanuit BPMN:

1. **Het `package` moet onder je eigen `@SpringBootApplication`-klasse vallen**, anders wordt de
   klasse niet gescand. Pas de `package`-regel aan en leg het bestand in de bijbehorende map.
2. **`@ProcessBean` moet blijven staan.** Valtimo geeft Operaton niet de hele Spring-context maar
   alleen de beans met die annotatie — `OperatonWhitelistedBeansPlugin` verzamelt ze via de
   `@ProcessBean`-qualifier en `SpringExpressionManager` gebruikt dan een `ReadOnlyMapELResolver`
   over precies die map. Dit staat standaard aan (`valtimo.operaton.bean-whitelisting`,
   `matchIfMissing = true`).

Klopt een van beide niet, dan faalt elke procesinstantie op de eerste taak met:

```
Unknown property used in expression: ${mailTest.storeBody(execution)}.
Cause: Cannot resolve identifier 'mailTest'
```

Die melding wijst niet naar de oorzaak — hij zegt alleen dat de naam niet oplost, niet waarom.
