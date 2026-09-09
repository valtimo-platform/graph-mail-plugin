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
herinnering — als het secret verloopt, stopt het versturen van mail met een authenticatiefout.

## Stap 1 — Pluginconfiguratie aanmaken

Ga naar **Admin → Plugins**, kies **Microsoft Graph Mail** en maak een nieuwe configuratie aan.

| Veld | Wat je invult |
| --- | --- |
| **Configuratienaam** | Vrije naam waaronder je deze configuratie terugvindt bij het koppelen aan een proces, bijv. `Graph Mail productie`. |
| **Tenant ID** | De Directory (tenant) ID van je beheerder. Moet een UUID zijn — het scherm geeft direct een melding als het formaat niet klopt. |
| **Client ID** | De Application (client) ID. Ook een UUID, met dezelfde controle. |
| **Client Secret** | Het secret van de beheerder. Wordt gemaskeerd weergegeven en versleuteld opgeslagen; je kunt hem na opslaan niet meer uitlezen. |
| **Toegestane afzenders** | Verplicht. De mailboxen waaruit deze configuratie mag versturen. Zie *Toegestane afzenders* hieronder. |
| **Afzender testmail (e-mailadres)** | Optioneel. Het afzenderadres dat standaard wordt voorgesteld bij de testmail hieronder. Heeft geen invloed op het versturen vanuit processen. |

Je kunt meerdere configuraties naast elkaar aanmaken, bijvoorbeeld één per afdeling of per
tenant. Bij het koppelen aan een processtap kies je welke je gebruikt.

**Een bestaande configuratie wijzigen:** laat *Client Secret* leeg om het huidige secret te
behouden. Het veld toont dan de hint *(ongewijzigd laten als leeg)*. Vul je het wél in, dan
wordt het secret overschreven.

Op één punt geldt dat niet: **wijzig je de lijst met toegestane afzenders, dan moet je het Client
Secret opnieuw invullen.** Zie hieronder waarom.

### Toegestane afzenders

De plugin verstuurt uitsluitend namens de mailboxen die je hier opgeeft. Staat een afzender er niet
tussen, dan wordt de verzending geweigerd voordat er contact met Microsoft is — de processtap stopt
met een foutmelding en er gaat geen mail uit. Dat geldt ook voor de testmail.

Je vult volledige adressen in (`noreply@gemeente.nl`), hele domeinen (`@gemeente.nl`), of een
combinatie daarvan, gescheiden door komma's. Hoofdletters maken niet uit. Een domein-entry dekt geen
subdomeinen: `@gemeente.nl` staat `post@gemeente.nl` toe, maar niet `post@mail.gemeente.nl`.

> **Bij het bijwerken van een bestaande installatie:** configuraties van vóór deze versie hebben nog
> geen lijst. Die versturen **niets** totdat je de lijst eenmalig invult en opslaat. Doe dat vóór of
> direct na de upgrade.

**Waarom het Client Secret erbij moet.** De machtiging die je beheerder in Azure heeft gegeven geldt
voor de hele tenant: technisch kan de plugin namens elke mailbox mailen. Deze lijst is de grens die
dat inperkt. Een adres toevoegen betekent dus dat er vanaf dat moment namens die mailbox gemaild kan
worden — en dat hoort niet te kunnen met alleen toegang tot dit scherm. Wie de lijst verruimt, moet
ook het secret bezitten.

Laat je de lijst ongemoeid, dan verandert er niets en mag het secretveld leeg blijven. De volgorde
of de spatiëring aanpassen telt niet als wijziging; een adres verwijderen wél.

## Stap 2 — Testmail versturen

Zodra Tenant ID en Client ID geldig zijn ingevuld — en bij een nieuwe configuratie ook het
Client Secret — verschijnt op dezelfde pagina het blok **Testmail versturen**. Hiermee
controleer je in één klik of de gegevens van je beheerder werken, nog voordat je een proces
inricht.

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

Bij een fout toont het scherm de melding plus de HTTP-status. De belangrijkste:

| Melding | Wat er aan de hand is |
| --- | --- |
| *Testmail succesvol verzonden naar …* | Alles goed. Credentials en machtigingen werken. |
| *Ongeldige aanvraag (400) — controleer Tenant ID en Client ID* | Een van beide ID's bestaat niet in Azure. |
| *Authenticatie mislukt (401) — controleer Tenant ID, Client ID en Client Secret* | Meestal een verlopen of verkeerd overgenomen client secret. |
| *Toegang geweigerd (403) — controleer of Mail.Send is toegekend in de Azure App Registration* | De machtiging ontbreekt, of er is geen beheerdersconsent gegeven. Terug naar je Azure-beheerder. |
| *Te veel verzoeken (429)* | Microsoft begrenst tijdelijk. Even wachten en opnieuw proberen. |
| *Azure / Graph API tijdelijk niet beschikbaar* | Storing aan de Microsoft-kant. Later opnieuw proberen. |
| *Ongeldig afzender e-mailadres* | Het ingevulde afzenderadres heeft geen geldig e-mailformaat. |
| *Plugin configuratie niet gevonden* | De configuratie is verwijderd of nog niet opgeslagen. Opnieuw opslaan. |
| *Verbindingsfout* | Valtimo kon de eigen server niet bereiken; geen Azure-probleem. |

Een 403 die pas ná een geslaagde 401-controle optreedt, wijst bijna altijd op een ontbrekende
`Mail.ReadWrite` bij grote bijlagen — niet op een verkeerd secret.

## Stap 3 — De actie aan een processtap koppelen

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

**Wordt verwijderd:** scripts, iframes, losse `<style>`-blokken bovenaan het document,
afbeeldingen die als `data:`-URI zijn ingebed, klik-acties op elementen, en afbeeldingen met een
`http`-adres (zonder de **s**).

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

**Dubbele mail wordt tegengehouden, maar niet onder alle omstandigheden.** Wordt de processtap door
een technische storing opnieuw uitgevoerd nadat de mail al was aangenomen, dan herkent de plugin dat
en verstuurt hij niet nog een keer. Je hoeft daar in het proces niets voor in te richten.

Die bescherming leeft in het geheugen van de server en houdt een verzending een half uur vast. Ze
werkt daarom níet wanneer de applicatie op meerdere servers draait, wanneer de herhaling pas na een
half uur komt, of wanneer de applicatie tussendoor is herstart. Gaat het om processen waar een
dubbele mail echt niet kan, stem dat dan af met je beheerder — en laat de ontvangende partij
daarnaast op dubbele berichten controleren, bijvoorbeeld via een kenmerk in het onderwerp.

**Versturen kan even duren.** Een verzending duurt maximaal 30 seconden, of 2 minuten bij grote
bijlagen. Begrenst Microsoft het verkeer tijdelijk, dan wacht de plugin hooguit twee seconden en
geeft de verzending daarna terug aan Valtimo, die het later opnieuw inplant. Zo houdt één trage
mailserver de rest van de procesverwerking niet op. Vraag je beheerder wel om op de mailstap een
herhaalschema in te stellen; zonder dat valt Valtimo terug op zijn standaard, die voor deze situatie
meestal te kort is.

**Log van verzendingen.** Elke verzending wordt vastgelegd, met e-mailadressen gedeeltelijk
afgeschermd (`p***@patje.nl`) om onnodige verwerking van persoonsgegevens te voorkomen. Vraag je
beheerder naar dit log als je wilt nagaan of een mail is aangeboden.
