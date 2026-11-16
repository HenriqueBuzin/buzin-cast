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
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Use an isolated emulator; physical devices are not allowed.')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'report.json').write_text('{"status":"running"}', encoding='utf-8')

    def adb(*command, binary=False):
        data = subprocess.check_output([args.adb, '-s', args.serial, *command], timeout=60)
        return data if binary else data.decode('utf-8', 'replace')

    def ui():
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/buzin-probe.xml')
        return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/buzin-probe.xml'))

    def tap(label):
        matches = [n for n in ui().iter() if n.get('text') == label]
        if len(matches) != 1:
            raise AssertionError('Expected one control: ' + label)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', matches[0].get('bounds')))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    def expect(*labels):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            texts = {n.get('text') for n in ui().iter()}
            if all(label in texts for label in labels):
                return
            time.sleep(.3)
        raise AssertionError('Missing result: ' + repr(labels) + '; UI=' + repr(texts))

    if adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '17':
        parser.error('This regression must run on Android API 17.')
    package = 'io.buzincast.fireos3probe'
    adb('install', '-r', str(args.apk.resolve()))
    adb('shell', 'input', 'keyevent', '82')
    report = {'api': 17, 'cycles': [], 'scope': 'synthetic callback, not a real sender session'}
    try:
        for cycle in range(2):
            adb('shell', 'am', 'force-stop', package)
            adb('shell', 'am', 'start', '-n', package+'/.ProbeActivity')
            expect('Run compatibility checks')
            tap('Run compatibility checks')
            expect('Native UxPlay HTTP parser: passed', 'UxPlay receiver: RTSP OPTIONS passed',
                   'H.264 sample: decoded frame')
            tap('Render receiver callback sample')
            expect('Receiver callback H.264: frame displayed')
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
            report['cycles'].append({'cycle': cycle+1, 'parser': 'pass', 'rtsp': 'pass', 'callback_surface': 'pass'})
        report['status'] = 'pass'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        adb('shell', 'am', 'force-stop', package)
        (args.output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
