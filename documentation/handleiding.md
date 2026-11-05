# Handleiding — Graph Mail Plugin

Deze pagina beschrijft de plugin volledig vanuit de **Valtimo-beheerinterface**. Er komt geen
code, YAML of BPMN-XML aan te pas. Bedoeld voor (functioneel) beheerders die de plugin inrichten
en aan een proces koppelen.

Voor bouwen, lokaal testen en installeren in een eigen project: zie [developer.md](developer.md).

## Wat de plugin doet

De plugin verstuurt e-mail vanuit een processtap via **Microsoft Graph** — de mail-API van
Microsoft 365. Er is geen SMTP-server nodig; Valtimo praat rechtstreeks met Microsoft 365 en
verstuurt de mail namens een bestaande mailbox in je eigen tenant.

Wat je per verzending kunt instellen:

- **Afzender** — elke mailbox in je Microsoft 365-tenant
- **Ontvangers** — To, CC en BCC, elk met meerdere adressen
- **Reply-To** — een ander antwoordadres dan de afzender
- **Onderwerp**
- **HTML-body** — opmaak, tabellen, afbeeldingen
- **Bijlagen** — tot 5 bestanden

Verzonden mail komt in de map *Verzonden items* van de afzendermailbox te staan, net alsof
iemand hem met de hand had verstuurd.

## Wat je nodig hebt van je Azure-beheerder

De plugin meldt zich bij Microsoft aan als *applicatie*, niet als gebruiker. Vraag je Azure-/
Entra-beheerder om een **App Registration** en om onderstaande drie waarden. Zonder deze drie
kun je de plugin niet inrichten.

| Wat je opvraagt | Waar de beheerder het vindt |
| --- | --- |
| Directory (tenant) ID | Overzichtspagina van de App Registration |
| Application (client) ID | Overzichtspagina van de App Registration |
| Client secret | *Certificates & secrets* → nieuw secret aanmaken |

Vraag daarnaast om deze machtigingen:

| Machtiging | Wanneer nodig |
| --- | --- |
| `Mail.Send` | Altijd |
| `Mail.ReadWrite` | Alleen als je bijlagen verstuurt die samen niet in één bericht passen (ruwweg vanaf 3 MB) |

> **Belangrijk:** dit zijn *applicatiemachtigingen*. Een gewone gebruiker kan deze niet zelf
> toekennen — een tenant-beheerder moet ze verlenen én er expliciet beheerdersconsent voor
> geven. Regel dit vóórdat je begint, anders loop je vast bij de testmail. Vraag alleen wat je
> echt nodig hebt: `Mail.ReadWrite` uitsluitend bij grote bijlagen.

Een client secret heeft een einddatum. Vraag de beheerder wanneer het verloopt en zet een
herinnering — als het secret verloopt, stopt het versturen van mail met een aanmeldfout.

## Stap 1 — Aanmelden bij Microsoft: een Http Client Authentication-configuratie

Graph Mail regelt het aanmelden niet zelf, maar gebruikt daarvoor een configuratie van de plugin
**Http Client Authentication**. Zo staan de Client ID, het secret en eventuele certificaten op één
plek. Ga naar **Admin → Plugins**, kies **Http Client Authentication** en maak een configuratie aan:

| Veld | Wat je invult |
| --- | --- |
| **Configuratienaam** | Bijv. `Graph Mail aanmelden`. Gebruik een eigen configuratie voor Graph Mail, die andere plugins niet gebruiken. |
| **Authenticatie type** | *Token exchange* |
| **Token endpoint URL** | `https://login.microsoftonline.com/<Directory (tenant) ID>/oauth2/v2.0/token` |
| **Client ID** | De Application (client) ID |
| **Client secret** | Het secret van de beheerder. Wordt versleuteld opgeslagen; je kunt hem na opslaan niet meer uitlezen. |
| **Audience** | **Leeg laten.** |
| **Scope** | `https://graph.microsoft.com/.default` |

Loopt het verkeer naar Microsoft via een gateway van je organisatie, dan vul je hier vaak iets
anders in: zo'n gateway meldt zich soms zelf bij Microsoft aan. Vraag de beheerder van de gateway
welk **Authenticatie type** en welke gegevens je invult, en of er certificaten nodig zijn. Zie *Via
een gateway* hieronder.

## Stap 2 — Graph Mail-configuratie aanmaken

Ga naar **Admin → Plugins**, kies **Microsoft Graph Mail** en maak een nieuwe configuratie aan.

| Veld | Wat je invult |
| --- | --- |
| **Configuratienaam** | Vrije naam waaronder je deze configuratie terugvindt bij het koppelen aan een proces, bijv. `Graph Mail productie`. |
| **Toegestane afzenders** | Verplicht. De mailboxen waaruit deze configuratie mag versturen. Zie *Toegestane afzenders* hieronder. |
| **Http Client Authentication-configuratie** | Aanbevolen. De configuratie uit stap 1. Zie *Bestaande configuraties met client secret* hieronder als je die (nog) niet gebruikt. |
| **Graph API-URL** | Optioneel; verschijnt zodra je een Http Client Authentication-configuratie kiest. Meestal leeg laten. Alleen invullen als Graph Mail niet rechtstreeks met Microsoft praat, bijvoorbeeld via een gateway van je organisatie; zie *Via een gateway* hieronder. |
| **Afzender testmail (e-mailadres)** | Optioneel. Het afzenderadres dat standaard wordt voorgesteld bij de testmail hieronder. Heeft geen invloed op het versturen vanuit processen. |

Bij een aantal velden staat een ⓘ: ga erop staan voor een korte uitleg. Of de gekozen configuratie
zich bij Microsoft kan aanmelden, blijkt pas bij de testmail (stap 3).

Je kunt meerdere Graph Mail-configuraties naast elkaar aanmaken, bijvoorbeeld één per afdeling of
per tenant. Bij het koppelen aan een processtap kies je welke je gebruikt.

### Bestaande configuraties met client secret (verouderd)

Configuraties uit versie 1.0 melden zich aan met een eigen **Tenant ID**, **Client ID** en **Client
Secret**. Die blijven werken. Zolang er geen Http Client Authentication-configuratie gekozen is, toont
het scherm deze drie velden onder de keuzelijst, met de melding dat dit aanmelden verouderd is. Het
secret hoef je niet opnieuw in te vullen; laat je het leeg, dan blijft het opgeslagen secret staan.

Dit aanmelden verdwijnt in een volgende major versie. Overstappen doe je zo:

1. Maak een Http Client Authentication-configuratie aan (stap 1), met dezelfde Client ID en hetzelfde
   secret, en de Tenant ID in het token endpoint.
2. Kies die in de keuzelijst. De drie velden verdwijnen; sla op.
3. Verstuur een testmail (stap 3).

### Toegestane afzenders

De plugin verstuurt uitsluitend namens de mailboxen die je hier opgeeft. Staat een afzender er niet
tussen, dan wordt de verzending geweigerd voordat er contact met Microsoft is — de processtap stopt
met een foutmelding en er gaat geen mail uit. Dat geldt ook voor de testmail.

Je vult volledige adressen in (`noreply@gemeente.nl`), hele domeinen (`@gemeente.nl`), of een
combinatie daarvan, gescheiden door komma's. Hoofdletters maken niet uit. Een domein-entry dekt geen
subdomeinen: `@gemeente.nl` staat `post@gemeente.nl` toe, maar niet `post@mail.gemeente.nl`.

De machtiging die je beheerder in Azure heeft gegeven, geldt voor álle mailboxen van je organisatie.
Deze lijst zorgt dat Graph Mail alleen de mailboxen gebruikt die je hier noemt. Wil je dat ook aan
de kant van Microsoft vastleggen, vraag je Microsoft 365-beheerder dan om de app tot die mailboxen
te beperken (een *Application Access Policy*).

### Via een gateway

Sommige organisaties laten het verkeer naar Microsoft via een eigen gateway lopen. Wat je dan
invult, hangt af van die gateway; vraag het na bij de beheerder ervan. Twee dingen kunnen spelen,
los of samen.

**De gateway vraagt om een certificaat.** Vul in de Http Client Authentication-configuratie (stap 1)
de certificaten in die je van de gateway-beheerder krijgt:

- **Clientcertificaat** en de bijbehorende **private key**: hiermee maakt Valtimo zich bekend bij de
  gateway. Vraag om het certificaat inclusief eventuele tussenliggende certificaten.
- **Servercertificaat**: hiermee herkent Valtimo de gateway.

**De gateway heeft een eigen adres.** Vul dat in bij **Graph API-URL** in de Graph
Mail-configuratie (stap 2), bijvoorbeeld `https://gateway.gemeente.nl/graph`. Het adres begint met
`https://`. Laat je het veld leeg, dan gaat de mail zoals voorheen rechtstreeks naar Microsoft.

Controleer daarna met een testmail (stap 3) of het werkt. Verstuur vanuit een proces ook eens een
mail met een **bijlage van 3 MB of meer**: die gaat via een andere route, en niet elke gateway laat
die door. Mislukt zo'n mail terwijl kleine mails wel aankomen, neem dan contact op met de beheerder
van de gateway. Voor die beheerder staan de technische details in
[developer.md](developer.md#clientcertificaat-via-een-gateway-mtls).

## Stap 3 — Testmail versturen

Zodra een bruikbare Http Client Authentication-configuratie is gekozen (of, verouderd, Tenant ID,
Client ID en Client Secret zijn ingevuld), verschijnt op dezelfde
pagina het blok **Testmail versturen**. Hiermee controleer je in één klik of het aanmelden en de
machtigingen werken, nog voordat je een proces inricht.

1. **Sla de configuratie eerst op.** Zolang dat niet is gebeurd, meldt het scherm
   *"Sla de configuratie eerst op voordat je een testmail verstuurt."* en blijft de knop uit.
2. Vul **Afzender testmail (e-mailadres)** in — een bestaande mailbox in je tenant.
3. Vul **Ontvanger e-mailadres** in, bijvoorbeeld je eigen adres.
4. Klik **Verstuur testmail**.

De testmail bevat voorbeeldgegevens (een fictieve *Pietje van Patje*) in een opgemaakte tabel,
zodat je meteen ziet of HTML-opmaak goed aankomt.

Twee dingen om te weten:

- De testmail wordt **niet** opgeslagen in *Verzonden items*. Mail uit processen wél.
- Er geldt een limiet van **één testmail per 10 seconden per gebruiker**. Klik je sneller, dan
  volgt de melding *"Te veel verzoeken — wacht 10 seconden voor de volgende testmail"*.

Het versturen van een testmail is voorbehouden aan gebruikers met de beheerdersrol.

### Wat de meldingen betekenen

Bij een fout toont het scherm de melding plus de HTTP-status. De meldingen noemen de Http Client
Authentication-configuratie (*'…' (id)*) en zeggen wat je moet controleren. De belangrijkste:

| Melding | Wat er aan de hand is |
| --- | --- |
| *Testmail succesvol verzonden naar …* | Alles goed. Aanmelden en machtigingen werken. |
| *Verzenden mislukt (401)* | Het aanmelden of het token werd geweigerd: controleer in de genoemde configuratie het token endpoint, de Client ID, het secret (verlopen?) en de scope, en of `Mail.Send` met beheerdersconsent is toegekend. |
| *Verzenden mislukt (403) — … Access denied* | De machtiging ontbreekt, er is geen beheerdersconsent gegeven, of een Application Access Policy sluit deze mailbox uit. Terug naar je Azure-beheerder. |
| *Verzenden mislukt (429)* of *(503)* | Microsoft, het token endpoint of de gateway begrenst tijdelijk of is niet bereikbaar. Even wachten en opnieuw proberen. |
| *Aanmelden niet mogelijk — …* | De gekozen Http Client Authentication-configuratie bestaat niet meer of kan zich niet bij Microsoft aanmelden (zie stap 2). |
| *Ongeldig afzender e-mailadres* | Het ingevulde afzenderadres heeft geen geldig e-mailformaat. |
| *Plugin configuratie niet gevonden* | De configuratie is verwijderd of nog niet opgeslagen. Opnieuw opslaan. |
| *Verbindingsfout* | Valtimo kon de eigen server niet bereiken; geen Microsoft-probleem. |
| *Certificaten — …* | Een probleem met de certificaten of de gateway: geweigerd, niet vertrouwd, verlopen of niet te lezen. Controleer client- en servercertificaat in de genoemde configuratie; de melding noemt de reden. |

Een 403 bij grote bijlagen wijst bijna altijd op een ontbrekende `Mail.ReadWrite` — niet op een
verkeerd secret.

## Stap 4 — De actie aan een processtap koppelen

De plugin biedt één actie: **Verstuur e-mail**. Die koppel je aan een **Service Task** in een
procesmodel. Ga naar **Admin → Processen**, kies het proces, selecteer de service-taak en maak
een proceskoppeling aan: kies plugin **Microsoft Graph Mail**, je configuratie, en de actie
**Verstuur e-mail**.

Vervolgens vul je per stap in:

| Veld | Verplicht | Wat je invult |
| --- | --- | --- |
| **Afzender e-mailadres (UPN)** | Ja | De verzendende mailbox, bijv. `noreply@gemeente.nl`. |
| **Ontvangers (verplicht)** | Ja | Eén adres, of meerdere gescheiden door komma's. |
| **CC-adressen (optioneel, komma-gescheiden)** | Nee | Meelezers, zichtbaar voor alle ontvangers. |
| **BCC-adressen (optioneel, komma-gescheiden)** | Nee | Meelezers, onzichtbaar voor de anderen. |
| **Reply-To adressen (optioneel)** | Nee | Waar antwoorden naartoe gaan als dat niet de afzender is. Deze adressen ontvangen de mail zelf niet. |
| **Onderwerp** | Ja | Maximaal 255 tekens. |
| **Mailinhoud (bestand-ID)** | Ja | Verwijzing naar het HTML-bestand met de mailtekst — zie hieronder. |
| **Bijlagen (bestand-IDs, optioneel)** | Nee | Verwijzingen naar bij te voegen bestanden, komma-gescheiden. |

### Waarden uit het proces halen

Vaste waarden intypen werkt, maar meestal wil je gegevens uit het lopende dossier gebruiken —
het e-mailadres van de aanvrager, een zaaknummer in het onderwerp. Zet daarvoor `pv:` vóór de
naam van de procesvariabele:

- `pv:aanvragerEmail` — gebruikt de waarde van de procesvariabele `aanvragerEmail`
- `noreply@gemeente.nl` — gebruikt precies dat adres

Dit werkt in elk veld. De schermen tonen `pv:…` ook als voorbeeld in het invoerveld.

### Mailinhoud: waarom een bestand-ID?

Je typt de mailtekst **niet** in dit scherm. Het veld **Mailinhoud (bestand-ID)** verwijst naar
een HTML-bestand dat eerder in het proces is klaargezet. In de praktijk laat je een eerdere
stap dat bestand genereren — bijvoorbeeld met de FreeMarker-documentplugin, die een template
vult met dossiergegevens — en geef je hier `pv:contentId` op, de variabele waarin die stap het
bestand-ID heeft opgeslagen.

Dat betekent: **de plugin heeft altijd een voorafgaande stap nodig die de mailtekst oplevert.**
Alleen deze plugin inrichten is niet genoeg om een mail te kunnen versturen.

Bijlagen werken hetzelfde: het veld verwacht bestand-ID's van bestanden die al in het proces
zijn klaargezet, niet een keuze uit de documenten van het dossier.

### Opmaak in de mailtekst

De HTML wordt vóór verzending automatisch opgeschoond, zodat een template geen onveilige of
privacygevoelige inhoud kan meesturen.

**Blijft behouden:** koppen, vet en cursief, lijsten, tabellen, links, afbeeldingen met een
`https`-adres of als bijlage ingesloten, en opmaak via `style`-attributen op elementen.

**Wordt verwijderd:** scripts, ingesloten webpagina's, `<style>`-blokken bovenaan het document,
afbeeldingen die in de HTML-tekst zelf zijn opgenomen, en afbeeldingen met een `http`-adres (zonder
de **s**).

Drie gevolgen voor wie templates maakt:

- Zet opmaak **per element** (`style="…"`), niet in een `<style>`-blok — dat laatste sneuvelt en
  je mail komt onopgemaakt aan.
- **Een logo blijft gewoon werken**, zolang het op `https://` staat of als bijlage is ingesloten.
  Staat het logo in een bestaand sjabloon nog op `http://`, dan komt de mail aan zonder die
  afbeelding; zet dat adres om naar `https://`. Reden: een afbeelding wordt opgehaald op het moment
  dat de ontvanger de mail opent, en bij `http` gebeurt dat over een onbeveiligde verbinding.
- Blijft er na het opschonen niets over, dan slaagt de verzending niet en stopt de processtap
  met een foutmelding. Controleer dan het gegenereerde HTML-bestand.

## Wat kan wel, en wat niet

| | |
| --- | --- |
| ✅ Versturen namens elke mailbox in de tenant | ❌ Weergavenaam van de afzender instellen — die komt uit Microsoft 365 |
| ✅ To, CC, BCC en Reply-To | ❌ Platte-tekstversie naast de HTML |
| ✅ Opgemaakte HTML met tabellen en afbeeldingen | ❌ Mailtekst rechtstreeks in het scherm intypen |
| ✅ Tot 5 bijlagen, samen tot 25 MB | ❌ Bijlagen kiezen uit de documenten van het dossier |
| ✅ Grote bijlagen (automatisch, zie onder) | ❌ Verzending inplannen op een later moment |
| ✅ Meerdere configuraties naast elkaar | ❌ Ontvangstbevestiging of leesbevestiging |
| ✅ Testmail vanuit het beheerscherm | ❌ Volgen of de mail is bezorgd |

**Weergavenaam afzender:** de naam die de ontvanger ziet, is de *Display Name* van de mailbox in
Microsoft 365. Wil je dat de mail van "Gemeente — Vergunningen" komt, laat je beheerder dan de
naam van de mailbox aanpassen in het Microsoft 365 Admin Center.

**Grote bijlagen:** je hoeft hier niets voor in te stellen. Passen de mailtekst en alle bijlagen
samen in één bericht — ruwweg tot 3 MB aan bijlagen — dan gaan ze in één keer mee. Daarboven maakt
de plugin eerst een concept aan en hangt elke bijlage daar afzonderlijk aan, waarna het concept
wordt verzonden. Dan is wel `Mail.ReadWrite` vereist, en het duurt merkbaar langer.

**Bezorging:** de plugin krijgt van Microsoft alleen terug dat de mail is *aangenomen*, niet dat
hij is *bezorgd*. Een mail die daarna alsnog bounct, ziet Valtimo niet. Controleer bezorging in
het Microsoft 365 Admin Center.

## Grenzen

Wordt een grens overschreden, dan stopt de processtap met een foutmelding en gaat er geen mail
uit.

| Grens | Waarde |
| --- | --- |
| Ontvangers per veld (To, CC of BCC apart) | 100 |
| Ontvangers in totaal (To + CC + BCC samen) | 200 |
| Lengte onderwerp | 255 tekens |
| Grootte mailtekst | 5 MB |
| Aantal bijlagen | 5 |
| Grootte per bijlage | 25 MB |
| Alle bijlagen samen | 25 MB |
| Testmails per gebruiker | 1 per 10 seconden |

Reply-To-adressen tellen niet mee in het ontvangerstotaal — die krijgen de mail immers niet.

> **Let op:** een mailtekst van meer dan ongeveer 4 MB wordt door Microsoft geweigerd, ook al
> staat de grens van de plugin op 5 MB. Houd mailteksten ruim onder die grens; gebruik voor
> omvangrijke inhoud een bijlage.

## Goed om te weten

**Dubbele mail wordt meestal tegengehouden.** Wordt een processtap na een storing opnieuw
uitgevoerd terwijl de mail al verstuurd was, dan verstuurt de plugin hem niet nog een keer. Je hoeft
daar niets voor in te richten. Waterdicht is het niet, bijvoorbeeld als de applicatie tussendoor is
herstart. Kan een dubbele mail in jouw proces echt niet, overleg dan met je applicatiebeheerder.

**Versturen kan even duren.** Is Microsoft tijdelijk druk, dan probeert Valtimo de mailstap later
opnieuw. Vraag je applicatiebeheerder om voor de mailstap een herhaalschema in te stellen, zodat
Valtimo het niet te snel opgeeft.

**Log van verzendingen.** Elke verzending wordt vastgelegd, met e-mailadressen gedeeltelijk
afgeschermd (`p***@patje.nl`) om onnodige verwerking van persoonsgegevens te voorkomen. Vraag je
beheerder naar dit log als je wilt nagaan of een mail is aangeboden.
