"""Touch/D-pad and persistent sidebar outline regression on an isolated emulator."""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--reset', action='store_true', help='Remove the app and its data in this isolated emulator before installation')
    p.add_argument('--output', type=Path, default=Path('outputs/focus-navigation'))
    args = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        p.error('Physical TVs are not allowed.')
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'status': 'running'}
    (args.output/'report.json').write_text(json.dumps(report), encoding='utf-8')

    def adb(*cmd, binary=False):
        data = subprocess.check_output([args.adb, '-s', args.serial, *map(str, cmd)], timeout=90)
        return data if binary else data.decode('utf-8', 'replace')

    def ui():
        result = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/buzin-focus.xml')
        if 'dumped' not in result:
            return ET.Element('hierarchy')
        return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/buzin-focus.xml'))

    def control(label):
        deadline = time.monotonic() + 30
        while True:
            root = ui()
            if any(n.get('text') == label for n in root.iter()) or time.monotonic() >= deadline:
                break
            time.sleep(.3)
        parents = {child: node for node in root.iter() for child in node}
        matches = [n for n in root.iter() if n.get('text') == label]
        if len(matches) != 1:
            raise AssertionError(f'Expected one {label}: {ET.tostring(root, encoding="unicode")}')
        node = matches[0]
        while node.get('clickable') != 'true' and node.get('focusable') != 'true' and node in parents:
            node = parents[node]
        return node

    def bounds(node):
        return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))

    def tap(label):
        print('Tapping '+label, flush=True)
        x1, y1, x2, y2 = bounds(control(label))
        adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
        time.sleep(.4)

    def capture(name):
        data = adb('exec-out', 'screencap', '-p', binary=True)
        (args.output/f'{name}.png').write_bytes(data)
        return Image.open(io.BytesIO(data)).convert('RGB')

    def outline(image, rect):
        x1, y1, x2, y2 = rect
        # The straight top stroke excludes the icon, label and rounded corners.
        strip = image.crop((x1+(x2-x1)//3, y1, x2-(x2-x1)//3, y1+max(5,(y2-y1)//8)))
        mint = sum(g > 85 and g > r*1.35 and b > 55 for r,g,b in strip.getdata())
        if mint < strip.width//2:
            raise AssertionError('The active sidebar top outline is missing.')

    package = 'com.buzincast.tv'
    if args.reset and adb('shell', 'pm', 'path', package).strip():
        adb('uninstall', package)
    adb('install', '-r', str(args.apk.resolve()))
    adb('shell', 'input', 'keyevent', '82')
    animation_scale = adb('shell', 'settings', 'get', 'global', 'animator_duration_scale').strip()
    # Continuous decorative orbits otherwise prevent UiAutomator's idle wait.
    adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
    try:
        adb('shell', 'am', 'force-stop', package)
        adb('shell', 'am', 'start', '-W', '-n', package+'/io.github.jqssun.airplay.DebugTvPreviewActivity')
        tap('Settings')
        active = control('Settings')
        assert active.get('selected') == 'true', 'Sidebar does not expose active destination'
        assert active.get('focused') == 'true', 'Touch did not focus sidebar control'
        tap('Cinema')
        active = control('Settings')
        assert active.get('focused') != 'true', 'Focus did not leave sidebar'
        assert control('Cinema').get('focused') == 'true', 'Touch did not focus the profile'
        outline(capture('settings-touch'), bounds(active))
        adb('shell', 'input', 'keyevent', '22')
        assert control('Presentation').get('focused') == 'true', 'D-pad did not resume from touched control'
        tap('Overview')
        overview = bounds(control('Overview'))
        tap('About BuzinCast')
        assert control('Close').get('focused') == 'true', 'About did not receive focus'
        outline(capture('about-active-menu'), overview)
        tap('Close')
        outline(capture('overview-after-close'), bounds(control('Overview')))
        report = {'status': 'pass', 'checks': ['touch sidebar', 'touch profile', 'D-pad after touch',
                  'active outline outside focus', 'active outline behind About', 'close About']}
        print(json.dumps(report, indent=2))
    except Exception as error:
        report = {'status': 'failed', 'error': str(error)}
        try:
            capture('failure')
            (args.output/'failure.xml').write_text(ET.tostring(ui(), encoding='unicode'), encoding='utf-8')
        except Exception:
            pass
        raise
    finally:
        adb('shell', 'am', 'force-stop', package)
        if animation_scale == 'null':
            adb('shell', 'settings', 'delete', 'global', 'animator_duration_scale')
        else:
            adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', animation_scale)
        (args.output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
