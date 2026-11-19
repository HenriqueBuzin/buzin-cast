/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player selected by the SDK resolver. */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createPlaybackSession, PlaybackSnapshot} from './playbackSession';
import {number, object, playRequest} from './fcastProtocol';
import type {ConnectionApprovalRequest} from './connectionApproval';
import {CastMessage, castChannel, decodeCastMessage, defaultAppId, encodeCastMessage, receiverId} from './castV2Protocol';

export function createCastV2Receiver(
  onReady: (token: number | null) => void,
  onChange: (snapshot: PlaybackSnapshot | null) => void,
  onFailure: () => void,
  onListening: (port: number) => void,
  onApproval: (request: ConnectionApprovalRequest | null) => void,
) {
  let client = 0;
  let sessionId = '';
  let transportId = '';
  let mediaSender = '';
  let nextSession = 0;
  let mediaId = 0;
  let token = 0;
  let lastLoad = -Infinity;
  let closed = false;
  let volume = {level: 1, muted: false};
  let media: Record<string, unknown> | undefined;
  let snapshot: PlaybackSnapshot | null = null;
  let player: VideoPlayer | undefined;
  let playback: ReturnType<typeof createPlaybackSession> | undefined;
  let wantsPlay = true;
  let pendingSeek: number | undefined;

  function stopMedia() {
    ++token;
    playback?.stop();
    playback = undefined;
    player = undefined;
    media = undefined;
    snapshot = null;
    pendingSeek = undefined;
    if (!closed) {
      onReady(null);
      onChange(null);
    }
  }
  function resetApplication() {
    stopMedia();
    sessionId = '';
    transportId = '';
    mediaSender = '';
    mediaId = 0;
  }
  function stop() {
    if (closed) {return;}
    closed = true;
    clearInterval(poll);
    clearInterval(tick);
    try {Network.stopCastV2();} finally {resetApplication();}
  }
  function fail() {
    try {stop();} finally {
      onApproval(null);
      onFailure();
    }
  }
  function send(message: CastMessage) {
    Network.sendCastV2(client, encodeCastMessage(message));
  }
  function respond(namespace: string, source: string, destination: string, body: object) {
    send({source, destination, namespace, text: JSON.stringify(body)});
  }
  function updateVolume(value: Record<string, unknown>) {
    const level = 'level' in value ? number(value, 'level', 0, 1) : volume.level;
    const muted = 'muted' in value ? value.muted : volume.muted;
    if (typeof muted !== 'boolean') {throw new Error('Invalid mute state');}
    volume = {level, muted};
    playback?.setVolume(muted ? 0 : level);
  }
  function receiverStatus(destination: string, requestId?: number) {
    const applications = sessionId ? [{
      appId: defaultAppId,
      displayName: 'Buzin Cast',
      sessionId,
      transportId,
      isIdleScreen: false,
      statusText: 'Buzin Cast',
      namespaces: [{name: castChannel.media}],
    }] : [];
    respond(castChannel.receiver, receiverId, destination, {
      type: 'RECEIVER_STATUS', requestId, status: {applications, volume},
    });
  }
  function mediaStatus(destination: string, requestId?: number) {
    const duration = player?.duration;
    const status = media ? [{
      mediaSessionId: mediaId,
      media: {...media, duration: Number.isFinite(duration) && duration! >= 0 ? duration : 0},
      currentTime: pendingSeek ?? snapshot?.seconds ?? 0,
      playbackRate: player?.playbackRate ?? 1,
      playerState: snapshot?.state === 'playing' ? 'PLAYING' :
        snapshot?.state === 'paused' || !wantsPlay ? 'PAUSED' : 'BUFFERING',
      supportedMediaCommands: 15,
      volume,
    }] : mediaId ? [{
      mediaSessionId: mediaId, playerState: 'IDLE',
      currentTime: 0, playbackRate: 1, supportedMediaCommands: 0,
    }] : [];
    respond(castChannel.media, transportId, destination, {type: 'MEDIA_STATUS', requestId, status});
  }
  function setPlaying(value: boolean) {
    wantsPlay = value;
    playback?.setPlaying(value);
  }
  function load(body: Record<string, unknown>) {
    if (Date.now() - lastLoad < 500) {throw new Error('Wait before loading another item');}
    const item = object(body.media);
    if ('drm' in item || 'contentProtection' in item) {throw new Error('Unsupported media');}
    if ('autoplay' in body && typeof body.autoplay !== 'boolean') {throw new Error('Invalid autoplay');}
    const request = playRequest({
      url: item.contentUrl || item.contentId, container: item.contentType, time: body.currentTime,
    });
    stopMedia();
    lastLoad = Date.now();
    ++mediaId;
    const generation = token;
    wantsPlay = body.autoplay !== false;
    media = {
      contentId: request.url, contentType: item.contentType,
      streamType: item.streamType === 'LIVE' ? 'LIVE' : 'BUFFERED',
    };
    playback = createPlaybackSession(
      ready => {
        if (!playback || generation !== token) {return;}
        player = ready;
        playback.load({...request, position: pendingSeek ?? request.position, autoplay: wantsPlay});
        pendingSeek = undefined;
        playback.setVolume(volume.muted ? 0 : volume.level);
        onReady(generation);
      },
      state => {
        if (generation !== token) {return;}
        snapshot = state;
        onChange(state);
        if (state.state === 'ended' || state.state === 'error') {
          stopMedia();
          if (state.state === 'error') {onChange(state);}
          if (mediaSender) {mediaStatus(mediaSender);}
        }
      },
      null,
    );
  }
  function receiverCommand(source: string, body: Record<string, unknown>, requestId?: number) {
    switch (body.type) {
      case 'GET_APP_AVAILABILITY': {
        const ids: unknown = body.appId;
        if (!Array.isArray(ids) || ids.length > 32 ||
            ids.some(id => typeof id !== 'string' || id.length > 64)) {
          throw new Error('Invalid app list');
        }
        const availability = Object.fromEntries(ids.map(id =>
          [id, id === defaultAppId ? 'APP_AVAILABLE' : 'APP_UNAVAILABLE']));
        respond(castChannel.receiver, receiverId, source, {type: body.type, requestId, availability});
        return;
      }
      case 'LAUNCH':
        if (body.appId !== defaultAppId) {
          respond(castChannel.receiver, receiverId, source, {type: 'LAUNCH_ERROR', requestId, reason: 'NOT_FOUND'});
          return;
        }
        if (!sessionId) {
          sessionId = `buzin-${client}-${++nextSession}`;
          transportId = `transport-${sessionId}`;
        }
        break;
      case 'STOP':
        if ('sessionId' in body && body.sessionId !== sessionId) {throw new Error('Invalid app session');}
        resetApplication();
        break;
      case 'SET_VOLUME':
        updateVolume(object(body.volume));
        break;
      case 'GET_STATUS':
        break;
      default:
        throw new Error('Unsupported receiver command');
    }
    receiverStatus(source, requestId);
  }
  function mediaCommand(source: string, body: Record<string, unknown>, requestId?: number) {
    if (body.type === 'LOAD') {
      load(body);
    } else if (body.type !== 'GET_STATUS') {
      if (!media || body.mediaSessionId !== mediaId) {throw new Error('Invalid media session');}
      switch (body.type) {
        case 'PLAY':
          setPlaying(true);
          break;
        case 'PAUSE':
          setPlaying(false);
          break;
        case 'STOP':
          stopMedia();
          break;
        case 'SEEK': {
          const seconds = number(body, 'currentTime', 0, 31536000);
          if (player) {player.currentTime = seconds;}
          else {pendingSeek = seconds;}
          if (snapshot) {snapshot = {...snapshot, seconds};}
          if (body.resumeState === 'PLAYBACK_START') {setPlaying(true);}
          if (body.resumeState === 'PLAYBACK_PAUSE') {setPlaying(false);}
          break;
        }
        case 'SET_VOLUME':
          updateVolume(object(body.volume));
          break;
        default:
          throw new Error('Unsupported media command');
      }
    }
    mediaSender = source;
    mediaStatus(source, requestId);
  }
  function command(message: CastMessage) {
    const {source, destination, namespace} = message;
    if (destination !== receiverId && !(transportId && destination === transportId)) {return;}
    if (namespace === castChannel.authentication && destination === receiverId && 'binary' in message) {
      // Report unavailable device authentication for the receiver's local TLS certificate.
      send({source: receiverId, destination: source, namespace, binary: '\x1a\x02\x08\x00'});
      return;
    }
    let requestId: number | undefined;
    try {
      if (!('text' in message)) {throw new Error('Expected text message');}
      const body = object(JSON.parse(message.text));
      if ('requestId' in body) {
        requestId = number(body, 'requestId', 0, 2147483647);
        if (!Number.isInteger(requestId)) {requestId = undefined; throw new Error('Invalid request ID');}
      }
      if (namespace === castChannel.connection && (body.type === 'CONNECT' || body.type === 'CLOSE')) {
        if (body.type === 'CLOSE' && mediaSender === source && destination === transportId) {
          mediaSender = '';
        }
        return;
      }
      if (namespace === castChannel.heartbeat && (body.type === 'PING' || body.type === 'PONG')) {
        if (body.type === 'PING') {respond(namespace, destination, source, {type: 'PONG'});}
        return;
      }
      if (namespace === castChannel.receiver && destination === receiverId) {
        receiverCommand(source, body, requestId);
        return;
      }
      if (namespace === castChannel.media && destination === transportId) {
        mediaCommand(source, body, requestId);
        return;
      }
      throw new Error('Unsupported Cast command');
    } catch {
      respond(namespace, destination, source, {type: 'INVALID_REQUEST', requestId, reason: 'INVALID_COMMAND'});
    }
  }

  const port = Network.startCastV2();
  const poll = setInterval(() => {
    try {
      for (let count = 0; count < 8; ++count) {
        const raw = Network.pollCastV2();
        if (!raw) {break;}
        const event = object(JSON.parse(raw));
        if ('approval' in event) {
          onApproval(event.approval as ConnectionApprovalRequest | null);
        } else if ('closed' in event) {
          if (event.closed === client) {
            client = 0;
            resetApplication();
          }
        } else {
          const sender = number(event, 'client', 1, Number.MAX_SAFE_INTEGER);
          if (sender !== client) {
            resetApplication();
            client = sender;
          }
          let message: CastMessage;
          try {
            message = decodeCastMessage(String(event.frame));
          } catch {
            Network.disconnectCastV2(client);
            break;
          }
          command(message);
        }
      }
    } catch {fail();}
  }, 50);
  const tick = setInterval(() => {
    try {
      if (mediaSender && media) {mediaStatus(mediaSender);}
    } catch {fail();}
  }, 1000);
  onListening(port);

  return {
    attachSurface(current: number, handle: string) {
      if (current === token) {playback?.attachSurface(handle);}
    },
    detachSurface(current: number, handle: string) {
      if (current === token) {playback?.detachSurface(handle);}
    },
    togglePause() {
      if (playback) {setPlaying(!wantsPlay);}
    },
    answerApproval(id: number, allow: boolean) {
      if (!closed) {
        try {Network.answerCastV2Approval(id, allow);} catch {fail();}
      }
    },
    stop,
  };
}
