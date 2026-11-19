"""Independent BZCAST02 peer for the isolated API17 relay transport test."""
import json
import os
import socket
import struct
import subprocess
import time
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b'BZCAST02'
VIDEO = struct.pack('>Bqii?i', 1, 100000000, 160, 90, False, 7) + bytes([0, 0, 0, 1, 0x65, 11, 22])
AUDIO = struct.pack('>Bqiii', 2, 100000010, 44100, 2, 4) + bytes([1, 2, 3, 4])


def exact(peer, size):
    out = bytearray()
    while len(out) < size:
        part = peer.recv(size-len(out))
        if not part:
            raise EOFError('Relay closed before the expected frame')
        out.extend(part)
    return bytes(out)


def derive(code, salt):
    normalized = ''.join(c for c in code.upper() if c.isalnum())
    if len(normalized) != 12 or len(salt) != 16:
        raise AssertionError('Invalid owned pairing parameters')
    return PBKDF2HMAC(algorithm=hashes.SHA1(), length=32, salt=salt, iterations=100000).derive(normalized.encode('ascii'))


class Channel:
    def __init__(self, peer, key):
        self.peer, self.aes, self.tx, self.rx = peer, AESGCM(key), 0, 0

    def send(self, data, corrupt=False, sequence=None):
        sequence = self.tx if sequence is None else sequence
        aad, nonce = struct.pack('>q', sequence), os.urandom(12)
        encrypted = bytearray(self.aes.encrypt(nonce, data, aad))
        if corrupt:
            encrypted[-1] ^= 1
        self.peer.sendall(struct.pack('>i', len(encrypted)) + aad + nonce + encrypted)
        self.tx += 1

    def receive(self):
        size, sequence = struct.unpack('>iq', exact(self.peer, 12))
        if not 16 <= size <= 4*1024*1024+64 or sequence != self.rx:
            raise AssertionError('Invalid independent relay frame bounds/order')
        nonce = exact(self.peer, 12)
        result = self.aes.decrypt(nonce, exact(self.peer, size), struct.pack('>q', sequence))
        self.rx += 1
        return result


def closed(peer):
    try:
        if peer.recv(1):
            raise AssertionError('Rejected relay peer received data')
    except ConnectionResetError:
        pass


def client(peer, code, authenticate=True):
    peer.sendall(MAGIC)
    channel = Channel(peer, derive(code, exact(peer, 16)))
    channel.send(b'hello')
    if authenticate and channel.receive() != b'ready':
        raise AssertionError('Independent relay peer was not admitted')
    return channel


def run_instrument(args, adb):
    command = [args.adb, '-P', str(args.adb_server_port), '-s', args.serial, 'shell', 'am', 'instrument', '-w',
               '-e', 'checks', 'relay', args.package+'.test/io.buzincast.legacy.LegacyGestureChecks']
    expected = ['wrong_code', 'receive', 'bad_tag', 'sequence', 'oversized', 'truncated', 'invalid_packet',
                'busy', 'stalled_open', 'stalled_closed', 'sender', 'done']
    seen, forwards = [], {}
    stalled = None
    def acknowledge(stage):
        adb('shell', 'run-as', args.package, 'mkdir', 'files/relay-host-ack-'+stage)
    with (args.output/'instrumentation.txt').open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic()+420
            while process.poll() is None:
                if time.monotonic()>deadline:
                    raise TimeoutError('Relay instrumentation timed out')
                files = adb('shell', 'run-as', args.package, 'ls', 'files').split()
                if 'relay-host-request' in files:
                    parameters = json.loads(adb('shell', 'run-as', args.package, 'cat', 'files/relay-host-request'))
                    stage = parameters['stage']
                    if stage not in seen:
                        if stage != expected[len(seen)]:
                            raise AssertionError('Unexpected relay stage order')
                        if stage == 'sender':
                            with socket.socket() as server:
                                server.bind(('127.0.0.1', 0)); server.listen(1); server.settimeout(20)
                                adb('shell', 'run-as', args.package, 'mkdir', 'files/relay-host-port-'+str(server.getsockname()[1]))
                                acknowledge(stage)
                                peer, _ = server.accept()
                                with peer:
                                    peer.settimeout(15)
                                    if exact(peer, 8) != MAGIC:
                                        raise AssertionError('Wrong outgoing relay magic')
                                    salt = os.urandom(16); peer.sendall(salt)
                                    channel = Channel(peer, derive('ABCDEFGHJKLM', salt))
                                    if channel.receive() != b'hello':
                                        raise AssertionError('Wrong outgoing relay authentication')
                                    channel.send(b'ready')
                                    received = []
                                    while len(received)<3:
                                        data = channel.receive()
                                        if data:
                                            received.append(data)
                                    if received != [VIDEO, AUDIO, b'\x03']:
                                        raise AssertionError('Independent receiver saw changed outgoing packets')
                                adb('shell', 'run-as', args.package, 'mkdir', 'files/relay-host-sent')
                        elif stage == 'stalled_closed':
                            closed(stalled); stalled.close(); stalled = None; acknowledge(stage)
                        elif stage == 'done':
                            acknowledge(stage)
                        else:
                            remote = parameters['port']
                            if not isinstance(remote, int) or not 1 <= remote <= 65535:
                                raise AssertionError('Invalid emulator relay port')
                            if remote not in forwards:
                                forwards[remote] = int(adb('forward', 'tcp:0', 'tcp:'+str(remote)).strip())
                            peer = socket.create_connection(('127.0.0.1', forwards[remote]), timeout=10)
                            peer.settimeout(15)
                            if stage == 'stalled_open':
                                stalled = peer; acknowledge(stage)
                            else:
                                with peer:
                                    if stage == 'busy':
                                        peer.sendall(MAGIC); closed(peer)
                                    elif stage == 'wrong_code':
                                        client(peer, 'ZZZZZZZZZZZZ', False); closed(peer)
                                    else:
                                        channel = client(peer, parameters['code'])
                                        if stage == 'receive':
                                            for data in (b'', VIDEO, AUDIO, b'\x03'):
                                                channel.send(data)
                                        elif stage == 'bad_tag':
                                            channel.send(VIDEO, corrupt=True)
                                        elif stage == 'sequence':
                                            channel.send(VIDEO, sequence=7)
                                        elif stage == 'oversized':
                                            peer.sendall(struct.pack('>i', 4*1024*1024+65))
                                        elif stage == 'truncated':
                                            peer.sendall(struct.pack('>iq', 64, 1)+os.urandom(12)+b'partial')
                                            peer.shutdown(socket.SHUT_WR)
                                        elif stage == 'invalid_packet':
                                            channel.send(b'\xff')
                                        closed(peer)
                                acknowledge(stage)
                        seen.append(stage)
                time.sleep(1)
            if process.returncode != 0 or seen != expected:
                raise AssertionError('Relay instrumentation or independent stages incomplete')
            (args.output/'host-relay.json').write_text(json.dumps({'stages': seen, 'kdf': 'PBKDF2-HMAC-SHA1/100000/256',
                'cipher': 'AES-256-GCM', 'receive': 'exact AVC/PCM packets', 'send': 'exact AVC/PCM/End packets'}, indent=2)+'\n', encoding='utf-8')
        finally:
            if stalled is not None:
                stalled.close()
            if process.poll() is None:
                process.kill(); process.wait(timeout=10)
            for local in forwards.values():
                adb('forward', '--remove', 'tcp:'+str(local))
    return (args.output/'instrumentation.txt').read_text(encoding='utf-8', errors='replace')
