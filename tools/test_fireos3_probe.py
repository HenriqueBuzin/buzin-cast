"""Repeatable API 17 receiver callback smoke test; refuses physical devices."""
import argparse
import json
import plistlib
import socket
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageStat


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--adb-server-port', type=int, default=5037, help='ADB server port; isolate concurrent emulator runs when needed.')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('outputs/fireos3-callback'))
    parser.add_argument('--cycles', type=int, default=2, help='Repeat one to ten complete startup/shutdown cycles')
    parser.add_argument('--keep-installed', action='store_true', help='Retain the stopped test APK for manual investigation')
    args = parser.parse_args()
    if not 1 <= args.adb_server_port <= 65535:
        parser.error('Invalid ADB server port.')
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Use an isolated emulator; physical devices are not allowed.')
    if not 1 <= args.cycles <= 10:
        parser.error('--cycles must be between one and ten.')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'report.json').write_text('{"status":"running"}', encoding='utf-8')

    def adb(*command, binary=False):
        data = subprocess.check_output([args.adb, '-P', str(args.adb_server_port), '-s', args.serial, *command], timeout=60)
        return data if binary else data.decode('utf-8', 'replace')

    def ui():
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/buzin-probe.xml')
        return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/buzin-probe.xml'))

    def tap(label):
        print('Tapping '+label, flush=True)
        matches = [n for n in ui().iter() if n.get('text') == label]
        if len(matches) != 1:
            raise AssertionError('Expected one control: ' + label)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', matches[0].get('bounds')))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    def expect(*labels, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            texts = {n.get('text') for n in ui().iter()}
            if all(label in texts for label in labels):
                return
            if any(label.startswith('Streaming stack:') for label in labels):
                failures = [value for value in texts if value and value.startswith('Streaming stack check failed:')]
                if failures: raise AssertionError(failures[0])
            time.sleep(.3)
        raise AssertionError('Missing result: ' + repr(labels) + '; UI=' + repr(texts))

    def expect_live_ready():
        deadline = time.monotonic()+25
        while time.monotonic() < deadline:
            texts = [n.get('text', '') for n in ui().iter()]
            if any('experimental H.264 + ALAC/AAC receiver' in text for text in texts): return
            failures = [text for text in texts if 'receiver failed:' in text]
            if failures: raise AssertionError(failures[0])
            time.sleep(.3)
        raise AssertionError('Experimental receiver never became ready')

    def check_configured_video(expected):
        port=adb('forward','tcp:0','tcp:17001').strip()
        try:
            with socket.create_connection(('127.0.0.1',int(port)),timeout=3) as connection:
                connection.sendall(b'GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n')
                data=b''
                while b'\r\n\r\n' not in data:
                    chunk=connection.recv(4096)
                    if not chunk:raise AssertionError('Configured receiver closed before /info')
                    data+=chunk
                header,body=data.split(b'\r\n\r\n',1)
                if not header.startswith(b'RTSP/1.0 200 OK'):raise AssertionError('Configured receiver did not answer')
                count=int(re.search(rb'(?i)Content-Length:\s*(\d+)',header).group(1))
                if count>65536:raise AssertionError('Unexpected info size')
                while len(body)<count:
                    chunk=connection.recv(count-len(body))
                    if not chunk:raise AssertionError('Truncated native info')
                    body+=chunk
                display=plistlib.loads(body)['displays'][0]
                if (display['width'],display['height'],display['maxFPS'])!=expected:
                    raise AssertionError('Configured dimensions did not reach the native receiver')
        finally:adb('forward','--remove','tcp:'+port)

    if adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '17':
        parser.error('This regression must run on Android API 17.')
    package = 'io.buzincast.fireos3probe'
    adb('install', '-r', str(args.apk.resolve()))
    adb('shell', 'input', 'keyevent', '82')
    report = {'api': 17, 'cycles': [], 'scope': 'real DNS-SD/RTSP sockets, synthetic H.264 and ALAC/AAC-LC/AAC-ELD, muted AudioTrack; not an Apple sender session'}
    try:
        for cycle in range(args.cycles):
            entry = {'cycle': cycle+1}
            report['cycles'].append(entry)
            adb('shell', 'am', 'force-stop', package)
            games=cycle%2==1
            adb('shell', 'am', 'start', '-n', package+'/.ProbeActivity','--ez','probe_games',str(games).lower())
            expect('Run compatibility checks')
            tap('Run compatibility checks')
            expect('Native UxPlay HTTP parser: passed', 'UxPlay receiver: RTSP OPTIONS passed',
                   'H.264 sample: decoded frame')
            entry.update(parser='pass', rtsp='pass')
            tap('Render receiver callback sample')
            expect('Receiver callback H.264: frame displayed')
            entry['callback_surface'] = 'pass'
            tap('Run streaming stack checks')
            expect('Streaming stack: DNS-SD + RTSP + H.264 + audio + A/V clock passed', timeout=45)
            tap('Start experimental AV receiver')
            expect_live_ready()
            check_configured_video((1280,720,60) if games else (1920,1080,30));entry['configured_dimensions_rtsp']='pass'
            entry['configured_mode']=[1280,720,60] if games else [1920,1080,30]
            tap('Stop experimental receiver')
            expect('Experimental receiver stopped.', timeout=15)
            # Restart the streaming self-test after a full live start/stop.
            tap('Run streaming stack checks')
            expect('Streaming stack: DNS-SD + RTSP + H.264 + audio + A/V clock passed', timeout=45)
            entry['dns_rtsp_live_decoder'] = 'pass'
            entry['live_start_stop'] = 'pass'
            entry['alac_aac_lc_aac_eld_timeline_pcm_output'] = 'pass'
            entry['native_audio_format_volume_flush_and_shutdown'] = 'pass'
            entry['audio_idle_release_and_resume'] = 'pass'
            entry['pcm_clock_video_wait_drop_and_flush'] = 'pass'
            # API 17's adbd does not implement exec-out. Pull a PNG file intact.
            adb('shell', 'screencap', '-p', '/data/local/tmp/buzin-probe.png')
            picture = args.output/f'cycle-{cycle+1}.png'
            adb('pull', '/data/local/tmp/buzin-probe.png', str(picture.resolve()))
            with Image.open(picture) as image:
                blank = max(ImageStat.Stat(image.convert('RGB')).stddev) < 5
            if blank:
                # Older adbd can capture an empty framebuffer despite visible
                # host rendering. The emulator console captures its real display.
                with tempfile.TemporaryDirectory(dir=args.output.resolve()) as folder:
                    adb('emu', 'screenrecord', 'screenshot', folder)
                    grabs = list(Path(folder).glob('*.png'))
                    if len(grabs) != 1: raise AssertionError('Emulator capture was not produced')
                    picture.write_bytes(grabs[0].read_bytes())
            with Image.open(picture) as image:
                rgb = image.convert('RGB')
                lower = rgb.crop((0, rgb.height//2, rgb.width, rgb.height))
                colored = sum(max(pixel)-min(pixel) > 60 for pixel in lower.getdata())
                if colored < 100:
                    raise AssertionError('The synthetic colored video frame is absent from the captured display.')
            # Repeat within the same Activity to detect a receiver/codec leak.
            tap('Render receiver callback sample')
            expect('Receiver callback H.264: frame displayed')
        report['status'] = 'pass'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        try:
            adb('shell', 'am', 'force-stop', package)
            if not args.keep_installed:
                adb('uninstall', package)
            report['cleanup'] = 'stopped' if args.keep_installed else 'uninstalled'
        except Exception as error:
            report['status'] = 'failed'
            report['cleanup_error'] = str(error)
            raise
        finally:
            (args.output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
