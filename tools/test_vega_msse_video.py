#!/usr/bin/env python3
"""Generate our own H.264 fixture and exercise native mux/MSSE probes on VVD."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = 'com.buzincast.tv.main'


def make_fixture(output, profile='baseline', annex_b=True):
    media = output / 'sample.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(ROOT / 'vega/assets/raw/media/playback-sample.mp4'),
                    '-an', '-c:v', 'libx264', '-profile:v', profile, '-pix_fmt', 'yuv420p',
                    '-bf', '0', '-g', '15', '-preset', 'fast' if profile == 'high' else 'ultrafast', str(media)], check=True, timeout=30)
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                     '-show_entries', 'stream=width,height,extradata:packet=pts_time,dts_time,pos,size,flags',
                     '-show_streams', '-show_packets', '-show_data', '-of', 'json', str(media)], timeout=30))
    stream = info['streams'][0]
    config = bytes.fromhex(''.join(line.split(':', 1)[1].strip().split('  ', 1)[0]
                           for line in stream['extradata'].splitlines() if ':' in line))
    prefix = b''
    if annex_b:
        offset = 6
        for count in [config[5] & 31, None]:
            if count is None:
                count = config[offset]
                offset += 1
            for _ in range(count):
                size = int.from_bytes(config[offset:offset + 2], 'big')
                offset += 2
                prefix += b'\0\0\0\1' + config[offset:offset + size]
                offset += size
        config = prefix
    packets = info['packets']
    encoded = media.read_bytes()
    number = lambda n: struct.pack('<I', n)
    payload = lambda data: number(len(data)) + data
    fixture = number(stream['width']) + number(stream['height']) + payload(config) + number(len(packets))
    for i, packet in enumerate(packets):
        if packet['pts_time'] != packet['dts_time']:
            raise RuntimeError('Probe requires a fixture without reordered frames')
        pts = round(float(packet['pts_time']) * 1000)
        following = round(float(packets[i + 1]['pts_time']) * 1000) if i + 1 < len(packets) else 8000
        data = encoded[int(packet['pos']):int(packet['pos']) + int(packet['size'])]
        if annex_b:
            units = b''
            offset = 0
            while offset < len(data):
                size = int.from_bytes(data[offset:offset + 4], 'big')
                offset += 4
                if size < 1 or offset + size > len(data):
                    raise RuntimeError('Invalid fixture AVC access unit')
                units += b'\0\0\0\1' + data[offset:offset + size]
                offset += size
            data = (prefix if 'K' in packet['flags'] else b'') + units
        fixture += number(pts) + number(following - pts) + number('K' in packet['flags']) + payload(data)
    (output / 'sample.bin').write_bytes(fixture)
    return media, fixture, len(packets)


AUDIO_PROFILES = {
    'lc': (4, 1024, 'aac', 'LC'),
    'eld480': (8, 480, 'aac', 'ELD'),
    'eld512': (8, 512, 'aac', 'ELD'),
    'alac': (2, 352, 'alac', None),
    'alac-aac': (2, 352, 'aac', 'LC'),
}


def make_aac_lc_packets(output):
    path = output / 'audio-source.m4a'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                    'sine=frequency=997:sample_rate=44100:duration=8', '-ac', '2',
                    '-c:a', 'aac', '-profile:a', 'aac_low', str(path)], check=True, timeout=30)
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error',
                     '-show_packets', '-select_streams', 'a:0', '-show_entries', 'packet=pos,size',
                     '-of', 'json', str(path)], timeout=30))
    data = path.read_bytes()
    packets = []
    for packet in info['packets']:
        position, size = int(packet['pos']), int(packet['size'])
        packets.append(data[position:position + size])
    return packets


def append_audio_fixture(output, fixture, profile):
    codec, samples, _, _ = AUDIO_PROFILES[profile]
    if profile == 'lc':
        packets = make_aac_lc_packets(output)
    else:
        assets = ROOT / 'fireos3-probe/src/main/assets/audio_samples.json'
        formats = json.loads(assets.read_text())
        selected = next(item for item in formats if item['codec'] == codec and item['samplesPerFrame'] == samples)
        packet_loop = [base64.b64decode(item, validate=True) for item in selected['packets']]
        count = -(-8 * 44100 // samples)
        packets = [packet_loop[i % len(packet_loop)] for i in range(count)]
    delay = 80000
    suffix = struct.pack('<III', delay, samples, len(packets))
    suffix += b''.join(struct.pack('<I', len(packet)) + packet for packet in packets)
    return fixture + suffix, delay, packets


def verify_audio_timeline(media, delay, raw_packets, profile):
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error',
                     '-show_packets', '-show_streams', '-select_streams', 'a:0',
                     '-show_data_hash', 'sha256', '-of', 'json', str(media)], timeout=30))
    stream, = info['streams']
    packets = info['packets']
    _, input_samples, expected_codec, expected_profile = AUDIO_PROFILES[profile]
    converted = profile == 'alac-aac'
    samples = 1024 if converted else input_samples
    if (stream['codec_name'], stream['sample_rate'], stream['channels'], stream['time_base']) != (expected_codec, '44100', 2, '1/44100'):
        raise RuntimeError('Native MP4 did not preserve the audio format')
    if expected_profile and stream.get('profile') != expected_profile:
        raise RuntimeError(f'Expected {expected_profile} but got {stream.get("profile")}')
    expected_packets = -(-len(raw_packets) * input_samples // samples)
    if len(packets) != expected_packets:
        raise RuntimeError('Native MP4 has the wrong audio packet count')
    initial_pts = round(delay * 44100 / 1000000)
    for index, packet in enumerate(packets):
        if ((not converted and packet['data_hash'] != 'SHA256:' + hashlib.sha256(raw_packets[index]).hexdigest())
                or int(packet['pts']) != initial_pts + index * samples
                or int(packet['duration']) != samples):
            raise RuntimeError(f'Native MP4 changed audio bytes or timeline at packet {index}')
    pcm = subprocess.check_output(['ffmpeg', '-v', 'error', '-xerror', '-i', str(media),
                                  '-map', '0:a:0', '-c:a', 'pcm_s16le', '-f', 's16le', '-'], timeout=30)
    decoded_frames = len(packets) * samples
    if len(pcm) != decoded_frames * 4:
        raise RuntimeError('Native MP4 audio decoded to the wrong number of stereo PCM frames')
    if converted and not any(pcm):
        raise RuntimeError('AAC encoder produced silence from the ALAC fixture')
    return {'audioPacketsIdentical': 0 if converted else len(packets),
            'audioPacketsWritten': len(packets), 'audioTranscoded': converted,
            'audioStartUs': delay, 'audioDecodedOnHost': True,
            'audioProfile': profile, 'audioSamplesPerPacket': samples, 'decodedPcmFrames': decoded_frames}


def verify_video_timeline(source, remux):
    def timestamps(path):
        info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_packets',
                         '-select_streams', 'v:0', '-show_entries', 'packet=pts_time',
                         '-of', 'json', str(path)], timeout=30))
        return [round(float(packet['pts_time']) * 1000000) for packet in info['packets']]

    expected = [round(value / 1000) * 1000 for value in timestamps(source)]
    if timestamps(remux) != expected:
        raise RuntimeError('Native MP4 changed video timestamps relative to the AAC track')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--mux', action='store_true', help='Verify native lossless video fMP4 remuxing')
    modes.add_argument('--av-mux', action='store_true', help='Verify native A/V fMP4 and the shared timeline')
    modes.add_argument('--mirror-reconnect', action='store_true', help='Verify encrypted mirror media EOF and a second connection')
    parser.add_argument('--audio-profile', choices=AUDIO_PROFILES, default='lc')
    parser.add_argument('--profile', choices=['baseline', 'high'], default='baseline')
    parser.add_argument('--mime', choices=['video/avc', 'video/h264'], default='video/avc')
    parser.add_argument('--gst-debug', action='store_true', help='Capture native H.264 caps negotiation diagnostics')
    parser.add_argument('--annex-b', action='store_true', help='Send Annex B access units like UxPlay')
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-msse-video')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    remuxing = args.mux or args.av_mux
    args.annex_b = args.annex_b or remuxing or args.mirror_reconnect
    args.output.mkdir(parents=True, exist_ok=True)
    history = []
    result = {'passed': False, 'physicalValidation': False, 'surfaceAttached': False}
    sdk = shutil.which('vega')

    def run(*command, payload=None, timeout=30):
        completed = subprocess.run([sdk, *command], input=payload, capture_output=True, timeout=timeout)
        stdout, stderr = completed.stdout.decode(errors='replace'), completed.stderr.decode(errors='replace')
        history.append({'args': command, 'exit': completed.returncode, 'stdout': stdout, 'stderr': stderr})
        if completed.returncode:
            raise RuntimeError((stderr or stdout)[-12000:])
        return stdout

    def shell(*command, **options):
        return run('exec', 'vda', '-s', args.serial, 'shell', '-T', '-c', COMPONENT, *command, **options)

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        media, fixture, packet_count = make_fixture(args.output, args.profile, args.annex_b)
        if args.av_mux:
            fixture, audio_delay, audio_packets = append_audio_fixture(args.output, fixture, args.audio_profile)
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        shell('cat > /data/buzin-msse-video-probe', payload=args.runner.read_bytes())
        shell('cat > /data/buzin-msse-video.bin', payload=fixture)
        shell('chmod', '700', '/data/buzin-msse-video-probe')
        if args.mirror_reconnect:
            mode = '--mirror-reconnect'
        elif args.av_mux:
            mode = '--av-transcode' if args.audio_profile == 'alac-aac' else '--av-mux'
        else:
            mode = '--mux' if args.mux else args.mime
        output = shell(*(['env', 'GST_DEBUG=6', 'GST_DEBUG_FILE=/data/buzin-video-gst.log'] if args.gst_debug else []), 'timeout', '55', '/data/buzin-msse-video-probe', '/data/buzin-msse-video.bin', mode, timeout=60)
        (args.output / 'runner.log').write_text(output)
        cycles = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
        if len(cycles) != 2 or any(c.get('released') is not True for c in cycles):
            raise RuntimeError('Two native video playback cycles were not observed')
        if args.av_mux and any(c.get('workerOwned') is not True or c.get('audioPackets') != len(audio_packets) for c in cycles):
            raise RuntimeError('Expected both tracks to pass through the native callback worker')
        if args.mirror_reconnect and (any(c.get('nativeEof') is not True or c.get('frames') != 1 for c in cycles) or
                                      cycles[0]['generation'] >= cycles[1]['generation']):
            raise RuntimeError('Native mirror did not renew and release its transport twice')
        if not remuxing and not args.mirror_reconnect and any(c.get('totalFrames', 0) <= 0 or c.get('seconds', 0) < 7.8 for c in cycles):
            raise RuntimeError('No native video frames were observed; clock alone does not prove decoding')
        if remuxing:
            # Byte transport must remain binary: subprocess's text shell helper
            # is only for JSON/log output, never media payloads.
            completed = subprocess.run([sdk, 'exec', 'vda', '-s', args.serial, 'shell', '-T', '-c', COMPONENT,
                                       'cat', '/data/buzin-video-remux.mp4'], capture_output=True, check=True, timeout=30)
            remux = args.output / 'remux.mp4'
            remux.write_bytes(completed.stdout)
            hashes = []
            for path in [media, remux]:
                raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(path), '-an', '-f', 'framemd5', '-'], timeout=30).decode()
                hashes.append([line.rsplit(',', 1)[1].strip() for line in raw.splitlines() if line and not line.startswith('#')])
            if len(hashes[0]) != packet_count or hashes[0] != hashes[1]:
                raise RuntimeError('Decoded frames changed during native MP4 remuxing')
            result['decodedFramesIdentical'] = len(hashes[0])
            if args.av_mux:
                verify_video_timeline(media, remux)
                result.update(verify_audio_timeline(remux, audio_delay, audio_packets, args.audio_profile))
        result.update(passed=True, mux=remuxing, avMux=args.av_mux, mirrorReconnect=args.mirror_reconnect,
                      rendered=False, annexB=args.annex_b, profile=args.profile, cycles=cycles)
        print(json.dumps(result, indent=2))
    except Exception as error:
        result['error'] = str(error)
        try:
            logs = shell('cat', '/data/buzin-video-gst.log')
            (args.output / 'gst.log').write_text(logs)
        except Exception:
            pass
        try:
            logs = run('exec', 'vda', '-s', args.serial, 'shell', 'loggingctl', 'log', '-v', 'buzin-msse-video-probe')
            (args.output / 'receiver.log').write_text(logs)
        except Exception:
            pass
        raise
    finally:
        for name in ['/data/buzin-msse-video-probe', '/data/buzin-msse-video.bin', '/data/buzin-video-gst.log', '/data/buzin-video-remux.mp4']:
            try:
                shell('rm', '-f', name)
            except Exception:
                pass
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        (args.output / 'commands.json').write_text(json.dumps(history, indent=2) + '\n')


if __name__ == '__main__':
    main()
