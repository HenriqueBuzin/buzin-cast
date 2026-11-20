import React, {useState} from 'react';
import {View} from 'react-native';
import type {ViewStyle} from 'react-native';
import Network from './turbo-modules/BuzinNetwork';

export const maximumVideoMargin = 10;
const defaultVideoMargin = 2;

export function readVideoMargin() {
  try {
    const margin = Network.getVideoMargin();
    if (Number.isInteger(margin) && margin >= 0 && margin <= maximumVideoMargin) {
      return margin;
    }
  } catch {
    return defaultVideoMargin;
  }
  return defaultVideoMargin;
}

export function videoMarginStyle(margin: number): ViewStyle {
  const inset = `${margin}%` as const;
  return {position: 'absolute', top: inset, right: inset, bottom: inset, left: inset};
}

export default function VideoViewport({style, children}: {
  style: ViewStyle;
  children: React.ReactNode;
}) {
  const [margin] = useState(readVideoMargin);
  return (
    <View style={style}>
      <View style={videoMarginStyle(margin)}>{children}</View>
    </View>
  );
}
