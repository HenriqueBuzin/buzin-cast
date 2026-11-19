"""Check coordinated Fire OS identities/API levels; does not verify console device assignment or signatures."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TARGETS = ('legacy', 'compat', 'modern')
REGIONS = ('general', 'nodownload')
EDITIONS = {f'{target}.{region}' for target in TARGETS for region in REGIONS}


def load_plan(path):
    plan = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in plan:
            raise ValueError('Duplicate release plan property: ' + key)
        plan[key] = value.strip()
    if not re.fullmatch(r'\d+\.\d+\.\d+', plan['versionName']):
        raise ValueError('Expected a semantic base versionName')
    if [int(plan[target + '.minSdk']) for target in TARGETS] != [17, 22, 25]:
        raise ValueError('Release must retain the API17/22/25 coverage boundaries')
    for region in REGIONS:
        codes = [int(plan[f'{target}.{region}.versionCode']) for target in TARGETS]
        if not 0 < codes[0] < codes[1] < codes[2] <= 2100000000:
            raise ValueError('Higher minimum API requires a higher versionCode: ' + region)
    return plan


def expected(plan, edition):
    target, region = edition.split('.')
    suffix = '-legacy' if target == 'legacy' else '-compat' if target == 'compat' else ''
    return {
        'package': 'com.buzincast.tv' + ('.deat' if region == 'nodownload' else ''),
        'versionCode': int(plan[edition + '.versionCode']),
        'versionName': plan['versionName'] + suffix + ('-nodownload' if region == 'nodownload' else ''),
        'minSdk': int(plan[target + '.minSdk']),
        'debuggable': False,
    }


def read_manifest(path):
    root = ET.parse(path).getroot()
    sdk = root.find('uses-sdk')
    app = root.find('application')
    if sdk is None or app is None or sdk.get(ANDROID + 'maxSdkVersion') is not None:
        raise ValueError('Missing application/SDK or an unexpected maximum SDK filter')
    return {'package': root.get('package'), 'versionCode': int(root.get(ANDROID + 'versionCode')),
            'versionName': root.get(ANDROID + 'versionName'), 'minSdk': int(sdk.get(ANDROID + 'minSdkVersion')),
            'debuggable': app.get(ANDROID + 'debuggable', 'false') != 'false'}


def read_apk(path, aapt):
    output = subprocess.check_output([aapt, 'dump', 'badging', str(path)], timeout=60).decode('utf-8', 'replace')
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", output, re.M)
    if not package or not sdk or re.search(r'^maxSdkVersion:', output, re.M):
        raise ValueError('Missing APK identity/SDK or an unexpected maximum SDK filter')
    return {'package': package[1], 'versionCode': int(package[2]), 'versionName': package[3],
            'minSdk': int(sdk[1]), 'debuggable': 'application-debuggable' in output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, default=ROOT / 'gradle/fireos-release.properties')
    parser.add_argument('--manifest', action='append', default=[], metavar='EDITION=PATH')
    parser.add_argument('--apk', action='append', default=[], metavar='EDITION=PATH')
    parser.add_argument('--aapt', help='Required with --apk')
    parser.add_argument('--require-all', action='store_true', help='Fail unless all six editions are provided')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = {'status': 'failed', 'verified': [], 'completeSet': False, 'deviceTargetingVerified': False,
              'scope': 'Version/package/API identity only; not signatures, runtime, country availability or console device assignments'}
    try:
        plan = load_plan(args.plan)
        report['plan_sha256'] = hashlib.sha256(args.plan.read_bytes()).hexdigest()
        seen = set()
        for kind, inputs in (('manifest', args.manifest), ('apk', args.apk)):
            for item in inputs:
                edition, filename = item.split('=', 1)
                if edition not in EDITIONS or edition in seen:
                    raise ValueError('Unknown or repeated edition: ' + edition)
                if kind == 'apk' and not args.aapt:
                    raise ValueError('--apk requires --aapt')
                path = Path(filename)
                actual = read_manifest(path) if kind == 'manifest' else read_apk(path, args.aapt)
                wanted = expected(plan, edition)
                if actual != wanted:
                    raise ValueError(f'{edition}: identity differs; expected {wanted}, found {actual}')
                seen.add(edition)
                report['verified'].append({'edition': edition, 'kind': kind, **actual,
                                           'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        report['completeSet'] = seen == EDITIONS
        if args.require_all and not report['completeSet']:
            raise ValueError('Missing editions: ' + ', '.join(sorted(EDITIONS - seen)))
        report['status'] = 'pass'
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        report['error'] = str(error)
    serialized = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding='utf-8')
    print(serialized, end='')
    return 0 if report['status'] == 'pass' else 1


if __name__ == '__main__':
    raise SystemExit(main())
