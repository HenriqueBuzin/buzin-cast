import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';
import SettingsScreen from '../src/SettingsScreen';
import Network from '../src/turbo-modules/BuzinNetwork';
jest.mock('../src/mediaCapabilities', () => ({supportsH264Mode: jest.fn()}));
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {getReceiverName: jest.fn(() => 'Living room'), setReceiverName: jest.fn(),
    getConnectionApproval: jest.fn(() => false), setConnectionApproval: jest.fn(),
    getExperienceProfile: jest.fn(() => 'default'), setExperienceProfile: jest.fn()},
}));
afterEach(() => jest.clearAllMocks());
test('edita e salva o nome, preservando a tela em falha de armazenamento', () => {
  const back = jest.fn();
  const screen = render(<SettingsScreen onBack={back} onCalibration={jest.fn()} />);
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
  const screen = render(<SettingsScreen onBack={jest.fn()} onCalibration={jest.fn()} />);
  const approval = () => screen.getByRole('switch', {name: 'Aprovar conexões dos receptores'});
  expect(approval().props.accessibilityState.checked).toBe(false);
  fireEvent.press(approval());
  expect(Network.setConnectionApproval).toHaveBeenCalledWith(true);
  expect(approval().props.accessibilityState.checked).toBe(true);
  jest.mocked(Network.setConnectionApproval).mockImplementationOnce(() => {throw new Error('disk full');});
  fireEvent.press(approval());
  expect(approval().props.accessibilityState.checked).toBe(true);
});

test('preserva o perfil marcado entre foco, mudança, falha na escrita e reabertura', () => {
  const screen = render(<SettingsScreen onBack={jest.fn()} onCalibration={jest.fn()} />);
  expect(screen.getByRole('radio', {name: 'Padrão'}).props.accessibilityState.checked).toBe(true);
  fireEvent(screen.getByRole('radio', {name: 'Cinema'}), 'focus');
  expect(screen.getByRole('radio', {name: 'Padrão'}).props.accessibilityState.checked).toBe(true);
  fireEvent.press(screen.getByRole('radio', {name: 'Jogos'}));
  expect(Network.setExperienceProfile).toHaveBeenCalledWith('games');
  expect(screen.getByRole('radio', {name: 'Jogos'}).props.accessibilityState.checked).toBe(true);
  jest.mocked(Network.setExperienceProfile).mockImplementationOnce(() => {throw new Error('disk full');});
  fireEvent.press(screen.getByRole('radio', {name: 'Apresentação'}));
  expect(screen.getByRole('radio', {name: 'Jogos'}).props.accessibilityState.checked).toBe(true);
  screen.unmount();
  jest.mocked(Network.getExperienceProfile).mockReturnValueOnce('games');
  const reopened = render(<SettingsScreen onBack={jest.fn()} onCalibration={jest.fn()} />);
  expect(reopened.getByRole('radio', {name: 'Jogos'}).props.accessibilityState.checked).toBe(true);
});
