"""Repeatable API 17 receiver callback smoke test; refuses physical devices."""
import argparse
import json
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
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('outputs/fireos3-callback'))
    parser.add_argument('--cycles', type=int, default=2, help='Repeat one to ten complete startup/shutdown cycles')
    parser.add_argument('--keep-installed', action='store_true', help='Retain the stopped test APK for manual investigation')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Use an isolated emulator; physical devices are not allowed.')
    if not 1 <= args.cycles <= 10:
        parser.error('--cycles must be between one and ten.')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'report.json').write_text('{"status":"running"}', encoding='utf-8')

    def adb(*command, binary=False):
        data = subprocess.check_output([args.adb, '-s', args.serial, *command], timeout=60)
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
            time.sleep(.3)
        raise AssertionError('Missing result: ' + repr(labels) + '; UI=' + repr(texts))

    def expect_live_ready():
        deadline = time.monotonic()+25
        while time.monotonic() < deadline:
            texts = [n.get('text', '') for n in ui().iter()]
            if any('experimental H.264 video only' in text for text in texts): return
            failures = [text for text in texts if 'receiver failed:' in text]
            if failures: raise AssertionError(failures[0])
            time.sleep(.3)
        raise AssertionError('Experimental receiver never became ready')

    if adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '17':
        parser.error('This regression must run on Android API 17.')
    package = 'io.buzincast.fireos3probe'
    adb('install', '-r', str(args.apk.resolve()))
    adb('shell', 'input', 'keyevent', '82')
    report = {'api': 17, 'cycles': [], 'scope': 'real DNS-SD/RTSP sockets with synthetic H.264, not an Apple sender session'}
    try:
        for cycle in range(args.cycles):
            entry = {'cycle': cycle+1}
            report['cycles'].append(entry)
            adb('shell', 'am', 'force-stop', package)
            adb('shell', 'am', 'start', '-n', package+'/.ProbeActivity')
            expect('Run compatibility checks')
            tap('Run compatibility checks')
            expect('Native UxPlay HTTP parser: passed', 'UxPlay receiver: RTSP OPTIONS passed',
                   'H.264 sample: decoded frame')
            entry.update(parser='pass', rtsp='pass')
            tap('Render receiver callback sample')
            expect('Receiver callback H.264: frame displayed')
            entry['callback_surface'] = 'pass'
            tap('Run streaming stack checks')
            expect('Streaming stack: DNS-SD + RTSP + H.264 Surface passed', timeout=45)
            tap('Start experimental video receiver')
            expect_live_ready()
            tap('Stop experimental receiver')
            expect('Experimental receiver stopped.', timeout=15)
            # Restart the streaming self-test after a full live start/stop.
            tap('Run streaming stack checks')
            expect('Streaming stack: DNS-SD + RTSP + H.264 Surface passed', timeout=45)
            entry['dns_rtsp_live_decoder'] = 'pass'
            entry['live_start_stop'] = 'pass'
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
