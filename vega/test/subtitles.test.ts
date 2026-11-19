import {encode} from 'base-64';
import {captionAt, parseSubtitle} from '../src/subtitles';

test('SRT UTF-8 with a BOM displays accents, overlaps and exact cue boundaries', () => {
  const subtitle = '\xef\xbb\xbf1\r\n00:00:01,500 --> 00:00:03,000\r\nOl\xc3\xa1\r\n\r\n' +
    '2\r\n00:00:02,000 --> 00:00:04,000\r\nOutra linha\r\n';
  const cues = parseSubtitle(encode(subtitle));
  expect(captionAt(cues, 1.49)).toBe('');
  expect(captionAt(cues, 1.5)).toBe('Olá');
  expect(captionAt(cues, 2)).toBe('Olá\nOutra linha');
  expect(captionAt(cues, 3)).toBe('Outra linha');
  expect(captionAt(cues, 4)).toBe('');
  expect(captionAt(cues, 1.5)).toBe('Olá');
});

test('WebVTT handles cue IDs, metadata, markup and entities as plain text', () => {
  const subtitle = 'WEBVTT\n\nNOTE comments\nignored\n\ncue-id\n00:01.000 --> 00:02.000 align:start\n' +
    '<v Narrator><i>Me &amp; you</i><br/>two lines\n';
  expect(captionAt(parseSubtitle(encode(subtitle)), 1.5)).toBe('Me & you\ntwo lines');
});

test.each(['\xff', 'WEBVTT\n\n', '00:00:03,000 --> 00:00:01,000\nText', '01:80:00,000 --> 01:90:01,000\nText'])(
  'rejects malformed subtitle input %#',
  subtitle => expect(() => parseSubtitle(encode(subtitle))).toThrow(),
);
