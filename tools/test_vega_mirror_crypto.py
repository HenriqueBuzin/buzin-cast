#!/usr/bin/env python3
"""ASan/UBSan contract for staged UxPlay mirror AES-CTR packet boundaries."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--openssl-prefix', type=Path, default=Path('/usr'))
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-mirror-crypto-host')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    prefix = args.openssl_prefix.resolve()
    if not (prefix / 'include/openssl/evp.h').exists():
        parser.error('Pass the prefix of a host OpenSSL development installation')
    source = ROOT / 'vega/generated/UxPlay/lib'
    result = {'passed': False, 'physicalValidation': False, 'fairPlayNegotiation': False}
    (output / 'result.json').write_text(json.dumps(result) + '\n')
    with (output / 'build.log').open('w') as log:
        def run(command):
            subprocess.run([str(v) for v in command], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        common = ['-g', '-O1', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                  f'-I{source}', f'-I{ROOT / "app/src/main/cpp/third_party/libplist/include"}', f'-I{prefix / "include"}']
        objects = []
        for name in ('mirror_buffer', 'crypto', 'utils'):
            target = output / f'{name}.o'
            run(['clang', '-std=c11', '-D_GNU_SOURCE', '-Wno-deprecated-declarations', *common,
                 '-c', source / f'{name}.c', '-o', target])
            objects.append(target)
        executable = output / 'mirror-crypto-test'
        run(['clang++', '-std=c++23', '-Wall', '-Wextra', '-Werror', *common,
             ROOT / 'vega/test/MirrorCryptoTest.cpp', *objects, f'-L{prefix / "lib"}',
             f'-Wl,-rpath,{prefix / "lib"}', '-lcrypto', '-pthread', '-o', executable])
        run([executable])
    result['passed'] = True
    (output / 'result.json').write_text(json.dumps(result) + '\n')
    print('Mirror AES-CTR: short/empty fragments, in-place decode and re-key contracts passed')


if __name__ == '__main__':
    main()
