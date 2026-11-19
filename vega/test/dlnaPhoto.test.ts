import {photoSource} from '../src/dlnaPhoto';
import {isDlnaPhoto} from '../src/dlnaProtocol';

function pngHeader(width: number, height: number) {
  const buffer = new ArrayBuffer(33);
  const header = new DataView(buffer);
  for (const [offset, value] of [[0, 0x89504e47], [4, 0x0d0a1a0a], [8, 13], [12, 0x49484452], [16, width], [20, height]]) {
    header.setUint32(offset, value);
  }
  return buffer;
}

test('limita JPEG e PNG antes de entregar os bytes ao decodificador', () => {
  const png = photoSource(pngHeader(1920, 1080));
  expect(png).toMatchObject({width: 1920, height: 1080});
  expect(png.uri).toMatch(/^data:image\/png;base64,/);
  expect(() => photoSource(pngHeader(100000, 100000))).toThrow('pixel limit');
  expect(() => photoSource(new ArrayBuffer(16 * 1024 * 1024 + 1))).toThrow('byte limit');
  const jpeg = new Uint8Array([255, 216, 255, 192, 0, 8, 8, 0, 64, 0, 128, 1]);
  expect(photoSource(jpeg.buffer)).toMatchObject({width: 128, height: 64});
  expect(() => photoSource(jpeg.slice(0, -2).buffer)).toThrow('Unsupported image');
});

test('distingue o recurso DIDL de uma capa ou de outro item', () => {
  const uri = 'http://example.invalid/media?id=1';
  const metadata = (mime: string) => `<DIDL-Lite><item><res protocolInfo="http-get:*:${mime}:*">${uri}</res></item></DIDL-Lite>`;
  expect(isDlnaPhoto(uri, metadata('image/jpeg'))).toBe(true);
  expect(isDlnaPhoto(uri, metadata('video/mp4'))).toBe(false);
  expect(isDlnaPhoto('http://example.invalid/movie', metadata('image/png'))).toBe(false);
  expect(isDlnaPhoto('https://example.invalid/IMG.JPG?token=1', '')).toBe(true);
  expect(() => isDlnaPhoto(uri, metadata('image/gif'))).toThrow('Unsupported photo format');
});
