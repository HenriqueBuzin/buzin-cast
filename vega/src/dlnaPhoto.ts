import {encode} from 'base-64';

export const DLNA_PHOTO_TYPES = ['image/jpeg', 'image/png'] as const;
const MAX_BYTES = 16 * 1024 * 1024;
const MAX_PIXELS = 24 * 1024 * 1024;
type PhotoType = typeof DLNA_PHOTO_TYPES[number];

function dimensions(bytes: Uint8Array): {mime: PhotoType; width: number; height: number} {
  const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length >= 33 && data.getUint32(0) === 0x89504e47 &&
      data.getUint32(4) === 0x0d0a1a0a && data.getUint32(8) === 13 && data.getUint32(12) === 0x49484452) {
    return {mime: 'image/png', width: data.getUint32(16), height: data.getUint32(20)};
  }
  if (bytes.length >= 4 && data.getUint16(0) === 0xffd8) {
    const frameMarkers = [0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf];
    let offset = 2;
    while (offset + 3 < bytes.length && bytes[offset++] === 0xff) {
      while (bytes[offset] === 0xff) {++offset;}
      const marker = bytes[offset++];
      if (offset + 2 > bytes.length || marker === 0xda || marker === 0xd9) {break;}
      const length = data.getUint16(offset);
      if (length < 2 || offset + length > bytes.length) {break;}
      if (frameMarkers.includes(marker) && length >= 8) {
        return {mime: 'image/jpeg', width: data.getUint16(offset + 5), height: data.getUint16(offset + 3)};
      }
      offset += length;
    }
  }
  throw new Error('Unsupported image');
}

export function photoSource(buffer: ArrayBuffer) {
  if (buffer.byteLength > MAX_BYTES) {throw new Error('Image exceeds byte limit');}
  const bytes = new Uint8Array(buffer);
  const {mime, width, height} = dimensions(bytes);
  if (!width || !height || width * height > MAX_PIXELS) {throw new Error('Image exceeds pixel limit');}
  // Groups of three bytes preserve base64 alignment when encoding in chunks.
  const segments: string[] = [];
  const step = 3 * 4096;
  for (let offset = 0; offset < bytes.length; offset += step) {
    segments.push(encode(String.fromCharCode(...bytes.subarray(offset, offset + step))));
  }
  return {uri: `data:${mime};base64,${segments.join('')}`, width, height};
}

export function loadDlnaPhoto(url: string, ready: (source: ReturnType<typeof photoSource>) => void, failed: () => void) {
  const request = new XMLHttpRequest();
  let finished = false;
  function cancel() {
    if (finished) {return;}
    finished = true;
    request.abort();
  }
  function fail() {
    if (finished) {return;}
    cancel();
    failed();
  }
  request.open('GET', url);
  request.responseType = 'arraybuffer';
  request.timeout = 15000;
  request.withCredentials = false;
  request.setRequestHeader('Accept', DLNA_PHOTO_TYPES.join(', '));
  request.onreadystatechange = () => {
    if (request.readyState === 2 && Number(request.getResponseHeader('Content-Length')) > MAX_BYTES) {fail();}
  };
  request.onprogress = event => {
    if (event.loaded > MAX_BYTES || (event.lengthComputable && event.total > MAX_BYTES)) {fail();}
  };
  request.onload = () => {
    if (finished) {return;}
    try {
      if (request.status !== 200 || !(request.response instanceof ArrayBuffer)) {throw new Error('Image download failed');}
      const source = photoSource(request.response);
      finished = true;
      ready(source);
    } catch {fail();}
  };
  request.onerror = fail;
  request.ontimeout = fail;
  request.send();
  return cancel;
}
