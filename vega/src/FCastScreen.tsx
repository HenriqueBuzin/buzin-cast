/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system video surface. */
import TvButton from './TvButton';
import React, {useEffect, useRef, useState} from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import {KeplerVideoSurfaceView} from '@amazon-devices/react-native-w3cmedia';
import {createFCastReceiver} from './fcastReceiver';
import {createAirPlayReceiver} from './airplayReceiver';
import type {PlaybackSnapshot} from './playbackSession';

export default function FCastScreen({onBack, protocol = 'fcast'}: {onBack: () => void; protocol?: 'fcast' | 'airplay'}) {
  const receiver = useRef<ReturnType<typeof createFCastReceiver> | null>(null);
  const [port, setPort] = useState(46899);
  const [token, setToken] = useState<number | null>(null);
  const [snapshot, setSnapshot] = useState<PlaybackSnapshot | null>(null);
  const [listening, setListening] = useState(false);
  const [failed, setFailed] = useState(false);
  const [focus, setFocus] = useState('back');
  useEffect(() => {
    try {
      receiver.current = (protocol === 'fcast' ? createFCastReceiver : createAirPlayReceiver)(
        setToken,
        setSnapshot,
        () => setFailed(true),
        (activePort?: number) => {setPort(activePort || 46899); setListening(true);},
      );
    } catch {
      setFailed(true);
    }
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {
        onBack();
      }
    });
    return () => {
      subscription.remove();
      receiver.current?.stop();
      receiver.current = null;
    };
  }, [onBack, protocol]);
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>{protocol === 'fcast' ? 'FCast v2 experimental' : 'AirPlay por URL experimental'}</Text>
      {token === null ? (
        <View style={styles.waiting}>
          <Text style={styles.details}>
            {failed
              ? 'Não foi possível iniciar o receptor.'
              : listening
              ? `Aguardando mídia na porta TCP ${port}.`
              : 'Preparando reprodução…'}
          </Text>
          <Text style={styles.details}>
            {protocol === 'fcast'
              ? 'Conexão manual pela rede local, sem PIN ou TLS. Descoberta FCast v2 por DNS-SD. Áudio, vídeo e HLS/DASH experimentais.'
              : 'Somente URLs por conexão manual na rede local. Sem descoberta, PIN ou espelhamento de tela/áudio nesta etapa.'}
          </Text>
          <Text style={styles.details}>Voltar desativa este receptor.</Text>
        </View>
      ) : (
        <View style={styles.video}>
          <KeplerVideoSurfaceView
            key={token}
            style={StyleSheet.absoluteFill}
            scalingmode="fit"
            onSurfaceViewCreated={(handle: string) =>
              receiver.current?.attachSurface(token, handle)
            }
            onSurfaceViewDestroyed={(handle: string) =>
              receiver.current?.detachSurface(token, handle)
            }
          />
        </View>
      )}
      <Text style={styles.details}>
        {snapshot
          ? `${
              snapshot.state === 'error'
                ? 'Falha de reprodução'
                : snapshot.state === 'paused'
                ? 'Pausado'
                : snapshot.state === 'ended'
                ? 'Concluído'
                : 'Reproduzindo'
            } • ${snapshot.seconds.toFixed(1)} s`
          : ''}
      </Text>
      <View style={styles.actions}>
        {(token === null ? ['back'] : ['pause', 'back']).map((id) => (
          <TvButton
            key={id}
            role="button"
            accessibilityHint={
              id === 'back'
                ? 'Desativa o receptor e retorna ao início'
                : 'Pausa ou retoma a transmissão'
            }
            hasTVPreferredFocus={id === 'back'}
            onFocus={() => setFocus(id)}
            onBlur={() => setFocus('')}
            onPress={
              id === 'back' ? onBack : () => receiver.current?.togglePause()
            }
            style={[styles.button, focus === id && styles.focused]}>
            <Text style={styles.label}>
              {id === 'back'
                ? 'Desativar e voltar'
                : snapshot?.state === 'paused'
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
  waiting: {flex: 1, maxWidth: 820, justifyContent: 'center', gap: 20},
  video: {width: '80%', flex: 1, backgroundColor: 'black'},
  details: {
    color: '#B7C9DD',
    fontSize: 20,
    textAlign: 'center',
    marginVertical: 10,
  },
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
