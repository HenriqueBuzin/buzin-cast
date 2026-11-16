"""Generate our own short codec fixtures; no recordings or third-party audio.

Requires host ffmpeg/ffprobe and our host-only FDK AAC fixture helper. Outputs raw packets
extracted from the MP4 packet offsets, so Android need not demux ALAC on API17.
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import struct
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--ffprobe', default='ffprobe')
    parser.add_argument('--eld-encoder', type=Path, required=True,
                        help='Host executable built from legacy_aac_fixture_encoder.cpp with FDK AAC v2.0.3')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] /
                        'fireos3-probe/src/main/assets/audio_samples.json')
    args = parser.parse_args()
    fixtures = []
    with tempfile.TemporaryDirectory() as temp:
        for encoder, ct, spf in [('alac', 2, 352), ('aac', 4, 1024)]:
            target = Path(temp)/(encoder+'.m4a')
            sample_format = ['-sample_fmt', 's16p'] if ct == 2 else []
            subprocess.run([args.ffmpeg, '-v', 'error', '-f', 'lavfi', '-i',
                            'sine=frequency=440:sample_rate=44100', '-ac', '2',
                            '-af', 'atrim=end_sample='+str(spf if ct == 2 else spf*4), '-c:a', encoder,
                            *sample_format, '-y', str(target)], check=True)
            packet_info = json.loads(subprocess.check_output([
                args.ffprobe, '-v', 'error', '-select_streams', 'a:0',
                '-show_packets', '-of', 'json', str(target)]))
            encoded = target.read_bytes()
            packets = [base64.b64encode(encoded[int(p['pos']):int(p['pos'])+int(p['size'])])
                       .decode('ascii') for p in packet_info['packets']]
            fixtures.append({'codec': ct, 'samplesPerFrame': spf,
                             'description': 'Synthetic 440 Hz tone, 44100 Hz stereo',
                             'packets': packets})
        for spf in (480, 512):
            target = Path(temp)/('eld-'+str(spf)+'.bin')
            subprocess.run([str(args.eld_encoder.resolve()), str(spf), str(target)], check=True)
            data = target.read_bytes()
            records = []
            while data:
                if len(data) < 4:
                    raise ValueError('Truncated fixture record')
                size = struct.unpack('<I', data[:4])[0]
                if size < 1 or size > len(data)-4:
                    raise ValueError('Invalid fixture record length')
                records.append(data[4:4+size])
                data = data[4+size:]
            expected = bytes.fromhex('f8e85000' if spf == 480 else 'f8e84000')
            if len(records) < 5 or records[0] != expected:
                raise ValueError('AAC ELD configuration does not match the receiver')
            fixtures.append({'codec': 8, 'samplesPerFrame': spf,
                             'description': 'Synthetic 440 Hz tone, FDK AAC v2.0.3, 44100 Hz stereo, no SBR',
                             'packets': [base64.b64encode(p).decode('ascii') for p in records[1:]]})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(fixtures, indent=2)+'\n', encoding='utf-8')
    print('Generated '+str(len(fixtures))+' synthetic codec fixtures')


if __name__ == '__main__':
    main()
