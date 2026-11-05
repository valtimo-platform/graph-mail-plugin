import {PluginConfigurationData} from '@valtimo/plugin';

interface GraphMailPluginConfig extends PluginConfigurationData {
  // Comma-separated sender allowlist: full addresses and/or '@domain' entries.
  // Required — the backend refuses every send when this is empty (deny-by-default).
  allowedSenders: string;
  testSenderMailbox?: string;
  // Id of the Http Client Authentication configuration (any authentication type) whose headers and certificates
  // Graph Mail sends to Microsoft, or to a gateway in between.
  authenticationPluginConfiguration?: string;
  // The Graph API URL, e.g. of a gateway with a URL of its own; used with authenticationPluginConfiguration. Empty: the
  // deployment's graph-mail.http.graph-base-url (Microsoft).
  baseUrl?: string;
  // The deprecated client-secret login of 1.x, used while authenticationPluginConfiguration is empty.
  tenantId?: string;
  clientId?: string;
  clientSecret?: string;
}

export {GraphMailPluginConfig};
