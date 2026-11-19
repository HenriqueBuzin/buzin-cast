import TvButton from './TvButton';
import React, {useCallback, useEffect, useState} from 'react';
import Network from './turbo-modules/BuzinNetwork';
import {getMediaReport} from './mediaCapabilities';
import PlaybackScreen from './PlaybackScreen';
import RecordingLibrary from './RecordingLibrary';
import FCastScreen from './FCastScreen';
import SettingsScreen from './SettingsScreen';
import AirPlayAudioScreen from './AirPlayAudioScreen';
import {
  BackHandler,
  Image,
  StyleSheet,
  Text,
  View,
} from 'react-native';

export default function App() {
  const [page, setPage] = useState<
    'home' | 'status' | 'about' | 'media' | 'playback' | 'fcast' | 'settings' | 'airplay' | 'airplayAudio' | 'airplayVideo' | 'dlna' | 'castv2' | 'recordings' | 'recording' | 'relay'
  >('home');
  const goHome = useCallback(() => setPage('home'), []);
  const goLibrary = useCallback(() => setPage('recordings'), []);
  const goMedia = useCallback(() => setPage('media'), []);
  const [recording, setRecording] = useState<{title: string; url: string}>();
  const [selected, setSelected] = useState('status');
  const [focused, setFocused] = useState('status');
  const [report, setReport] = useState('');
  const [mediaReport, setMediaReport] = useState('');

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        if (page === 'home') {
          return false;
        }
        setPage(page === 'recording' ? 'recordings' : page === 'recordings' ? 'media' : 'home');
        return true;
      },
    );
    return () => subscription.remove();
  }, [page]);

  if (page === 'airplayAudio') {
    return <AirPlayAudioScreen onBack={goHome} />;
  }

  if (page === 'airplay') {
    return <FCastScreen onBack={goHome} protocol="airplay" />;
  }
  if (page === 'airplayVideo') {
    return <FCastScreen onBack={goHome} protocol="mirror" />;
  }

  if (page === 'fcast') {
    return <FCastScreen onBack={goHome} />;
  }
  if (page === 'relay') {
    return <FCastScreen onBack={goHome} protocol="relay" />;
  }
  if (page === 'castv2') {
    return <FCastScreen onBack={goHome} protocol="castv2" />;
  }
  if (page === 'dlna') {
    return <FCastScreen onBack={goHome} protocol="dlna" />;
  }

  if (page === 'recordings') {
    return <RecordingLibrary onBack={goMedia} onPlay={(title, url) => {
      setRecording({title, url});
      setPage('recording');
    }} />;
  }
  if (page === 'recording') {
    return <PlaybackScreen onBack={goLibrary} recording={recording} />;
  }
  if (page === 'playback') {
    return <PlaybackScreen onBack={goHome} />;
  }

  const action = (
    id: string,
    label: string,
    onPress: () => void,
    preferred: boolean,
  ) => (
    <TvButton
      key={id}
      role="button"
      accessibilityHint={
        id === 'back' ? 'Retorna à tela inicial' : `Abre ${label}`
      }
      accessibilityState={['status', 'media', 'about', 'fcast', 'settings'].includes(id) ? {selected: selected === id} : undefined}
      hasTVPreferredFocus={page === 'home' ? selected === id : preferred}
      onFocus={() => setFocused(id)}
      onBlur={() => setFocused((current) => current === id ? '' : current)}
      onPress={() => {
        if (['status', 'media', 'about', 'fcast', 'settings'].includes(id)) {setSelected(id);}
        onPress();
      }}
      style={[
        styles.button,
        page !== 'home' && styles.compactButton,
        selected === id && styles.selected,
        focused === id && styles.focused,
      ]}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </TvButton>
  );

  return (
    <View style={[styles.screen, page !== 'home' && styles.compactScreen]}>
      <Image
        accessible
        aria-label="Buzin Cast"
        source={require('./assets/buzin_banner.png')}
        resizeMode="contain"
        style={page === 'home' ? styles.logo : styles.compactLogo}
      />
      {page === 'home' && (
        <>
          <Text style={styles.title}>Prévia para Vega OS</Text>
          <Text style={styles.description}>Receptor em desenvolvimento</Text>
        </>
      )}
      <View key={page === 'home' ? 'home-menu' : 'page-menu'} style={styles.actions} aria-label="Menu principal">
          {action('status', 'Estado do port', () => setPage('status'), true)}
          {action(
            'media',
            'Mídia',
            () => {
              setPage('media');
              try {
                const result = JSON.parse(getMediaReport()) as Record<
                  string,
                  unknown
                >;
                const fields = [
                  ['H.264', 'h264Decoders'],
                  ['HEVC', 'hevcDecoders'],
                  ['AAC', 'aacDecoders'],
                ];
                if (
                  fields.some(
                    ([, key]) =>
                      !Number.isInteger(result[key]) || Number(result[key]) < 0,
                  )
                ) {
                  throw new Error('Invalid media capabilities');
                }
                setMediaReport(
                  fields
                    .map(
                      ([name, key]) =>
                        `${name}: ${result[key]} resultado(s) informado(s)`,
                    )
                    .join('\n'),
                );
              } catch {
                setMediaReport(
                  'Não foi possível consultar os codecs neste dispositivo.',
                );
              }
            },
            false,
          )}
          {action('about', 'Sobre', () => setPage('about'), false)}
          {action('fcast', 'Ativar FCast', () => setPage('fcast'), false)}
          {action('settings', 'Configurações', () => setPage('settings'), false)}
      </View>
      {page === 'settings' ? (
        <View style={styles.settings}><SettingsScreen onBack={goHome} /></View>
      ) : page !== 'home' && (
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>
            {page === 'status'
              ? 'Estado do port'
              : page === 'media'
              ? 'Capacidades de mídia'
              : 'Sobre'}
          </Text>
          <Text style={styles.details}>
            {page === 'status'
              ? 'FCast v2, DLNA e espelhamento AirPlay experimental têm descoberta na rede local. Cast V2 experimental também anuncia na rede. O teste AirPlay por URL usa conexão manual.'
              : page === 'media'
              ? 'Resultados da consulta ao Vega. Zero pode indicar ausência de suporte ou falha da consulta. Use a amostra para testar a reprodução.'
              : 'Buzin Cast 0.6.0 • Vega OS 2.0 • Henrique Buzin. Validação de recepção em aparelho físico pendente.'}
          </Text>
          {page === 'status' &&
            action(
              'network',
              'Testar rede local',
              () => {
                try {
                  const result = JSON.parse(Network.getReport()) as Record<
                    string,
                    unknown
                  >;
                  const keys = [
                    'tcpLoopback',
                    'udpLoopback',
                    'wildcardBind',
                    'multicastMembership',
                    'rtspParser',
                    'malformedRtspRejected',
                    'aesCtrVector',
                  ];
                  if (keys.some((key) => typeof result[key] !== 'boolean')) {
                    throw new Error('Invalid network diagnostic');
                  }
                  const state = (key: string) =>
                    result[key] ? 'OK' : 'Falhou';
                  setReport(
                    `TCP local: ${state('tcpLoopback')} • UDP local: ${state(
                      'udpLoopback',
                    )}\nBind de rede: ${state(
                      'wildcardBind',
                    )} • Multicast: ${state(
                      'multicastMembership',
                    )}\nParser RTSP: ${
                      result.rtspParser && result.malformedRtspRejected
                        ? 'OK'
                        : 'Falhou'
                    } • AES-CTR: ${state('aesCtrVector')}`,
                  );
                } catch {
                  setReport('Falha no diagnóstico nativo. Tente novamente.');
                }
              },
              true,
            )}
          {page === 'status' && <Text style={styles.details}>{report}</Text>}
          {page === 'status' && action('airplay', 'Testar AirPlay por URL', () => setPage('airplay'), false)}
          {page === 'status' && action('airplayAudio', 'Testar áudio AirPlay', () => setPage('airplayAudio'), false)}
          {page === 'status' && action('airplayVideo', 'Testar vídeo AirPlay', () => setPage('airplayVideo'), false)}
          {page === 'status' && action('dlna', 'Ativar DLNA', () => setPage('dlna'), false)}
          {page === 'status' && action('castv2', 'Testar Cast V2', () => setPage('castv2'), false)}
          {page === 'status' && action('relay', 'Receber retransmissão Buzin', () => setPage('relay'), false)}
          {page === 'media' && (
            <Text style={styles.details}>{mediaReport}</Text>
          )}
          {page === 'media' &&
            action(
              'playback',
              'Testar reprodução',
              () => setPage('playback'),
              true,
            )}
          {page === 'media' && action('recordings', 'Biblioteca de gravações', goLibrary, false)}
          {action('back', 'Voltar', goHome, page === 'about')}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#081323',
    padding: 48,
  },
  compactScreen: {padding: 24},
  settings: {flex: 1, width: '100%'},
  compactLogo: {width: 160, height: 90, marginBottom: 12},
  compactButton: {paddingVertical: 10},
  actions: {flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', marginTop: 32, gap: 24},
  button: {
    backgroundColor: '#18334F',
    borderColor: 'transparent',
    borderWidth: 3,
    borderRadius: 12,
    paddingHorizontal: 28,
    paddingVertical: 16,
  },
  selected: {borderColor: '#69D8FF'},
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
  buttonLabel: {color: '#FFFFFF', fontSize: 24},
  panel: {marginTop: 16, maxWidth: 820, alignItems: 'center', gap: 12},
  panelTitle: {color: '#FFFFFF', fontSize: 28},
  details: {
    color: '#B7C9DD',
    fontSize: 20,
    lineHeight: 26,
    textAlign: 'center',
  },
  logo: {width: 320, height: 180, marginBottom: 24},
  title: {color: '#FFFFFF', fontSize: 32, fontWeight: '600'},
  description: {color: '#B7C9DD', fontSize: 22, marginTop: 12},
});
