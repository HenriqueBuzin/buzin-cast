#!/usr/bin/env python3
"""Exercise native MSSE AAC access units in two sandboxed VVD cycles."""
import argparse
import base64
import math
import struct
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = 'com.buzincast.tv.main'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--worker', action='store_true')
    parser.add_argument('--eld', type=int, choices=[480, 512], help='Probe native AAC-ELD using our synthetic fixture')
    parser.add_argument('--rtp', action='store_true', help='Use real loopback RTP/AES/NTP instead of direct queue injection')
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-msse-audio')
    args = parser.parse_args()
    args.worker = args.worker or args.rtp
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = shutil.which('vega')
    result = {'physicalValidation': False, 'passed': False}
    history = []

    def run(*command, payload=None, timeout=30):
        completed = subprocess.run([sdk, *command], input=payload, capture_output=True, timeout=timeout)
        stdout, stderr = completed.stdout.decode(errors='replace'), completed.stderr.decode(errors='replace')
        history.append({'args': command, 'exit': completed.returncode, 'stdout': stdout, 'stderr': stderr})
        if completed.returncode:
            raise RuntimeError(stderr or stdout)
        return stdout

    def shell(*command, **options):
        return run('exec', 'vda', '-s', args.serial, 'shell', '-T', '-c', COMPONENT, *command, **options)

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        fixture = args.output / 'sample.aac'
        if args.eld:
            fixtures = json.loads((ROOT / 'fireos3-probe/src/main/assets/audio_samples.json').read_text())
            packets = next(f['packets'] for f in fixtures if f['codec'] == 8 and f['samplesPerFrame'] == args.eld)
            count = math.ceil(8 * 44100 / args.eld)
            fixture.write_bytes(b''.join(struct.pack('<I', len(raw)) + raw for raw in
                                (base64.b64decode(packets[i % len(packets)], validate=True) for i in range(count))))
        else:
            subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i',
                            str(ROOT / 'vega/assets/raw/media/playback-sample.mp4'), '-vn',
                            *(['-c:a', 'aac', '-ar', '44100', '-ac', '2'] if args.worker else ['-c:a', 'copy']), '-f', 'adts', str(fixture)], check=True, timeout=30)
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        # A component's /tmp differs from the developer shell's /tmp. Stream
        # our own fixtures into its sandbox, without changing its privileges.
        for source, destination in [(args.runner, '/data/buzin-msse-probe'), (fixture, '/data/buzin-msse-sample.aac')]:
            shell(f'cat > {destination}', payload=source.read_bytes())
        shell('chmod', '700', '/data/buzin-msse-probe')
        options = ([f'--eld-{args.eld}'] if args.eld else []) + (['--rtp'] if args.rtp else ['--worker'] if args.worker else [])
        output = shell('timeout', '55', '/data/buzin-msse-probe', '/data/buzin-msse-sample.aac', *options, timeout=60)
        (args.output / 'runner.log').write_text(output)
        cycles = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
        if len(cycles) != 2 or any(value.get('released') is not True or value.get('seconds', 0) < 7.5 for value in cycles):
            raise RuntimeError('Two completed native playback/release cycles were not observed')
        if args.worker and cycles[1].get('flushes') != 1:
            raise RuntimeError('Native worker flush was not observed')
        if args.rtp and any(value.get('rtpFrames') != value.get('frames') or value.get('ntpFrames') != value.get('frames') or value.get('duplicatesSent', 0) <= 0 or value.get('rtpSocketsReleased') is not True for value in cycles):
            raise RuntimeError('RTP/AES/NTP did not cover every decoded sample')
        result.update(passed=True, worker=args.worker, rtp=args.rtp, eldSamplesPerFrame=args.eld, cycles=cycles)
        print(json.dumps(result, indent=2))
    except Exception as error:
        result['error'] = str(error)
        try:
            logs = run('exec', 'vda', '-s', args.serial, 'shell', 'loggingctl', 'log', '-v', 'buzin-msse-probe')
            (args.output / 'receiver.log').write_text(logs)
        except Exception:
            pass
        raise
    finally:
        for name in ['/data/buzin-msse-probe', '/data/buzin-msse-sample.aac']:
            try:
                shell('rm', '-f', name)
            except Exception:
                pass
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        (args.output / 'commands.json').write_text(json.dumps(history, indent=2) + '\n')


if __name__ == '__main__':
    main()
