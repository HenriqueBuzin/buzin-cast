"""Synthetic PIN/SRP client for the isolated legacy instrumentation runner.

Protocol arithmetic follows the vendored UxPlay pairing.c/srp.c (RFC5054
2048-bit group, SHA1 and Apple's two-hash session key). This is a test
client, never application crypto or an implementation for a real sender.
Private keys and PINs remain in memory and are not included in reports.
"""
import hashlib
import hmac
import os
import plistlib
import socket
import subprocess
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


N = int(
    'AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4'
    'A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60'
    '95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF'
    '747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907'
    '8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861'
    '60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB'
    'FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73', 16)
USER = '02:00:00:00:00:42'


def bn(value):
    return value.to_bytes((value.bit_length() + 7) // 8, 'big')


def sha(*parts):
    return hashlib.sha1(b''.join(parts)).digest()


def public(key):
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


class Client:
    def __init__(self, port):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=10)
        self.stream = self.socket.makefile('rb')
        self.sequence = 0

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.stream.close()
        self.socket.close()

    def request(self, path, payload=b'', plist=False):
        self.sequence += 1
        kind = 'application/x-apple-binary-plist' if plist else 'application/octet-stream'
        self.socket.sendall((f'POST {path} RTSP/1.0\r\nHost: localhost\r\nCSeq: {self.sequence}\r\n'
                            f'Content-Type: {kind}\r\nContent-Length: {len(payload)}\r\n\r\n').encode() + payload)
        line = self.stream.readline(4096)
        if not line.startswith((b'HTTP/', b'RTSP/')):
            raise AssertionError('Native PIN request did not return an HTTP/RTSP response')
        status = int(line.split()[1])
        headers = {}
        size = 0
        while True:
            line = self.stream.readline(4096)
            size += len(line)
            if size > 16384 or not line:
                raise AssertionError('Invalid native PIN response headers')
            if line == b'\r\n':
                break
            name, value = line.split(b':', 1)
            headers[name.strip().lower()] = value.strip()
        length = int(headers.get(b'content-length', b'0'))
        if not 0 <= length <= 16384:
            raise AssertionError('Native PIN response exceeded budget')
        body = self.stream.read(length)
        if len(body) != length:
            raise AssertionError('Truncated native PIN response')
        return status, body

    def plist(self, body):
        return self.request('/pair-setup-pin', plistlib.dumps(body, fmt=plistlib.FMT_BINARY), True)

    def begin(self):
        status, body = self.plist({'method': 'pin', 'user': USER})
        if status != 200:
            raise AssertionError('SRP setup was rejected')
        reply = plistlib.loads(body)
        if len(reply['salt']) != 16 or not 1 <= len(reply['pk']) <= 256:
            raise AssertionError('Invalid SRP salt/server public key')
        return reply

    def prove(self, pin, valid=True):
        reply = self.begin()
        a = int.from_bytes(os.urandom(32), 'big') + 1
        A = pow(2, a, N)
        B = int.from_bytes(reply['pk'], 'big')
        salt = bn(int.from_bytes(reply['salt'], 'big'))
        if B % N == 0:
            raise AssertionError('Unsafe SRP server key')
        u = int.from_bytes(sha(A.to_bytes(256, 'big'), B.to_bytes(256, 'big')), 'big')
        k = int.from_bytes(sha(N.to_bytes(256, 'big'), (2).to_bytes(256, 'big')), 'big')
        x = int.from_bytes(sha(salt, sha(USER.encode(), b':', pin.encode())), 'big')
        secret = bn(pow((B - k * pow(2, x, N)) % N, a + u * x, N))
        session = sha(secret, b'\0\0\0\0') + sha(secret, b'\0\0\0\1')
        xor = bytes(x ^ y for x, y in zip(sha(bn(N)), sha(b'\2')))
        proof = sha(xor, sha(USER.encode()), salt, bn(A), bn(B), session)
        status, body = self.plist({'pk': bn(A), 'proof': proof})
        if not valid:
            if status != 470:
                raise AssertionError('Incorrect PIN did not fail native SRP authentication')
            return None
        if status != 200 or not hmac.compare_digest(plistlib.loads(body)['proof'], sha(bn(A), proof, session)):
            raise AssertionError('Native SRP server proof did not verify')
        return session

    def finish(self, session, pin):
        signing = ed25519.Ed25519PrivateKey.generate()
        key = hashlib.sha512(b'Pair-Setup-AES-Key' + session).digest()[:16]
        iv = hashlib.sha512(b'Pair-Setup-AES-IV' + session).digest()[:16]
        iv1 = iv[:-1] + bytes([(iv[-1] + 1) & 255])
        iv2 = iv[:-1] + bytes([(iv[-1] + 2) & 255])
        encrypted = AESGCM(key).encrypt(iv1, public(signing), None)
        tampered = encrypted[-16:-1] + bytes([encrypted[-1] ^ 1])
        status, _ = self.plist({'epk': encrypted[:-16], 'authTag': tampered})
        if status != 470:
            raise AssertionError('Tampered PIN setup authentication tag was accepted')
        # UxPlay consumes and releases SRP state on every confirmation attempt.
        # A retry must perform a fresh SRP exchange, with fresh derived keys.
        session = self.prove(pin)
        key = hashlib.sha512(b'Pair-Setup-AES-Key' + session).digest()[:16]
        iv = hashlib.sha512(b'Pair-Setup-AES-IV' + session).digest()[:16]
        iv1 = iv[:-1] + bytes([(iv[-1] + 1) & 255])
        iv2 = iv[:-1] + bytes([(iv[-1] + 2) & 255])
        encrypted = AESGCM(key).encrypt(iv1, public(signing), None)
        status, body = self.plist({'epk': encrypted[:-16], 'authTag': encrypted[-16:]})
        if status != 200:
            raise AssertionError('Valid PIN setup confirmation failed')
        reply = plistlib.loads(body)
        server_key = AESGCM(key).decrypt(iv2, reply['epk'] + reply['authTag'], None)
        if len(server_key) != 32:
            raise AssertionError('Invalid server long-term key')
        status, _ = self.plist({'epk': encrypted[:-16], 'authTag': encrypted[-16:]})
        if status != 470:
            raise AssertionError('Completed PIN setup could be replayed')
        self.verify(signing, server_key)
        return signing

    def verify(self, signing, server_key):
        exchange = x25519.X25519PrivateKey.generate()
        client_exchange = public(exchange)
        status, body = self.request('/pair-verify', b'\1\0\0\0' + client_exchange + public(signing))
        if status != 200 or len(body) != 96:
            raise AssertionError('PIN-authenticated peer did not reach pair verification')
        server_exchange = body[:32]
        secret = exchange.exchange(x25519.X25519PublicKey.from_public_bytes(server_exchange))
        key = hashlib.sha512(b'Pair-Verify-AES-Key' + secret).digest()[:16]
        iv = hashlib.sha512(b'Pair-Verify-AES-IV' + secret).digest()[:16]
        decryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).decryptor()
        signature = decryptor.update(body[32:])
        ed25519.Ed25519PublicKey.from_public_bytes(server_key).verify(signature, server_exchange + client_exchange)
        signature = signing.sign(client_exchange + server_exchange)
        encryptor = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        encryptor.update(bytes(64))  # Pair-verify continues after the server signature.
        status, body = self.request('/pair-verify', bytes(4) + encryptor.update(signature))
        if status != 200 or body:
            raise AssertionError('Native pair verification signature failed')


def malformed(port):
    cases = [[], {}, {'method': '', 'user': USER}, {'method': 'p', 'user': USER},
             {'pk': bytes(256), 'proof': bytes(20)}, {'epk': bytes(32), 'authTag': bytes(16)}]
    for case in cases:
        with Client(port) as client:
            if client.plist(case)[0] != 470:
                raise AssertionError('Invalid PIN setup state/type was accepted')
    for proof_length in (0, 19, 21, 64):
        with Client(port) as client:
            client.begin()
            if client.plist({'pk': bytes([2]) * 256, 'proof': bytes(proof_length)})[0] != 470:
                raise AssertionError('Invalid PIN proof length was accepted')
    for a_length in (0, 257):
        with Client(port) as client:
            client.begin()
            if client.plist({'pk': bytes(a_length), 'proof': bytes(20)})[0] != 470:
                raise AssertionError('Invalid SRP client key length was accepted')
    for unsafe in (bytes(256), bn(N)):
        with Client(port) as client:
            client.begin()
            if client.plist({'pk': unsafe, 'proof': bytes(20)})[0] != 470:
                raise AssertionError('Unsafe SRP client public key was accepted')
    for key_size, tag_size in ((31, 16), (33, 16), (32, 15), (32, 17)):
        with Client(port) as client:
            client.begin()
            if client.plist({'epk': bytes(key_size), 'authTag': bytes(tag_size)})[0] != 470:
                raise AssertionError('Invalid PIN confirmation length/state was accepted')
    return len(cases) + 4 + 2 + 2 + 4


def run_instrument(args, adb):
    """Host half of LegacyPinScenario, serialized with its actual Android UI."""
    seen = []
    forwards = []
    command = [args.adb, '-P', str(args.adb_server_port), '-s', args.serial, 'shell', 'am', 'instrument', '-w',
               '-e', 'checks', 'pin', args.package + '.test/io.buzincast.legacy.LegacyGestureChecks']
    def code():
        until = time.monotonic() + 45
        while time.monotonic() < until:
            if process.poll() is not None:
                raise AssertionError('PIN instrumentation stopped before publishing its UI result; inspect instrumentation.txt')
            value = adb('shell', 'run-as', args.package, 'cat', 'files/pin-value').strip()
            if len(value) == 4 and value.isascii() and value.isdigit():
                return value
            time.sleep(.1)
        raise AssertionError('Native PIN did not reach the actual Android UI')
    with (args.output / 'instrumentation.txt').open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            until = time.monotonic() + 210
            while process.poll() is None:
                if time.monotonic() > until:
                    raise TimeoutError('PIN instrumentation exceeded its deadline')
                files = adb('shell', 'run-as', args.package, 'ls', 'files').split()
                stage = adb('shell', 'run-as', args.package, 'cat', 'files/pin-request').strip() if 'pin-request' in files else ''
                if stage and stage not in seen:
                    if stage not in ('pairing', 'background', 'disabled', 'stop'):
                        raise AssertionError('Unknown PIN instrumentation stage')
                    remote = int(adb('shell', 'run-as', args.package, 'cat', 'files/pin-port').strip())
                    local = int(adb('forward', 'tcp:0', 'tcp:' + str(remote)).strip())
                    forwards.append(local)
                    with Client(local) as client:
                        if client.request('/pair-pin-start')[0] != 200:
                            raise AssertionError('Native pair-pin-start failed')
                        if stage != 'disabled':
                            pin = code()
                            if stage == 'pairing':
                                wrong = str((int(pin) + 1) % 10000).zfill(4)
                                client.prove(wrong, valid=False)
                                session = client.prove(pin)
                                client.finish(session, pin)
                                if malformed(local) != 18:
                                    raise AssertionError('Malformed PIN coverage incomplete')
                    adb('shell', 'run-as', args.package, 'mkdir', 'files/pin-ack-' + stage)
                    seen.append(stage)
                time.sleep(.1)
            if process.returncode != 0:
                raise AssertionError('PIN instrumentation process failed')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            for port in forwards:
                adb('forward', '--remove', 'tcp:' + str(port))
    if seen != ['pairing', 'background', 'disabled', 'stop']:
        raise AssertionError('PIN instrumentation did not complete every host stage')
    return (args.output / 'instrumentation.txt').read_text(encoding='utf-8', errors='replace')
