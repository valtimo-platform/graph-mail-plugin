import {NgModule} from '@angular/core';
import {GraphMailPluginConfigurationComponent} from './components/graph-mail-configuration/graph-mail-configuration.component';
import {SendEmailConfigurationComponent} from './components/send-email-configuration/send-email-configuration.component';

@NgModule({
  imports: [
    GraphMailPluginConfigurationComponent,
    SendEmailConfigurationComponent,
  ],
  exports: [
    GraphMailPluginConfigurationComponent,
    SendEmailConfigurationComponent,
  ],
})
export class GraphMailPluginModule {}
