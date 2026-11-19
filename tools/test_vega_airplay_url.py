#!/usr/bin/env python3
"""Real AirPlay URL -> native UxPlay -> Vega player test, on VVD only."""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = 'com.buzincast.tv.main'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--media-file', type=Path, default=ROOT / 'vega/assets/raw/media/playback-sample.mp4')
    parser.add_argument('--live', action='store_true', help='Check startup and release without seeking in an unindexed MP4')
    parser.add_argument('--volume', action='store_true', help='Verify RTSP gain and mute reach the active Vega player')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-airplay-url')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only the VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = shutil.which('vega')
    history = []
    result = {'physicalValidation': False, 'liveStartupOnly': args.live, 'checks': {}}
    forwards = []
    client = None
    volume_client = None
    log_stream = None
    log_output = None
    media_requests = []

    content = args.media_file.read_bytes()

    class Media(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_HEAD(self):
            self.serve(False)
        def do_GET(self):
            self.serve(True)
        def serve(self, body):
            if self.path != '/playback-sample.mp4':
                self.send_error(404)
                return
            byte_range = self.headers.get('Range')
            start, end = 0, len(content) - 1
            if byte_range:
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', byte_range)
                if not match:
                    self.send_error(416)
                    return
                start = int(match[1])
                end = min(int(match[2]), end) if match[2] else end
                if start > end:
                    self.send_error(416)
                    return
            media_requests.append({'body': body, 'range': byte_range})
            self.send_response(206 if byte_range else 200)
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            if byte_range:
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(content)}')
            self.end_headers()
            if body:
                try:
                    self.wfile.write(content[start:end + 1])
                except (BrokenPipeError, ConnectionResetError):
                    pass

    server = ThreadingHTTPServer(('127.0.0.1', 40881), Media)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def run(*command, timeout=30):
        completed = subprocess.run([sdk, *command], capture_output=True, text=True, env=os.environ.copy(), timeout=timeout)
        history.append({'args': command, 'exit': completed.returncode, 'stdout': completed.stdout, 'stderr': completed.stderr})
        if completed.returncode:
            raise RuntimeError(completed.stderr or completed.stdout)
        return completed.stdout

    def shell(*command):
        return run('exec', 'vda', '-s', args.serial, 'shell', *command)

    def request(method, path, body=b''):
        client.request(method, path, body, {'Content-Type': 'text/parameters', 'X-Apple-Session-ID': '00112233-4455-6677-8899-aabbccddeeff'})
        response = client.getresponse()
        data = response.read()
        if response.status != 200:
            raise RuntimeError(f'AirPlay HTTP status {response.status}')
        return data

    def info():
        return plistlib.loads(request('GET', '/playback-info'))

    def wait(predicate):
        deadline = time.monotonic() + 20
        last = None
        while time.monotonic() < deadline:
            last = info()
            if predicate(last):
                return last
            time.sleep(0.25)
        raise RuntimeError(f'Playback did not reach expected state: {last}')

    def set_volume(sequence, db, gain):
        log_path = args.output / 'receiver-stream.log'
        offset = log_path.stat().st_size
        payload = f'volume: {db}\r\n'.encode()
        header = (
            f'SET_PARAMETER rtsp://127.0.0.1/video RTSP/1.0\r\nCSeq: {sequence}\r\n'
            f'Content-Type: text/parameters\r\nContent-Length: {len(payload)}\r\n\r\n'
        ).encode()
        volume_client.sendall(header + payload)
        response = b''
        while b'\r\n\r\n' not in response:
            chunk = volume_client.recv(4096)
            if not chunk or len(response) + len(chunk) > 16384:
                raise RuntimeError('Invalid RTSP volume response')
            response += chunk
        if not response.startswith(b'RTSP/1.0 200 '):
            raise RuntimeError('RTSP volume command was rejected')
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            with log_path.open('rb') as log:
                log.seek(offset)
                levels = re.findall(rb'(?:HtmlMediaElement::setVolumeLevel:|MediaPlayer: set volume)\s*([0-9.]+)', log.read())
            if any(abs(float(level) - gain) < 0.000001 for level in levels):
                return gain
            time.sleep(0.15)
        raise RuntimeError(f'Player did not report the requested volume level {gain}')

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        log_output = (args.output / 'receiver-stream.log').open('w')
        log_stream = subprocess.Popen([sdk, 'exec', 'vda', '-s', args.serial, 'shell', 'loggingctl', 'log', '-f', '-v', 'com.buzincast.tv'], stdout=log_output, stderr=subprocess.STDOUT)
        shell('-c', COMPONENT, 'rm', '-f', '/data/airplay-port.json')
        shell('inputd-cli', 'button_press', 'KEY_ENTER')
        shell('inputd-cli', 'button_press', 'KEY_DOWN')
        shell('inputd-cli', 'button_press', 'KEY_ENTER')
        port = None
        for _ in range(30):
            try:
                port = json.loads(shell('-c', COMPONENT, 'cat', '/data/airplay-port.json'))['port']
                break
            except (RuntimeError, json.JSONDecodeError):
                time.sleep(0.5)
        if not isinstance(port, int) or not 1 <= port <= 65535:
            raise RuntimeError('AirPlay URL listener did not start')
        for number, direction in [(port, 'true'), (40881, 'false')]:
            run('device', 'start-port-forwarding', '--device', args.serial, '--port', str(number), '--forward', direction)
            forwards.append((number, direction))
        class AirPlayResponse(http.client.HTTPResponse):
            def begin(self):
                super().begin()
                # UxPlay's bodyless POST acknowledgements omit Content-Length.
                # Keep the AirPlay control session open instead of waiting for
                # EOF; playback-info still uses its explicit response length.
                if self._method == 'POST' and self.status == 200 and self.length is None:
                    self.length = 0
                    self.will_close = self.headers.get('Connection', '').lower() == 'close'
        client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        client.response_class = AirPlayResponse
        start = 0 if args.live else 1
        request('POST', '/play', f'Content-Location: http://127.0.0.1:40881/playback-sample.mp4\r\nStart-Position: {start}\r\n'.encode())
        # A currentTime setter can expose its target before native seeking
        # finishes. Require an advancing clock before issuing pause.
        result['checks']['playing'] = wait(lambda value: value.get('rate', 0) > 0 and value.get('position', 0) > start + 0.3)
        if args.volume:
            volume_client = socket.create_connection(('127.0.0.1', port), timeout=3)
            result['checks']['volume'] = set_volume(1, -18, 10 ** (-18 / 20))
            result['checks']['muted'] = set_volume(2, -144, 0)
        try:
            result['videoQuality'] = json.loads(shell('-c', COMPONENT, 'cat', '/data/video-quality.json'))
        except (RuntimeError, json.JSONDecodeError):
            result['videoQuality'] = {'available': False}
        if not args.live:
            request('POST', '/rate?value=0')
            paused = wait(lambda value: value.get('rate') == 0 and value.get('duration', 0) > 0)
            time.sleep(0.5)
            held = info()
            if abs(held['position'] - paused['position']) > 0.3:
                raise RuntimeError('Paused clock advanced')
            result['checks']['paused'] = held
            request('POST', '/scrub?position=3')
            result['checks']['seek'] = wait(lambda value: abs(value.get('position', 0) - 3) < 0.4)
            request('POST', '/rate?value=1')
            result['checks']['resumed'] = wait(lambda value: value.get('rate', 0) > 0 and value.get('position', 0) > 3.3)
            request('POST', '/stop')
            result['checks']['stopped'] = wait(lambda value: value.get('rate') == 0 and value.get('duration') == 0)
        if not any(value['body'] for value in media_requests):
            raise RuntimeError('The system player did not request the media')
        shell('inputd-cli', 'button_press', 'KEY_BACK')
        deadline = time.monotonic() + 5
        while True:
            result['checks']['released'] = json.loads(shell('-c', COMPONENT, 'cat', '/data/playback-state.json'))
            if result['checks']['released']['state'] == 'released' or time.monotonic() >= deadline:
                break
            time.sleep(0.1)
        if result['checks']['released']['state'] != 'released':
            raise RuntimeError('Native player was not released')
        result['mediaRequests'] = media_requests
        result['passed'] = True
        print(json.dumps(result, indent=2))
    except Exception as error:
        result.update(passed=False, error=str(error), mediaRequests=len(media_requests))
        try:
            result['playbackDiagnostic'] = json.loads(shell('-c', COMPONENT, 'cat', '/data/playback-state.json'))
        except Exception:
            pass
        try:
            (args.output / 'receiver.log').write_text(shell('loggingctl', 'log', '-v', 'com.buzincast.tv'))
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        except Exception:
            pass
        raise
    finally:
        if log_stream:
            log_stream.terminate()
            try:
                log_stream.wait(timeout=5)
            except subprocess.TimeoutExpired:
                log_stream.kill()
                log_stream.wait(timeout=5)
        if log_output:
            log_output.close()
        if client:
            client.close()
        if volume_client:
            volume_client.close()
        for number, direction in reversed(forwards):
            try:
                run('device', 'stop-port-forwarding', '--device', args.serial, '--port', str(number), '--forward', direction)
            except Exception:
                pass
        server.shutdown()
        server.server_close()
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        (args.output / 'commands.json').write_text(json.dumps(history, indent=2) + '\n')

if __name__ == '__main__':
    main()
