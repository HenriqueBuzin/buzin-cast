import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';
import SettingsScreen from '../src/SettingsScreen';
import Network from '../src/turbo-modules/BuzinNetwork';
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {getReceiverName: jest.fn(() => 'Living room'), setReceiverName: jest.fn(),
    getDlnaApproval: jest.fn(() => false), setDlnaApproval: jest.fn()},
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

test('salva a aprovação DLNA e mantém o estado se a escrita falhar', () => {
  const screen = render(<SettingsScreen onBack={jest.fn()} />);
  const approval = () => screen.getByRole('switch', {name: 'Aprovar conexões DLNA'});
  expect(approval().props.accessibilityState.checked).toBe(false);
  fireEvent.press(approval());
  expect(Network.setDlnaApproval).toHaveBeenCalledWith(true);
  expect(approval().props.accessibilityState.checked).toBe(true);
  jest.mocked(Network.setDlnaApproval).mockImplementationOnce(() => {throw new Error('disk full');});
  fireEvent.press(approval());
  expect(approval().props.accessibilityState.checked).toBe(true);
});
