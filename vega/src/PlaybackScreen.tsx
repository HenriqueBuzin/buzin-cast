/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system surface selected by the SDK resolver. */
import React, {useEffect, useRef, useState} from 'react';
import {AppState, Pressable, StyleSheet, Text, View} from 'react-native';
import {KeplerVideoSurfaceView} from '@amazon-devices/react-native-w3cmedia';
import {createPlaybackSession, PlaybackSnapshot} from './playbackSession';

export default function PlaybackScreen({onBack}: {onBack: () => void}) {
  const session = useRef<ReturnType<typeof createPlaybackSession> | null>(null);
  const [ready, setReady] = useState(false);
  const [focused, setFocused] = useState('pause');
  const [snapshot, setSnapshot] = useState<PlaybackSnapshot>({
    state: 'loading',
    seconds: 0,
  });
  useEffect(() => {
    const active = createPlaybackSession(() => setReady(true), setSnapshot);
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
  }, [onBack]);
  const labels = {
    loading: 'Preparando amostra local…',
    playing: 'Reproduzindo',
    paused: 'Pausado',
    ended: 'Amostra concluída',
    error: 'Não foi possível reproduzir a amostra neste dispositivo.',
  };
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Teste de reprodução local</Text>
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
      </View>
      <Text style={styles.status}>
        {labels[snapshot.state]} • {snapshot.seconds.toFixed(1)} s
      </Text>
      <View style={styles.actions}>
        {['pause', 'back'].map((id) => (
          <Pressable
            key={id}
            role="button"
            accessibilityHint={
              id === 'back'
                ? 'Encerra o teste e retorna ao início'
                : 'Pausa ou retoma a amostra local'
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
          </Pressable>
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
