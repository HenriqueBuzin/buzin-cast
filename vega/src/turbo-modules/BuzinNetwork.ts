import type {KeplerTurboModule} from '@amazon-devices/keplerscript-turbomodule-api';
import {TurboModuleRegistry} from '@amazon-devices/keplerscript-turbomodule-api';

export interface BuzinNetwork extends KeplerTurboModule {
  getReport: () => string;
  getReceiverName: () => string;
  setReceiverName: (name: string) => void;
  startAirPlayUrl: () => number;
  stopAirPlayUrl: () => void;
  pollAirPlayCommand: () => string;
  updateAirPlayState: (position: number, duration: number, rate: number, ready: boolean) => void;
  startFCast: () => number;
  stopFCast: () => void;
  pollFCastCommand: () => string;
  isFCastClient: (client: number) => boolean;
  disconnectFCastClient: (client: number) => void;
  fcastError: (client: number, message: string) => void;
  updateFCastState: (playback: string, volume: string) => void;
  recordPlaybackState: (state: string, seconds: number) => void;
  recordMediaCapabilities: (h264: number, hevc: number, aac: number) => string;
}

export default TurboModuleRegistry.getEnforcing<BuzinNetwork>('BuzinNetwork');
