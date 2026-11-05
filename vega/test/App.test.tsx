import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import App from '../src/App';
import {BackHandler} from 'react-native';

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
