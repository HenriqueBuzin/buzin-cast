#!/usr/bin/env python3
"""VVD: native paced H.264 -> bounded loopback fMP4 -> real Vega surface/player."""
import argparse
import http.client
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import time

from test_vega_msse_video import append_audio_fixture, make_fixture

COMPONENT = 'com.buzincast.tv.main'


class AirPlayResponse(http.client.HTTPResponse):
    def begin(self):
        super().begin()
        if self._method == 'POST' and self.status == 200 and self.length is None:
            self.length = 0
            self.will_close = self.headers.get('Connection', '').lower() == 'close'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--transport-only', action='store_true', help='Verify progressive transport/lifecycle; does not validate rendered frames')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--mirror', action='store_true', help='Pass a fixture with a public test key through the native loopback TCP/AES-CTR mirror parser, without FairPlay negotiation')
    modes.add_argument('--alac-conversion', action='store_true', help='Feed ALAC and H.264 callbacks to the production AAC-LC conversion and HTTP output')
    parser.add_argument('--recording', action='store_true', help='Save the live ALAC/H.264 output and verify the recording library')
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--profile', choices=['baseline', 'high'], default='baseline')
    parser.add_argument('--output', type=Path, default=Path('build/vega-local-video'))
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    if args.recording and not args.alac_conversion:
        parser.error('--recording requires --alac-conversion')
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = shutil.which('vega')
    history, forwards, cycles, recordings = [], [], [], []
    result = {'passed': False, 'physicalValidation': False, 'transportOnly': args.transport_only, 'visualValidation': False,
              'encryptedMirrorLoopback': args.mirror, 'fairPlayNegotiation': False, 'alacConversion': args.alac_conversion}
    producer = log = client = None

    def run(*command, payload=None, timeout=30):
        completed = subprocess.run([sdk, *command], input=payload, capture_output=True, timeout=timeout)
        stdout, stderr = completed.stdout.decode(errors='replace'), completed.stderr.decode(errors='replace')
        history.append({'args': command, 'exit': completed.returncode, 'stdout': stdout, 'stderr': stderr})
        if completed.returncode:
            raise RuntimeError(stderr or stdout)
        return stdout

    def shell(*command, **options):
        return run('exec', 'vda', '-s', args.serial, 'shell', '-T', *command, **options)

    def read(name):
        return json.loads(shell('-c', COMPONENT, 'cat', name))

    def observe(name, predicate, seconds=20):
        deadline, last = time.monotonic() + seconds, None
        while time.monotonic() < deadline:
            try:
                last = read(name)
                if predicate(last):
                    return last
            except (RuntimeError, json.JSONDecodeError):
                pass
            time.sleep(0.1)
        raise RuntimeError(f'Observation did not match for {name}: {last}')

    def request(method, path, body=b''):
        client.request(method, path, body, {'Content-Type': 'text/parameters', 'X-Apple-Session-ID': '00112233-4455-6677-8899-aabbccddeeff'})
        response = client.getresponse()
        if response.status != 200:
            raise RuntimeError(f'AirPlay URL control returned {response.status}')
        return response.read()

    try:
        devices = run('exec', 'vda', 'devices')
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', devices, re.M):
            raise RuntimeError('VVD is not connected')
        _, fixture, frame_count = make_fixture(args.output, args.profile, True)
        if args.alac_conversion:
            fixture, _, audio_packets = append_audio_fixture(args.output, fixture, 'alac-aac')
            mode = '--av-transcode-record' if args.recording else '--av-transcode-serve'
        else:
            mode = '--mirror-serve' if args.mirror else '--serve'
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        shell('-c', COMPONENT, 'cat > /data/buzin-msse-video-probe', payload=args.runner.read_bytes())
        shell('-c', COMPONENT, 'cat > /data/buzin-msse-video.bin', payload=fixture)
        shell('-c', COMPONENT, 'chmod', '700', '/data/buzin-msse-video-probe')
        for cycle in range(2):
            shell('-c', COMPONENT, 'rm', '-f', '/data/airplay-port.json', '/data/local-video-url.json', '/data/local-video-state.json', '/data/video-quality.json', '/data/local-recording-name')
            for key in ['KEY_ENTER', 'KEY_DOWN', 'KEY_ENTER']:
                shell('inputd-cli', 'button_press', key)
            control = observe('/data/airplay-port.json', lambda value: isinstance(value.get('port'), int))['port']
            run('device', 'start-port-forwarding', '--device', args.serial, '--port', str(control), '--forward', 'true')
            forwards.append(control)
            log = (args.output / f'producer-{cycle + 1}.log').open('w')
            producer = subprocess.Popen([sdk, 'exec', 'vda', '-s', args.serial, 'shell', '-T', '-c', COMPONENT,
                                         'timeout', '35', '/data/buzin-msse-video-probe', '/data/buzin-msse-video.bin', mode],
                                        stdout=log, stderr=subprocess.STDOUT)
            url = observe('/data/local-video-url.json', lambda value: isinstance(value.get('url'), str))['url']
            client = http.client.HTTPConnection('127.0.0.1', control, timeout=5)
            client.response_class = AirPlayResponse
            request('POST', '/play', f'Content-Location: {url}\r\nStart-Position: 0\r\n'.encode())
            playing = observe('/data/playback-state.json', lambda value: value.get('state') == 'playing' and value.get('seconds', 0) > 0.8)
            print(json.dumps({'observedPlayback': playing}), flush=True)
            quality = observe('/data/video-quality.json', lambda value: value.get('attached') and (args.transport_only or (value.get('available') and value.get('total', 0) > value.get('dropped', 0))))
            active = read('/data/local-video-state.json')
            if active['framesFed'] >= frame_count or not active['connected']:
                raise RuntimeError('Playback did not start while native frames were still being produced')
            if active.get('workerOwned') is not True or active.get('dropped') != 0 or not 0 <= active.get('callbackQueuedBytes', -1) <= 8 * 1024 * 1024:
                raise RuntimeError('Expected the shared native callback worker with bounded queue and no fixture drops')
            if active.get('mirrorEncrypted') is not args.mirror:
                raise RuntimeError('Native producer did not use the requested mirror transport')
            if args.alac_conversion and (active.get('alacConversion') is not True or
                                         active.get('audioAccepted', 0) <= 0 or active.get('audioDropped') != 0):
                raise RuntimeError('ALAC callback flow did not run alongside video')
            entry = {'playing': playing, 'quality': quality, 'whileProducing': active, 'positiveFrameCounters': quality.get('available') is True and quality.get('total', 0) > quality.get('dropped', 0)}
            if cycle == 0:
                entry['end'] = observe('/data/playback-state.json', lambda value: value.get('seconds', 0) > 7 and value.get('state') in ['playing', 'ended'])
                entry['stream'] = observe('/data/local-video-state.json', lambda value: value.get('complete') and value.get('released'))
                if entry['stream']['framesFed'] != frame_count or entry['stream']['error']:
                    raise RuntimeError('Native full stream did not complete cleanly')
                if args.alac_conversion and (entry['stream'].get('audioAccepted') != len(audio_packets)
                                             or entry['stream'].get('audioDropped') != 0):
                    raise RuntimeError('ALAC conversion did not consume the full audio fixture')
            shell('inputd-cli', 'button_press', 'KEY_BACK')
            entry['releasedPlayer'] = observe('/data/playback-state.json', lambda value: value.get('state') == 'released')
            entry['releasedSurface'] = observe('/data/video-quality.json', lambda value: value.get('attached') is False)
            if cycle == 1:
                entry['stream'] = observe('/data/local-video-state.json', lambda value: value.get('released') and value.get('error') == 'clientClosed')
                if entry['stream']['framesFed'] >= frame_count:
                    raise RuntimeError('Early Back did not cancel native media production')
            if args.mirror:
                transport = entry['stream']
                if not transport.get('mirrorReleased') or transport.get('mirrorTimed', 0) < 1 or transport.get('mirrorReceived') != transport['mirrorTimed'] or transport.get('mirrorWrites', 0) <= transport['mirrorReceived'] * 2:
                    raise RuntimeError('Native mirror did not verify decrypted bytes/NTP, fragmented writes and released sockets')
                if cycle == 0 and transport['mirrorReceived'] != frame_count:
                    raise RuntimeError('Native mirror lost a fixture frame')
            if producer.wait(timeout=10) != 0:
                raise RuntimeError('Native producer did not exit cleanly')
            producer = None
            if args.recording:
                name = shell('-c', COMPONENT, 'cat', '/data/local-recording-name').strip()
                if not re.fullmatch(r'Buzin-\d{13,}-[A-Za-z0-9]{6}\.mp4', name):
                    raise RuntimeError('Unexpected recording name')
                recordings.append(name)
                copied = args.output / f'recording-{cycle + 1}.mp4'
                binary = subprocess.run([sdk, 'exec', 'vda', '-s', args.serial, 'shell', '-T',
                                         '-c', COMPONENT, 'cat', '/data/recordings/' + name],
                                        capture_output=True, check=True, timeout=20)
                copied.write_bytes(binary.stdout)
                tracks = json.loads(subprocess.check_output([
                    'ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name,codec_type:format=duration',
                    '-of', 'json', str(copied)], timeout=20))
                codecs = {track['codec_type']: track['codec_name'] for track in tracks['streams']}
                if codecs != {'video': 'h264', 'audio': 'aac'} or float(tracks['format']['duration']) <= 0.25:
                    raise RuntimeError('Saved recording lacks a usable A/V timeline')
                subprocess.run(['ffmpeg', '-v', 'error', '-xerror', '-i', str(copied), '-f', 'null', '-'],
                               check=True, timeout=20)
                entry['recording'] = tracks
            log.close(); log = None
            client.close(); client = None
            cycles.append(entry)
        if args.recording:
            for key in ('RIGHT', 'ENTER', 'DOWN', 'ENTER'):
                shell('inputd-cli', 'button_press', 'KEY_' + key)
            shell('-c', COMPONENT, 'rm', '-f', '/data/playback-state.json')
            shell('inputd-cli', 'button_press', 'KEY_ENTER')
            observe('/data/playback-state.json', lambda value: value.get('state') == 'playing' and value.get('seconds', 0) > 0.25)
            shell('inputd-cli', 'button_press', 'KEY_BACK')
            observe('/data/playback-state.json', lambda value: value.get('state') == 'released')
            result['recordingLibraryPlayback'] = True
        result.update(passed=True, nativeCallbackWorker=True, profile=args.profile, cycles=cycles)
        print(json.dumps(result, indent=2))
    except Exception as error:
        result['error'] = str(error)
        for name in ['playback-state', 'video-quality', 'local-video-state']:
            try:
                result[name] = read(f'/data/{name}.json')
            except Exception:
                pass
        raise
    finally:
        if client:
            client.close()
        if producer:
            producer.terminate()
            try:
                producer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                producer.kill(); producer.wait(timeout=5)
        if log:
            log.close()
        for port in forwards:
            try:
                run('device', 'stop-port-forwarding', '--device', args.serial, '--port', str(port), '--forward', 'true')
            except Exception:
                pass
        for name in recordings:
            try:
                shell('-c', COMPONENT, 'rm', '-f', '/data/recordings/' + name)
            except Exception:
                pass
        for name in ['local-recording-name', 'buzin-msse-video-probe', 'buzin-msse-video.bin', 'local-video-url.json', 'local-video-state.json', 'local-video-state.json.tmp']:
            try:
                shell('-c', COMPONENT, 'rm', '-f', '/data/' + name)
            except Exception:
                pass
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        (args.output / 'commands.json').write_text(json.dumps(history, indent=2) + '\n')


if __name__ == '__main__':
    main()
