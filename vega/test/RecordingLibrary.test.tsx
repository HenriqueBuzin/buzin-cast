import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';
import RecordingLibrary from '../src/RecordingLibrary';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  listMirrorRecordings: jest.fn(),
  getMirrorRecordingUrl: jest.fn(),
  removeMirrorRecording: jest.fn(),
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
