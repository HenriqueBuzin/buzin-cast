import React, {useEffect, useRef, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import Network from './turbo-modules/BuzinNetwork';
import {captionAt, parseSubtitle} from './subtitles';

export function usePlaybackSubtitles(media: number | null) {
  const [cues, setCues] = useState<ReturnType<typeof parseSubtitle>>([]);
  const [link, setLink] = useState('');
  const [message, setMessage] = useState('');
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  function stop() {
    if (timer.current !== null) {
      clearInterval(timer.current);
      timer.current = null;
      Network.stopSubtitleUpload();
    }
  }

  useEffect(() => {
    setCues([]);
    setLink('');
    setMessage('');
    return stop;
  }, [media]);

  function select() {
    if (media === null) {return;}
    setMessage('');
    if (link || cues.length) {
      stop();
      setLink('');
      setCues([]);
      return;
    }
    try {
      setLink(Network.startSubtitleUpload());
      timer.current = setInterval(() => {
        try {
          const received = Network.pollSubtitleUpload();
          if (!received) {return;}
          stop();
          setLink('');
          if (received === 'expired') {
            setMessage('O endereço expirou. Abra a importação novamente.');
          } else {
            setCues(parseSubtitle(received));
            setMessage('Legenda carregada.');
          }
        } catch {
          stop();
          setLink('');
          setMessage('Use um arquivo SRT ou WebVTT válido em UTF-8.');
        }
      }, 200);
    } catch {
      setMessage('Não foi possível abrir o envio na rede local.');
    }
  }

  return {
    caption: (seconds: number) => captionAt(cues, seconds),
    label: link ? 'Cancelar envio' : cues.length ? 'Desligar legenda' : 'Importar legenda',
    select,
    link,
    message,
  };
}

export function SubtitleCaption({text}: {text: string}) {
  if (!text) {return null;}
  return (
    <View pointerEvents="none" style={styles.overlay}>
      <Text style={styles.caption}>{text}</Text>
    </View>
  );
}

export function SubtitleNotice({link, message}: {link: string; message: string}) {
  if (!link && !message) {return null;}
  return (
    <View style={styles.notice}>
      {!!link && <Text style={styles.note}>Abra no navegador de um aparelho na mesma rede e envie o arquivo.</Text>}
      <Text aria-live="polite" style={styles.note}>{link || message}</Text>
    </View>
  );
}

export function SubtitleButton({label, onPress}: {label: string; onPress: () => void}) {
  const [focused, setFocused] = useState(false);
  return (
    <TvButton role="button" onPress={onPress} onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)} style={[styles.button, focused && styles.focused]}>
      <Text style={styles.label}>{label}</Text>
    </TvButton>
  );
}

const styles = StyleSheet.create({
  overlay: {position: 'absolute', left: '8%', right: '8%', bottom: '8%', alignItems: 'center'},
  caption: {backgroundColor: '#000D', color: 'white', fontSize: 26, textAlign: 'center', paddingHorizontal: 12, paddingVertical: 4},
  notice: {alignItems: 'center', paddingVertical: 4},
  note: {color: '#B7C9DD', fontSize: 18, textAlign: 'center'},
  button: {backgroundColor: '#18334F', borderWidth: 3, borderColor: 'transparent', borderRadius: 12, paddingHorizontal: 28, paddingVertical: 10},
  focused: {backgroundColor: '#245578', borderColor: '#69D8FF'},
  label: {color: 'white', fontSize: 24},
});
