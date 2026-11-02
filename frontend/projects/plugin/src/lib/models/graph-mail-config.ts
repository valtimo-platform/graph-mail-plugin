import {PluginConfigurationData} from '@valtimo/plugin';

interface GraphMailPluginConfig extends PluginConfigurationData {
  // Comma-separated sender allowlist: full addresses and/or '@domain' entries.
  // Required — the backend refuses every send when this is empty (deny-by-default).
  allowedSenders: string;
  testSenderMailbox?: string;
  // Id of the Http Client Authentication configuration Graph Mail logs in to Microsoft with (type Token exchange,
  // without audience), and whose certificates it uses.
  authenticationPluginConfiguration?: string;
  // The deprecated client-secret login of 1.x, used while authenticationPluginConfiguration is empty.
  tenantId?: string;
  clientId?: string;
  clientSecret?: string;
}

export {GraphMailPluginConfig};
