/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- Type for the native player test double. */
/* global globalThis */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {createAirPlayReceiver} from '../src/airplayReceiver';
import {createPlaybackSession} from '../src/playbackSession';
import Network from '../src/turbo-modules/BuzinNetwork';
const {URL: StandardURL} = jest.requireActual<{URL: typeof URL}>('url');
jest.mock('../src/playbackSession', () => ({createPlaybackSession: jest.fn()}));
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({__esModule: true, default: {
  startAirPlayUrl: jest.fn(() => 7001), stopAirPlayUrl: jest.fn(),
  pollAirPlayCommand: jest.fn(), updateAirPlayState: jest.fn(),
}}));
const originalURL = globalThis.URL;
beforeEach(() => {jest.useFakeTimers(); globalThis.URL = StandardURL as unknown as typeof URL;});
afterEach(() => {jest.useRealTimers(); jest.clearAllMocks(); globalThis.URL = originalURL;});

function setup() {
  const packets: string[] = [];
  const player = {duration: 8, playbackRate: 1, currentTime: 0, pause: jest.fn(), play: jest.fn(() => Promise.resolve())};
  const media = {load: jest.fn(() => Promise.resolve()), attachSurface: jest.fn(), detachSurface: jest.fn(), togglePause: jest.fn(), stop: jest.fn(() => Promise.resolve())};
  let ready!: Parameters<typeof createPlaybackSession>[0];
  let change!: Parameters<typeof createPlaybackSession>[1];
  jest.mocked(createPlaybackSession).mockImplementation((onReady, onChange) => {ready = onReady; change = onChange; return media;});
  jest.mocked(Network.pollAirPlayCommand).mockImplementation(() => packets.shift() || '');
  const failed = jest.fn();
  const active = jest.fn();
  const receiver = createAirPlayReceiver(active, jest.fn(), failed);
  expect(Network.startAirPlayUrl).not.toHaveBeenCalled();
  ready(player as unknown as VideoPlayer);
  const send = (kind: string, value = 0, url = '') => {packets.push(JSON.stringify({kind, value, url})); jest.advanceTimersByTime(50);};
  return {receiver, send, change, failed, active, player, media};
}

test('encaminha URL e controles ao player e devolve observação real', () => {
  const {receiver, send, change, player, media} = setup();
  send('play', 1.5, 'https://example.com/clip.mp4');
  expect(media.load).toHaveBeenCalledWith({url: 'https://example.com/clip.mp4', headers: {}, position: 1.5, speed: 1});
  receiver.attachSurface(1, 'current'); receiver.attachSurface(0, 'old');
  expect(media.attachSurface).toHaveBeenCalledTimes(1);
  change({state: 'playing', seconds: 2});
  expect(Network.updateAirPlayState).toHaveBeenLastCalledWith(2, 8, 1, true);
  send('rate', 0); expect(player.pause).toHaveBeenCalledTimes(1);
  send('seek', 3); expect(player.currentTime).toBe(3);
  send('rate', 1); expect(player.play).toHaveBeenCalledTimes(1);
  send('stop');
  expect(Network.updateAirPlayState).toHaveBeenLastCalledWith(0, 0, 0, false);
  receiver.stop(); receiver.stop();
  expect(Network.stopAirPlayUrl).toHaveBeenCalledTimes(1);
  expect(media.stop).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});

test('URL inválida encerra o receptor sem entregar arquivos locais ao player', () => {
  const {receiver, send, failed, media} = setup();
  send('play', 0, 'file:///data/private.mp4');
  expect(media.load).not.toHaveBeenCalled();
  expect(failed).toHaveBeenCalledTimes(1);
  expect(Network.stopAirPlayUrl).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
  receiver.stop();
});
