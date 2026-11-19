/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- Native OS 2.0 player. */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createPlaybackSession, PlaybackSnapshot} from './playbackSession';
import {number, object, playRequest} from './fcastProtocol';
import type {ConnectionApprovalRequest} from './connectionApproval';

export function createAirPlayReceiver(
  onReady: (token: number | null) => void,
  onChange: (snapshot: PlaybackSnapshot | null) => void,
  onFailure: () => void,
  onListening?: (port: number) => void,
  mode: 'url' | 'mirror' = 'url',
  onPin?: (pin: string) => void,
  onApproval?: (request: ConnectionApprovalRequest | null) => void,
) {
  let player: VideoPlayer | undefined;
  let session: ReturnType<typeof createPlaybackSession> | undefined;
  let token = 0;
  let port = 0;
  let closed = false;
  let ownsMedia = false;
  let snapshot: PlaybackSnapshot | null = null;
  let lastPlay = -Infinity;
  let poll: ReturnType<typeof setInterval> | undefined;
  let tick: ReturnType<typeof setInterval> | undefined;
  const deadline = setTimeout(fail, 20000);
  function endMedia() {
    ++token;
    const hadMedia = ownsMedia;
    ownsMedia = false;
    snapshot = null;
    try {if (hadMedia) {player?.pause();}} catch { /* native release still follows on stop */ }
    if (!closed) {onReady(null); onChange(null);}
  }
  function publish() {
    const active = ownsMedia && snapshot && !['error', 'ended'].includes(snapshot.state);
    const duration = active && player ? player.duration : 0;
    Network.updateAirPlayState(
      active ? snapshot!.seconds : 0,
      Number.isFinite(duration) && duration >= 0 ? duration : 0,
      active && snapshot!.state === 'playing' && player ? player.playbackRate : 0,
      !!active && snapshot!.state !== 'loading',
    );
  }
  function stop() {
    if (closed) {return;}
    closed = true;
    clearTimeout(deadline);
    if (poll) {clearInterval(poll);}
    if (tick) {clearInterval(tick);}
    try {if (port) {Network.stopAirPlayUrl();}} finally {
      port = 0;
      endMedia();
      session?.stop();
      player = undefined;
    }
  }
  function fail() {
    if (closed) {return;}
    endMedia();
    try {stop();} finally {onApproval?.(null); onFailure();}
  }
  session = createPlaybackSession((ready) => {
    if (closed) {return;}
    player = ready;
    try {
      port = mode === 'mirror' ? Network.startAirPlayVideo() : Network.startAirPlayUrl();
      clearTimeout(deadline);
      publish();
      onListening?.(port);
    } catch {fail();}
  }, (state) => {
    if (closed) {return;}
    if (!port && state.state === 'error') {fail(); return;}
    if (ownsMedia) {snapshot = state; onChange(state); publish();}
  }, null);
  poll = setInterval(() => {
    if (!port) {return;}
    try {
      if (onApproval) {
        const approval = Network.pollAirPlayApproval();
        if (approval) {onApproval(JSON.parse(approval).approval as ConnectionApprovalRequest | null);}
      }
      for (let n = 0; n < 8; ++n) {
        const packet = Network.pollAirPlayCommand();
        if (!packet) {break;}
        const event = object(JSON.parse(packet));
        if (event.kind === 'play' || event.kind === 'mirror') {
          const url = String(event.url);
          if (mode === 'mirror') {
            if (event.kind !== 'mirror' || !/^http:\/\/127\.0\.0\.1:\d{1,5}\/[0-9a-f]{64}\.mp4$/.test(url)) {
              throw new Error('Invalid native mirror output');
            }
          } else if (event.kind !== 'play' || Date.now() - lastPlay < 500) {throw new Error('Play rate exceeded');}
          const container = /\.m3u8(?:[?#]|$)/i.test(url) ? 'application/x-mpegURL'
            : /\.mpd(?:[?#]|$)/i.test(url) ? 'application/dash+xml' : 'video/mp4';
          const request = playRequest({url, container, time: number(event, 'value', 0, 31536000)});
          if (!session || !player) {throw new Error('Player is not ready');}
          lastPlay = Date.now();
          endMedia(); ownsMedia = true;
          session.load(request);
          onReady(token);
        } else if (event.kind === 'stop') {
          endMedia(); publish();
        } else if (event.kind === 'volume') {
          session?.setVolume(number(event, 'value', 0, 1));
        } else if (event.kind === 'pin' && mode === 'mirror') {
          const pin = number(event, 'value', 0, 9999);
          if (!Number.isInteger(pin)) {throw new Error('Invalid native PIN');}
          onPin?.(String(pin).padStart(4, '0'));
        } else if (ownsMedia && player) {
          if (event.kind === 'seek') {player.currentTime = number(event, 'value', 0, 31536000);}
          else if (event.kind === 'rate') {
            const rate = number(event, 'value', 0, 4);
            if (rate === 0) {player.pause();} else {
              player.playbackRate = rate;
              player.play().catch(fail);
            }
          }
        }
      }
    } catch {fail();}
  }, 50);
  tick = setInterval(() => {if (port) {try {publish();} catch {fail();}}}, 250);
  return {
    get port() {return port;},
    attachSurface(current: number, handle: string) {if (current === token && ownsMedia) {session?.attachSurface(handle);}},
    detachSurface(current: number, handle: string) {if (current === token) {session?.detachSurface(handle);}},
    togglePause() {if (ownsMedia && mode !== 'mirror') {session?.togglePause();}},
    answerApproval(requestId: number, allow: boolean) {
      if (!port || closed) {return;}
      try {Network.answerAirPlayApproval(requestId, allow);} catch {fail();}
    },
    stop,
  };
}
