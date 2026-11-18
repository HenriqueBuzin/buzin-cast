import React, {useEffect, useState} from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import Network from './turbo-modules/BuzinNetwork';
import TvButton from './TvButton';

type Snapshot = {phase: 'waiting' | 'preparing' | 'playing' | 'stopped' | 'error'; seconds: number};
export default function AirPlayAudioScreen({onBack}: {onBack: () => void}) {
  const [port, setPort] = useState(0);
  const [focus, setFocus] = useState(true);
  const [snapshot, setSnapshot] = useState<Snapshot>({phase: 'waiting', seconds: 0});
  useEffect(() => {
    let stopped = false;
    let started = false;
    let poll: ReturnType<typeof setInterval> | undefined;
    const stop = () => {
      if (stopped) {return;}
      stopped = true;
      if (poll) {clearInterval(poll);}
      if (started) {Network.stopAirPlayAudio();}
    };
    try {
      const listeningPort = Network.startAirPlayAudio();
      started = true;
      setPort(listeningPort);
      poll = setInterval(() => {
        try {
          const state = JSON.parse(Network.getAirPlayAudioState()) as Snapshot;
          if (!['waiting', 'preparing', 'playing', 'stopped', 'error'].includes(state.phase) ||
              !Number.isFinite(state.seconds) || state.seconds < 0) {throw new Error('Invalid audio observation');}
          setSnapshot(state);
          if (state.phase === 'error') {stop();}
        } catch {
          setSnapshot({phase: 'error', seconds: 0});
          try {stop();} catch { /* the error remains visible */ }
        }
      }, 250);
    } catch {
      setSnapshot({phase: 'error', seconds: 0});
      try {stop();} catch { /* the error remains visible */ }
    }
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {onBack();}
    });
    return () => {
      subscription.remove();
      try {stop();} catch { /* native error is observable in the sandbox report */ }
    };
  }, [onBack]);
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Áudio AirPlay experimental</Text>
      <Text style={styles.details}>Conexão manual na rede local{port ? ` • TCP ${port}` : ''}.</Text>
      <Text style={styles.details}>ALAC, AAC-LC e AAC-ELD estéreo a 44,1 kHz. Sem descoberta automática, PIN ou vídeo nesta etapa.</Text>
      <Text style={styles.details}>A recepção com emissores Apple reais ainda precisa de validação.</Text>
      <Text style={styles.status} aria-live="polite">
        {snapshot.phase === 'error' ? 'Não foi possível receber este áudio. Confira o formato ou reinicie o app.'
          : snapshot.phase === 'playing' ? `Reproduzindo áudio • ${snapshot.seconds.toFixed(1)} s`
          : snapshot.phase === 'preparing' ? 'Preparando áudio…' : 'Aguardando áudio compatível…'}
      </Text>
      <TvButton role="button" hasTVPreferredFocus accessibilityHint="Desativa o receptor e retorna ao início"
        onPress={onBack} onFocus={() => setFocus(true)} onBlur={() => setFocus(false)}
        style={[styles.button, focus && styles.focused]}>
        <Text style={styles.label}>Desativar e voltar</Text>
      </TvButton>
    </View>
  );
}
const styles = StyleSheet.create({
  screen: {flex: 1, padding: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: '#081323', gap: 24},
  title: {fontSize: 28, color: 'white'},
  details: {fontSize: 20, color: '#B7C9DD', textAlign: 'center', maxWidth: 900},
  status: {fontSize: 24, color: 'white', marginVertical: 24},
  button: {paddingVertical: 12, paddingHorizontal: 28, borderWidth: 3, borderColor: 'transparent', backgroundColor: '#18334F', borderRadius: 12},
  focused: {borderColor: '#69D8FF'}, label: {fontSize: 24, color: 'white'},
});
