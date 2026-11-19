import type {KeplerTurboModule} from '@amazon-devices/keplerscript-turbomodule-api';
import {TurboModuleRegistry} from '@amazon-devices/keplerscript-turbomodule-api';

export interface BuzinNetwork extends KeplerTurboModule {
  getReport: () => string;
  getReceiverName: () => string;
  setReceiverName: (name: string) => void;
  startAirPlayUrl: () => number;
  startAirPlayAudio: () => number;
  startAirPlayVideo: () => number;
  getAirPlayVideoState: () => string;
  stopAirPlayAudio: () => void;
  getAirPlayAudioState: () => string;
  stopAirPlayUrl: () => void;
  pollAirPlayCommand: () => string;
  pollAirPlayApproval: () => string;
  answerAirPlayApproval: (requestId: number, allow: boolean) => void;
  updateAirPlayState: (position: number, duration: number, rate: number, ready: boolean) => void;
  startFCast: () => number;
  stopFCast: () => void;
  pollFCastCommand: () => string;
  answerFCastApproval: (requestId: number, allow: boolean) => void;
  isFCastClient: (client: number) => boolean;
  disconnectFCastClient: (client: number) => void;
  fcastError: (client: number, message: string) => void;
  updateFCastState: (playback: string, volume: string) => void;
  startDlna: () => string;
  getConnectionApproval: () => boolean;
  setConnectionApproval: (enabled: boolean) => void;
  answerDlnaApproval: (requestId: number, allow: boolean) => void;
  stopDlna: () => void;
  pollDlnaRequest: () => string;
  respondDlna: (requestId: number, status: number, body: string) => void;
  publishDlnaEvents: (transport: string, rendering: string, connection: string) => void;
  recordVideoPlaybackQuality: (total: number, dropped: number, attached: boolean) => void;
  recordPlaybackState: (state: string, seconds: number) => void;
  recordMediaCapabilities: (h264: number, hevc: number, aac: number) => string;
}

export default TurboModuleRegistry.getEnforcing<BuzinNetwork>('BuzinNetwork');
