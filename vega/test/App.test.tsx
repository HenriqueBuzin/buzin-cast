import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import App from '../src/App';

jest.mock('../src/PlaybackScreen', () => () => null);

jest.mock('../src/mediaCapabilities', () => ({
  getMediaReport: jest.fn(
    () => '{"h264Decoders":2,"hevcDecoders":0,"aacDecoders":1}',
  ),
}));
import {getMediaReport} from '../src/mediaCapabilities';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {
    recordMediaCapabilities: jest.fn(),
    getReport: jest.fn(
      () =>
        '{"tcpLoopback":true,"udpLoopback":true,"wildcardBind":true,"multicastMembership":true,"rtspParser":true,"malformedRtspRejected":true,"aesCtrVector":true}',
    ),
  },
}));
import {BackHandler} from 'react-native';
import Network from '../src/turbo-modules/BuzinNetwork';

test('identifica a prévia e informa que o receptor está em desenvolvimento', () => {
  const screen = render(<App />);
  expect(screen.getByLabelText('Buzin Cast')).toBeTruthy();
  expect(screen.getByText('Prévia para Vega OS')).toBeTruthy();
  expect(screen.getByText('Receptor em desenvolvimento')).toBeTruthy();
});

test('abre o estado sem anunciar receptor e permite voltar pelo controle', () => {
  const screen = render(<App />);
  fireEvent.press(screen.getByRole('button', {name: 'Estado do port'}));
  expect(screen.getByText(/Nenhum receptor é anunciado/)).toBeTruthy();
  fireEvent.press(screen.getByRole('button', {name: 'Voltar'}));
  fireEvent.press(screen.getByRole('button', {name: 'Sobre'}));
  expect(screen.getByText(/Buzin Cast 0.6.0/)).toBeTruthy();
});

test('Voltar fecha a página interna e permite sair a partir do início', () => {
  let onBack: (() => boolean | null | undefined) | undefined;
  const remove = jest.fn();
  const listener = jest
    .spyOn(BackHandler, 'addEventListener')
    .mockImplementation((_event, callback) => {
      onBack = callback;
      return {remove};
    });
  const screen = render(<App />);
  expect(onBack?.()).toBe(false);
  fireEvent.press(screen.getByRole('button', {name: 'Sobre'}));
  act(() => {
    expect(onBack?.()).toBe(true);
  });
  expect(screen.getByRole('button', {name: 'Estado do port'})).toBeTruthy();
  screen.unmount();
  expect(remove).toHaveBeenCalled();
  listener.mockRestore();
});

test('exibe o resultado do módulo e informa uma falha nativa ao usuário', () => {
  const screen = render(<App />);
  fireEvent.press(screen.getByRole('button', {name: 'Estado do port'}));
  fireEvent.press(screen.getByRole('button', {name: 'Testar rede local'}));
  expect(screen.getByText(/TCP local: OK/)).toBeTruthy();
  jest.mocked(Network.getReport).mockImplementationOnce(() => {
    throw new Error('socket failure');
  });
  fireEvent.press(screen.getByRole('button', {name: 'Testar rede local'}));
  expect(
    screen.getByText('Falha no diagnóstico nativo. Tente novamente.'),
  ).toBeTruthy();
});

test('consulta capacidades reais pelo adaptador e informa falhas sem inventar suporte', () => {
  const screen = render(<App />);
  fireEvent.press(screen.getByRole('button', {name: 'Mídia'}));
  expect(getMediaReport).toHaveBeenCalled();
  expect(screen.getByText(/H.264: 2 resultado/)).toBeTruthy();
  expect(screen.getByText(/HEVC: 0 resultado/)).toBeTruthy();
  fireEvent.press(screen.getByRole('button', {name: 'Voltar'}));
  jest
    .mocked(getMediaReport)
    .mockImplementationOnce(() => '{"h264Decoders":-1}');
  fireEvent.press(screen.getByRole('button', {name: 'Mídia'}));
  expect(
    screen.getByText('Não foi possível consultar os codecs neste dispositivo.'),
  ).toBeTruthy();
});
