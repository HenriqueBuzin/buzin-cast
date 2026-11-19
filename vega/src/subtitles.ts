import {decode} from 'base-64';

type Cue = {start: number; end: number; text: string};
const entities: Record<string, string> = {
  '&amp;': '&', '&lt;': '<', '&gt;': '>', '&nbsp;': '\u00a0', '&lrm;': '\u200e', '&rlm;': '\u200f',
};

function timestamp(value: string, vtt: boolean) {
  const match = vtt
    ? /^(?:(\d{2,6}):)?([0-5]\d):([0-5]\d)\.(\d{3})$/.exec(value)
    : /^(\d{2,6}):([0-5]\d):([0-5]\d),(\d{3})$/.exec(value);
  if (!match) {throw new Error('Invalid subtitle timestamp');}
  return (((Number(match[1] ?? 0) * 60 + Number(match[2])) * 60 + Number(match[3])) * 1000 + Number(match[4])) / 1000;
}

export function parseSubtitle(encoded: string): Cue[] {
  if (encoded.length > 4 * Math.ceil(2 * 1024 * 1024 / 3)) {throw new Error('Invalid subtitle size');}
  const bytes = decode(encoded);
  if (!bytes.length || bytes.length > 2 * 1024 * 1024) {throw new Error('Invalid subtitle size');}
  // The bridge transports raw bytes as base64, so malformed UTF-8 is rejected here.
  const text = decodeURIComponent(bytes.replace(/[\s\S]/g, byte =>
    '%' + byte.charCodeAt(0).toString(16).padStart(2, '0'),
  )).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
  if (text.includes('\0')) {throw new Error('Invalid subtitle text');}
  const blocks = text.trim().split(/\n[\t ]*\n+/);
  const vtt = /^WEBVTT(?:[\t ]|\n|$)/.test(blocks[0]);
  if (vtt) {blocks.shift();}
  const cues: Cue[] = [];
  for (const block of blocks) {
    if (vtt && /^(NOTE(?:[\t ]|\n|$)|STYLE(?:\n|$)|REGION(?:\n|$))/.test(block)) {continue;}
    const lines = block.split('\n');
    let timing = lines.shift() ?? '';
    if (!timing.includes('-->') && (vtt || /^\d+$/.test(timing.trim()))) {timing = lines.shift() ?? '';}
    const match = /^(\S+)[\t ]+-->[\t ]+(\S+)(?:[\t ]+.*)?$/.exec(timing.trim());
    if (!match) {throw new Error('Invalid subtitle cue');}
    const start = timestamp(match[1], vtt);
    const end = timestamp(match[2], vtt);
    const caption = lines.join('\n')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/?(?:b|i|u|ruby|rt|c|v|lang|font)(?=[.\s>])[^>]*>|<\d[\d:.]+>/gi, '')
      .replace(/&(?:amp|lt|gt|nbsp|lrm|rlm);/g, entity => entities[entity] ?? entity);
    if (end <= start || !caption.trim() || caption.length > 4000 || cues.length >= 20000) {
      throw new Error('Invalid subtitle cue');
    }
    cues.push({start, end, text: caption});
  }
  if (!cues.length) {throw new Error('Subtitle has no cues');}
  return cues.sort((a, b) => a.start - b.start);
}

export function captionAt(cues: Cue[], seconds: number) {
  let visible = '';
  for (const cue of cues) {
    if (cue.start > seconds || visible.length >= 4000) {break;}
    if (cue.end > seconds) {visible += (visible ? '\n' : '') + cue.text;}
  }
  return visible.slice(0, 4000);
}
