/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player. */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createPlaybackSession, PlaybackRequest, PlaybackSnapshot} from './playbackSession';
import {dlnaConnectionInfo, DlnaFault, DlnaRequest, DlnaService, formatDlnaTime, isDlnaPhoto, parseDlnaTime, routeDlna} from './dlnaProtocol';
import {loadDlnaPhoto, photoSource} from './dlnaPhoto';
import {playRequest} from './fcastProtocol';

type Media = {kind: 'photo'; url: string} | ({kind: 'player'} & PlaybackRequest);
export type DlnaPhotoView = {token: number; source?: ReturnType<typeof photoSource>};

export function createDlnaReceiver(
  onReady: (token: number | null) => void,
  onChange: (snapshot: PlaybackSnapshot | null) => void,
  onFailure: () => void,
  onListening: (port: number) => void,
  onPhoto: (photo: DlnaPhotoView | null) => void,
) {
  let player: VideoPlayer | undefined;
  let session: ReturnType<typeof createPlaybackSession> | undefined;
  let active = false;
  let closed = false;
  let serving = false;
  let token = 0;
  let surface: string | undefined;
  let uri: Media | null = null;
  let cancelPhoto: (() => void) | undefined;
  let photoTimeout: ReturnType<typeof setTimeout> | undefined;
  let metadata = '';
  let snapshot: PlaybackSnapshot | null = null;
  let volume = 100;
  let muted = false;
  let uuid = '';
  let name = '';
  let lastLoad = -Infinity;

  function clearPhotoTimeout() {
    if (photoTimeout !== undefined) {
      clearTimeout(photoTimeout);
      photoTimeout = undefined;
    }
  }

  function endMedia() {
    const wasPlaying = active && uri?.kind === 'player';
    active = false;
    snapshot = null;
    ++token;
    cancelPhoto?.();
    cancelPhoto = undefined;
    clearPhotoTimeout();
    if (surface) {
      session?.detachSurface(surface);
      surface = undefined;
    }
    if (wasPlaying) {player?.pause();}
    if (!closed) {onPhoto(null);}
    if (!closed) {onReady(null); onChange(null);}
  }

  function photoDisplayed(current: number, success: boolean) {
    if (closed || !active || current !== token || uri?.kind !== 'photo' ||
        snapshot?.state !== 'loading') {return;}
    clearPhotoTimeout();
    if (!success) {onPhoto({token});}
    snapshot = {seconds: 0, state: success ? 'playing' : 'error'};
    onChange(snapshot);
  }

  function startMedia(request: Media) {
    if (Date.now() - lastLoad < 500) {throw new DlnaFault(705, 'Transport busy');}
    lastLoad = Date.now();
    endMedia();
    uri = request;
    active = true;
    snapshot = {seconds: 0, state: 'loading'};
    if (request.kind === 'photo') {
      const current = token;
      onChange(snapshot);
      onPhoto({token: current});
      try {
        cancelPhoto = loadDlnaPhoto(request.url, source => {
          if (closed || current !== token) {return;}
          cancelPhoto = undefined;
          photoTimeout = setTimeout(() => photoDisplayed(current, false), 8000);
          onPhoto({token: current, source});
        }, () => {
          if (closed || current !== token) {return;}
          cancelPhoto = undefined;
          photoDisplayed(current, false);
        });
      } catch {photoDisplayed(current, false);}
    } else {
      session?.load(request);
      onReady(token);
    }
  }

  function stop() {
    if (closed) {return;}
    closed = true;
    clearInterval(poll);
    clearTimeout(deadline);
    try {
      if (serving) {Network.stopDlna();}
    } finally {
      serving = false;
      active = false;
      cancelPhoto?.();
      clearPhotoTimeout();
      session?.stop();
      player = undefined;
    }
  }

  function fail() {
    if (closed) {return;}
    try {stop();} finally {onPhoto(null); onReady(null); onChange(null); onFailure();}
  }

  function transportState() {
    if (!uri) {return 'NO_MEDIA_PRESENT';}
    if (!active || !snapshot || ['ended', 'error'].includes(snapshot.state)) {return 'STOPPED';}
    if (snapshot.state === 'loading') {return 'TRANSITIONING';}
    return snapshot.state === 'paused' ? 'PAUSED_PLAYBACK' : 'PLAYING';
  }

  function setOutputVolume() {
    session?.setVolume(muted ? 0 : volume / 100);
  }

  function dispatch(service: DlnaService, action: string, args: Record<string, string>): Record<string, string> {
    if (service === 'cmn') {return dlnaConnectionInfo(action, args);}
    if (service === 'rcs') {
      switch (action) {
        case 'GetVolume': return {CurrentVolume: String(volume)};
        case 'GetMute': return {CurrentMute: muted ? '1' : '0'};
        case 'ListPresets': return {CurrentPresetNameList: 'FactoryDefaults'};
        case 'SelectPreset':
          if (args.PresetName !== 'FactoryDefaults') {throw new DlnaFault(701, 'Invalid PresetName');}
          volume = 100;
          muted = false;
          break;
        case 'SetVolume':
          if (!/^(100|\d{1,2})$/.test(args.DesiredVolume)) {throw new DlnaFault(601, 'Argument Value Out of Range');}
          volume = Number(args.DesiredVolume);
          break;
        case 'SetMute':
          if (!['0', '1', 'true', 'false'].includes(args.DesiredMute)) {throw new DlnaFault(600, 'Argument Value Invalid');}
          muted = args.DesiredMute === '1' || args.DesiredMute === 'true';
          break;
      }
      setOutputVolume();
      return {};
    }

    const state = transportState();
    const duration = formatDlnaTime(active && uri?.kind === 'player' ? player?.duration ?? 0 : 0);
    const position = formatDlnaTime(snapshot?.seconds ?? 0);
    switch (action) {
      case 'SetAVTransportURI': {
        if (!args.CurrentURI) {
          endMedia();
          uri = null;
          metadata = '';
          return {};
        }
        let request;
        try {request = playRequest({url: args.CurrentURI.trim(), container: 'video/mp4'});}
        catch {throw new DlnaFault(714, 'Illegal MIME-type or URI');}
        // Match senders that only set the URI and poll the transport.
        startMedia(isDlnaPhoto(request.url, args.CurrentURIMetaData || '')
          ? {kind: 'photo', url: request.url}
          : {kind: 'player', ...request});
        metadata = args.CurrentURIMetaData || '';
        break;
      }
      case 'Play':
        if (args.Speed !== '1') {throw new DlnaFault(717, 'Play speed not supported');}
        if (!uri) {throw new DlnaFault(702, 'No Contents');}
        if (state === 'STOPPED') {startMedia(uri);}
        else if (state === 'PAUSED_PLAYBACK') {player?.play().catch(() => endMedia());}
        break;
      case 'Pause':
        if (uri?.kind !== 'player' || (state !== 'PLAYING' && state !== 'PAUSED_PLAYBACK')) {throw new DlnaFault(701, 'Transition not available');}
        player?.pause();
        break;
      case 'Stop':
        endMedia();
        break;
      case 'Seek':
        if (uri?.kind !== 'player' || !active || !player || !Number.isFinite(player.duration) || player.duration <= 0) {
          throw new DlnaFault(701, 'Transition not available');
        }
        if (!['REL_TIME', 'ABS_TIME'].includes(args.Unit)) {throw new DlnaFault(710, 'Seek mode not supported');}
        player.currentTime = Math.min(player.duration, parseDlnaTime(args.Target));
        break;
      case 'GetPositionInfo':
        return {Track: uri ? '1' : '0', TrackDuration: duration, TrackMetaData: metadata,
          TrackURI: uri?.url || '', RelTime: position, AbsTime: position, RelCount: '2147483647', AbsCount: '2147483647'};
      case 'GetMediaInfo':
        return {NrTracks: uri ? '1' : '0', MediaDuration: duration, CurrentURI: uri?.url || '', CurrentURIMetaData: metadata,
          NextURI: '', NextURIMetaData: '', PlayMedium: uri ? 'NETWORK' : 'NONE', RecordMedium: 'NOT_IMPLEMENTED', WriteStatus: 'NOT_IMPLEMENTED'};
      case 'GetTransportInfo':
        return {CurrentTransportState: state, CurrentTransportStatus: snapshot?.state === 'error' ? 'ERROR_OCCURRED' : 'OK',
          CurrentSpeed: '1'};
      case 'GetCurrentTransportActions':
        if (uri?.kind === 'photo') {return {Actions: state === 'STOPPED' ? 'Play' : 'Stop'};}
        return {Actions: state === 'PLAYING' ? 'Pause,Stop' + (duration !== '00:00:00' ? ',Seek' : '') :
          state === 'PAUSED_PLAYBACK' ? 'Play,Stop' + (duration !== '00:00:00' ? ',Seek' : '') :
          state === 'TRANSITIONING' ? 'Stop' : uri ? 'Play' : ''};
    }
    return {};
  }

  const deadline = setTimeout(fail, 20000);
  const poll = setInterval(() => {
    if (!serving) {return;}
    try {
      const packet = Network.pollDlnaRequest();
      if (!packet) {return;}
      const request = JSON.parse(packet) as DlnaRequest & {id: number};
      const result = routeDlna(request, uuid, name, dispatch);
      Network.respondDlna(request.id, result.status, result.body);
    } catch {fail();}
  }, 40);
  session = createPlaybackSession(ready => {
    if (closed) {return;}
    player = ready;
    try {
      const encoded = Network.startDlna();
      serving = true;
      const info = JSON.parse(encoded) as {port: number; uuid: string; name: string};
      uuid = info.uuid;
      name = info.name;
      clearTimeout(deadline);
      onListening(info.port);
    } catch {fail();}
  }, state => {
    if (closed) {return;}
    if (!serving && state.state === 'error') {fail(); return;}
    if (active && uri?.kind === 'player') {snapshot = state; onChange(state);}
  }, null);

  return {
    attachSurface(current: number, handle: string) {
      if (current !== token || !active) {return;}
      surface = handle;
      session?.attachSurface(handle);
    },
    detachSurface(current: number, handle: string) {
      if (current !== token || handle !== surface) {return;}
      surface = undefined;
      session?.detachSurface(handle);
    },
    togglePause() {if (active && uri?.kind === 'player') {session?.togglePause();}},
    photoDisplayed,
    stop,
  };
}
