import React, {useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import Network from './turbo-modules/BuzinNetwork';
import TvButton from './TvButton';

const notices: Record<string, string> = {
  waiting: 'Aguardando o próximo quadro-chave.',
  recording: 'Gravando espelhamento AirPlay.',
  saving: 'Salvando gravação…',
  saved: 'Gravação salva na Biblioteca.',
  empty: 'Nenhum quadro-chave foi recebido.',
  space: 'Espaço insuficiente para iniciar a gravação.',
  limited: 'Gravação salva até o limite de armazenamento.',
  error: 'Não foi possível salvar a gravação.',
};

export default function RecordingButton({disabled}: {disabled: boolean}) {
  const [phase, setPhase] = useState('idle');
  const [focused, setFocused] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const timer = setInterval(() => {
      try {setPhase(Network.getMirrorRecordingState());}
      catch {setFailed(true);}
    }, 300);
    return () => clearInterval(timer);
  }, []);

  const active = phase === 'waiting' || phase === 'recording';
  return (
    <View style={styles.panel}>
      <TvButton role="button" disabled={disabled || phase === 'saving'} onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)} style={[styles.button, focused && styles.focused]}
        onPress={() => {
          try {
            if (active) {Network.stopMirrorRecording();}
            else {Network.startMirrorRecording();}
            setPhase(Network.getMirrorRecordingState());
            setFailed(false);
          } catch {setFailed(true);}
        }}>
        <Text style={styles.label}>{active ? 'Parar gravação' : 'Gravar espelhamento'}</Text>
      </TvButton>
      {(failed || !!notices[phase]) && (
        <Text style={styles.message} aria-live="polite">
          {failed ? 'A gravação está indisponível.' : notices[phase]}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {alignItems: 'center'},
  button: {backgroundColor: '#18334F', borderWidth: 3, borderColor: 'transparent', borderRadius: 12, paddingHorizontal: 28, paddingVertical: 10},
  focused: {backgroundColor: '#245578', borderColor: '#69D8FF'},
  label: {color: 'white', fontSize: 24},
  message: {color: '#B7C9DD', fontSize: 18, paddingTop: 4},
});
