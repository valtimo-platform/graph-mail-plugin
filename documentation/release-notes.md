# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.4

Een grote release. Het verzenden is betrouwbaar gemaakt onder de omstandigheden waarin het eerder
stukliep — throttling door Graph, een teruggedraaide transactie, een loop over dezelfde taak, een
uitgaande proxy — en er is een reeks beveiligingsmaatregelen bijgekomen, waarvan de belangrijkste
een verplichte afzender-whitelist is. Daarnaast verhuist een aantal instellingen van de beheer-UI
naar `application.yml`, omdat het daar thuishoort.

Lees **Actie vereist** voordat je upgradet: zonder ingevulde whitelist verstuurt de plugin niets
meer.

### Actie vereist

**`allowedSenders` is verplicht geworden.** Elke verzending wordt geweigerd tenzij het afzenderadres
op de whitelist van de pluginconfiguratie staat. Bestaande configuraties versturen na de upgrade
**niets meer** totdat die lijst eenmalig is ingevuld en opgeslagen. Volledige adressen en
`@domein`-entries mogen allebei; de regel geldt ook voor het testmail-endpoint.

**Endpoints en timeouts staan nu in `application.yml`.** `tokenBaseUrl`, `graphBaseUrl`,
`connectTimeoutSeconds` en `readTimeoutSeconds` zijn geen pluginproperty meer; ze staan onder
`graph-mail.http`. Bestaande configuraties met afwijkende waarden negeren die na de upgrade, dus zet
ze over. Alleen Microsoft-endpoints worden geaccepteerd — een eigen host laat de applicatie bij het
opstarten falen. De aanleiding staat in [developer.md](developer.md): het client secret wordt naar
`tokenBaseUrl` gePOST, en die instelbaar houden vanuit de beheer-UI was een exfiltratiepad.

**De afzender-whitelist wijzigen vraagt om het client secret.** Wie `allowedSenders` aanpast, moet
het `clientSecret` in dezelfde request opnieuw meegeven. Een adres toevoegen is immers een
rechtenuitbreiding: vanaf dat moment kan er namens die mailbox gemaild worden. Laat je de lijst
ongewijzigd, dan verandert er niets en mag het secretveld leeg blijven. Herordenen, andere
spatiëring en hoofdletters tellen niet als wijziging; een adres verwijderen wel. Uit te schakelen
met `graph-mail.require-secret-for-allowlist-change: false`.

**Stel een `failedJobRetryTimeCycle` in** op de send-email service task, bijvoorbeeld `R5/PT2M`. De
plugin geeft een verzending nu na hooguit twee seconden wachten terug aan de engine in plaats van de
thread bezet te houden, en leunt daarvoor op de retry-instelling van de taak. Houd de totale duur
van die cyclus onder de dertig minuten: daarna vergeet de duplicaatbescherming een verzending.

**De plugin schrijft een procesvariabele.** Elke procesinstantie die mail verstuurt krijgt
`graphMailPass_<activityId>`, zichtbaar in Cockpit en in de variabelenhistorie.

### Verzendingen komen niet meer dubbel of helemaal niet aan

Vier situaties konden ertoe leiden dat een ontvanger twee keer dezelfde mail kreeg, of juist geen.

Bij een teruggedraaide transactie kon dezelfde mail alsnog twee keer uitgaan. De plugin herkent die
herhaling nu en slaat de tweede Graph-aanroep over. De keerzijde van diezelfde bescherming was dat
een loop over dezelfde service task alleen de eerste e-mail verstuurde en de rest stil oversloeg,
terwijl het proces doorliep alsof er verzonden was. Beide gevallen worden nu uit elkaar gehouden;
hoe dat werkt en waar de grenzen liggen staat in [developer.md](developer.md).

Een read-timeout op de verzendaanroep leidde tot herhaalde pogingen, waarmee de ontvanger meerdere
kopieën kon krijgen. Zo'n verzending wordt nu gemeld als `verdict=UNKNOWN` — mogelijk aangekomen,
controleer de mailbox — en wordt niet automatisch herhaald. In het verlengde daarvan verwijderde een
verzending die op het antwoord time-oute het bericht daarna uit Verzonden items; dat gebeurt niet
meer.

Twee stappen probeerden het bovendien nooit opnieuw waar dat wel had gemoeten: het aanmaken van een
upload-sessie voor grote bijlagen gaf bij throttling meteen op, en de conceptflow deed dat bij
netwerkfouten.

Tot slot bleef bij een mislukte verzending via de upload-sessie het concept achter in de
afzendermailbox. Bij een cyclus als `R5/PT2M` liepen die op tot vijf per mislukte verzending.
Concepten worden nu opgeruimd zodra vaststaat dat er niets verstuurd is; bij een onzekere afloop
blijft het concept met rust, omdat het al in Verzonden items kan staan.

### De engine loopt niet meer vast op één externe API

Bij throttling door Graph lag de hele engine stil, ook voor werk dat niets met e-mail te maken had:
alle job-executor threads lagen tegelijk een `Retry-After` uit te zitten. De plugin wacht nu hooguit
twee seconden per aanroep en geeft de thread daarna terug aan de engine. De harde tijdslimiet van
dertig seconden per verzending, die met tientallen seconden overschreden kon worden, wordt weer
gerespecteerd.

Een verwant risico is het geheugen: een verzending houdt haar bijlagen volledig in de heap.
`graph-mail.http.attachment-concurrency` begrenst nu hoeveel verzendingen mét bijlagen tegelijk
lopen, los van de thread-pool. Zonder die grens kon een piek in bijlagen het geheugen laten
vollopen. Verzendingen zonder bijlagen worden niet begrensd.

Daarnaast gebruikt de plugin één gedeelde, gepoolde HTTP-verbinding voor alle verzendingen. Eerder
werd per plugin-actie een nieuwe client opgebouwd, waardoor elke e-mail een volledige TLS-handshake
kostte.

### Netwerk en cloudomgeving

Achter een uitgaande proxy faalde élke verzending met een connectiefout, en omdat die fout als
tijdelijk gold bleef de job-executor het eindeloos proberen — een storing die eruitziet als een
netwerkprobleem terwijl de configuratie de oorzaak is. De plugin volgt nu weer de proxy-instellingen
van de JVM en logt bij het opstarten welke proxy hij gebruikt. Een afwijkende proxy stel je in met
`graph-mail.http.proxy-host` en `proxy-port`.

Vroeg die proxy om authenticatie, dan werd de `407` behandeld als tijdelijke storing en bleef de
job-executor herproberen op iets dat elke poging identiek weigert. Dat is nu een permanente fout die
naar de proxy wijst in plaats van naar Graph. Een proxy die authenticatie eist wordt overigens niet
ondersteund: de HTTP-client kan geen proxy-credentials aanbieden.

Verzenden vanuit een sovereign cloud (US Gov, China) faalde altijd al bij het ophalen van het token.
Toen dat was opgelost, bleek elke bijlage boven 2 MiB alsnog te falen: de configuratie accepteerde
het sovereign endpoint, maar de controle op de upload-URL kende uitsluitend commerciële hosts. Kleine
mails werkten daardoor wel, wat het lastig te herkennen maakte. Uploadhosts worden nu per cloud
bijgehouden — een US Gov-omgeving accepteert dus ook geen commerciële uploadhost meer.

Tot slot gooide een onderbroken upload van een grote bijlage de hele upload weg, of leverde hij een
bijlage met een gat erin. De plugin volgt nu de positie die Graph zelf teruggeeft, ook wanneer die
achteruit wijst, en breekt af wanneer de upload niet meer vordert.

### Beveiliging

Twee routes lieten een tracking pixel door de HTML-filter. Via `style="background:url(...)"` kwam
een externe request alsnog binnen; die wordt nu geweerd, inclusief de varianten met CSS-escapes en
commentaar waarmee een letterlijke filtercontrole te omzeilen was. Diezelfde pixel kon er ook via
`<img src="http://...">` langs. Een afbeelding wordt opgehaald zodra de ontvanger de mail opent, dus
een `http`-bron verraadt het leesmoment over een onbeveiligde verbinding. `http` vervalt daarom voor
afbeeldingen. Logo's blijven werken: `https` en `cid:` zijn ongemoeid, en alleen een sjabloon met een
logo op `http://` moet verhuizen. Een `<a href="http://...">` blijft toegestaan, want een link wordt
pas gevolgd wanneer iemand klikt.

Tokens werden niet gecached over verzendingen heen, waardoor elke e-mail een nieuwe tokenaanvraag
deed. Ernstiger was de keuze van de cachesleutel: een configuratie met een verkeerd secret kon
meeliften op het token dat een andere configuratie voor dezelfde tenant had opgehaald. De sleutel
bevat nu een hash van het secret, zodat dat niet meer kan. Het client secret en het access-token
konden bovendien in cleartext in de log belanden; ook dat is gedicht.

Rond het testmail-scherm zijn twee lekken gedicht: e-mailadressen worden nu ook gemaskeerd in
foutmeldingen van het endpoint, en een verkeerd getypt client secret logde de beheerder niet langer
uit. De naam en het content-type van een bijlage komen uit resource-metadata en gingen ongefilterd
door naar Graph en de logging; die worden nu gecontroleerd zoals elk ander extern beïnvloedbaar veld.
`tenantId` wordt server-side gevalideerd en percent-encoded in de token-URL, zonder UUID-eis: een
verified domain en de aliassen `common` en `organizations` blijven geldig.

Twee instellingen konden stilzwijgend niets doen. `graph-mail.http.allow-non-microsoft-endpoints`
schakelt de endpoint-controle uit maar logde niets; staat die vlag aan, dan meldt de plugin dat nu
bij elke start als `ERROR`. En `non-proxy-hosts` accepteerde een komma-gescheiden lijst die nergens
op matchte, waardoor intranetverkeer alsnog door de proxy ging — dat is nu een startupfout.

`jsoup` is bijgewerkt van 1.17.2 naar 1.23.1 vanwege CVE-2026-71497. De filterinstellingen van deze
plugin waren niet daadwerkelijk kwetsbaar — de safelist voegt geen raw-text-elementen toe — maar het
is de bibliotheek waar de HTML-sanitisatie op leunt. In de publicatie-workflows is script-injectie
via `changed-files`-uitvoer gedicht, en is `tj-actions/changed-files` op een commit-SHA vastgezet in
plaats van op een verplaatsbare tag (CVE-2025-30066).

### Diagnose bij een mislukte verzending

Elke mislukte verzending logt nu een `verdict` dat zegt of opnieuw proberen zin heeft:
`PERMANENT_INPUT`, `PERMANENT_REMOTE`, `TRANSIENT` of `UNKNOWN`. Een beheerder hoeft daarvoor geen
stacktrace te lezen. Twee meldingen die eerder in de verkeerde richting wezen zijn gecorrigeerd:
throttling van Entra werd gemeld als "controleer Client ID en Secret", en een onderbroken verzending
bij het afsluiten van de applicatie kwam als `UNCLASSIFIED` met stacktrace in de auditlog in plaats
van als tijdelijk. Een onbruikbare of te lange bijlagenaam liep bovendien pas bij Graph stuk; dat
wordt nu vooraf afgevangen.

Elk Graph-verzoek draagt een `client-request-id`, en bij een fout staan dat id en Graphs eigen
`request-id` in de auditlog — de twee waarden waar Microsoft Support als eerste om vraagt. Een
geweigerde upload-URL meldde alleen dát hostvalidatie faalde; op `DEBUG` staat nu ook welke host het
was, zodat een ontbrekend clouddomein te melden is. Uit de foutmelding zelf blijft die host weg,
omdat de waarde uit een extern antwoord komt.

Heeft de applicatie Actuator, dan publiceert de plugin Micrometer-metrics: `graph.mail.sends`,
`graph.mail.send.duration` en gauges voor de vrije bijlage-slots en de tokencache. De `outcome`-tag
gebruikt hetzelfde vocabulaire als het `verdict`-veld. Zonder Micrometer op het classpath verandert
er niets.

### Voorbereid op meerdere nodes

Waar de duplicaatbescherming haar markeringen bewaart zit nu achter `SentMarkerStore`. De standaard
blijft in-memory en dus per JVM. Draai je meerdere nodes en heb je de garantie nodig, dan zet je een
eigen implementatie bij zonder de guard zelf te vervangen; de enige — maar bepalende — eis is dat
die opslag buiten de omliggende transactie commit. Zie [developer.md](developer.md).

### Publicatie, frontend en onderhoud

`cn.lalaki.central` stond als runtime-dependency in de gepubliceerde POM, waardoor elke consument
een Gradle-publicatieplugin binnenhaalde; die is verwijderd. De frontend bouwt API-URL's nu via
`ConfigService`, zodat de plugin ook werkt wanneer frontend en backend op verschillende origins
draaien. De rate-limiter van het testmail-endpoint hield voor elke gebruiker permanent een entry
vast; die worden nu opgeruimd. Tot slot is ktlint aan de PR-checks toegevoegd en is Dependabot
ingesteld voor gradle, npm en github-actions.

## 1.0.3

- Bijlagen krijgen weer hun oorspronkelijke bestandsnaam.
- De testmail toont het afzenderadres in de voettekst.
- Bij het openen van een bestaande pluginconfiguratie is *Opslaan* direct beschikbaar.

## 1.0.2

Valtimo bijgewerkt naar versie 13.41.0.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
