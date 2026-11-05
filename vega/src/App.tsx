import React, {useEffect, useState} from 'react';
import {
  BackHandler,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

export default function App() {
  const [page, setPage] = useState<'home' | 'status' | 'about'>('home');
  const [focused, setFocused] = useState('status');

  useEffect(() => {
    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        if (page === 'home') {
          return false;
        }
        setPage('home');
        return true;
      },
    );
    return () => subscription.remove();
  }, [page]);

  const action = (
    id: string,
    label: string,
    onPress: () => void,
    preferred: boolean,
  ) => (
    <Pressable
      key={id}
      role="button"
      accessibilityHint={
        id === 'back' ? 'Retorna à tela inicial' : `Abre ${label}`
      }
      hasTVPreferredFocus={preferred}
      onFocus={() => setFocused(id)}
      onBlur={() => setFocused('')}
      onPress={onPress}
      style={[styles.button, focused === id && styles.focused]}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );

  return (
    <View style={styles.screen}>
      <Image
        accessible
        aria-label="Buzin Cast"
        source={require('./assets/buzin_banner.png')}
        resizeMode="contain"
        style={styles.logo}
      />
      <Text style={styles.title}>Prévia para Vega OS</Text>
      <Text style={styles.description}>Receptor em desenvolvimento</Text>
      {page === 'home' ? (
        <View style={styles.actions}>
          {action('status', 'Estado do port', () => setPage('status'), true)}
          {action('about', 'Sobre', () => setPage('about'), false)}
        </View>
      ) : (
        <View style={styles.panel}>
          <Text style={styles.panelTitle}>
            {page === 'status' ? 'Estado do port' : 'Sobre'}
          </Text>
          <Text style={styles.details}>
            {page === 'status'
              ? 'AirPlay, DLNA, FCast e Cast V2 ainda não estão disponíveis no Vega. Nenhum receptor é anunciado na rede.'
              : 'Buzin Cast 0.6.0 • Vega OS 2.0 • Henrique Buzin. Validação de recepção em aparelho físico pendente.'}
          </Text>
          {action('back', 'Voltar', () => setPage('home'), true)}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#081323',
    padding: 48,
  },
  actions: {flexDirection: 'row', marginTop: 32, gap: 24},
  button: {
    backgroundColor: '#18334F',
    borderColor: 'transparent',
    borderWidth: 3,
    borderRadius: 12,
    paddingHorizontal: 28,
    paddingVertical: 16,
  },
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
  buttonLabel: {color: '#FFFFFF', fontSize: 24},
  panel: {marginTop: 24, maxWidth: 900, alignItems: 'center', gap: 20},
  panelTitle: {color: '#FFFFFF', fontSize: 28},
  details: {color: '#B7C9DD', fontSize: 22, textAlign: 'center'},
  logo: {width: 320, height: 180, marginBottom: 24},
  title: {color: '#FFFFFF', fontSize: 32, fontWeight: '600'},
  description: {color: '#B7C9DD', fontSize: 22, marginTop: 12},
});
