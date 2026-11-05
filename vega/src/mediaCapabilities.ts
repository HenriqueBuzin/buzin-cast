/* eslint-disable @amazon-devices/kepler/sdl-package-version-check-imports -- OS-distributed API resolved by vega project install for OS 2.0; doctor and VVD verify compatibility. */
import {
  AudioDecoderConfig,
  CodecMimeType,
  KeplerMediaDescriptor,
  MediaFormat,
  VideoDecoderConfig,
} from '@amazon-devices/keplermediadescriptor';
import Network from './turbo-modules/BuzinNetwork';

// A capability match does not prove successful playback or hardware decoding.
// The SDK may also return an empty array on a platform query error.
export function getMediaReport(): string {
  const h264 = new VideoDecoderConfig();
  h264.mediaFormat = new MediaFormat(CodecMimeType.MIME_H264);
  const hevc = new VideoDecoderConfig();
  hevc.mediaFormat = new MediaFormat(CodecMimeType.MIME_HEVC);
  const aac = new AudioDecoderConfig();
  aac.mediaFormat = new MediaFormat(CodecMimeType.MIME_AAC);
  return Network.recordMediaCapabilities(
    KeplerMediaDescriptor.queryMediaCapabilitiesSync(h264).length,
    KeplerMediaDescriptor.queryMediaCapabilitiesSync(hevc).length,
    KeplerMediaDescriptor.queryMediaCapabilitiesSync(aac).length,
  );
}
