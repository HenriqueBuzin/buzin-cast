"""Capture real Fire TV screens after animations settle, then remove test apps.

Requires an authorized, idle test Stick and prebuilt locale-enabled capture APKs
in outputs/. Does not build or upload application packages.
"""
from pathlib import Path
import argparse
import json
import io
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe'
DEVICE = ''
RTL = False
TOUCHED_PACKAGES = set()
CAPTURED = []
ACTIVITY = '/io.github.jqssun.airplay.MainActivity'
OUTPUT = ROOT / 'outputs/firestick-captures'
APP_LOCALES = ['en-US', 'ar-SA', 'cs-CZ', 'de-DE', 'el-GR', 'es-ES',
               'fr-FR', 'he-IL', 'hi-IN', 'it-IT', 'ja-JP', 'kk-KZ',
               'ko-KR', 'kw-GB', 'nl-NL', 'pl-PL', 'pt-BR', 'ru-RU',
               'sv-SE', 'tl-PH', 'vi-VN', 'zh-CN']
STORE_VARIANTS = ['en-AU', 'en-GB', 'en-IN', 'es-MX']


def selected_locales(requested, edition):
    supported = APP_LOCALES + STORE_VARIANTS
    if edition == 'deat':
        supported = [tag for tag in supported if tag != 'sv-SE']
    if not requested:
        return supported
    selected = list(dict.fromkeys(tag.strip() for item in requested for tag in item.split(',') if tag.strip()))
    unknown = sorted(set(selected) - set(supported))
    if unknown:
        raise ValueError(f'Unsupported locales for {edition}: {", ".join(unknown)}. Supported: {", ".join(supported)}')
    return selected


def adb(*args):
    return subprocess.check_output([str(ADB), '-s', DEVICE, *map(str, args)], timeout=150)


def frame():
    image = Image.open(io.BytesIO(adb('exec-out', 'screencap', '-p'))).convert('RGB')
    assert image.size == (1920, 1080)
    return image


def screen_region(region):
    left, top, right, bottom = region
    return (1920 - right, top, 1920 - left, bottom) if RTL else region


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/buzin-ui.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/buzin-ui.xml')).iter('node'))


def settled_shot(path, region):
    # Accessibility nodes exist before Compose finishes its entrance animation.
    # Wait independently, then compare actual rendered content across frames.
    time.sleep(7)
    previous = frame()
    for _ in range(12):
        time.sleep(1.5)
        current = frame()
        difference = ImageStat.Stat(ImageChops.difference(
            previous.crop(screen_region(region)), current.crop(screen_region(region)))).mean
        if max(difference) < 0.12:
            complete = max(current.getextrema()[c][1] for c in range(3)) >= 150
            complete = complete and focus_visible(current, path.name)
            complete = complete and all(current.crop(screen_region((115, top, 360, top + 35))).convert('L').getextrema()[1] >= 55
                                        for top in (105, 235, 365))
            if not complete:
                current.save(path.with_suffix('.rejected.png'))
                print('Rejected incomplete frame; retrying', path.name, flush=True)
                for code in ([20, 19] if path.name.startswith('01') else [19, 20]):
                    key(code)
                time.sleep(3)
                previous = frame()
                continue
            current.save(path)
            CAPTURED.append(path)
            print('Captured', path.relative_to(OUTPUT), flush=True)
            return
        previous = current
    raise RuntimeError(f'Visual content did not settle: {path}')


def tap(x, y):
    adb('shell', 'input', 'tap', x, y)


def key(code):
    adb('shell', 'input', 'keyevent', code)
    time.sleep(0.5)


def focus_visible(image, name):
    # Measure the straight part of the real mint focus stroke, not the label.
    region = ((110, 71, 400, 79) if name.startswith('01') else
              (110, 327, 400, 335) if name.startswith('02') else
              (410, 885, 1510, 981))
    crop = image.crop(screen_region(region))
    pixels = list(crop.get_flattened_data() if hasattr(crop, 'get_flattened_data') else crop.getdata())
    return sum(g > 180 and b > 140 and r < g * .85 for r, g, b in pixels) > 180


def capture(edition, package, locales, apk=None, locale_hook=True):
    global RTL
    selected_apk = Path(apk) if apk else ROOT / f'outputs/buzin-capture-{edition}.apk'
    if not selected_apk.is_file():
        raise FileNotFoundError(f'APK not found: {selected_apk}')
    # Stop the other receiver to avoid port conflicts, but preserve its install.
    peer = 'com.buzincast.tv' if package.endswith('.deat') else 'com.buzincast.tv.deat'
    adb('shell', 'am', 'force-stop', peer)
    result = adb('install', '-r', selected_apk).decode()
    print(result, flush=True)
    if 'Success' not in result:
        raise RuntimeError('APK installation was not confirmed; existing app will be preserved.')
    TOUCHED_PACKAGES.add(package)
    for tag in locales:
        device_locale = tag if tag != 'system' else adb('shell', 'getprop', 'persist.sys.locale').decode().strip()
        RTL = device_locale.split('-')[0].lower() in ('ar', 'he', 'iw')
        adb('shell', 'input', 'keyevent', '224')
        dest = OUTPUT / edition / tag
        dest.mkdir(parents=True, exist_ok=True)
        adb('shell', 'am', 'force-stop', package)
        args = ['shell', 'am', 'start', '-n', package + ACTIVITY]
        if locale_hook:
            args += ['--es', 'capture_locale', tag]
        adb(*args)
        time.sleep(10)
        # The startup overlay is skippable; skip if a slow cold launch persists.
        key(20)
        toward_sidebar = 22 if RTL else 21
        for code in [toward_sidebar, toward_sidebar, 19, 19, 19, 19, 23]:
            key(code)
        settled_shot(dest / '01-conectar.png', (500, 160, 1200, 800))
        key(20)
        key(20)
        key(23)
        settled_shot(dest / '02-configuracoes.png', (500, 100, 1850, 780))
        key(19)
        key(19)
        key(23)
        time.sleep(5)
        candidates = [n for n in nodes() if 'BuzinCast' in n.get('text', '')
                      and int(re.findall(r'\d+', n.get('bounds'))[1]) > 600]
        if len(candidates) != 1:
            raise RuntimeError(f'About entry not found: {edition}/{tag}')
        bounds = list(map(int, re.findall(r'\d+', candidates[0].get('bounds'))))
        tap((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
        time.sleep(3)
        # Locales can render the year with non-Latin numerals. The copyright
        # symbol and legal entity identify the visible About footer reliably.
        if not any('©' in n.get('text', '') and 'Buzin Solutions' in n.get('text', '') for n in nodes()):
            raise RuntimeError(f'About not open: {edition}/{tag}')
        settled_shot(dest / '03-sobre.png', (450, 120, 1700, 950))
    adb('shell', 'am', 'force-stop', package)
    print(adb('uninstall', package).decode(), flush=True)


def contact_sheets():
    from PIL import ImageDraw
    for name in ('01-conectar', '02-configuracoes', '03-sobre'):
        files = sorted(p for p in CAPTURED if p.name == name + '.png') if CAPTURED else sorted(OUTPUT.glob(f'*/*/{name}.png'))
        sheet = Image.new('RGB', (1440, max(1, (len(files) + 2) // 3) * 300), '#eeeeee')
        draw = ImageDraw.Draw(sheet)
        for index, path in enumerate(files):
            x, y = index % 3 * 480, index // 3 * 300
            with Image.open(path) as source:
                source.thumbnail((480, 270))
                sheet.paste(source, (x, y + 25))
            draw.text((x + 5, y + 5), f'{path.parent.parent.name}/{path.parent.name}', fill='black')
        sheet.save(OUTPUT / f'review-{name}.jpg')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, help='Local alias or ADB IP[:port]/serial')
    parser.add_argument('--adb', type=Path, default=ADB, help='Path to the Android SDK adb executable')
    parser.add_argument('--suite', choices=['store', 'production', 'all'], default='all')
    parser.add_argument('--edition', choices=['both', 'normal', 'deat'], default='both')
    parser.add_argument('--locales', nargs='+', help='Override store languages for a quick check')
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--restore-production', action='store_true')
    options = parser.parse_args()
    ADB = options.adb.expanduser().resolve()
    if not ADB.is_file():
        parser.error('ADB executable not found. Provide --adb.')
    # Validate all requested languages before touching the selected device.
    if options.suite in ['store', 'all']:
        if options.edition != 'deat':
            selected_locales(options.locales, 'normal')
        if options.edition != 'normal':
            selected_locales(options.locales, 'deat')
    config_path = ROOT / 'tools/firesticks.local.json'
    aliases = json.loads(config_path.read_text(encoding='utf-8')) if config_path.exists() else {}
    DEVICE = aliases.get(options.device, options.device)
    if '.' in DEVICE and ':' not in DEVICE:
        DEVICE += ':5555'
    OUTPUT = options.output.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if ':' in DEVICE:
        subprocess.run([str(ADB), 'connect', DEVICE], check=True, timeout=30)
    api = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    report = {'device': DEVICE, 'api': api, 'status': 'running', 'screenshots': []}
    try:
        if options.suite in ['store', 'all']:
            if options.edition != 'deat':
                capture('normal', 'com.buzincast.tv', selected_locales(options.locales, 'normal'))
            if options.edition != 'normal':
                capture('deat', 'com.buzincast.tv.deat', selected_locales(options.locales, 'deat'))
        if options.suite in ['production', 'all']:
            variants = [('normal', 'com.buzincast.tv', '0.6.6', 25),
                        ('compat', 'com.buzincast.tv', '0.6.6-compat', 22),
                        ('deat', 'com.buzincast.tv.deat', '0.6.5-nodownload-deat', 25),
                        ('compat-deat', 'com.buzincast.tv.deat', '0.6.5-compat-nodownload-deat', 22)]
            for edition, package, version, minimum in variants:
                if options.edition == 'normal' and package.endswith('.deat'):
                    continue
                if options.edition == 'deat' and not package.endswith('.deat'):
                    continue
                if api < minimum:
                    print(f'Skipped {edition}: API {minimum}+ required', flush=True)
                    continue
                capture('production-' + edition, package, ['system'],
                        ROOT / f'outputs/buzin-cast-{version}.apk', locale_hook=False)
        report['status'] = 'captured; visual review required'
    except BaseException as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        for package in sorted(TOUCHED_PACKAGES):
            adb('shell', 'am', 'force-stop', package)
            exists = subprocess.run([str(ADB), '-s', DEVICE, 'shell', 'pm', 'path', package], capture_output=True, timeout=30)
            if exists.returncode == 0 and exists.stdout.strip():
                adb('uninstall', package)
        if options.restore_production:
            adb('install', '-r', ROOT / 'outputs/buzin-cast-0.6.6.apk')
            adb('shell', 'am', 'start', '-n', 'com.buzincast.tv' + ACTIVITY)
        report['screenshots'] = [str(p.relative_to(OUTPUT)) for p in sorted(CAPTURED)]
        contact_sheets()
        (OUTPUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('TV released. Report:', OUTPUT / 'report.json', flush=True)
