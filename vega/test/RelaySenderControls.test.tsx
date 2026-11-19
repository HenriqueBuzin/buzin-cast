import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import RelaySenderControls from '../src/RelaySenderControls';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  startRelaySender: jest.fn(),
  getRelaySenderState: jest.fn(() => 'idle'),
  stopRelaySender: jest.fn(),
}));

test('valida os campos, atualiza a sessão e encerra o envio pelo controle', () => {
  jest.useFakeTimers();
  const screen = render(<RelaySenderControls disabled={false} />);
  try {
    fireEvent.press(screen.getByRole('button', {name: 'Retransmitir'}));
    fireEvent.press(screen.getByRole('button', {name: 'Conectar'}));
    expect(Network.startRelaySender).not.toHaveBeenCalled();
    expect(screen.getByText('Informe o IP, a porta e o código de 12 caracteres.')).toBeTruthy();
    fireEvent.changeText(screen.getByLabelText('IP do receptor'), '192.0.2.10');
    fireEvent.changeText(screen.getByLabelText('Porta'), '46000');
    fireEvent.changeText(screen.getByLabelText('Código de pareamento'), 'ABCD-EFGH-2345');
    fireEvent.press(screen.getByRole('button', {name: 'Conectar'}));
    expect(Network.startRelaySender).toHaveBeenCalledWith('192.0.2.10', 46000, 'ABCD-EFGH-2345');
    expect(screen.queryByLabelText('Código de pareamento')).toBeNull();
    jest.mocked(Network.getRelaySenderState).mockReturnValue('waiting');
    act(() => jest.advanceTimersByTime(300));
    expect(screen.getByText('Pareado. Aguardando um quadro-chave do AirPlay.')).toBeTruthy();
    jest.mocked(Network.getRelaySenderState).mockReturnValue('ending');
    fireEvent.press(screen.getByRole('button', {name: 'Parar retransmissão'}));
    expect(Network.stopRelaySender).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Encerrando retransmissão…')).toBeTruthy();
  } finally {
    screen.unmount();
    jest.useRealTimers();
  }
});
