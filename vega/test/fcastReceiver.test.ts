/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- Type only for the native player test double. */
/* global globalThis */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {createFCastReceiver} from '../src/fcastReceiver';
import {createPlaybackSession} from '../src/playbackSession';
import Network from '../src/turbo-modules/BuzinNetwork';
const {URL: StandardURL} = jest.requireActual<{URL: typeof URL}>('url');
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {
    startFCast: jest.fn(() => 46899),
    stopFCast: jest.fn(),
    pollFCastCommand: jest.fn(),
    isFCastClient: jest.fn((id) => id === 1),
    disconnectFCastClient: jest.fn(),
    updateFCastState: jest.fn(),
    fcastError: jest.fn(),
  },
}));
jest.mock('../src/playbackSession', () => ({createPlaybackSession: jest.fn()}));
const originalURL = globalThis.URL;
beforeEach(() => {
  jest.useFakeTimers();
  globalThis.URL = StandardURL as unknown as typeof URL;
});
afterEach(() => {
  jest.useRealTimers();
  jest.clearAllMocks();
  globalThis.URL = originalURL;
});

test('comandos válidos controlam o player; Play inválido e eventos antigos não substituem a sessão', () => {
  const packets: string[] = [];
  const player = {
    duration: 8,
    currentTime: 2,
    playbackRate: 1,
    volume: 1,
    pause: jest.fn(),
    play: jest.fn(() => Promise.resolve()),
  };
  const media = {
    load: jest.fn(),
    attachSurface: jest.fn(),
    detachSurface: jest.fn(),
    togglePause: jest.fn(),
    stop: jest.fn(() => Promise.resolve()),
  };
  jest
    .mocked(Network.pollFCastCommand)
    .mockImplementation(() => packets.shift() || '');
  jest.mocked(createPlaybackSession).mockImplementation((ready, changed) => {
    ready(player as unknown as VideoPlayer);
    changed({state: 'playing', seconds: 2});
    return media;
  });
  const ready = jest.fn();
  const receiver = createFCastReceiver(ready, jest.fn(), jest.fn());
  const send = (opcode: number, body = {}, client = 1) => {
    packets.push(JSON.stringify({client, opcode, body: JSON.stringify(body)}));
    jest.advanceTimersByTime(50);
  };
  send(1, {url: 'https://host/video.mp4', container: 'video/mp4'});
  expect(createPlaybackSession).toHaveBeenCalledTimes(1);
  receiver.attachSurface(1, 'current');
  receiver.attachSurface(0, 'stale');
  expect(media.attachSurface).toHaveBeenCalledTimes(1);
  send(2);
  expect(player.pause).toHaveBeenCalledTimes(1);
  send(3);
  expect(player.play).toHaveBeenCalledTimes(1);
  send(5, {time: 3});
  expect(player.currentTime).toBe(3);
  send(8, {volume: 0.35});
  expect(player.volume).toBe(0.35);
  send(10, {speed: 1.25});
  expect(player.playbackRate).toBe(1.25);
  jest.advanceTimersByTime(600);
  send(1, {url: 'file:///data/private.mp4', container: 'video/mp4'});
  expect(Network.fcastError).toHaveBeenCalledWith(
    1,
    'Expected HTTP(S) media URL',
  );
  expect(media.stop).not.toHaveBeenCalled();
  send(4, {}, 2);
  expect(media.stop).not.toHaveBeenCalled();
  send(4);
  expect(media.stop).not.toHaveBeenCalled();
  receiver.stop();
  receiver.stop();
  expect(Network.stopFCast).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});

test('negociação de versão inválida encerra somente o controlador atual', () => {
  const packets = [
    JSON.stringify({client: 1, opcode: 11, body: '{"version":1}'}),
  ];
  jest
    .mocked(Network.pollFCastCommand)
    .mockImplementation(() => packets.shift() || '');
  const receiver = createFCastReceiver(jest.fn(), jest.fn(), jest.fn());
  jest.advanceTimersByTime(50);
  expect(Network.disconnectFCastClient).toHaveBeenCalledWith(1);
  expect(createPlaybackSession).toHaveBeenCalledTimes(1);
  receiver.stop();
});

test('a porta só abre depois que o player nativo conclui initialize', () => {
  let ready: Parameters<typeof createPlaybackSession>[0] | undefined;
  const media = {load: jest.fn(), attachSurface: jest.fn(), detachSurface: jest.fn(), togglePause: jest.fn(), stop: jest.fn(() => Promise.resolve())};
  jest.mocked(createPlaybackSession).mockImplementation((callback) => {ready = callback; return media;});
  const receiver = createFCastReceiver(jest.fn(), jest.fn(), jest.fn());
  expect(Network.startFCast).not.toHaveBeenCalled();
  ready?.({duration: 0, volume: 1} as VideoPlayer);
  expect(Network.startFCast).toHaveBeenCalledTimes(1);
  receiver.stop();
  expect(media.stop).toHaveBeenCalledTimes(1);
});
