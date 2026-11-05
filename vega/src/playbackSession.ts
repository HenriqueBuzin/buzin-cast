/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player selected by the SDK resolver; validated with doctor and VVD. */
import {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';

export type PlaybackState =
  | 'loading'
  | 'playing'
  | 'paused'
  | 'ended'
  | 'error';
export type PlaybackSnapshot = {
  state: PlaybackState;
  seconds: number;
};
export type PlaybackRequest = {
  url: string;
  headers: Record<string, string>;
  position: number;
  speed: number;
};

// A quick Back/reopen must wait for the previous native decoder to be released.
let releaseBarrier: Promise<void> = Promise.resolve();
const SAMPLE = 'file:///pkg/assets/raw/media/playback-sample.mp4';

export function createPlaybackSession(
  onReady: (player: VideoPlayer) => void,
  onChange: (snapshot: PlaybackSnapshot) => void,
  request?: PlaybackRequest | null,
) {
  const previousRelease = releaseBarrier;
  let player: VideoPlayer | undefined;
  let initialized = false;
  let failed = false;
  let closed = false;
  let closing: Promise<void> | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;
  let snapshot: PlaybackSnapshot = {state: 'loading', seconds: 0};
  const listeners: Array<[string, () => void]> = [];
  let position = request?.position || 0;
  let hasSource = request !== null;
  function observe() {
    if (!timer) {
      timer = setInterval(() => {
        try {
          publish();
        } catch {
          fail();
        }
      }, 250);
    }
  }
  function configure(source: PlaybackRequest) {
    if (!player || !initialized || closed) {
      throw new Error('Player is not ready');
    }
    failed = false;
    hasSource = true;
    position = source.position;
    player.volume = 1;
    player.playbackRate = source.speed;
    player.setSrcWithCustomHeaders(source.url, source.headers);
    player.load();
    publish('loading');
    observe();
  }

  function publish(state = snapshot.state) {
    if (closed || (failed && state !== 'error')) {
      return;
    }
    if (initialized && player && !failed) {
      const seconds = player.currentTime;
      snapshot = {
        state,
        seconds: Number.isFinite(seconds) && seconds >= 0 ? seconds : 0,
      };
    } else {
      snapshot = {...snapshot, state};
    }
    try {
      Network.recordPlaybackState(state, snapshot.seconds);
    } catch {
      snapshot = {...snapshot, state: 'error'};
    }
    onChange(snapshot);
  }

  function fail() {
    if (closed || failed) {
      return;
    }
    failed = true;
    if (timer) {
      clearInterval(timer);
      timer = undefined;
    }
    // Preserve the last observation if a native getter is no longer available.
    publish('error');
  }

  const starting = previousRelease.then(async () => {
    if (closed) {
      return;
    }
    publish('loading');
    player = new VideoPlayer();
    for (const state of ['playing', 'paused', 'ended', 'error'] as const) {
      const event = state === 'paused' ? 'pause' : state;
      const handler = () => {
        try {
          if (state === 'error') {
            fail();
          } else {
            publish(state);
          }
        } catch {
          fail();
        }
      };
      listeners.push([event, handler]);
      player.addEventListener(event, handler);
    }
    await player.initialize();
    initialized = true;
    if (closed) {
      return;
    }
    const seek = () => {
      if (!closed && !failed && player && position > 0) {
        try {
          const target = position;
          position = 0;
          player.currentTime = target;
        } catch {
          fail();
        }
      }
    };
    listeners.push(['loadedmetadata', seek]);
    player.addEventListener('loadedmetadata', seek);
    if (request) {
      configure(request);
    } else if (request === undefined) {
      player.volume = 0.15;
      player.src = SAMPLE;
      player.load();
    }
    onReady(player);
    observe();
  });
  // Attach rejection handling immediately, including cancellation during init.
  starting.catch(fail);

  return {
    load(source: PlaybackRequest) {
      configure(source);
    },
    attachSurface(handle: string) {
      if (!closed && initialized && player) {
        try {
          player.setSurfaceHandle(handle);
          if (hasSource) {
            player.play().catch(fail);
          }
        } catch {
          fail();
        }
      }
    },
    detachSurface(handle: string) {
      if (!closed && initialized && player) {
        player.clearSurfaceHandle(handle);
      }
    },
    togglePause() {
      if (!closed && initialized && player) {
        try {
          if (player.paused) {
            player.play().catch(fail);
          } else {
            player.pause();
          }
        } catch {
          fail();
        }
      }
    },
    stop(): Promise<void> {
      if (closing) {
        return closing;
      }
      closed = true;
      if (timer) {
        clearInterval(timer);
      }
      const pending = player && !initialized ? player : undefined;
      if (pending) {
        for (const [event, handler] of listeners) {
          pending.removeEventListener(event, handler);
        }
        player = undefined;
      }
      const releasing = (
        pending
          ? Promise.resolve().then(() => pending.deinitialize())
          : starting.catch(() => {})
      ).then(async () => {
        if (player) {
          for (const [event, handler] of listeners) {
            player.removeEventListener(event, handler);
          }
          try {
            if (initialized) {
              player.pause();
            }
          } finally {
            await player.deinitialize();
          }
          player = undefined;
        }
        Network.recordPlaybackState('released', snapshot.seconds);
      });
      // A failed release is reported, but does not leave an unhandled rejection.
      releaseBarrier = releasing;
      closing = releasing.catch(() => {
        try {
          Network.recordPlaybackState('error', snapshot.seconds);
        } catch {
          // No further writes are possible if the sandbox storage failed.
        }
      });
      return closing;
    },
  };
}
