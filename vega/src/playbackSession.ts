/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player selected by the SDK resolver; validated with doctor and VVD. */
import {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createAdaptivePlayback} from './adaptivePlayback';

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
  mimeType?: string;
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
  let surface: string | undefined;
  let sourceGeneration = 0;
  let sourceReady = false;
  let adaptive: ReturnType<typeof createAdaptivePlayback> | undefined;
  let sourceBarrier: Promise<void> = Promise.resolve();
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
    ++sourceGeneration;
    failed = false;
    hasSource = true;
    position = source.position;
    sourceReady = false;
    const generation = sourceGeneration;
    publish('loading');
    // Interrupt an in-flight manifest request before waiting on its load.
    const interrupted = adaptive?.stop();
    interrupted?.catch(() => {});
    sourceBarrier = sourceBarrier.catch(() => {}).then(async () => {
      await interrupted;
      adaptive = undefined;
      if (closed || generation !== sourceGeneration || !player) {return;}
      player.volume = 1;
      player.playbackRate = source.speed;
      if (source.mimeType) {
        adaptive = createAdaptivePlayback(player, () => {
          if (generation === sourceGeneration) {fail();}
        });
        await adaptive.load(source.url, source.position, source.mimeType, source.headers);
      } else if (Object.keys(source.headers).length) {
        player.setSrcWithCustomHeaders(source.url, source.headers);
      } else {
        // The native custom-header setter rejects an empty header list.
        player.src = source.url;
      }
      if (closed || generation !== sourceGeneration) {return;}
      sourceReady = true;
      if (surface) {player.play().catch(fail);}
    });
    sourceBarrier.catch(() => {
      if (generation === sourceGeneration) {fail();}
    });
    observe();
    return sourceBarrier;
  }

  function recordQuality() {
    let total = -1;
    let dropped = -1;
    try {
      if (initialized && player && !failed) {
        const quality = player.getVideoPlaybackQuality();
        if (Number.isInteger(quality.totalVideoFrames) && quality.totalVideoFrames >= 0 && quality.totalVideoFrames <= 4294967295 &&
            Number.isInteger(quality.droppedVideoFrames) && quality.droppedVideoFrames >= 0 && quality.droppedVideoFrames <= quality.totalVideoFrames) {
          total = quality.totalVideoFrames;
          dropped = quality.droppedVideoFrames;
        }
      }
    } catch { /* Some platform players do not expose video counters. */ }
    try {Network.recordVideoPlaybackQuality(total, dropped, !!surface);} catch { /* Diagnostics must not interrupt playback. */ }
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
    recordQuality();
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
      sourceReady = true;
    }
    onReady(player);
    observe();
  });
  // Attach rejection handling immediately, including cancellation during init.
  starting.catch(fail);

  return {
    load(source: PlaybackRequest) {
      return configure(source);
    },
    attachSurface(handle: string) {
      if (!closed && initialized && player) {
        try {
          player.setSurfaceHandle(handle);
          surface = handle;
          if (hasSource && sourceReady) {
            const generation = sourceGeneration;
            player.play().catch(() => {
              if (generation === sourceGeneration) {
                fail();
              }
            });
          }
        } catch {
          fail();
        }
      }
    },
    detachSurface(handle: string) {
      if (!closed && initialized && player && surface === handle) {
        player.clearSurfaceHandle(handle);
        surface = undefined;
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
        // Cancel adaptive downloads before releasing the native player.
        let adaptiveReleaseError: unknown;
        try {await adaptive?.stop();} catch (error) {adaptiveReleaseError = error;}
        await sourceBarrier.catch(() => {});
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
        if (adaptiveReleaseError) {throw adaptiveReleaseError;}
        initialized = false; surface = undefined;
        recordQuality();
        Network.recordPlaybackState('released', snapshot.seconds);
      });
      // A failed release is reported, but does not leave an unhandled rejection.
      closing = releasing.catch(() => {
        try {
          Network.recordPlaybackState('error', snapshot.seconds);
        } catch {
          // No further writes are possible if the sandbox storage failed.
        }
      });
      // Cleanup failure is reported above. The next session must still be
      // allowed to initialize once this attempt has finished.
      releaseBarrier = closing;
      return closing;
    },
  };
}
