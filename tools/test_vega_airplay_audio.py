#!/usr/bin/env python3
"""VVD-only UI/RTSP lifecycle for experimental AirPlay audio or video."""
import argparse
import json
from pathlib import Path
import plistlib
import re
import shutil
import socket
import subprocess
import time

COMPONENT = 'com.buzincast.tv.main'


def txt_fields(record):
    result = {}
    offset = 0
    while offset < len(record):
        length = record[offset]
        offset += 1
        if offset + length > len(record):
            raise RuntimeError('Truncated AirPlay TXT entry')
        key, value = record[offset:offset + length].decode().split('=', 1)
        if key in result:
            raise RuntimeError('Duplicate AirPlay TXT entry')
        result[key] = value
        offset += length
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', required=True, type=Path)
    parser.add_argument('--video', action='store_true', help='Test video worker ownership; no Apple media negotiation or rendering proof')
    parser.add_argument('--output', type=Path, default=Path('build/vega-airplay-audio'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = shutil.which('vega')
    history, cycles, forwards = [], [], []
    mode = 'video' if args.video else 'audio'
    report = {'passed': False, 'physicalValidation': False, 'mode': mode, 'mediaNegotiation': False, 'visualValidation': False}
    client = None

    def run(*command, timeout=30):
        result = subprocess.run([sdk, *command], capture_output=True, text=True, timeout=timeout)
        history.append({'args': command, 'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    def shell(*command):
        return run('exec', 'vda', '-s', args.serial, 'shell', *command)

    def observation(predicate):
        deadline = time.monotonic() + 10
        last = None
        while time.monotonic() < deadline:
            try:
                last = json.loads(shell('-c', COMPONENT, 'cat', f'/data/airplay-{mode}-state.json'))
                if predicate(last):
                    return last
            except (RuntimeError, json.JSONDecodeError):
                pass
            time.sleep(0.15)
        raise RuntimeError(f'Native {mode} observation did not match: {last}')

    def request(method, sequence, body=b'', path='/audio', content_type='text/parameters', status=200):
        client.sendall(
            f'{method} rtsp://127.0.0.1{path} RTSP/1.0\r\nCSeq: {sequence}\r\n'
            f'Content-Type: {content_type}\r\nContent-Length: {len(body)}\r\n\r\n'.encode() + body)
        response = b''
        while b'\r\n\r\n' not in response:
            packet = client.recv(4096)
            if not packet or len(response) + len(packet) > 16384:
                raise RuntimeError('Invalid RTSP response')
            response += packet
        if not response.startswith(f'RTSP/1.0 {status} '.encode()):
            raise RuntimeError(f'RTSP command failed: {response[:80]!r}')
        header, data = response.split(b'\r\n\r\n', 1)
        content_length = re.search(rb'(?:^|\r\n)Content-Length: (\d+)', header, re.I)
        length = int(content_length[1]) if content_length else 0
        if length > 16384:
            raise RuntimeError('RTSP response body exceeds probe limit')
        while len(data) < length:
            packet = client.recv(length - len(data))
            if not packet:
                raise RuntimeError('Truncated RTSP response body')
            data += packet
        return data

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        first_identity = None
        for cycle in range(2):
            shell('-c', COMPONENT, 'rm', '-f', f'/data/airplay-{mode}-port.json', f'/data/airplay-{mode}-state.json')
            for key in ['KEY_ENTER', *(['KEY_DOWN'] * (3 if args.video else 2)), 'KEY_ENTER']:
                shell('inputd-cli', 'button_press', key)
            deadline = time.monotonic() + 10
            port = None
            while time.monotonic() < deadline:
                try:
                    port = json.loads(shell('-c', COMPONENT, 'cat', f'/data/airplay-{mode}-port.json'))['port']
                    break
                except (RuntimeError, json.JSONDecodeError):
                    time.sleep(0.2)
            if not isinstance(port, int) or not 0 < port <= 65535:
                raise RuntimeError(f'{mode} listener did not start')
            run('device', 'start-port-forwarding', '-d', args.serial, '--port', str(port), '--forward', 'true')
            forwards.append(port)
            client = socket.create_connection(('127.0.0.1', port), timeout=3)
            request('OPTIONS', 1)
            waiting = observation(lambda value: value.get('phase') == 'waiting')
            if args.video:
                txt = {}
                for index, key in enumerate(('txtAirPlay', 'txtRAOP'), start=7):
                    qualifier = plistlib.dumps({'qualifier': [key]}, fmt=plistlib.FMT_BINARY)
                    info = plistlib.loads(request('GET', index, qualifier, '/info', 'application/x-apple-binary-plist'))
                    txt[key] = txt_fields(info[key])
                airplay, raop = txt['txtAirPlay'], txt['txtRAOP']
                feature_bits = int(airplay['features'].split(',')[0], 16)
                identity = (airplay['deviceid'], airplay['pk'])
                if (not feature_bits & (1 << 7) or raop['ft'] != airplay['features'] or
                        raop['pk'] != identity[1] or raop['cn'] != '1,2,3' or
                        airplay['pw'] != 'true' or raop['pw'] != 'true'):
                    raise RuntimeError('AirPlay and RAOP identity records disagree')
                if first_identity is not None and first_identity != identity:
                    raise RuntimeError('AirPlay identity changed when reopening the receiver')
                first_identity = identity
                # An accepted socket that closes before any RTSP request can
                # emit a generic flush, but it never owned the media session.
                with socket.create_connection(('127.0.0.1', port), timeout=3):
                    time.sleep(0.15)
                # Round-trip through the listener after the auxiliary close.
                request('OPTIONS', 6)
            if waiting.get('volumeDb') != 0:
                raise RuntimeError('New receiver session inherited stale volume')
            if args.video:
                request('SET_PARAMETER', 2, b'volume: -18.000000\r\n', status=470)
                request('SETUP', 3, status=470)
                protected = observation(lambda value: value.get('phase') == 'waiting')
                if protected['generation'] != waiting['generation'] or protected['volumeDb'] != 0:
                    raise RuntimeError('An unpaired request modified the mirror session')
                cycle_result = {'authenticationRequired': True}
            else:
                request('SET_PARAMETER', 2, b'volume: -18.000000\r\n')
                gain = observation(lambda value: value.get('volumeDb') == -18)
                request('SET_PARAMETER', 5, b'volume: -144.000000\r\n')
                muted = observation(lambda value: value.get('volumeDb') == -144)
                cycle_result = {'gain': gain, 'muted': muted}
            shell('inputd-cli', 'button_press', 'KEY_BACK' if cycle == 0 else 'KEY_HOMEPAGE')
            stopped = observation(lambda value: value.get('phase') == 'stopped' and value.get('released') is True)
            if args.video and stopped.get('advertising') is not False:
                raise RuntimeError('The receiver kept advertising after shutdown')
            if client.recv(1) != b'':
                raise RuntimeError('Leaving the screen did not close the native control socket')
            client.close(); client = None
            cycles.append({**cycle_result, 'waiting': waiting, 'stopped': stopped,
                           'controlSocketClosed': True, 'rtspIdentityVerified': args.video})
        report.update(passed=True, cycles=cycles)
        print(json.dumps(report, indent=2))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        if client:
            client.close()
        for port in forwards:
            try:
                run('device', 'stop-port-forwarding', '-d', args.serial, '--port', str(port), '--forward', 'true')
            except Exception:
                pass
        (args.output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        (args.output / 'commands.json').write_text(json.dumps(history, indent=2) + '\n')


if __name__ == '__main__':
    main()
