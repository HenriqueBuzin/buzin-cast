#!/usr/bin/env python3
"""VVD: AirPlay mirror output sends BZCAST02 video and stereo PCM to a peer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from test_vega_msse_video import append_audio_fixture, make_fixture

COMPONENT = 'com.buzincast.tv.main'
PROBE = '/data/buzin-relay-sender-probe'
FIXTURE = '/data/buzin-relay-sender-fixture'
SETTINGS = '/data/buzin-relay-sender-settings'
CODE = 'ABCDEFGH2345'


def receive_peer(peer):
    stream = peer.makefile('rb')
    def read(size):
        result = stream.read(size)
        if len(result) != size:
            raise EOFError('Relay closed before sending End')
        return result

    with stream:
        assert read(8) == b'BZCAST02'
        salt = os.urandom(16)
        peer.sendall(salt)
        cipher = AESGCM(hashlib.pbkdf2_hmac('sha1', CODE.encode(), salt, 100000, 32))
        def frame(sequence):
            size, observed, nonce = struct.unpack('>IQ12s', read(24))
            assert 16 <= size <= 4 * 1024 * 1024 + 64 and observed == sequence
            return cipher.decrypt(nonce, read(size), struct.pack('>Q', sequence))
        assert frame(0) == b'hello'
        nonce = os.urandom(12)
        peer.sendall(struct.pack('>IQ12s', 21, 0, nonce) + cipher.encrypt(nonce, b'ready', bytes(8)))

        video, pcm = bytearray(), bytearray()
        first_video = None
        frames = audio = 0
        for sequence in range(1, 1600):
            packet = frame(sequence)
            if not packet:
                continue
            if packet == b'\x03':
                break
            if packet[0] == 1:
                _, stamp, width, height, hevc, size = struct.unpack_from('>BQII?I', packet)
                assert (width, height) != (0, 0) and not hevc and size == len(packet) - 22
                if first_video is None:
                    first_video = stamp
                    nals = [part[0] % 32 for part in packet[22:].split(b'\0\0\0\1') if part]
                    assert {5, 7, 8} <= set(nals)
                    assert first_video > 1_000_000_000
                video += packet[22:]
                frames += 1
            else:
                marker, stamp, rate, channels, size = struct.unpack_from('>BQIII', packet)
                assert marker == 2 and rate == 44100 and channels == 2 and size == len(packet) - 21
                assert first_video is not None and stamp >= first_video and size % 4 == 0
                pcm += packet[21:]
                audio += 1
        else:
            raise AssertionError('Relay never finished')
        assert frames > 10 and audio > 10 and len(pcm) > 44100 * 4, (
            f'Incomplete relay: {frames} video frames, {audio} PCM packets, {len(pcm)} PCM bytes'
        )
        assert any(pcm)
        return video, frames, audio


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', required=True, type=Path)
    parser.add_argument('--runner', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/vega-relay-sender'))
    parser.add_argument('--profiles', nargs='+', choices=('alac', 'lc', 'eld480', 'eld512'),
                        default=['alac', 'lc', 'eld480', 'eld512'])
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only the Vega virtual device is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    _, fixture, _ = make_fixture(args.output, annex_b=True)

    def vega(*command, data=None, timeout=30):
        return subprocess.run(['vega', *command], input=data, capture_output=True, check=True,
                              timeout=timeout).stdout.decode(errors='replace')

    def shell(*command, data=None):
        return vega('exec', 'vda', '-s', args.serial, 'shell', '-T', *command, data=data)

    def sandbox(*command, data=None):
        return shell('-c', COMPONENT, *command, data=data)

    assert re.search(rf'^{re.escape(args.serial)}\s+device\b', vega('exec', 'vda', 'devices'), re.M)
    if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
        shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
    vega('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
    results = {}
    try:
        sandbox('cat > ' + PROBE, data=args.runner.read_bytes())
        sandbox('chmod', '700', PROBE)
        for profile in args.profiles:
            bundle, _, _ = append_audio_fixture(args.output, fixture, profile)
            sandbox('cat > ' + FIXTURE, data=bundle)
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                listener.listen()
                listener.settimeout(12)
                port = str(listener.getsockname()[1])
                sandbox('cat > ' + SETTINGS, data=f'127.0.0.1\n{port}\nABCD-EFGH-2345\n'.encode())
                vega('device', 'start-port-forwarding', '--device', args.serial, '--port', port, '--forward', 'false')
                producer = None
                try:
                    with (args.output / f'{profile}.log').open('w') as log:
                        producer = subprocess.Popen(['vega', 'exec', 'vda', '-s', args.serial, 'shell', '-T',
                                                     '-c', COMPONENT, 'timeout', '25',
                                                     PROBE, FIXTURE, '--relay-send'],
                                                    stdout=log, stderr=subprocess.STDOUT)
                        peer, _ = listener.accept()
                        with peer:
                            peer.settimeout(8)
                            video, frames, audio = receive_peer(peer)
                        assert producer.wait(12) == 0
                finally:
                    if producer and producer.poll() is None:
                        producer.terminate()
                        producer.wait(5)
                    vega('device', 'stop-port-forwarding', '--device', args.serial, '--port', port, '--forward', 'false')
            h264 = args.output / f'{profile}.h264'
            h264.write_bytes(video)
            subprocess.run(['ffmpeg', '-v', 'error', '-xerror', '-i', str(h264), '-f', 'null', '-'],
                           check=True, timeout=15)
            results[profile] = {'videoFrames': frames, 'pcmPackets': audio}
            print(json.dumps({profile: results[profile]}), flush=True)
        print(json.dumps({'passed': True, 'profiles': results, 'physicalValidation': False}))
    finally:
        sandbox('rm', '-f', PROBE, FIXTURE, SETTINGS)


if __name__ == '__main__':
    main()
