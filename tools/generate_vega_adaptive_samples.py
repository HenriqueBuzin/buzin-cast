#!/usr/bin/env python3
"""Generate local HLS/DASH fixtures from the packaged Buzin test pattern."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-adaptive-samples')
    args = parser.parse_args()
    source = ROOT / 'vega/assets/raw/media/playback-sample.mp4'
    for kind in ('hls', 'dash'):
        folder = args.output.resolve() / kind
        folder.mkdir(parents=True, exist_ok=True)
        command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(source), '-c', 'copy']
        if kind == 'hls':
            command += ['-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_playlist_type', 'vod', 'stream.m3u8']
        else:
            command += ['-f', 'dash', '-seg_duration', '2', '-use_template', '1', '-use_timeline', '1', 'stream.mpd']
        subprocess.run(command, cwd=folder, check=True)
    print(args.output.resolve())

if __name__ == '__main__':
    main()
