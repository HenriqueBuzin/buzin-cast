import TvButton from './TvButton';
import React, {useState} from 'react';
import {StyleSheet, Text, TextInput, View} from 'react-native';
import Network from './turbo-modules/BuzinNetwork';

export default function SettingsScreen({onBack}: {onBack: () => void}) {
  const [name, setName] = useState(() => {
    try {return Network.getReceiverName();} catch {return '';}
  });
  const [message, setMessage] = useState('');
  const [focus, setFocus] = useState('name');
  return (
    <View style={styles.screen}>
      <Text style={styles.title}>Configurações</Text>
      <Text style={styles.text}>Nome mostrado aos emissores FCast</Text>
      <TextInput
        aria-label="Nome do receptor"
        accessibilityHint="Edita o nome exibido aos emissores"
        value={name}
        onChangeText={setName}
        onFocus={() => setFocus('name')}
        onBlur={() => setFocus('')}
        style={[styles.input, focus === 'name' && styles.focused]}
        maxLength={48}
        autoCorrect={false}
        autoCapitalize="sentences"
      />
      <Text style={styles.text}>Use um nome curto, sem ponto ou barra invertida.</Text>
      <Text style={styles.text} aria-live="polite">{message}</Text>
      <View style={styles.actions}>
        {['save', 'back'].map((id) => (
          <TvButton
            key={id}
            role="button"
            accessibilityHint={id === 'save' ? 'Salva o nome do receptor' : 'Retorna à tela inicial'}
            hasTVPreferredFocus={id === 'save'}
            onFocus={() => setFocus(id)}
            onBlur={() => setFocus('')}
            style={[styles.button, focus === id && styles.focused]}
            onPress={id === 'back' ? onBack : () => {
              try {
                const value = name.trim();
                Network.setReceiverName(value);
                setName(value);
                setMessage('Nome salvo. Será usado ao ativar FCast.');
              } catch {
                setMessage('Não foi possível salvar. Confira o nome e tente novamente.');
              }
            }}>
            <Text style={styles.label}>{id === 'save' ? 'Salvar' : 'Voltar'}</Text>
          </TvButton>
        ))}
      </View>
    </View>
  );
}
const styles = StyleSheet.create({
  screen: {flex: 1, padding: 48, justifyContent: 'center', alignItems: 'center', backgroundColor: '#081323'},
  title: {fontSize: 28, color: 'white', marginBottom: 24},
  text: {fontSize: 20, color: '#B7C9DD', marginVertical: 12},
  input: {width: 600, maxWidth: '90%', color: 'white', fontSize: 24, padding: 16, borderWidth: 3, borderColor: '#B7C9DD', borderRadius: 12},
  actions: {flexDirection: 'row', gap: 24},
  button: {paddingVertical: 12, paddingHorizontal: 28, borderWidth: 3, borderColor: 'transparent', backgroundColor: '#18334F', borderRadius: 12},
  focused: {borderColor: '#69D8FF'},
  label: {fontSize: 24, color: 'white'},
});
