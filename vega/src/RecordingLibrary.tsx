import React, {useEffect, useState} from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import Network from './turbo-modules/BuzinNetwork';

type Entry = {name: string; bytes: number};

export default function RecordingLibrary({onBack, onPlay}: {
  onBack: () => void;
  onPlay: (title: string, url: string) => void;
}) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [message, setMessage] = useState('');
  const [focused, setFocused] = useState('back');
  const [confirm, setConfirm] = useState('');
  function refresh() {
    try {
      const parsed: Entry[] = JSON.parse(Network.listMirrorRecordings());
      if (!Array.isArray(parsed)) {throw new Error('Invalid recording library');}
      setEntries(parsed);
      setMessage('');
    } catch {setMessage('Não foi possível consultar a Biblioteca.');}
  }
  useEffect(() => {refresh();}, []);

  function button(id: string, label: string, action: () => void, primary = false) {
    return (
      <TvButton key={id} role="button" hasTVPreferredFocus={primary}
        onFocus={() => setFocused(id)} onBlur={() => setFocused(value => value === id ? '' : value)}
        onPress={action} style={[styles.button, focused === id && styles.focused]}>
        <Text style={styles.label}>{label}</Text>
      </TvButton>
    );
  }
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Biblioteca de gravações</Text>
      {!!message && <Text style={styles.details} aria-live="polite">{message}</Text>}
      {!entries.length && !message && <Text style={styles.details}>Nenhuma gravação salva.</Text>}
      <ScrollView style={styles.list} contentContainerStyle={styles.rows}>
        {entries.map(({name, bytes}) => (
          <View key={name} style={styles.row}>
            <Text style={styles.details}>{name} · {(bytes / 1024 / 1024).toFixed(1)} MiB</Text>
            <View style={styles.actions}>
              {confirm === name ? (
                <>
                  {button(name + 'cancel', 'Cancelar', () => setConfirm(''), true)}
                  {button(name + 'delete', 'Confirmar exclusão', () => {
                    setConfirm('');
                    try {Network.removeMirrorRecording(name); refresh();}
                    catch {setMessage('Não foi possível excluir a gravação.');}
                  })}
                </>
              ) : (
                <>
                  {button(name + 'play', 'Reproduzir', () => {
                    try {onPlay(name, Network.getMirrorRecordingUrl(name));}
                    catch {setMessage('Não foi possível abrir a gravação.');}
                  }, !confirm && name === entries[0].name)}
                  {button(name + 'remove', 'Excluir', () => setConfirm(name))}
                </>
              )}
            </View>
          </View>
        ))}
      </ScrollView>
      <View style={styles.actions}>
        {button('refresh', 'Atualizar', refresh)}
        {button('back', 'Voltar', onBack, !entries.length)}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, padding: 24, alignItems: 'center', backgroundColor: '#081323'},
  title: {color: 'white', fontSize: 28, marginBottom: 12},
  details: {color: '#B7C9DD', fontSize: 20},
  list: {width: '90%', flex: 1},
  rows: {gap: 12},
  row: {padding: 12, gap: 8, backgroundColor: '#11253A', borderRadius: 8},
  actions: {flexDirection: 'row', gap: 12, flexWrap: 'wrap'},
  button: {backgroundColor: '#18334F', borderRadius: 12, borderWidth: 3, borderColor: 'transparent', paddingHorizontal: 24, paddingVertical: 10},
  focused: {backgroundColor: '#245578', borderColor: '#69D8FF'},
  label: {color: 'white', fontSize: 22},
});
