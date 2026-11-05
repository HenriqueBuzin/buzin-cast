import type {KeplerTurboModule} from '@amazon-devices/keplerscript-turbomodule-api';
import {TurboModuleRegistry} from '@amazon-devices/keplerscript-turbomodule-api';

export interface BuzinNetwork extends KeplerTurboModule {
  getReport: () => string;
  recordPlaybackState: (state: string, seconds: number) => void;
  recordMediaCapabilities: (h264: number, hevc: number, aac: number) => string;
}

export default TurboModuleRegistry.getEnforcing<BuzinNetwork>('BuzinNetwork');
