/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- Native player is replaced by a controllable lifecycle double. */
import {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {createPlaybackSession} from '../src/playbackSession';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock('@amazon-devices/react-native-w3cmedia', () => ({
  VideoPlayer: jest.fn(),
}));
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {recordPlaybackState: jest.fn()},
}));

function fakePlayer() {
  const listeners = new Map<string, () => void>();
  return {
    listeners,
    initialize: jest.fn(() => Promise.resolve()),
    deinitialize: jest.fn(() => Promise.resolve()),
    addEventListener: jest.fn((event: string, handler: () => void) =>
      listeners.set(event, handler),
    ),
    removeEventListener: jest.fn((event: string) => listeners.delete(event)),
    setSurfaceHandle: jest.fn(),
    clearSurfaceHandle: jest.fn(),
    play: jest.fn(() => Promise.resolve()),
    pause: jest.fn(),
    load: jest.fn(),
    currentTime: 1.5,
    paused: false,
    volume: 1,
    src: '',
  };
}

afterEach(() => {
  jest.useRealTimers();
  jest.clearAllMocks();
});

test('aguarda a superfície, controla pausa e libera o player uma única vez', async () => {
  jest.useFakeTimers();
  const player = fakePlayer();
  jest
    .mocked(VideoPlayer)
    .mockImplementation(() => player as unknown as VideoPlayer);
  let ready!: () => void;
  const opened = new Promise<void>((resolve) => {
    ready = resolve;
  });
  const changed = jest.fn();
  const session = createPlaybackSession(ready, changed);
  await opened;
  expect(player.src).toBe('file:///pkg/assets/raw/media/playback-sample.mp4');
  expect(player.play).not.toHaveBeenCalled();
  session.attachSurface('surface-1');
  expect(player.setSurfaceHandle).toHaveBeenCalledWith('surface-1');
  expect(player.play).toHaveBeenCalledTimes(1);
  player.listeners.get('playing')?.();
  expect(changed).toHaveBeenLastCalledWith({
    state: 'playing',
    seconds: 1.5,
  });
  session.togglePause();
  expect(player.pause).toHaveBeenCalledTimes(1);
  player.paused = true;
  player.listeners.get('pause')?.();
  session.togglePause();
  expect(player.play).toHaveBeenCalledTimes(2);
  await session.stop();
  await session.stop();
  expect(player.deinitialize).toHaveBeenCalledTimes(1);
  expect(player.listeners.size).toBe(0);
  expect(jest.getTimerCount()).toBe(0);
  expect(Network.recordPlaybackState).toHaveBeenLastCalledWith('released', 1.5);
  session.attachSurface('late-surface');
  expect(player.play).toHaveBeenCalledTimes(2);
});

test('Voltar durante initialize aguarda a liberação antes de uma nova sessão', async () => {
  const first = fakePlayer();
  const second = fakePlayer();
  let finishInitialization!: () => void;
  first.initialize.mockReturnValue(
    new Promise<void>((resolve) => {
      finishInitialization = resolve;
    }),
  );
  jest
    .mocked(VideoPlayer)
    .mockImplementationOnce(() => first as unknown as VideoPlayer)
    .mockImplementationOnce(() => second as unknown as VideoPlayer);
  const firstReady = jest.fn();
  const oldSession = createPlaybackSession(firstReady, jest.fn());
  await Promise.resolve();
  const closed = oldSession.stop();
  let secondReady!: () => void;
  const reopened = new Promise<void>((resolve) => {
    secondReady = resolve;
  });
  const newSession = createPlaybackSession(secondReady, jest.fn());
  await Promise.resolve();
  expect(VideoPlayer).toHaveBeenCalledTimes(1);
  finishInitialization();
  await closed;
  await reopened;
  expect(firstReady).not.toHaveBeenCalled();
  expect(first.deinitialize).toHaveBeenCalledTimes(1);
  expect(first.play).not.toHaveBeenCalled();
  expect(VideoPlayer).toHaveBeenCalledTimes(2);
  await newSession.stop();
});

test('falha de inicialização é visível e ainda libera o objeto nativo', async () => {
  const player = fakePlayer();
  player.initialize.mockRejectedValue(new Error('native init failed'));
  jest
    .mocked(VideoPlayer)
    .mockImplementation(() => player as unknown as VideoPlayer);
  let failed!: () => void;
  const failure = new Promise<void>((resolve) => {
    failed = resolve;
  });
  const ready = jest.fn();
  const session = createPlaybackSession(ready, (state) => {
    if (state.state === 'error') {
      failed();
    }
  });
  await failure;
  await session.stop();
  expect(ready).not.toHaveBeenCalled();
  expect(player.play).not.toHaveBeenCalled();
  expect(player.deinitialize).toHaveBeenCalledTimes(1);
});

test('erro de decodificação não é apagado por um evento atrasado de reprodução', async () => {
  const player = fakePlayer();
  jest
    .mocked(VideoPlayer)
    .mockImplementation(() => player as unknown as VideoPlayer);
  let ready!: () => void;
  const opened = new Promise<void>((resolve) => {
    ready = resolve;
  });
  const changed = jest.fn();
  const session = createPlaybackSession(ready, changed);
  await opened;
  player.listeners.get('playing')?.();
  player.listeners.get('error')?.();
  player.listeners.get('playing')?.();
  expect(changed).toHaveBeenLastCalledWith({state: 'error', seconds: 1.5});
  await session.stop();
});

test('Voltar cancela initialize pendente pela API do SDK sem esperar seu retorno', async () => {
  const player = fakePlayer();
  player.initialize.mockReturnValue(new Promise<void>(() => {}));
  jest.mocked(VideoPlayer).mockImplementation(() => player as unknown as VideoPlayer);
  const ready = jest.fn();
  const session = createPlaybackSession(ready, jest.fn());
  await Promise.resolve();
  await session.stop();
  expect(player.deinitialize).toHaveBeenCalledTimes(1);
  expect(ready).not.toHaveBeenCalled();
});

test('falha ao liberar uma sessão não impede a próxima inicialização', async () => {
  const first = fakePlayer();
  const second = fakePlayer();
  first.deinitialize.mockRejectedValue(new Error('native release failed'));
  jest.mocked(VideoPlayer)
    .mockImplementationOnce(() => first as unknown as VideoPlayer)
    .mockImplementationOnce(() => second as unknown as VideoPlayer);
  let opened!: () => void;
  const ready = new Promise<void>((resolve) => {opened = resolve;});
  const oldSession = createPlaybackSession(opened, jest.fn());
  await ready;
  await oldSession.stop();
  expect(Network.recordPlaybackState).toHaveBeenLastCalledWith('error', 0);
  const nextReady = new Promise<void>((resolve) => {opened = resolve;});
  const newSession = createPlaybackSession(opened, jest.fn());
  await nextReady;
  expect(second.initialize).toHaveBeenCalledTimes(1);
  await newSession.stop();
});

test('destruição atrasada da superfície antiga preserva a superfície atual', async () => {
  const player = fakePlayer();
  jest.mocked(VideoPlayer).mockImplementation(() => player as unknown as VideoPlayer);
  let opened!: () => void;
  const ready = new Promise<void>((resolve) => {opened = resolve;});
  const session = createPlaybackSession(opened, jest.fn());
  await ready;
  session.attachSurface('old');
  session.attachSurface('new');
  session.detachSurface('old');
  expect(player.clearSurfaceHandle).not.toHaveBeenCalled();
  session.detachSurface('new');
  expect(player.clearSurfaceHandle).toHaveBeenCalledWith('new');
  await session.stop();
});

test('rejeição atrasada do Play antigo não derruba a nova mídia', async () => {
  const player = {...fakePlayer(), setSrcWithCustomHeaders: jest.fn()};
  let rejectPlay!: (reason: Error) => void;
  player.play.mockReturnValueOnce(new Promise<void>((_resolve, reject) => {rejectPlay = reject;}));
  jest.mocked(VideoPlayer).mockImplementation(() => player as unknown as VideoPlayer);
  let opened!: () => void;
  const ready = new Promise<void>((resolve) => {opened = resolve;});
  const changed = jest.fn();
  const session = createPlaybackSession(opened, changed);
  await ready;
  session.attachSurface('old');
  session.load({url: 'https://example.com/new.mp4', headers: {}, position: 0, speed: 1});
  rejectPlay(new Error('previous playback cancelled'));
  await Promise.resolve();
  expect(changed).toHaveBeenLastCalledWith({state: 'loading', seconds: 1.5});
  await session.stop();
});

test('stop repetido numa sessão antiga preserva a liberação pendente da atual', async () => {
  const first = fakePlayer();
  const second = fakePlayer();
  const third = fakePlayer();
  let finishRelease!: () => void;
  second.deinitialize.mockReturnValue(new Promise<void>((resolve) => {finishRelease = resolve;}));
  jest.mocked(VideoPlayer)
    .mockImplementationOnce(() => first as unknown as VideoPlayer)
    .mockImplementationOnce(() => second as unknown as VideoPlayer)
    .mockImplementationOnce(() => third as unknown as VideoPlayer);
  async function open() {
    let ready!: () => void;
    const opened = new Promise<void>((resolve) => {ready = resolve;});
    const session = createPlaybackSession(ready, jest.fn());
    await opened;
    return session;
  }
  const oldSession = await open();
  await oldSession.stop();
  const current = await open();
  const releasing = current.stop();
  await oldSession.stop();
  const pending = open();
  for (let n = 0; n < 10; ++n) {await Promise.resolve();}
  expect(third.initialize).not.toHaveBeenCalled();
  finishRelease();
  await releasing;
  const next = await pending;
  expect(third.initialize).toHaveBeenCalledTimes(1);
  await next.stop();
});

test('carrega URL uma única vez pelo setter do SDK, preservando cabeçalhos', async () => {
  const player = {...fakePlayer(), setSrcWithCustomHeaders: jest.fn()};
  // The installed Vega SDK's setter invokes load internally.
  player.setSrcWithCustomHeaders.mockImplementation(() => player.load());
  jest.mocked(VideoPlayer).mockImplementation(() => player as unknown as VideoPlayer);
  let ready!: () => void;
  const opened = new Promise<void>((resolve) => {ready = resolve;});
  const session = createPlaybackSession(ready, jest.fn(), null);
  await opened;
  session.load({url: 'https://example.com/clip.mp4', headers: {'X-Test': 'value'}, position: 0, speed: 1});
  expect(player.setSrcWithCustomHeaders).toHaveBeenCalledWith('https://example.com/clip.mp4', {'X-Test': 'value'});
  expect(player.load).toHaveBeenCalledTimes(1);
  await session.stop();
});
