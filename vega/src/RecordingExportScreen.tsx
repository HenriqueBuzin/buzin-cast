import React, {useEffect, useState} from 'react';
import {AppState, StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import Network from './turbo-modules/BuzinNetwork';

const status: Record<string, string> = {
  ready: 'Abra o endereço no navegador de um aparelho na mesma rede.',
  sending: 'Enviando gravação…',
  sent: 'Envio concluído.',
  expired: 'O endereço expirou. Abra a exportação novamente.',
  error: 'Não foi possível exportar a gravação.',
  closed: 'Exportação encerrada.',
};

export default function RecordingExportScreen({name, onBack}: {name: string; onBack: () => void}) {
  const [link, setLink] = useState('');
  const [phase, setPhase] = useState('ready');
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined;
    let opened = false;
    function close() {
      if (timer !== undefined) {
        clearInterval(timer);
        timer = undefined;
      }
      if (opened) {
        opened = false;
        Network.stopRecordingExport();
      }
    }
    try {
      setLink(Network.startRecordingExport(name));
      opened = true;
      timer = setInterval(() => {
        try {
          const snapshot = Network.getRecordingExportState();
          setPhase(snapshot);
          if (snapshot !== 'ready' && snapshot !== 'sending') {close();}
        } catch {
          close();
          setPhase('error');
        }
      }, 200);
    } catch {setPhase('error');}
    const listener = AppState.addEventListener('change', state => {
      if (state !== 'active' && opened) {
        close();
        setPhase('closed');
      }
    });
    return () => {listener.remove(); close();};
  }, [name]);

  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Exportar gravação</Text>
      <Text style={styles.details}>{name}</Text>
      <Text aria-live="polite" style={styles.details}>{status[phase]}</Text>
      {phase === 'ready' && !!link && <Text selectable style={styles.link}>{link}</Text>}
      <TvButton role="button" hasTVPreferredFocus onPress={onBack} style={styles.button}>
        <Text style={styles.label}>{phase === 'ready' || phase === 'sending' ? 'Cancelar exportação' : 'Voltar à biblioteca'}</Text>
      </TvButton>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, padding: 24, alignItems: 'center', justifyContent: 'center', gap: 16, backgroundColor: '#081323'},
  title: {color: 'white', fontSize: 28},
  details: {color: '#B7C9DD', fontSize: 20, textAlign: 'center'},
  link: {color: 'white', fontSize: 26},
  button: {backgroundColor: '#245578', borderRadius: 12, borderWidth: 3, borderColor: '#69D8FF', paddingHorizontal: 24, paddingVertical: 10},
  label: {color: 'white', fontSize: 22},
});
