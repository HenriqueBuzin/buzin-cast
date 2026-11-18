#!/usr/bin/env python3
"""Exercise our own raw H.264 fixture through native MSSE on VVD only."""
import argparse
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = 'com.buzincast.tv.main'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mux', action='store_true', help='Verify native lossless fMP4 remuxing instead of MSSE playback')
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
    args.annex_b = args.annex_b or args.mux
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
        media = args.output / 'sample.mp4'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(ROOT / 'vega/assets/raw/media/playback-sample.mp4'),
                        '-an', '-c:v', 'libx264', '-profile:v', args.profile, '-pix_fmt', 'yuv420p',
                        '-bf', '0', '-g', '15', '-preset', 'fast' if args.profile == 'high' else 'ultrafast', str(media)], check=True, timeout=30)
        info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                         '-show_entries', 'stream=width,height,extradata:packet=pts_time,dts_time,pos,size,flags',
                         '-show_streams', '-show_packets', '-show_data', '-of', 'json', str(media)], timeout=30))
        stream = info['streams'][0]
        config = bytes.fromhex(''.join(line.split(':', 1)[1].strip().split('  ', 1)[0]
                               for line in stream['extradata'].splitlines() if ':' in line))
        prefix = b''
        if args.annex_b:
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
            if args.annex_b:
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
        (args.output / 'sample.bin').write_bytes(fixture)
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        shell('cat > /data/buzin-msse-video-probe', payload=args.runner.read_bytes())
        shell('cat > /data/buzin-msse-video.bin', payload=fixture)
        shell('chmod', '700', '/data/buzin-msse-video-probe')
        output = shell(*(['env', 'GST_DEBUG=6', 'GST_DEBUG_FILE=/data/buzin-video-gst.log'] if args.gst_debug else []), 'timeout', '55', '/data/buzin-msse-video-probe', '/data/buzin-msse-video.bin', '--mux' if args.mux else args.mime, timeout=60)
        (args.output / 'runner.log').write_text(output)
        cycles = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
        if len(cycles) != 2 or any(c.get('released') is not True or (not args.mux and c.get('seconds', 0) < 7.8) for c in cycles):
            raise RuntimeError('Two native video playback cycles were not observed')
        if not args.mux and any(c.get('totalFrames', 0) <= 0 for c in cycles):
            raise RuntimeError('No native video frames were observed; clock alone does not prove decoding')
        if args.mux:
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
            if len(hashes[0]) != len(packets) or hashes[0] != hashes[1]:
                raise RuntimeError('Decoded frames changed during native MP4 remuxing')
            result['decodedFramesIdentical'] = len(hashes[0])
        result.update(passed=True, mux=args.mux, rendered=False, annexB=args.annex_b, profile=args.profile, cycles=cycles)
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
