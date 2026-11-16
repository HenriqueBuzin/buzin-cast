import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';
import SettingsScreen from '../src/SettingsScreen';
import Network from '../src/turbo-modules/BuzinNetwork';
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {getReceiverName: jest.fn(() => 'Living room'), setReceiverName: jest.fn()},
}));
afterEach(() => jest.clearAllMocks());
test('edita e salva o nome, preservando a tela em falha de armazenamento', () => {
  const back = jest.fn();
  const screen = render(<SettingsScreen onBack={back} />);
  const input = screen.getByLabelText('Nome do receptor');
  expect(input.props.value).toBe('Living room');
  fireEvent.changeText(input, '  Sala  ');
  fireEvent.press(screen.getByRole('button', {name: 'Salvar'}));
  expect(Network.setReceiverName).toHaveBeenCalledWith('Sala');
  expect(screen.getByText(/Nome salvo/)).toBeTruthy();
  jest.mocked(Network.setReceiverName).mockImplementationOnce(() => {throw new Error('disk full');});
  fireEvent.changeText(input, 'Quarto');
  fireEvent.press(screen.getByRole('button', {name: 'Salvar'}));
  expect(screen.getByText(/Não foi possível salvar/)).toBeTruthy();
  expect(back).not.toHaveBeenCalled();
  fireEvent.press(screen.getByRole('button', {name: 'Voltar'}));
  expect(back).toHaveBeenCalledTimes(1);
});
