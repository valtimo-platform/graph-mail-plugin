import {PluginConfigurationData} from '@valtimo/plugin';

interface GraphMailPluginConfig extends PluginConfigurationData {
  tenantId: string;
  clientId: string;
  clientSecret: string | undefined;
  // Comma-separated sender allowlist: full addresses and/or '@domain' entries.
  // Required — the backend refuses every send when this is empty (deny-by-default).
  allowedSenders: string;
  testSenderMailbox?: string;
}

export {GraphMailPluginConfig};
