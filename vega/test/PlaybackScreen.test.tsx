import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import {AppState, AppStateStatus} from 'react-native';
import PlaybackScreen from '../src/PlaybackScreen';
import {createPlaybackSession, PlaybackSnapshot} from '../src/playbackSession';
import Network from '../src/turbo-modules/BuzinNetwork';
import {encode} from 'base-64';

jest.mock('@amazon-devices/react-native-w3cmedia', () => ({
  KeplerVideoSurfaceView: () => null,
}));
jest.mock('../src/playbackSession', () => ({createPlaybackSession: jest.fn()}));

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  startSubtitleUpload: jest.fn(),
  pollSubtitleUpload: jest.fn(),
  stopSubtitleUpload: jest.fn(),
}));

test('controle pausa a amostra e segundo plano sai da tela liberando a sessão', () => {
  const session = {
    load: jest.fn(),
    setVolume: jest.fn(),
    attachSurface: jest.fn(),
    detachSurface: jest.fn(),
    setPlaying: jest.fn(),
    togglePause: jest.fn(),
    stop: jest.fn(() => Promise.resolve()),
  };
  jest.mocked(createPlaybackSession).mockImplementation((_ready, changed) => {
    changed({state: 'playing', seconds: 1.5});
    return session;
  });
  let onState: ((state: AppStateStatus) => void) | undefined;
  const remove = jest.fn();
  const subscription = jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_event, listener) => {
      onState = listener;
      return {remove};
    });
  const back = jest.fn();
  const screen = render(<PlaybackScreen onBack={back} />);
  fireEvent.press(screen.getByRole('button', {name: 'Pausar'}));
  expect(session.togglePause).toHaveBeenCalledTimes(1);
  act(() => onState?.('background'));
  expect(back).toHaveBeenCalledTimes(1);
  screen.unmount();
  expect(session.stop).toHaveBeenCalledTimes(1);
  expect(remove).toHaveBeenCalledTimes(1);
  subscription.mockRestore();
});

test('importa legenda, acompanha a posição do player e fecha o envio ao sair', () => {
  jest.useFakeTimers();
  const subscription = jest.spyOn(AppState, 'addEventListener').mockReturnValue({remove: jest.fn()});
  let update: (snapshot: PlaybackSnapshot) => void = () => {};
  jest.mocked(createPlaybackSession).mockImplementation((_ready, changed) => {
    update = changed;
    changed({state: 'playing', seconds: 1.5});
    return {
      load: jest.fn(), setVolume: jest.fn(), attachSurface: jest.fn(), detachSurface: jest.fn(),
      setPlaying: jest.fn(), togglePause: jest.fn(), stop: jest.fn(() => Promise.resolve()),
    };
  });
  const fixtureUrl = 'http://fixture.invalid/oneshot';
  jest.mocked(Network.startSubtitleUpload).mockReturnValue(fixtureUrl);
  jest.mocked(Network.pollSubtitleUpload).mockReturnValueOnce(
    encode('WEBVTT\n\n00:01.000 --> 00:02.000\nSample caption'),
  );
  const screen = render(<PlaybackScreen onBack={jest.fn()} />);
  try {
    fireEvent.press(screen.getByRole('button', {name: 'Importar legenda'}));
    expect(screen.getByText(fixtureUrl)).toBeTruthy();
    act(() => jest.advanceTimersByTime(200));
    expect(screen.getByText('Sample caption')).toBeTruthy();
    expect(screen.queryByText(fixtureUrl)).toBeNull();
    act(() => update({state: 'playing', seconds: 3}));
    expect(screen.queryByText('Sample caption')).toBeNull();
    act(() => update({state: 'paused', seconds: 1.5}));
    expect(screen.getByText('Sample caption')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', {name: 'Desligar legenda'}));
    expect(screen.queryByText('Sample caption')).toBeNull();
    fireEvent.press(screen.getByRole('button', {name: 'Importar legenda'}));
    screen.unmount();
    expect(Network.stopSubtitleUpload).toHaveBeenCalledTimes(2);
  } finally {
    subscription.mockRestore();
    jest.useRealTimers();
  }
});
