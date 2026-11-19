import React, {useEffect, useState} from 'react';
import {StyleSheet, Text, TextInput, View} from 'react-native';
import TvButton from './TvButton';
import Network from './turbo-modules/BuzinNetwork';

const messages: Record<string, string> = {
  connecting: 'Conectando ao receptor…',
  waiting: 'Pareado. Aguardando um quadro-chave do AirPlay.',
  sending: 'Retransmitindo espelhamento.',
  ending: 'Encerrando retransmissão…',
  ended: 'Retransmissão concluída.',
  error: 'A retransmissão foi interrompida. Confira o receptor e o código.',
};

export default function RelaySenderControls({disabled}: {disabled: boolean}) {
  const [editing, setEditing] = useState(false);
  const [phase, setPhase] = useState('idle');
  const [host, setHost] = useState('');
  const [port, setPort] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    const timer = setInterval(() => {
      try {setPhase(Network.getRelaySenderState());}
      catch {setPhase('error');}
    }, 300);
    return () => clearInterval(timer);
  }, []);

  function connect() {
    const portNumber = Number(port);
    if (!host.trim() || !/^\d{1,5}$/.test(port) || portNumber < 1 || portNumber > 65535 ||
        !/^[A-Z0-9]{12}$/i.test(code.replace(/[ -]/g, ''))) {
      setError('Informe o IP, a porta e o código de 12 caracteres.');
      return;
    }
    try {
      Network.startRelaySender(host.trim(), portNumber, code);
      setPhase('connecting');
      setError('');
      setCode('');
      setEditing(false);
    } catch {
      setError('Use o IP local mostrado pelo receptor.');
    }
  }

  const active = ['connecting', 'waiting', 'sending', 'ending'].includes(phase);
  return (
    <View style={styles.panel}>
      {editing && (
        <View style={styles.row}>
          <TextInput aria-label="IP do receptor" accessibilityHint="Edita o endereço exibido no aparelho receptor" placeholder="IP do receptor" placeholderTextColor="#B7C9DD"
            value={host} onChangeText={setHost} maxLength={15} autoCapitalize="none" autoCorrect={false}
            style={[styles.input, styles.host]} />
          <TextInput aria-label="Porta" accessibilityHint="Edita a porta exibida no aparelho receptor" placeholder="Porta" placeholderTextColor="#B7C9DD"
            value={port} onChangeText={setPort} maxLength={5} keyboardType="numeric" style={styles.input} />
          <TextInput aria-label="Código de pareamento" accessibilityHint="Edita o código de 12 caracteres exibido no aparelho receptor" placeholder="Código" placeholderTextColor="#B7C9DD"
            value={code} onChangeText={setCode} maxLength={16} autoCapitalize="characters" autoCorrect={false}
            style={[styles.input, styles.host]} />
        </View>
      )}
      <View style={styles.row}>
        {editing ? (
          <>
            <RelayButton label="Conectar" disabled={disabled} onPress={connect} />
            <RelayButton label="Cancelar" onPress={() => {setEditing(false); setError(''); setCode('');}} />
          </>
        ) : (
          <RelayButton label={active ? 'Parar retransmissão' : 'Retransmitir'} disabled={disabled || phase === 'ending'}
            onPress={() => {
              if (active) {
                try {Network.stopRelaySender(); setPhase(Network.getRelaySenderState());} catch {setPhase('error');}
              } else {
                setEditing(true);
              }
            }} />
        )}
      </View>
      {(error || (!editing && messages[phase])) && (
        <Text style={styles.message} aria-live="polite">{error || messages[phase]}</Text>
      )}
    </View>
  );
}

function RelayButton({label, disabled, onPress}: {label: string; disabled?: boolean; onPress: () => void}) {
  const [focused, setFocused] = useState(false);
  return (
    <TvButton role="button" disabled={disabled} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
      style={[styles.button, focused && styles.focused]} onPress={onPress}>
      <Text style={styles.label}>{label}</Text>
    </TvButton>
  );
}

const styles = StyleSheet.create({
  panel: {alignItems: 'center', gap: 6},
  row: {flexDirection: 'row', gap: 8, flexWrap: 'wrap', justifyContent: 'center'},
  input: {color: 'white', borderRadius: 8, borderWidth: 2, borderColor: '#B7C9DD', padding: 8, minWidth: 90, fontSize: 19},
  host: {minWidth: 175},
  button: {backgroundColor: '#18334F', borderRadius: 12, borderWidth: 3, borderColor: 'transparent', paddingHorizontal: 24, paddingVertical: 10},
  focused: {backgroundColor: '#245578', borderColor: '#69D8FF'},
  label: {color: 'white', fontSize: 22},
  message: {color: '#B7C9DD', fontSize: 18, textAlign: 'center'},
});
