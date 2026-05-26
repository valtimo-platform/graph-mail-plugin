import {PluginConfigurationData} from '@valtimo/plugin';

interface GraphMailPluginConfig extends PluginConfigurationData {
  tenantId: string;
  clientId: string;
  clientSecret: string | undefined;
  testSenderMailbox?: string;
}

export {GraphMailPluginConfig};
