import React, {useCallback, useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import Network from './turbo-modules/BuzinNetwork';
import TvButton from './TvButton';

const notices: Record<string, string> = {
  cancelling: 'Cancelando download…',
  cancelled: 'Download cancelado.',
  saved: 'Vídeo salvo na Biblioteca de downloads.',
  error: 'Não foi possível baixar este vídeo.',
};

export default function DownloadButton({url, blocked = false, onSaved}: {
  url?: string;
  blocked?: boolean;
  onSaved?: () => void;
}) {
  const [status, setStatus] = useState({phase: 'idle', percent: -1});
  const [focused, setFocused] = useState(false);
  const [failed, setFailed] = useState(false);
  const refresh = useCallback(() => {
    try {setStatus(JSON.parse(Network.getMediaDownloadState()));}
    catch {setFailed(true);}
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 300);
    return () => clearInterval(timer);
  }, [refresh]);
  useEffect(() => {
    if (status.phase === 'saved') {onSaved?.();}
  }, [status.phase, onSaved]);

  const working = status.phase === 'downloading';
  const message = failed ? 'O download está indisponível.' : working
    ? status.percent < 0 ? 'Preparando download…' : `Baixando vídeo · ${status.percent}%`
    : notices[status.phase];
  if (!working && !url && !message) {return null;}
  return (
    <View style={styles.panel}>
      {(working || !!url) && (
        <TvButton role="button" disabled={!working && (blocked || status.phase === 'cancelling')}
          onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
          style={[styles.button, focused && styles.focused]}
          onPress={() => {
            try {
              if (working) {Network.cancelMediaDownload();}
              else if (url) {Network.startMediaDownload(url);}
              setFailed(false);
              refresh();
            } catch {setFailed(true);}
          }}>
          <Text style={styles.label}>{working ? 'Cancelar download' : 'Baixar vídeo'}</Text>
        </TvButton>
      )}
      {!!message && <Text style={styles.message} aria-live="polite">{message}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {alignItems: 'center'},
  button: {backgroundColor: '#18334F', borderWidth: 3, borderColor: 'transparent', borderRadius: 12, paddingHorizontal: 22, paddingVertical: 10},
  focused: {backgroundColor: '#245578', borderColor: '#69D8FF'},
  label: {color: 'white', fontSize: 24},
  message: {color: '#B7C9DD', fontSize: 18, paddingTop: 4},
});
