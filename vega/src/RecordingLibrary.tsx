import React, {useCallback, useEffect, useState} from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import RecordingExportScreen from './RecordingExportScreen';
import Network from './turbo-modules/BuzinNetwork';
import DownloadButton from './DownloadButton';

type Entry = {name: string; bytes: number};

export default function RecordingLibrary({onBack, onPlay, downloads = false}: {
  onBack: () => void;
  onPlay: (title: string, url: string) => void;
  downloads?: boolean;
}) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [message, setMessage] = useState('');
  const [focused, setFocused] = useState('back');
  const [confirm, setConfirm] = useState('');
  const [exportName, setExportName] = useState('');
  const refresh = useCallback(() => {
    try {
      const parsed: Entry[] = JSON.parse(downloads ? Network.listMediaDownloads() : Network.listMirrorRecordings());
      if (!Array.isArray(parsed)) {throw new Error('Invalid recording library');}
      setEntries(parsed);
      setMessage('');
    } catch {setMessage('Não foi possível consultar a Biblioteca.');}
  }, [downloads]);
  useEffect(() => {refresh();}, [refresh]);

  function button(id: string, label: string, action: () => void, primary = false) {
    return (
      <TvButton key={id} role="button" hasTVPreferredFocus={primary}
        onFocus={() => setFocused(id)} onBlur={() => setFocused(value => value === id ? '' : value)}
        onPress={action} style={[styles.button, focused === id && styles.focused]}>
        <Text style={styles.label}>{label}</Text>
      </TvButton>
    );
  }
  if (exportName) {
    return <RecordingExportScreen name={exportName} download={downloads} onBack={() => setExportName('')} />;
  }
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>{downloads ? 'Biblioteca de downloads' : 'Biblioteca de gravações'}</Text>
      {downloads && <DownloadButton onSaved={refresh} />}
      {!!message && <Text style={styles.details} aria-live="polite">{message}</Text>}
      {!entries.length && !message && <Text style={styles.details}>{downloads ? 'Nenhum vídeo baixado.' : 'Nenhuma gravação salva.'}</Text>}
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
                    try {
                      if (downloads) {Network.removeMediaDownload(name);}
                      else {Network.removeMirrorRecording(name);}
                      refresh();
                    } catch {setMessage(downloads ? 'Não foi possível excluir o vídeo.' : 'Não foi possível excluir a gravação.');}
                  })}
                </>
              ) : (
                <>
                  {button(name + 'play', 'Reproduzir', () => {
                    try {onPlay(name, downloads ? Network.getMediaDownloadUrl(name) : Network.getMirrorRecordingUrl(name));}
                    catch {setMessage(downloads ? 'Não foi possível abrir o vídeo.' : 'Não foi possível abrir a gravação.');}
                  }, !confirm && name === entries[0].name)}
                  {button(name + 'export', 'Exportar', () => setExportName(name))}
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
