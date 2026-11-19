import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import {AppState} from 'react-native';
import RecordingLibrary from '../src/RecordingLibrary';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  listMirrorRecordings: jest.fn(),
  getMirrorRecordingUrl: jest.fn(),
  removeMirrorRecording: jest.fn(),
  startRecordingExport: jest.fn(),
  getRecordingExportState: jest.fn(),
  stopRecordingExport: jest.fn(),
}));

test('abre o arquivo selecionado e só exclui após confirmar', () => {
  const name = 'Buzin-1795129000000-aB9cDe.mp4';
  const url = 'file:///data/recordings/' + name;
  jest.mocked(Network.listMirrorRecordings).mockReturnValueOnce(JSON.stringify([{name, bytes: 1048576}]))
    .mockReturnValueOnce('[]');
  jest.mocked(Network.getMirrorRecordingUrl).mockReturnValue(url);
  const play = jest.fn();
  const screen = render(<RecordingLibrary onBack={jest.fn()} onPlay={play} />);
  fireEvent.press(screen.getByRole('button', {name: 'Reproduzir'}));
  expect(Network.getMirrorRecordingUrl).toHaveBeenCalledWith(name);
  expect(play).toHaveBeenCalledWith(name, url);
  fireEvent.press(screen.getByRole('button', {name: 'Excluir'}));
  expect(Network.removeMirrorRecording).not.toHaveBeenCalled();
  fireEvent.press(screen.getByRole('button', {name: 'Cancelar'}));
  expect(Network.removeMirrorRecording).not.toHaveBeenCalled();
  fireEvent.press(screen.getByRole('button', {name: 'Excluir'}));
  fireEvent.press(screen.getByRole('button', {name: 'Confirmar exclusão'}));
  expect(Network.removeMirrorRecording).toHaveBeenCalledWith(name);
  expect(screen.getByText('Nenhuma gravação salva.')).toBeTruthy();
});

test('exporta apenas a gravação selecionada e encerra o link após o envio ou ao cancelar', () => {
  jest.useFakeTimers();
  const subscription = jest.spyOn(AppState, 'addEventListener').mockReturnValue({remove: jest.fn()});
  const name = 'Buzin-1795129000000-aB9cDe.mp4';
  const link = 'http://fixture.invalid/file';
  jest.mocked(Network.listMirrorRecordings).mockReturnValue(JSON.stringify([{name, bytes: 1048576}]));
  jest.mocked(Network.startRecordingExport).mockReturnValue(link);
  jest.mocked(Network.getRecordingExportState).mockReturnValue('sent');
  const screen = render(<RecordingLibrary onBack={jest.fn()} onPlay={jest.fn()} />);
  try {
    fireEvent.press(screen.getByRole('button', {name: 'Exportar'}));
    expect(Network.startRecordingExport).toHaveBeenCalledWith(name);
    expect(screen.getByText(link)).toBeTruthy();
    act(() => jest.advanceTimersByTime(200));
    expect(screen.getByText('Envio concluído.')).toBeTruthy();
    expect(screen.queryByText(link)).toBeNull();
    expect(Network.stopRecordingExport).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getByRole('button', {name: 'Voltar à biblioteca'}));
    fireEvent.press(screen.getByRole('button', {name: 'Exportar'}));
    fireEvent.press(screen.getByRole('button', {name: 'Cancelar exportação'}));
    expect(screen.queryByText(link)).toBeNull();
    expect(Network.stopRecordingExport).toHaveBeenCalledTimes(2);
  } finally {
    screen.unmount();
    subscription.mockRestore();
    jest.useRealTimers();
  }
});
