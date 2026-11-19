#!/usr/bin/env python3
"""VVD BZCAST02 interoperability, authenticated H.264/PCM input and Vega playback."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import time
from urllib.parse import urlsplit

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from test_vega_local_video import AirPlayResponse
from test_vega_msse_video import make_fixture

COMPONENT = 'com.buzincast.tv.main'
PREFIX = '/data/buzin-relay-probe'


class Sender:
    def __init__(self, sock, code):
        self.sock = sock
        self.sequence = 0
        sock.sendall(b'BZCAST02')
        salt = self.read(16)
        self.aead = AESGCM(hashlib.pbkdf2_hmac('sha1', code.encode(), salt, 100000, 32))

    def read(self, size):
        chunks = bytearray()
        while len(chunks) < size:
            chunk = self.sock.recv(size - len(chunks))
            if not chunk:
                raise EOFError('Relay disconnected')
            chunks += chunk
        return bytes(chunks)

    def send(self, data):
        aad = struct.pack('>Q', self.sequence)
        nonce = os.urandom(12)
        encoded = self.aead.encrypt(nonce, data, aad)
        packet = struct.pack('>I', len(encoded)) + aad + nonce + encoded
        self.sock.sendall(packet)
        self.sequence += 1

    def pair(self):
        self.send(b'hello')
        size, seq = struct.unpack('>IQ', self.read(12))
        assert (size, seq) == (21, 0)
        nonce = self.read(12)
        assert self.aead.decrypt(nonce, self.read(size), struct.pack('>Q', seq)) == b'ready'


def media_packets(directory):
    _, fixture, _ = make_fixture(directory, annex_b=True)
    width, height, config_size = struct.unpack_from('<III', fixture)
    offset = 12 + config_size
    count, = struct.unpack_from('<I', fixture, offset)
    offset += 4
    video = []
    epoch = 3_000_000_000
    for _ in range(count):
        pts, _, _, size = struct.unpack_from('<IIII', fixture, offset)
        offset += 16
        if pts < 3000:
            timestamp = epoch + pts * 1_000_000
            video.append((timestamp, struct.pack('>BQII?I', 1, timestamp, width, height, False, size)
                          + fixture[offset:offset + size]))
        offset += size
    pcm = subprocess.check_output([
        'ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=997:sample_rate=44100:duration=3',
        '-ac', '2', '-f', 's16le', '-'], timeout=20)
    audio = []
    for offset in range(0, len(pcm), 352 * 4):
        block = pcm[offset:offset + 352 * 4]
        timestamp = epoch + (offset // 4) * 1_000_000_000 // 44100
        audio.append((timestamp, struct.pack('>BQIII', 2, timestamp, 44100, 2, len(block)) + block))
    return sorted(video + audio, key=lambda packet: packet[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/vega-relay-checks'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only the Vega virtual device is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = media_packets(args.output)

    def vega(*command, data=None, timeout=30):
        return subprocess.run(['vega', *command], input=data, capture_output=True,
                              check=True, timeout=timeout).stdout.decode(errors='replace')

    def shell(*command, data=None):
        return vega('exec', 'vda', '-s', args.serial, 'shell', '-T', *command, data=data)

    def sandbox(*command, data=None):
        return shell('-c', COMPONENT, *command, data=data)

    def key(value):
        shell('inputd-cli', 'button_press', 'KEY_' + value)

    def wait(predicate, seconds=12):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                pass
            time.sleep(0.15)
        raise RuntimeError('Timed out waiting for the relay observation')

    def port_list():
        return set(re.findall(r'^tcp\s+\d+\s+\d+\s+0\.0\.0\.0:(\d+)\s+\S+\s+LISTEN',
                              shell('netstat', '-ltn'), re.M))

    def playback(state):
        result = json.loads(sandbox('cat', '/data/playback-state.json'))
        return result.get('state') == state and (state != 'playing' or result.get('seconds', 0) > 0.6)

    forwards = []
    def forward(port):
        vega('device', 'start-port-forwarding', '--device', args.serial, '--port', str(port), '--forward', 'true')
        forwards.append(port)

    def remove_forward(port):
        vega('device', 'stop-port-forwarding', '--device', args.serial, '--port', str(port), '--forward', 'true')
        forwards.remove(port)

    def send_media(port, code, faults):
        if faults:
            wrong = ('B' if code[0] == 'A' else 'A') + code[1:]
            with socket.create_connection(('127.0.0.1', port), timeout=5) as candidate:
                try:
                    Sender(candidate, wrong).pair()
                except (EOFError, ConnectionResetError):
                    pass
                else:
                    raise AssertionError('Receiver accepted the wrong pairing code')
        with socket.create_connection(('127.0.0.1', port), timeout=5) as peer:
            client = Sender(peer, code)
            client.pair()
            client.send(b'')
            begin, first = time.monotonic(), fixture[0][0]
            for timestamp, packet in fixture:
                delay = begin + (timestamp - first) / 1e9 - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                client.send(packet)
            client.send(b'\x03')

    assert re.search(rf'^{re.escape(args.serial)}\s+device\b', vega('exec', 'vda', 'devices'), re.M)
    if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
        shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
    vega('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
    probe_files = [PREFIX + suffix for suffix in ('-url', '-state', '-endpoint.json')]
    producer = None
    try:
        baseline = port_list()
        key('ENTER')
        for _ in range(6):
            key('DOWN')
        key('ENTER')
        offered = wait(lambda: port_list() - baseline)
        assert len(offered) == 1
        key('BACK')
        wait(lambda: not (port_list() & offered))

        sandbox('cat > ' + PREFIX, data=args.runner.read_bytes())
        sandbox('chmod', '700', PREFIX)
        for use_player in (False, True):
            sandbox('rm', '-f', *probe_files)
            if use_player:
                sandbox('rm', '-f', '/data/airplay-port.json', '/data/playback-state.json')
                for name in ('ENTER', 'DOWN', 'ENTER'):
                    key(name)
                control = wait(lambda: json.loads(sandbox('cat', '/data/airplay-port.json'))['port'])
                forward(control)
            with (args.output / f'probe-{int(use_player)}.log').open('w') as log:
                producer = subprocess.Popen(['vega', 'exec', 'vda', '-s', args.serial, 'shell', '-T', '-c',
                                             COMPONENT, PREFIX], stdout=log, stderr=subprocess.STDOUT)
                endpoint = wait(lambda: json.loads(sandbox('cat', PREFIX + '-endpoint.json')))
                port = int(endpoint['address'].rsplit(':', 1)[1])
                forward(port)
                with ThreadPoolExecutor(max_workers=1) as task:
                    sent = task.submit(send_media, port, endpoint['code'], not use_player)
                    url = wait(lambda: sandbox('cat', PREFIX + '-url').strip())
                    media = urlsplit(url)
                    if use_player:
                        conn = http.client.HTTPConnection('127.0.0.1', control, timeout=6)
                        try:
                            conn.response_class = AirPlayResponse
                            conn.request('POST', '/play', f'Content-Location: {url}\r\nStart-Position: 0\r\n',
                                         {'Content-Type': 'text/parameters',
                                          'X-Apple-Session-ID': '00112233-4455-6677-8899-aabbccddeeff'})
                            reply = conn.getresponse()
                            assert reply.status == 200
                            reply.read()
                            wait(lambda: playback('playing'))
                        finally:
                            conn.close()
                    else:
                        forward(media.port)
                        conn = http.client.HTTPConnection('127.0.0.1', media.port, timeout=8)
                        try:
                            conn.request('GET', media.path)
                            response = conn.getresponse()
                            assert response.status == 200
                            (args.output / 'relay.mp4').write_bytes(response.read())
                        finally:
                            conn.close()
                        remove_forward(media.port)
                    sent.result(timeout=10)
                assert producer.wait(timeout=12) == 0
                producer = None
            remove_forward(port)
            if use_player:
                key('BACK')
                wait(lambda: playback('released'))
                remove_forward(control)
        saved = args.output / 'relay.mp4'
        result = json.loads(subprocess.check_output([
            'ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name,codec_type',
            '-of', 'json', str(saved)], timeout=15))
        assert {track['codec_type']: track['codec_name'] for track in result['streams']} == {'audio': 'aac', 'video': 'h264'}
        subprocess.run(['ffmpeg', '-v', 'error', '-xerror', '-i', str(saved), '-f', 'null', '-'], check=True, timeout=15)
        print(json.dumps({'passed': True, 'physicalValidation': False, 'wrongPairingCodeRejected': True,
                          'relayUiLifecycle': True, 'decodedTracks': result['streams'], 'vegaPlayerClock': True}))
    finally:
        if producer:
            producer.terminate()
            producer.wait(timeout=8)
        for port in reversed(forwards[:]):
            remove_forward(port)
        sandbox('rm', '-f', PREFIX, *probe_files)


if __name__ == '__main__':
    main()
