#!/usr/bin/env python3
"""Build the pinned media libraries and check ALAC PCM and H.264 MP4 mux contracts."""
import argparse
import base64
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', default='clang++')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-alac-host')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'app/src/main/cpp/third_party/ffmpeg'
    prefix = output / 'install'
    result = {'passed': False, 'physicalValidation': False,
              'ffmpegCommit': subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()}
    (output / 'result.json').write_text(json.dumps(result) + '\n')
    with (output / 'build.log').open('w') as log:
        def run(command):
            subprocess.run([str(value) for value in command], cwd=output, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=300)
        run(['bash', source / 'configure', f'--prefix={prefix}', '--disable-all',
             '--disable-asm', '--disable-x86asm', '--disable-autodetect', '--disable-network',
             '--disable-doc', '--disable-programs', '--enable-avcodec', '--enable-decoder=alac', '--enable-encoder=aac',
             '--enable-static', '--disable-shared', '--enable-pic', '--enable-avformat', '--enable-muxer=mp4'])
        run(['make', '-j2'])
        run(['make', 'DESTDIR=', 'install'])
        fixtures = json.loads((ROOT / 'fireos3-probe/src/main/assets/audio_samples.json').read_text())
        packet = next(f['packets'][0] for f in fixtures if f['codec'] == 2 and f['samplesPerFrame'] == 352)
        (output / 'packet.bin').write_bytes(base64.b64decode(packet, validate=True))
        executable = output / 'alac-decoder-test'
        run([args.compiler, '-std=c++23', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
             f'-I{prefix / "include"}', ROOT / 'vega/test/AlacDecoderTest.cpp',
             ROOT / 'vega/kepler/turbo-modules/AlacDecoder.cpp', prefix / 'lib/libavcodec.a',
             prefix / 'lib/libavutil.a', '-lm', '-pthread', '-o', executable])
        run([executable, output / 'packet.bin'])
        transcoder_sources = [ROOT / 'vega/kepler/turbo-modules/AlacDecoder.cpp',
                              ROOT / 'vega/kepler/turbo-modules/AlacAacTranscoder.cpp']
        mux_test = output / 'h264-mux-test'
        run([args.compiler, '-std=c++23', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
             f'-I{prefix / "include"}', ROOT / 'vega/test/H264Fmp4MuxerTest.cpp',
             ROOT / 'vega/kepler/turbo-modules/H264Fmp4Muxer.cpp', *transcoder_sources, prefix / 'lib/libavformat.a',
             prefix / 'lib/libavcodec.a', prefix / 'lib/libavutil.a', '-lm', '-pthread', '-o', mux_test])
        run([mux_test, output / 'packet.bin'])
        output_test = output / 'fmp4-output-test'
        run([args.compiler, '-std=c++23', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
             f'-I{prefix / "include"}', ROOT / 'vega/test/Fmp4VideoOutputTest.cpp',
             ROOT / 'vega/kepler/turbo-modules/Fmp4VideoOutput.cpp',
             ROOT / 'vega/kepler/turbo-modules/LocalVideoStream.cpp',
             ROOT / 'vega/kepler/turbo-modules/H264Fmp4Muxer.cpp', *transcoder_sources, prefix / 'lib/libavformat.a',
             prefix / 'lib/libavcodec.a', prefix / 'lib/libavutil.a', '-lm', '-pthread', '-o', output_test])
        run([output_test])
    result['passed'] = True
    (output / 'result.json').write_text(json.dumps(result) + '\n')
    print('Pinned media libraries: ALAC PCM, H.264 MP4 and cancellable video output contracts passed')


if __name__ == '__main__':
    main()
