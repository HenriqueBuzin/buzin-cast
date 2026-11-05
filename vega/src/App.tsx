import React, {useCallback, useEffect, useState} from 'react';
import Network from './turbo-modules/BuzinNetwork';
import {getMediaReport} from './mediaCapabilities';
import PlaybackScreen from './PlaybackScreen';
import {
  BackHandler,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

export default function App() {
  const [page, setPage] = useState<
    'home' | 'status' | 'about' | 'media' | 'playback'
  >('home');
  const goHome = useCallback(() => setPage('home'), []);
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
        setPage('home');
        return true;
      },
    );
    return () => subscription.remove();
  }, [page]);

  if (page === 'playback') {
    return <PlaybackScreen onBack={goHome} />;
  }

  const action = (
    id: string,
    label: string,
    onPress: () => void,
    preferred: boolean,
  ) => (
    <Pressable
      key={id}
      role="button"
      accessibilityHint={
        id === 'back' ? 'Retorna à tela inicial' : `Abre ${label}`
      }
      hasTVPreferredFocus={preferred}
      onFocus={() => setFocused(id)}
      onBlur={() => setFocused('')}
      onPress={onPress}
      style={[
        styles.button,
        page !== 'home' && styles.compactButton,
        focused === id && styles.focused,
      ]}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
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
      {page === 'home' ? (
        <View style={styles.actions}>
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
        </View>
      ) : (
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
              ? 'AirPlay, DLNA, FCast e Cast V2 ainda não estão disponíveis no Vega. Nenhum receptor é anunciado na rede.'
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
  compactLogo: {width: 160, height: 90, marginBottom: 12},
  compactButton: {paddingVertical: 10},
  actions: {flexDirection: 'row', marginTop: 32, gap: 24},
  button: {
    backgroundColor: '#18334F',
    borderColor: 'transparent',
    borderWidth: 3,
    borderRadius: 12,
    paddingHorizontal: 28,
    paddingVertical: 16,
  },
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
