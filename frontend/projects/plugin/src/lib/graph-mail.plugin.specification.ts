import {PluginSpecification} from '@valtimo/plugin';
import {GraphMailPluginConfigurationComponent} from './components/graph-mail-configuration/graph-mail-configuration.component';
import {SendEmailConfigurationComponent} from './components/send-email-configuration/send-email-configuration.component';
import {GRAPH_MAIL_PLUGIN_LOGO_BASE64} from './assets/graph-mail-plugin-logo';

const graphMailPluginSpecification: PluginSpecification = {
  pluginId: 'entra',
  pluginConfigurationComponent: GraphMailPluginConfigurationComponent,
  pluginLogoBase64: GRAPH_MAIL_PLUGIN_LOGO_BASE64,
  functionConfigurationComponents: {
    'send-email': SendEmailConfigurationComponent,
  },
  pluginTranslations: {
    nl: {
      title: 'Microsoft Graph Mail',
      description: 'Verstuur e-mails via Microsoft Graph API, aangemeld via een Http Client Authentication-configuratie',
      configurationTitle: 'Configuratienaam',
      'send-email': 'Verstuur e-mail',
      allowedSenders: 'Toegestane afzenders (whitelist, verplicht)',
      allowedSendersTooltip: 'Komma-gescheiden lijst van toegestane afzendermailboxen, bijv. noreply@gemeente.nl, zaken@gemeente.nl. Een heel domein toestaan kan met @gemeente.nl. E-mails met een afzender buiten deze lijst worden geweigerd.',
      allowedSendersInvalidFormat: 'Elke entry moet een geldig e-mailadres zijn (noreply@gemeente.nl) of een domein beginnend met @ (@gemeente.nl), gescheiden door komma’s.',
      testSenderMailbox: 'Afzender testmail (e-mailadres)',
      testMailTitle: 'Testmail versturen',
      testMailDescription: 'Verstuur een testmail met voorbeelddata (Pietje van Patje) om te valideren of het aanmelden werkt en e-mail kan worden verstuurd via Microsoft Graph.',
      testMailSaveFirst: 'Sla de configuratie eerst op voordat je een testmail verstuurt.',
      testMailConfigAmbiguous: 'Kan deze configuratie niet eenduidig herleiden — er bestaan meerdere Graph Mail-configuraties met dezelfde titel. Geef de configuratie een unieke naam en sla opnieuw op voordat je een testmail verstuurt.',
      invalidEmailAddress: 'Ongeldig e-mailadres',
      testRecipientLabel: 'Ontvanger e-mailadres',
      sending: 'Versturen…',
      sendTestMail: 'Verstuur testmail',
      connectionError: 'Verbindingsfout:',
      // Process link fields
      senderMailbox: 'Afzender e-mailadres (UPN)',
      recipients: 'Ontvangers (verplicht)',
      cc: 'CC-adressen (optioneel, komma-gescheiden)',
      bcc: 'BCC-adressen (optioneel, komma-gescheiden)',
      replyTo: 'Reply-To adressen (optioneel)',
      subject: 'Onderwerp',
      contentId: 'Mailinhoud (bestand-ID)',
      contentIdTooltip: 'UUID van het tijdelijk opgeslagen HTML-bestand. Gebruik pv:contentId om het ID uit een procesvariabele op te halen. Compatibel met de FreeMarker-documentplugin.',
      attachmentIds: 'Bijlagen (bestand-IDs, optioneel)',
      attachmentIdsTooltip: 'Komma-gescheiden lijst van UUID\'s van tijdelijk opgeslagen bijlagen. Gebruik pv:attachmentIds om de lijst uit een procesvariabele op te halen.',
      senderMailboxPlaceholder: 'bijv. noreply@gemeente.nl',
      testRecipientPlaceholder: 'bijv. beheerder@gemeente.nl',
      connectionErrorFallback: 'Verbindingsfout',
      authenticationPluginConfiguration: 'Http Client Authentication-configuratie (aanbevolen)',
      authenticationPluginConfigurationTooltip:
        'De configuratie waarmee Graph Mail zich bij Microsoft aanmeldt: type Token exchange, met het token endpoint ' +
        '(https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token), client ID, client secret en scope ' +
        'https://graph.microsoft.com/.default, zonder audience. Haar certificaten (mTLS) gelden ook voor Graph.',
      authenticationPluginConfigurationSelect: 'Kies…',
      authenticationPluginConfigurationNone:
        'Er zijn geen Http Client Authentication-configuraties. Maak er eerst een aan (type Token exchange).',
      authenticationPluginConfigurationMissing:
        'De gekozen configuratie bestaat niet meer of is niet beschikbaar. Kies een andere om op te kunnen slaan.',
      authenticationPluginConfigurationUnknown: 'Onbekende configuratie',
      loginNotTokenExchange: 'kies authenticatietype Token exchange om bij Microsoft aan te melden.',
      loginHasAudience: 'laat Audience leeg: Microsoft Entra ID doet alleen client credentials, geen token exchange.',
      loginWithoutScope: 'vul Scope in, bijvoorbeeld https://graph.microsoft.com/.default.',
      clientSecretLogin: 'Aanmelden met client secret (verouderd)',
      clientSecretLoginDeprecated:
        'Zonder Http Client Authentication-configuratie meldt Graph Mail zich aan met de Tenant ID, Client ID en ' +
        'Client Secret hieronder, zoals in versie 1.0. Dat blijft werken, maar verdwijnt in een volgende major versie: ' +
        'kies hierboven een Http Client Authentication-configuratie om over te stappen.',
      tenantId: 'Tenant ID',
      clientId: 'Client ID',
      clientSecret: 'Client Secret',
      tenantIdInvalidFormat:
        'Tenant ID moet een directory-GUID, een geverifieerd domein (contoso.onmicrosoft.com) of ' +
        '\'organizations\' zijn, zonder spaties of / ? # \\ @.',
      clientSecretPlaceholder: '(ongewijzigd laten als leeg)',
    },
    en: {
      title: 'Microsoft Graph Mail',
      description: 'Send emails via Microsoft Graph API, logged in through an Http Client Authentication configuration',
      configurationTitle: 'Configuration name',
      'send-email': 'Send email',
      allowedSenders: 'Allowed senders (whitelist, required)',
      allowedSendersTooltip: 'Comma-separated list of permitted sender mailboxes, e.g. noreply@organisation.gov, cases@organisation.gov. Allow an entire domain with @organisation.gov. Emails with a sender outside this list are rejected.',
      allowedSendersInvalidFormat: 'Each entry must be a valid email address (noreply@organisation.gov) or a domain starting with @ (@organisation.gov), separated by commas.',
      testSenderMailbox: 'Test sender mailbox (email address)',
      testMailTitle: 'Send test email',
      testMailDescription: 'Send a test email with sample data (John Doe) to validate that the login works and email can be sent via Microsoft Graph.',
      testMailSaveFirst: 'Save the configuration first before sending a test email.',
      testMailConfigAmbiguous: 'Cannot uniquely resolve this configuration — multiple Graph Mail configurations share the same title. Give this configuration a unique name and save again before sending a test email.',
      invalidEmailAddress: 'Invalid email address',
      testRecipientLabel: 'Recipient email address',
      sending: 'Sending…',
      sendTestMail: 'Send test email',
      connectionError: 'Connection error:',
      // Process link fields
      senderMailbox: 'Sender email address (UPN)',
      recipients: 'Recipients (required)',
      cc: 'CC addresses (optional, comma-separated)',
      bcc: 'BCC addresses (optional, comma-separated)',
      replyTo: 'Reply-To addresses (optional)',
      subject: 'Subject',
      contentId: 'Mail body (file ID)',
      contentIdTooltip: 'UUID of the temporarily stored HTML file. Use pv:contentId to resolve the ID from a process variable. Compatible with the FreeMarker document plugin.',
      attachmentIds: 'Attachments (file IDs, optional)',
      attachmentIdsTooltip: 'Comma-separated list of UUIDs of temporarily stored attachments. Use pv:attachmentIds to resolve the list from a process variable.',
      senderMailboxPlaceholder: 'e.g. noreply@organisation.gov',
      testRecipientPlaceholder: 'e.g. admin@organisation.gov',
      connectionErrorFallback: 'Connection error',
      authenticationPluginConfiguration: 'Http Client Authentication configuration (recommended)',
      authenticationPluginConfigurationTooltip:
        'The configuration Graph Mail logs in to Microsoft with: type Token exchange, with the token endpoint ' +
        '(https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token), client ID, client secret and scope ' +
        'https://graph.microsoft.com/.default, without audience. Its certificates (mTLS) apply to Graph too.',
      authenticationPluginConfigurationSelect: 'Select…',
      authenticationPluginConfigurationNone:
        'There are no Http Client Authentication configurations. Create one first (type Token exchange).',
      authenticationPluginConfigurationMissing:
        'The selected configuration no longer exists or is not available. Select another one to save.',
      authenticationPluginConfigurationUnknown: 'Unknown configuration',
      loginNotTokenExchange: 'select authentication type Token exchange to log in to Microsoft.',
      loginHasAudience: 'leave Audience empty: Microsoft Entra ID only does client credentials, not a token exchange.',
      loginWithoutScope: 'fill in Scope, e.g. https://graph.microsoft.com/.default.',
      clientSecretLogin: 'Log in with client secret (deprecated)',
      clientSecretLoginDeprecated:
        'Without an Http Client Authentication configuration, Graph Mail logs in with the Tenant ID, Client ID and ' +
        'Client Secret below, as in version 1.0. That keeps working, but will be removed in a future major version: ' +
        'select an Http Client Authentication configuration above to migrate.',
      tenantId: 'Tenant ID',
      clientId: 'Client ID',
      clientSecret: 'Client Secret',
      tenantIdInvalidFormat:
        'Tenant ID must be a directory GUID, a verified domain (contoso.onmicrosoft.com) or ' +
        '\'organizations\', without spaces or / ? # \\ @.',
      clientSecretPlaceholder: '(leave blank to keep unchanged)',
    },
  },
};

export {graphMailPluginSpecification};
