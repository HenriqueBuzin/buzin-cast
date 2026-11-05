/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player selected by the SDK resolver. */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createPlaybackSession, PlaybackSnapshot} from './playbackSession';
import {number, object, playRequest} from './fcastProtocol';

export function createFCastReceiver(
  onReady: (token: number | null) => void,
  onChange: (snapshot: PlaybackSnapshot | null) => void,
  onFailure: () => void,
  onListening?: () => void,
) {
  let player: VideoPlayer | undefined;
  let session: ReturnType<typeof createPlaybackSession> | undefined;
  let token = 0;
  let lastPlay = -Infinity;
  let lastClient = 0;
  let closed = false;
  let listening = false;
  let ownsMedia = false;
  let snapshot: PlaybackSnapshot | null = null;
  let poll: ReturnType<typeof setInterval> | undefined;
  let tick: ReturnType<typeof setInterval> | undefined;

  function endMedia() {
    ++token;
    if (ownsMedia && !closed) {
      try {
        player?.pause();
      } catch {
        /* deinitialize still runs on shutdown */
      }
    }
    ownsMedia = false;
    snapshot = null;
    if (!closed) {
      onReady(null);
      onChange(null);
    }
  }
  function publish() {
    const active =
      ownsMedia &&
      player &&
      snapshot &&
      !['ended', 'error'].includes(snapshot.state);
    const duration = active ? player!.duration : 0;
    Network.updateFCastState(
      JSON.stringify({
        generationTime: Date.now(),
        time: active ? snapshot!.seconds : 0,
        duration: Number.isFinite(duration) && duration >= 0 ? duration : 0,
        state: !active ? 0 : snapshot!.state === 'playing' ? 1 : 2,
        speed: active ? player!.playbackRate : 1,
      }),
      JSON.stringify({
        generationTime: Date.now(),
        volume: ownsMedia && player ? player.volume : 1,
      }),
    );
  }
  function stop() {
    if (closed) {
      return;
    }
    closed = true;
    if (poll) {
      clearInterval(poll);
    }
    if (tick) {
      clearInterval(tick);
    }
    clearTimeout(deadline);
    try {
      if (listening) {
        Network.stopFCast();
      }
    } finally {
      listening = false;
      endMedia();
      session?.stop();
      player = undefined;
    }
  }
  function fail() {
    if (closed) {
      return;
    }
    endMedia();
    try {
      stop();
    } finally {
      onFailure();
    }
  }
  function command(
    client: number,
    opcode: number,
    body: Record<string, unknown>,
  ) {
    if (!Network.isFCastClient(client)) {
      return;
    }
    if (opcode === 11) {
      if (number(body, 'version', 2, Number.MAX_SAFE_INTEGER) % 1 !== 0) {
        throw new Error('Invalid protocol version');
      }
      return;
    }
    if (opcode === 1) {
      if (Date.now() - lastPlay < 500) {
        throw new Error('Wait before starting another item');
      }
      const request = playRequest(body);
      if (!player || !session) {
        throw new Error('Player is not ready');
      }
      lastPlay = Date.now();
      lastClient = client;
      endMedia();
      ownsMedia = true;
      session.load(request);
      onReady(token);
      return;
    }
    if (![2, 3, 4, 5, 8, 10].includes(opcode)) {
      throw new Error('Unsupported FCast v2 opcode');
    }
    if (!player || !session || !ownsMedia) {
      throw new Error('No FCast media is playing');
    }
    switch (opcode) {
      case 2:
        player.pause();
        break;
      case 3:
        player
          .play()
          .catch(() => Network.fcastError(client, 'Playback failed'));
        break;
      case 4:
        endMedia();
        break;
      case 5:
        player.currentTime = number(body, 'time', 0, 31536000);
        break;
      case 8:
        player.volume = number(body, 'volume', 0, 1);
        break;
      case 10:
        player.playbackRate = number(body, 'speed', 0.25, 4);
        break;
    }
    publish();
  }
  // Prepare the native player before accepting a controller, as in Fire OS.
  const deadline = setTimeout(fail, 20000);
  session = createPlaybackSession(
    (ready) => {
      if (closed) {
        return;
      }
      player = ready;
      try {
        Network.startFCast();
        listening = true;
        clearTimeout(deadline);
        publish();
        onListening?.();
      } catch {
        fail();
      }
    },
    (state) => {
      if (closed) {
        return;
      }
      if (!listening && state.state === 'error') {
        fail();
        return;
      }
      if (!ownsMedia) {
        return;
      }
      snapshot = state;
      onChange(state);
      if (state.state === 'error') {
        Network.fcastError(lastClient, 'Playback failed');
      }
      publish();
    },
    null,
  );
  poll = setInterval(() => {
    if (!listening) {
      return;
    }
    try {
      for (let n = 0; n < 8; ++n) {
        const encoded = Network.pollFCastCommand();
        if (!encoded) {
          break;
        }
        const event = object(JSON.parse(encoded));
        const client = number(event, 'client', 1, Number.MAX_SAFE_INTEGER);
        const opcode = number(event, 'opcode', 0, 255);
        try {
          command(
            client,
            opcode,
            event.body ? object(JSON.parse(String(event.body))) : {},
          );
        } catch (error) {
          if (opcode === 11) {
            Network.disconnectFCastClient(client);
          } else {
            Network.fcastError(
              client,
              error instanceof Error ? error.message : 'Invalid command',
            );
          }
        }
      }
    } catch {
      fail();
    }
  }, 50);
  tick = setInterval(() => {
    if (listening) {
      try {
        publish();
      } catch {
        fail();
      }
    }
  }, 500);
  return {
    port: 46899,
    attachSurface(current: number, handle: string) {
      if (current === token && ownsMedia) {
        session?.attachSurface(handle);
      }
    },
    detachSurface(current: number, handle: string) {
      if (current === token) {
        session?.detachSurface(handle);
      }
    },
    togglePause() {
      if (ownsMedia) {
        session?.togglePause();
      }
    },
    stop,
  };
}
