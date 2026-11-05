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

// A quick Back/reopen must wait for the previous native decoder to be released.
let releaseBarrier: Promise<void> = Promise.resolve();
const SAMPLE = 'file:///pkg/assets/raw/media/playback-sample.mp4';

export function createPlaybackSession(
  onReady: (player: VideoPlayer) => void,
  onChange: (snapshot: PlaybackSnapshot) => void,
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

  function publish(state = snapshot.state) {
    if (closed || (failed && state !== 'error')) {
      return;
    }
    if (initialized && player) {
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
    initialized = false;
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
    player.volume = 0.15;
    player.src = SAMPLE;
    player.load();
    onReady(player);
    timer = setInterval(() => {
      try {
        publish();
      } catch {
        fail();
      }
    }, 250);
  });
  // Attach rejection handling immediately, including cancellation during init.
  starting.catch(fail);

  return {
    attachSurface(handle: string) {
      if (!closed && initialized && player) {
        try {
          player.setSurfaceHandle(handle);
          player.play().catch(fail);
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
      const releasing = starting
        .catch(() => {})
        .then(async () => {
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
