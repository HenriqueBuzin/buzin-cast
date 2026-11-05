/* global globalThis */
const {URL: StandardURL} = jest.requireActual<{URL: typeof URL}>('url');
import {object, playRequest} from '../src/fcastProtocol';

// Jest uses stock RN's legacy URL shim; Vega 2.0 supplies its fixed constructor.
const originalURL = globalThis.URL;
beforeAll(() => {
  globalThis.URL = StandardURL as unknown as typeof URL;
});
afterAll(() => {
  globalThis.URL = originalURL;
});
const valid = {url: 'http://192.168.1.2/video.mp4', container: 'video/mp4'};

test('preserva URL, cabeçalhos e limites de posição/velocidade do FCast Fire OS', () => {
  expect(
    playRequest({...valid, headers: {'X-Test': 'value'}, time: 2, speed: 1.25}),
  ).toEqual({
    url: valid.url,
    headers: {'X-Test': 'value'},
    position: 2,
    speed: 1.25,
  });
});

test.each([
  {url: 'file:///data/private.mp4'},
  {url: 'http://user:password@host/video.mp4'},
  {url: 'http://@host/video.mp4'},
  {url: 'http:////host/video.mp4'},
  {url: 'http://host:99999/video.mp4'},
  {url: 'http://host/\\video.mp4'},
  {url: 'http://host/vi\ndeo.mp4'},
  {url: `http://host/${'a'.repeat(8192)}`},
  {content: '<manifest/>'},
  {container: 'image/jpeg'},
  {url: 'https://host/video.m3u8?token=abc'},
  {headers: {Host: 'other'}},
  {headers: {'X-Test': 'injected\r\nHeader: x'}},
  {
    headers: Object.fromEntries(
      Array.from({length: 25}, (_, i) => [`X-${i}`, 'a']),
    ),
  },
  {time: -1},
  {speed: Infinity},
  {speed: 0},
])('recusa comando inválido antes de substituir a mídia: %j', (invalid) => {
  expect(() => playRequest({...valid, ...invalid})).toThrow();
});

test.each([null, [], 1, true, 'text'])(
  'corpos JSON devem ser objetos: %j',
  (body) => {
    expect(() => object(body)).toThrow();
  },
);
