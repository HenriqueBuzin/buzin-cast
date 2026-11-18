#!/usr/bin/env python3
"""VVD-only UI/RTSP lifecycle and volume callbacks for experimental AirPlay audio."""
import argparse
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time

COMPONENT = 'com.buzincast.tv.main'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/vega-airplay-audio'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = shutil.which('vega')
    history, cycles, forwards = [], [], []
    report = {'passed': False, 'physicalValidation': False}
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
                last = json.loads(shell('-c', COMPONENT, 'cat', '/data/airplay-audio-state.json'))
                if predicate(last):
                    return last
            except (RuntimeError, json.JSONDecodeError):
                pass
            time.sleep(0.15)
        raise RuntimeError(f'Native audio observation did not match: {last}')

    def request(method, sequence, body=b''):
        client.sendall(f'{method} rtsp://127.0.0.1/audio RTSP/1.0\r\nCSeq: {sequence}\r\nContent-Type: text/parameters\r\nContent-Length: {len(body)}\r\n\r\n'.encode() + body)
        response = b''
        while b'\r\n\r\n' not in response:
            packet = client.recv(4096)
            if not packet or len(response) + len(packet) > 16384:
                raise RuntimeError('Invalid RTSP response')
            response += packet
        if not response.startswith(b'RTSP/1.0 200 '):
            raise RuntimeError(f'RTSP command failed: {response[:80]!r}')

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        for cycle in range(2):
            shell('-c', COMPONENT, 'rm', '-f', '/data/airplay-audio-port.json', '/data/airplay-audio-state.json')
            for key in ['KEY_ENTER', 'KEY_DOWN', 'KEY_DOWN', 'KEY_ENTER']:
                shell('inputd-cli', 'button_press', key)
            deadline = time.monotonic() + 10
            port = None
            while time.monotonic() < deadline:
                try:
                    port = json.loads(shell('-c', COMPONENT, 'cat', '/data/airplay-audio-port.json'))['port']
                    break
                except (RuntimeError, json.JSONDecodeError):
                    time.sleep(0.2)
            if not isinstance(port, int) or not 0 < port <= 65535:
                raise RuntimeError('Audio listener did not start')
            run('device', 'start-port-forwarding', '-d', args.serial, '--port', str(port), '--forward', 'true')
            forwards.append(port)
            client = socket.create_connection(('127.0.0.1', port), timeout=3)
            request('OPTIONS', 1)
            waiting = observation(lambda value: value.get('phase') == 'waiting')
            request('SET_PARAMETER', 2, b'volume: -18.000000\r\n')
            gain = observation(lambda value: value.get('volumeDb') == -18)
            request('SET_PARAMETER', 3, b'volume: -144.000000\r\n')
            muted = observation(lambda value: value.get('volumeDb') == -144)
            shell('inputd-cli', 'button_press', 'KEY_BACK' if cycle == 0 else 'KEY_HOMEPAGE')
            stopped = observation(lambda value: value.get('phase') == 'stopped' and value.get('released') is True)
            if client.recv(1) != b'':
                raise RuntimeError('Leaving the screen did not close the native control socket')
            client.close(); client = None
            cycles.append({'waiting': waiting, 'gain': gain, 'muted': muted, 'stopped': stopped, 'controlSocketClosed': True})
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
