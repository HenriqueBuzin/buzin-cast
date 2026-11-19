import {decode, encode} from 'base-64';

export const receiverId = 'receiver-0';
export const defaultAppId = 'CC1AD845';
const namespace = 'urn:x-cast:com.google.cast.';
export const castChannel = {
  connection: namespace + 'tp.connection',
  heartbeat: namespace + 'tp.heartbeat',
  authentication: namespace + 'tp.deviceauth',
  receiver: namespace + 'receiver',
  media: namespace + 'media',
};

export type CastMessage = {
  source: string;
  destination: string;
  namespace: string;
} & ({text: string} | {binary: string});

const maxFrame = 65536;

function toUtf8(text: string) {
  return encodeURIComponent(text).replace(/%([A-F0-9]{2})/g, (_, hex) =>
    String.fromCharCode(parseInt(hex, 16)),
  );
}

function fromUtf8(bytes: string) {
  return decodeURIComponent(
    bytes.replace(/[\s\S]/g, byte =>
      '%' + byte.charCodeAt(0).toString(16).padStart(2, '0'),
    ),
  );
}

function varint(value: number) {
  let bytes = '';
  while (value >= 128) {
    bytes += String.fromCharCode((value % 128) + 128);
    value = Math.floor(value / 128);
  }
  return bytes + String.fromCharCode(value);
}

function field(id: number, bytes: string) {
  return varint(id * 8 + 2) + varint(bytes.length) + bytes;
}

export function encodeCastMessage(message: CastMessage) {
  const binary = 'binary' in message;
  const bytes = '\x08\x00' +
    field(2, toUtf8(message.source)) +
    field(3, toUtf8(message.destination)) +
    field(4, toUtf8(message.namespace)) +
    '\x28' + varint(binary ? 1 : 0) +
    (binary ? field(7, message.binary) : field(6, toUtf8(message.text)));
  if (bytes.length > maxFrame) {throw new Error('Cast envelope is too large');}
  return encode(bytes);
}

export function decodeCastMessage(encoded: string): CastMessage {
  if (encoded.length > Math.ceil(maxFrame / 3) * 4) {
    throw new Error('Cast envelope is too large');
  }
  const bytes = decode(encoded);
  if (bytes.length > maxFrame) {throw new Error('Cast envelope is too large');}
  let offset = 0;
  function readVarint() {
    let value = 0;
    for (let i = 0; i < 5 && offset < bytes.length; ++i) {
      const byte = bytes.charCodeAt(offset++);
      value += (byte % 128) * 128 ** i;
      if (byte < 128 && value <= 0xffffffff) {return value;}
    }
    throw new Error('Invalid Cast varint');
  }
  function readBytes(size: number) {
    if (size > bytes.length - offset) {throw new Error('Truncated Cast envelope');}
    const value = bytes.slice(offset, offset + size);
    offset += size;
    return value;
  }
  const fields = new Map<number, string | number>();
  while (offset < bytes.length) {
    const tag = readVarint();
    const id = Math.floor(tag / 8);
    const wire = tag % 8;
    if (!id) {throw new Error('Invalid Cast field');}
    if (id <= 9) {
      const lengthDelimited = [2, 3, 4, 6, 7].includes(id);
      if (wire !== (lengthDelimited ? 2 : 0) || fields.has(id)) {
        throw new Error('Invalid Cast field');
      }
      fields.set(id, lengthDelimited ? readBytes(readVarint()) : readVarint());
    } else if (wire === 0) {
      readVarint();
    } else {
      const size = wire === 2 ? readVarint() : wire === 1 ? 8 : wire === 5 ? 4 : -1;
      if (size < 0) {throw new Error('Unsupported Cast wire type');}
      readBytes(size);
    }
  }
  function string(id: number, limit: number) {
    const value = fields.get(id);
    if (typeof value !== 'string' || !value || value.length > limit) {
      throw new Error('Invalid Cast address');
    }
    return fromUtf8(value);
  }
  const type = fields.get(5);
  if (fields.get(1) !== 0 || fields.get(8) || fields.get(9) || (type !== 0 && type !== 1) ||
      fields.has(type === 0 ? 7 : 6)) {
    throw new Error('Unsupported Cast envelope');
  }
  const payload = fields.get(type === 0 ? 6 : 7);
  if (typeof payload !== 'string') {throw new Error('Missing Cast payload');}
  const address = {source: string(2, 128), destination: string(3, 128), namespace: string(4, 256)};
  return type === 0 ? {...address, text: fromUtf8(payload)} : {...address, binary: payload};
}
