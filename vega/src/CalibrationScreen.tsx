import React, {useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import TvButton from './TvButton';
import Network from './turbo-modules/BuzinNetwork';
import {maximumVideoMargin, readVideoMargin, videoMarginStyle} from './VideoViewport';

export default function CalibrationScreen({onBack}: {onBack: () => void}) {
  const [margin, setMargin] = useState(readVideoMargin);
  const [focus, setFocus] = useState('Aumentar');
  const [failed, setFailed] = useState(false);

  function save() {
    try {
      Network.setVideoMargin(margin);
    } catch {
      setFailed(true);
      return;
    }
    onBack();
  }

  function change(delta: number) {
    setMargin(current => Math.max(0, Math.min(maximumVideoMargin, current + delta)));
    setFailed(false);
  }

  function button(label: string, onPress: () => void) {
    return (
      <TvButton
        role="button"
        hasTVPreferredFocus={label === 'Aumentar'}
        onFocus={() => setFocus(label)}
        onBlur={() => setFocus(current => current === label ? '' : current)}
        onPress={onPress}
        style={[styles.button, focus === label && styles.focused]}>
        <Text style={styles.label}>{label}</Text>
      </TvButton>
    );
  }

  return (
    <View style={styles.screen}>
      <View pointerEvents="none" style={[videoMarginStyle(margin), styles.outline]} />
      <Text style={styles.title}>Calibrar bordas</Text>
      <Text style={styles.details}>Ajuste até enxergar toda a moldura na TV.</Text>
      <Text style={styles.label} aria-live="polite">Margem de {margin}% em cada borda</Text>
      <View style={styles.actions}>
        {button('Diminuir', () => change(-1))}
        {button('Aumentar', () => change(1))}
      </View>
      <View style={styles.actions}>
        {button('Salvar margem', save)}
        {button('Cancelar', onBack)}
      </View>
      {failed && <Text style={styles.details} role="alert">Não foi possível salvar a margem. Tente novamente.</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, alignItems: 'center', justifyContent: 'center', gap: 20, backgroundColor: '#081323'},
  outline: {borderWidth: 4, borderColor: '#69D8FF'},
  title: {color: 'white', fontSize: 36, fontWeight: '600'},
  details: {color: '#B7C9DD', fontSize: 22},
  label: {color: 'white', fontSize: 24},
  actions: {flexDirection: 'row', gap: 24},
  button: {
    paddingVertical: 12, paddingHorizontal: 28, borderWidth: 3,
    borderColor: 'transparent', borderRadius: 12, backgroundColor: '#18334F',
  },
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
});
