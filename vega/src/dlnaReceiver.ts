/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system player. */
import type {VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import Network from './turbo-modules/BuzinNetwork';
import {createPlaybackSession, PlaybackRequest, PlaybackSnapshot} from './playbackSession';
import {dlnaConnectionInfo, DlnaFault, DlnaRequest, DlnaService, formatDlnaTime, parseDlnaTime, routeDlna} from './dlnaProtocol';
import {playRequest} from './fcastProtocol';

export function createDlnaReceiver(
  onReady: (token: number | null) => void,
  onChange: (snapshot: PlaybackSnapshot | null) => void,
  onFailure: () => void,
  onListening: (port: number) => void,
) {
  let player: VideoPlayer | undefined;
  let session: ReturnType<typeof createPlaybackSession> | undefined;
  let active = false;
  let closed = false;
  let serving = false;
  let token = 0;
  let surface: string | undefined;
  let uri: PlaybackRequest | null = null;
  let metadata = '';
  let snapshot: PlaybackSnapshot | null = null;
  let volume = 100;
  let muted = false;
  let uuid = '';
  let name = '';
  let lastLoad = -Infinity;

  function endMedia() {
    const wasActive = active;
    active = false;
    snapshot = null;
    ++token;
    if (surface) {
      session?.detachSurface(surface);
      surface = undefined;
    }
    if (wasActive) {player?.pause();}
    if (!closed) {onReady(null); onChange(null);}
  }

  function startMedia(request: PlaybackRequest) {
    if (Date.now() - lastLoad < 500) {throw new DlnaFault(705, 'Transport busy');}
    lastLoad = Date.now();
    endMedia();
    active = true;
    snapshot = {seconds: 0, state: 'loading'};
    session?.load(request);
    onReady(token);
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
      session?.stop();
      player = undefined;
    }
  }

  function fail() {
    if (closed) {return;}
    try {stop();} finally {onFailure();}
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
    const duration = formatDlnaTime(active ? player?.duration ?? 0 : 0);
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
        startMedia(request);
        uri = request;
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
        if (state !== 'PLAYING' && state !== 'PAUSED_PLAYBACK') {throw new DlnaFault(701, 'Transition not available');}
        player?.pause();
        break;
      case 'Stop':
        endMedia();
        break;
      case 'Seek':
        if (!active || !player || !Number.isFinite(player.duration) || player.duration <= 0) {
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
    if (active) {snapshot = state; onChange(state);}
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
    togglePause() {if (active) {session?.togglePause();}},
    stop,
  };
}
