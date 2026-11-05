import type {KeplerTurboModule} from '@amazon-devices/keplerscript-turbomodule-api';
import {TurboModuleRegistry} from '@amazon-devices/keplerscript-turbomodule-api';

export interface BuzinNetwork extends KeplerTurboModule {
  getReport: () => string;
}

export default TurboModuleRegistry.getEnforcing<BuzinNetwork>('BuzinNetwork');
