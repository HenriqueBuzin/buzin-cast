import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import {AppState, AppStateStatus} from 'react-native';
import AirPlayAudioScreen from '../src/AirPlayAudioScreen';
import Network from '../src/turbo-modules/BuzinNetwork';
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({__esModule: true, default: {
  startAirPlayAudio: jest.fn(() => 7001), stopAirPlayAudio: jest.fn(),
  getAirPlayAudioState: jest.fn(() => '{"phase":"waiting","seconds":0}'),
}}));
beforeEach(() => jest.useFakeTimers());
afterEach(() => {jest.useRealTimers(); jest.clearAllMocks();});
test('mostra observação nativa, encerra uma vez e libera os timers', () => {
  const back = jest.fn();
  const screen = render(<AirPlayAudioScreen onBack={back} />);
  expect(screen.getByText(/TCP 7001/)).toBeTruthy();
  jest.mocked(Network.getAirPlayAudioState).mockReturnValueOnce('{"phase":"playing","seconds":1.5}');
  act(() => jest.advanceTimersByTime(250));
  expect(screen.getByText(/Reproduzindo áudio • 1.5 s/)).toBeTruthy();
  fireEvent.press(screen.getByRole('button', {name: 'Desativar e voltar'}));
  expect(back).toHaveBeenCalledTimes(1);
  screen.unmount();
  expect(Network.stopAirPlayAudio).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});
test('erro nativo ou observação inválida desativa o receptor', () => {
  const screen = render(<AirPlayAudioScreen onBack={jest.fn()} />);
  jest.mocked(Network.getAirPlayAudioState).mockReturnValueOnce('{"phase":"error","seconds":0}');
  act(() => jest.advanceTimersByTime(250));
  expect(screen.getByText(/Não foi possível receber/)).toBeTruthy();
  expect(Network.stopAirPlayAudio).toHaveBeenCalledTimes(1);
  screen.unmount();
  expect(Network.stopAirPlayAudio).toHaveBeenCalledTimes(1);
  const again = render(<AirPlayAudioScreen onBack={jest.fn()} />);
  jest.mocked(Network.getAirPlayAudioState).mockReturnValueOnce('{"phase":"playing","seconds":-1}');
  act(() => jest.advanceTimersByTime(250));
  expect(again.getByText(/Não foi possível receber/)).toBeTruthy();
  again.unmount();
});
test('sair para segundo plano solicita o encerramento', () => {
  let state!: (value: AppStateStatus) => void;
  const subscription = jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, callback) => {
    state = callback; return {remove: jest.fn()};
  });
  const back = jest.fn();
  const screen = render(<AirPlayAudioScreen onBack={back} />);
  act(() => state('background'));
  expect(back).toHaveBeenCalledTimes(1);
  screen.unmount(); subscription.mockRestore();
});
