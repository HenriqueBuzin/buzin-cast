import React from 'react';
import {fireEvent, render} from '@testing-library/react-native';
import {Text} from 'react-native';
import TvButton from '../src/TvButton';

test('foca somente na ativação concluída e preserva uma única ação', () => {
  const {focus} = jest.requireActual<{default: {focus: jest.Mock}}>('react-native/jest/MockNativeMethods').default;
  focus.mockClear();
  const press = jest.fn();
  const screen = render(<TvButton onPress={press}><Text>Abrir</Text></TvButton>);
  const button = screen.getByText('Abrir');
  fireEvent(button, 'pressIn');
  fireEvent(button, 'pressOut');
  expect(focus).not.toHaveBeenCalled();
  expect(press).not.toHaveBeenCalled();
  fireEvent.press(button);
  expect(focus).toHaveBeenCalledTimes(1);
  expect(press).toHaveBeenCalledTimes(1);
});
