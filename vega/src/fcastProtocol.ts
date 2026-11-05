import type {PlaybackRequest} from './playbackSession';

type ObjectValue = Record<string, unknown>;
function controls(value: string, includeSpace = false) {
  for (let i = 0; i < value.length; ++i) {
    const code = value.charCodeAt(i);
    if (code < (includeSpace ? 33 : 32) || (code >= 127 && code <= 159)) {
      return true;
    }
  }
  return false;
}
export function object(value: unknown): ObjectValue {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Expected a JSON object');
  }
  return value as ObjectValue;
}
export function number(
  body: ObjectValue,
  key: string,
  min: number,
  max: number,
): number {
  const value = body[key];
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < min ||
    value > max
  ) {
    throw new Error(`Invalid ${key}`);
  }
  return value;
}
export function playRequest(body: ObjectValue): PlaybackRequest {
  if (body.content != null) {
    throw new Error('Inline manifests are not supported');
  }
  if (
    typeof body.url !== 'string' ||
    body.url.length > 8192 ||
    !/^https?:\/\//.test(body.url) ||
    controls(body.url, true) ||
    body.url.includes('\\')
  ) {
    throw new Error('Expected HTTP(S) media URL');
  }
  // Vega fixes the native URL constructor; RN's declaration still omits
  // some WHATWG properties. Validate the parsed serialization instead.
  const normalized = new URL(body.url).toString();
  const authority = normalized.split('/')[2];
  if (
    !authority ||
    authority.includes('@') ||
    !body.url.split('/')[2] ||
    body.url.split('/')[2].includes('@')
  ) {
    throw new Error('Expected HTTP(S) media URL');
  }
  if (
    typeof body.container !== 'string' ||
    body.container.length > 120 ||
    !/^(video|audio)\//.test(body.container)
  ) {
    throw new Error('Expected progressive audio/video media');
  }
  if (
    /mpegurl|dash\+xml/i.test(body.container) ||
    /\.(m3u8|mpd)$/i.test(
      normalized.replace(/^https?:\/\/[^/]+/, '').split(/[?#]/)[0],
    )
  ) {
    throw new Error('Adaptive playback is not implemented on Vega yet');
  }
  const headers: Record<string, string> = {};
  if (body.headers != null) {
    const entries = Object.entries(object(body.headers));
    if (entries.length > 24) {
      throw new Error('Too many headers');
    }
    for (const [key, value] of entries) {
      if (
        !/^[A-Za-z0-9-]{1,80}$/.test(key) ||
        typeof value !== 'string' ||
        value.length > 4096 ||
        controls(value) ||
        ['host', 'connection', 'content-length', 'transfer-encoding'].includes(
          key.toLowerCase(),
        )
      ) {
        throw new Error('Invalid media header');
      }
      headers[key] = value;
    }
  }
  return {
    url: body.url,
    headers,
    position: body.time == null ? 0 : number(body, 'time', 0, 31536000),
    speed: body.speed == null ? 1 : number(body, 'speed', 0.25, 4),
  };
}
