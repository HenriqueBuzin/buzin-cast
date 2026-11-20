import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import DownloadButton from '../src/DownloadButton';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {getMediaDownloadState: jest.fn(), startMediaDownload: jest.fn(), cancelMediaDownload: jest.fn()},
}));

test('inicia o vídeo recebido, mostra progresso e permite cancelar após o emissor parar', () => {
  jest.useFakeTimers();
  const state = jest.mocked(Network.getMediaDownloadState);
  state.mockReturnValue('{"phase":"idle","percent":-1}');
  const url = 'https://fixture.invalid/video.mp4';
  const screen = render(<DownloadButton url={url} />);
  try {
    state.mockReturnValue('{"phase":"downloading","percent":20}');
    fireEvent.press(screen.getByRole('button', {name: 'Baixar vídeo'}));
    expect(Network.startMediaDownload).toHaveBeenCalledWith(url);
    expect(screen.getByText('Baixando vídeo · 20%')).toBeTruthy();
    screen.rerender(<DownloadButton />);
    state.mockReturnValue('{"phase":"cancelling","percent":-1}');
    fireEvent.press(screen.getByRole('button', {name: 'Cancelar download'}));
    expect(Network.cancelMediaDownload).toHaveBeenCalledTimes(1);
    state.mockReturnValue('{"phase":"cancelled","percent":-1}');
    act(() => jest.advanceTimersByTime(300));
    expect(screen.getByText('Download cancelado.')).toBeTruthy();
  } finally {
    screen.unmount();
    jest.useRealTimers();
  }
});
