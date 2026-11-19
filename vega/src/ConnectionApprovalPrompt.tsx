import React, {useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import type {ConnectionApprovalRequest} from './connectionApproval';

export default function ConnectionApprovalPrompt({request, protocol, onAnswer}: {
  request: ConnectionApprovalRequest;
  protocol: 'DLNA' | 'FCast' | 'AirPlay';
  onAnswer: (id: number, allow: boolean) => void;
}) {
  const [focus, setFocus] = useState<boolean | null>(false);
  return (
    <View style={styles.panel}>
      <Text style={styles.details} aria-live="polite">
        {protocol === 'DLNA'
          ? `Permitir que ${request.peer} controle o DLNA por cinco minutos?`
          : `Permitir a conexão ${protocol} de ${request.peer}?`}
      </Text>
      <View style={styles.actions}>
        {[false, true].map(allow => (
          <TvButton
            key={String(allow)}
            role="button"
            hasTVPreferredFocus={!allow}
            onFocus={() => setFocus(allow)}
            onBlur={() => setFocus(current => current === allow ? null : current)}
            style={[styles.button, focus === allow && styles.focused]}
            onPress={() => onAnswer(request.id, allow)}>
            <Text style={styles.label}>{allow ? 'Permitir' : 'Recusar'}</Text>
          </TvButton>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {alignItems: 'center', gap: 12, marginBottom: 16},
  details: {color: '#B7C9DD', fontSize: 20, textAlign: 'center'},
  actions: {flexDirection: 'row', gap: 24},
  button: {paddingVertical: 10, paddingHorizontal: 28, borderWidth: 3, borderColor: 'transparent', borderRadius: 12, backgroundColor: '#18334F'},
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
  label: {fontSize: 24, color: 'white'},
});
