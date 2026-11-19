/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS 2.0 system video surface. */
import TvButton from './TvButton';
import React, {useEffect, useRef, useState} from 'react';
import {AppState, Image, StyleSheet, Text, View} from 'react-native';
import {KeplerVideoSurfaceView} from '@amazon-devices/react-native-w3cmedia';
import {createFCastReceiver} from './fcastReceiver';
import {createAirPlayReceiver} from './airplayReceiver';
import {createDlnaReceiver, DlnaPhotoView} from './dlnaReceiver';
import type {ConnectionApprovalRequest} from './connectionApproval';
import type {PlaybackSnapshot} from './playbackSession';

const titles = {
  fcast: 'FCast v2 experimental',
  airplay: 'AirPlay por URL experimental',
  mirror: 'Vídeo AirPlay experimental',
  dlna: 'DLNA experimental',
};

export default function FCastScreen({onBack, protocol = 'fcast'}: {onBack: () => void; protocol?: keyof typeof titles}) {
  const receiver = useRef<(Pick<ReturnType<typeof createFCastReceiver>, 'attachSurface' | 'detachSurface' | 'togglePause' | 'stop'> &
    Partial<Pick<ReturnType<typeof createDlnaReceiver>, 'photoDisplayed' | 'answerApproval'>>) | null>(null);
  const [port, setPort] = useState(46899);
  const [token, setToken] = useState<number | null>(null);
  const [snapshot, setSnapshot] = useState<PlaybackSnapshot | null>(null);
  const [photo, setPhoto] = useState<DlnaPhotoView | null>(null);
  const [approval, setApproval] = useState<ConnectionApprovalRequest | null>(null);
  const [listening, setListening] = useState(false);
  const [failed, setFailed] = useState(false);
  const [pin, setPin] = useState('');
  const [focus, setFocus] = useState('back');
  useEffect(() => {
    try {
      const onFailure = () => setFailed(true);
      const onListening = (activePort?: number) => {setPort(activePort || 46899); setListening(true);};
      receiver.current = protocol === 'fcast'
        ? createFCastReceiver(setToken, setSnapshot, onFailure, onListening, setApproval)
        : protocol === 'dlna'
        ? createDlnaReceiver(setToken, setSnapshot, onFailure, onListening, setPhoto, setApproval)
        : createAirPlayReceiver(setToken, setSnapshot, onFailure, onListening, protocol === 'mirror' ? 'mirror' : 'url', setPin);
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
      <Text style={styles.title}>{titles[protocol]}</Text>
      {photo ? (
        <View style={styles.video}>
          {photo.source && (
            <Image
              key={photo.token}
              style={styles.photo}
              source={photo.source}
              resizeMode="contain"
              aria-label="Foto recebida pelo DLNA"
              onLoad={() => receiver.current?.photoDisplayed?.(photo.token, true)}
              onError={() => receiver.current?.photoDisplayed?.(photo.token, false)}
            />
          )}
        </View>
      ) : token === null ? (
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
              : protocol === 'mirror'
              ? 'Selecione o receptor no emissor e digite o PIN quando solicitado.'
              : protocol === 'dlna'
              ? 'Selecione o receptor DLNA no emissor conectado à mesma rede.'
              : 'Somente URLs por conexão manual na rede local. Sem descoberta, PIN ou espelhamento de tela/áudio nesta etapa.'}
          </Text>
          {protocol === 'mirror' && listening && !failed && pin !== '' && (
            <Text style={styles.pin}>PIN {pin}</Text>
          )}
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
        {photo
          ? snapshot?.state === 'error' ? 'Não foi possível exibir a foto.'
            : snapshot?.state === 'loading' ? 'Carregando foto…' : ''
          : snapshot
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
      {approval && (
        <View style={styles.approval}>
          <Text style={styles.details} aria-live="polite">
            {protocol === 'fcast'
              ? `Permitir a conexão FCast de ${approval.peer}?`
              : `Permitir que ${approval.peer} controle o DLNA por cinco minutos?`}
          </Text>
          <View style={styles.actions}>
            {[false, true].map(allow => {
              const id = allow ? 'allow' : 'deny';
              return (
                <TvButton
                  key={id}
                  role="button"
                  hasTVPreferredFocus={!allow}
                  onFocus={() => setFocus(id)}
                  onBlur={() => setFocus(current => current === id ? '' : current)}
                  style={[styles.button, focus === id && styles.focused]}
                  onPress={() => receiver.current?.answerApproval?.(approval.id, allow)}>
                  <Text style={styles.label}>{allow ? 'Permitir' : 'Recusar'}</Text>
                </TvButton>
              );
            })}
          </View>
        </View>
      )}
      <View style={styles.actions}>
        {(token === null || protocol === 'mirror' ? ['back'] : ['pause', 'back']).map((id) => (
          <TvButton
            key={id}
            role="button"
            accessibilityHint={
              id === 'back'
                ? 'Desativa o receptor e retorna ao início'
                : 'Pausa ou retoma a transmissão'
            }
            hasTVPreferredFocus={id === 'back' && !approval}
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
  pin: {color: 'white', fontSize: 36, fontWeight: 'bold'},
  waiting: {flex: 1, maxWidth: 820, justifyContent: 'center', gap: 20},
  video: {width: '80%', flex: 1, backgroundColor: 'black'},
  photo: {width: '100%', height: '100%'},
  details: {
    color: '#B7C9DD',
    fontSize: 20,
    textAlign: 'center',
    marginVertical: 10,
  },
  actions: {flexDirection: 'row', gap: 24},
  approval: {alignItems: 'center', marginBottom: 16},
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
