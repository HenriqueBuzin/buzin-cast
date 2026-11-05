/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- Exercise the actual OS 2.0 SDK configuration classes, mocking only native queries. */
import {
  AudioDecoderConfig,
  CodecMimeType,
  KeplerMediaDescriptor,
  VideoDecoderConfig,
} from '@amazon-devices/keplermediadescriptor';
import {getMediaReport} from '../src/mediaCapabilities';
import Network from '../src/turbo-modules/BuzinNetwork';

jest.mock(
  '@amazon-devices/keplermediadescriptor/dist/turbo-modules/KeplerMediaDescriptorTurboModule',
  () => ({__esModule: true, default: {}}),
);
jest.mock('../src/turbo-modules/BuzinNetwork', () => ({
  __esModule: true,
  default: {recordMediaCapabilities: jest.fn(() => 'saved report')},
}));

afterEach(() => {
  jest.restoreAllMocks();
  jest.clearAllMocks();
});

test('consulta os três MIME nos tipos corretos do SDK e preserva zero resultados', () => {
  const query = jest
    .spyOn(KeplerMediaDescriptor, 'queryMediaCapabilitiesSync')
    .mockReturnValueOnce(new Array(2))
    .mockReturnValueOnce([])
    .mockReturnValueOnce(new Array(1));
  expect(getMediaReport()).toBe('saved report');
  const configs = query.mock.calls.map(([config]) => config);
  expect(configs[0]).toBeInstanceOf(VideoDecoderConfig);
  expect(configs[1]).toBeInstanceOf(VideoDecoderConfig);
  expect(configs[2]).toBeInstanceOf(AudioDecoderConfig);
  expect(configs.map((config) => config.mediaFormat.mimeType)).toEqual([
    CodecMimeType.MIME_H264,
    CodecMimeType.MIME_HEVC,
    CodecMimeType.MIME_AAC,
  ]);
  expect(Network.recordMediaCapabilities).toHaveBeenCalledWith(2, 0, 1);
});

test('uma consulta interrompida não grava um relatório parcial de suporte', () => {
  jest
    .spyOn(KeplerMediaDescriptor, 'queryMediaCapabilitiesSync')
    .mockReturnValueOnce([])
    .mockImplementationOnce(() => {
      throw new Error('media service unavailable');
    });
  expect(getMediaReport).toThrow('media service unavailable');
  expect(Network.recordMediaCapabilities).not.toHaveBeenCalled();
});
