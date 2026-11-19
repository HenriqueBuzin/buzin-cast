/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system surface selected by the SDK resolver. */
import TvButton from './TvButton';
import React, {useEffect, useRef, useState} from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import {KeplerVideoSurfaceView} from '@amazon-devices/react-native-w3cmedia';
import {SubtitleButton, SubtitleCaption, SubtitleNotice, usePlaybackSubtitles} from './PlaybackSubtitles';
import {createPlaybackSession, PlaybackSnapshot} from './playbackSession';

export default function PlaybackScreen({onBack, recording}: {
  onBack: () => void;
  recording?: {title: string; url: string};
}) {
  const session = useRef<ReturnType<typeof createPlaybackSession> | null>(null);
  const [ready, setReady] = useState(false);
  const [focused, setFocused] = useState('pause');
  const [snapshot, setSnapshot] = useState<PlaybackSnapshot>({
    state: 'loading',
    seconds: 0,
  });
  const activeMedia = ['playing', 'paused'].includes(snapshot.state);
  const subtitles = usePlaybackSubtitles(activeMedia ? 1 : null);
  useEffect(() => {
    const request = recording
      ? {url: recording.url, headers: {}, position: 0, speed: 1}
      : undefined;
    const active = createPlaybackSession(() => setReady(true), setSnapshot, request);
    session.current = active;
    const listener = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {
        onBack();
      }
    });
    return () => {
      listener.remove();
      session.current = null;
      active.stop();
    };
  }, [onBack, recording]);
  const labels = {
    loading: recording ? 'Preparando gravação…' : 'Preparando amostra local…',
    playing: 'Reproduzindo',
    paused: 'Pausado',
    ended: recording ? 'Gravação concluída' : 'Amostra concluída',
    error: 'Não foi possível reproduzir o vídeo neste dispositivo.',
  };
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>{recording?.title ?? 'Teste de reprodução local'}</Text>
      <View style={styles.video}>
        {ready && (
          <KeplerVideoSurfaceView
            style={StyleSheet.absoluteFill}
            scalingmode="fit"
            onSurfaceViewCreated={(handle: string) =>
              session.current?.attachSurface(handle)
            }
            onSurfaceViewDestroyed={(handle: string) =>
              session.current?.detachSurface(handle)
            }
          />
        )}
        {activeMedia && <SubtitleCaption text={subtitles.caption(snapshot.seconds)} />}
      </View>
      <Text style={styles.status}>
        {labels[snapshot.state]} • {snapshot.seconds.toFixed(1)} s
      </Text>
      {activeMedia && <SubtitleNotice link={subtitles.link} message={subtitles.message} />}
      <View style={styles.actions}>
        {activeMedia && <SubtitleButton label={subtitles.label} onPress={subtitles.select} />}
        {['pause', 'back'].map((id) => (
          <TvButton
            key={id}
            role="button"
            accessibilityHint={
              id === 'back'
                ? 'Encerra a reprodução e volta à tela anterior'
                : 'Pausa ou retoma o vídeo'
            }
            hasTVPreferredFocus={id === 'pause'}
            onFocus={() => setFocused(id)}
            onBlur={() => setFocused('')}
            onPress={
              id === 'back' ? onBack : () => session.current?.togglePause()
            }
            style={[styles.button, focused === id && styles.focused]}>
            <Text style={styles.label}>
              {id === 'back'
                ? 'Voltar'
                : snapshot.state === 'paused'
                ? 'Retomar'
                : 'Pausar'}
            </Text>
          </TvButton>
        ))}
      </View>
    </View>
  );
}
const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#081323',
    padding: 24,
    alignItems: 'center',
  },
  title: {color: 'white', fontSize: 26, marginBottom: 12},
  video: {width: '80%', flex: 1, backgroundColor: 'black'},
  status: {color: '#B7C9DD', fontSize: 20, marginVertical: 12},
  actions: {flexDirection: 'row', gap: 24},
  button: {
    paddingVertical: 10,
    paddingHorizontal: 28,
    borderWidth: 3,
    borderColor: 'transparent',
    borderRadius: 12,
    backgroundColor: '#18334F',
  },
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
  label: {color: 'white', fontSize: 24},
});
