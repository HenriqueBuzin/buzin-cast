import React from 'react';
import {render} from '@testing-library/react-native';
import App from '../src/App';

test('identifica a prévia e informa que o receptor está em desenvolvimento', () => {
  const screen = render(<App />);
  expect(screen.getByLabelText('Buzin Cast')).toBeTruthy();
  expect(screen.getByText('Prévia para Vega OS')).toBeTruthy();
  expect(screen.getByText('Receptor em desenvolvimento')).toBeTruthy();
});
