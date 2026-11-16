import React, {useRef} from 'react';
import {Pressable, PressableProps, View} from 'react-native';

// Keep remote focus on a completed activation, including taps. Pressable
// cancels activation for a drag, so scrolling does not move focus or click.
export default function TvButton({onPress, ...props}: PressableProps) {
  const target = useRef<View>(null);
  return (
    <Pressable
      {...props}
      ref={target}
      focusable={props.focusable ?? !props.disabled}
      onPress={(event) => {
        target.current?.focus();
        onPress?.(event);
      }}
    />
  );
}
