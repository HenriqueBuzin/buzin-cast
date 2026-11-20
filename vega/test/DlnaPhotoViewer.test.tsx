import React from 'react';
import {act, fireEvent, render} from '@testing-library/react-native';
import {StyleSheet} from 'react-native';
import DlnaPhotoViewer from '../src/DlnaPhotoViewer';

jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {getVideoMargin: () => 2},
}));

const photo = {token: 10, index: 1, count: 2, source: {uri: 'data:image/png;base64,AAAA', width: 10, height: 20}};

test('gira no centro e mantém o modo de preenchimento ao trocar de foto', () => {
  const onDisplayed = jest.fn();
  const props = {photo, state: 'playing', blocked: false, viewportStyle: {}, onMove: jest.fn(), onClose: jest.fn(), onDisplayed};
  const screen = render(<DlnaPhotoViewer {...props} />);
  fireEvent(screen.getByTestId('dlna-photo-frame'), 'layout', {
    nativeEvent: {layout: {width: 800, height: 400}},
  });
  const image = () => screen.getByLabelText('Foto recebida pelo DLNA');
  const press = (name: string) => fireEvent.press(screen.getByRole('button', {name}));
  expect(image().props.resizeMode).toBe('contain');
  fireEvent(image(), 'load');
  expect(onDisplayed).toHaveBeenCalledWith(10, true);
  press('Girar');
  expect(StyleSheet.flatten(image().props.style)).toMatchObject({
    width: 400, height: 800, left: 200, top: -200, transform: [{rotate: '90deg'}],
  });
  press('Preencher');
  expect(image().props.resizeMode).toBe('cover');
  screen.rerender(<DlnaPhotoViewer {...props} photo={{...photo, token: 11, index: 0}} />);
  expect(StyleSheet.flatten(image().props.style)).toMatchObject({
    width: 800, height: 400, left: 0, top: 0, transform: [{rotate: '0deg'}],
  });
  expect(image().props.resizeMode).toBe('cover');
  expect(screen.getByText('Foto 1 de 2')).toBeTruthy();
  press('Anterior');
  expect(props.onMove).toHaveBeenLastCalledWith(-1);
  press('Próxima');
  expect(props.onMove).toHaveBeenLastCalledWith(1);
  press('Fechar foto');
  expect(props.onClose).toHaveBeenCalledTimes(1);
});

test('apresentação aguarda a foto, pausa durante aprovação e libera o timer ao fechar', () => {
  jest.useFakeTimers();
  try {
    const onMove = jest.fn();
    const props = {photo, state: 'playing', blocked: false, viewportStyle: {}, onMove, onClose: jest.fn(), onDisplayed: jest.fn()};
    const screen = render(<DlnaPhotoViewer {...props} />);
    fireEvent.press(screen.getByRole('button', {name: 'Apresentação'}));
    act(() => jest.advanceTimersByTime(5000));
    expect(onMove).toHaveBeenCalledWith(1);
    screen.rerender(<DlnaPhotoViewer {...props} state="loading" photo={{...photo, token: 11}} />);
    act(() => jest.advanceTimersByTime(10000));
    screen.rerender(<DlnaPhotoViewer {...props} blocked />);
    act(() => jest.advanceTimersByTime(10000));
    expect(onMove).toHaveBeenCalledTimes(1);
    screen.rerender(<DlnaPhotoViewer {...props} />);
    screen.unmount();
    act(() => jest.advanceTimersByTime(10000));
    expect(onMove).toHaveBeenCalledTimes(1);
  } finally {
    jest.useRealTimers();
  }
});
