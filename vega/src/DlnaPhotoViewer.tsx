import React, {useEffect, useState} from 'react';
import {Image, StyleSheet, Text, View} from 'react-native';
import type {ViewStyle} from 'react-native';
import type {DlnaPhotoView} from './dlnaReceiver';
import TvButton from './TvButton';
import VideoViewport from './VideoViewport';

export default function DlnaPhotoViewer({photo, state, blocked, viewportStyle, onMove, onClose, onDisplayed}: {
  photo: DlnaPhotoView;
  state?: string;
  blocked: boolean;
  viewportStyle: ViewStyle;
  onMove: (delta: -1 | 1) => void;
  onClose: () => void;
  onDisplayed: (token: number, success: boolean) => void;
}) {
  const [size, setSize] = useState({width: 0, height: 0});
  const [turn, setTurn] = useState({token: photo.token, angle: 0});
  const [fill, setFill] = useState(false);
  const [slideshow, setSlideshow] = useState(false);
  const [focus, setFocus] = useState('');
  const rotation = turn.token === photo.token ? turn.angle : 0;
  const canMove = !blocked && photo.count > 1;
  const canTransform = !blocked && state === 'playing';

  useEffect(() => {
    if (!slideshow || !canMove || state === 'loading') {return;}
    const next = setTimeout(() => onMove(1), 5000);
    return () => clearTimeout(next);
  }, [slideshow, canMove, state, photo.token, onMove]);

  const portraitTurn = rotation % 180 !== 0;
  const imageWidth = portraitTurn ? size.height : size.width;
  const imageHeight = portraitTurn ? size.width : size.height;
  const imagePlacement = {
    width: imageWidth,
    height: imageHeight,
    left: (size.width - imageWidth) / 2,
    top: (size.height - imageHeight) / 2,
    transform: [{rotate: `${rotation}deg`}],
  };

  const controls = [
    {id: 'previous', label: 'Anterior', enabled: canMove, press: () => onMove(-1)},
    {id: 'next', label: 'Próxima', enabled: canMove, press: () => onMove(1)},
    {id: 'slideshow', label: slideshow ? 'Parar apresentação' : 'Apresentação', enabled: canMove, press: () => setSlideshow(!slideshow)},
    {id: 'rotate', label: 'Girar', enabled: canTransform, press: () => setTurn({token: photo.token, angle: (rotation + 90) % 360})},
    {id: 'fill', label: fill ? 'Ajustar' : 'Preencher', enabled: canTransform, press: () => setFill(!fill)},
    {id: 'close', label: 'Fechar foto', enabled: !blocked, press: onClose},
  ];

  return (
    <>
      <VideoViewport style={viewportStyle}>
        <View testID="dlna-photo-frame" style={styles.frame} onLayout={({nativeEvent: {layout: {width, height}}}) =>
          setSize(current => current.width === width && current.height === height ? current : {width, height})
        }>
          {photo.source && size.width > 0 && size.height > 0 && (
            <Image
              key={photo.token}
              style={[styles.image, imagePlacement]}
              source={photo.source}
              resizeMode={fill ? 'cover' : 'contain'}
              aria-label="Foto recebida pelo DLNA"
              onLoad={() => onDisplayed(photo.token, true)}
              onError={() => onDisplayed(photo.token, false)}
            />
          )}
        </View>
      </VideoViewport>
      <Text style={styles.details} aria-live="polite">
        {state === 'error' ? 'Não foi possível exibir a foto.' :
          state === 'loading' ? 'Carregando foto…' : `Foto ${photo.index + 1} de ${photo.count}`}
      </Text>
      <View style={styles.controls}>
        {controls.map(({id, label, enabled, press}) => (
          <TvButton
            key={id}
            role="button"
            disabled={!enabled}
            onFocus={() => setFocus(id)}
            onBlur={() => setFocus(current => current === id ? '' : current)}
            onPress={press}
            style={[styles.button, !enabled && styles.disabled, focus === id && enabled && styles.focused]}>
            <Text style={styles.label}>{label}</Text>
          </TvButton>
        ))}
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  frame: {...StyleSheet.absoluteFillObject, overflow: 'hidden'},
  image: {position: 'absolute'},
  details: {color: '#B7C9DD', fontSize: 20, marginVertical: 10},
  controls: {flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', gap: 12, marginBottom: 12},
  button: {paddingVertical: 8, paddingHorizontal: 14, borderWidth: 3, borderColor: 'transparent', borderRadius: 12, backgroundColor: '#18334F'},
  disabled: {opacity: 0.45},
  focused: {borderColor: '#69D8FF', backgroundColor: '#245578'},
  label: {color: 'white', fontSize: 22},
});
