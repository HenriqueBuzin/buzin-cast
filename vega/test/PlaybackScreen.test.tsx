import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import {AppState, AppStateStatus} from 'react-native';
import PlaybackScreen from '../src/PlaybackScreen';
import {createPlaybackSession} from '../src/playbackSession';

jest.mock('@amazon-devices/react-native-w3cmedia', () => ({
  KeplerVideoSurfaceView: () => null,
}));
jest.mock('../src/playbackSession', () => ({createPlaybackSession: jest.fn()}));

test('controle pausa a amostra e segundo plano sai da tela liberando a sessão', () => {
  const session = {
    load: jest.fn(),
    attachSurface: jest.fn(),
    detachSurface: jest.fn(),
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
