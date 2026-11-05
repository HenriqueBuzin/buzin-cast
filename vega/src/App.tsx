import React from 'react';
import {Image, StyleSheet, Text, View} from 'react-native';

export default function App() {
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
  logo: {width: 320, height: 180, marginBottom: 24},
  title: {color: '#FFFFFF', fontSize: 32, fontWeight: '600'},
  description: {color: '#B7C9DD', fontSize: 22, marginTop: 12},
});
