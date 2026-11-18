"""Generate synthetic, silent HTTP media fixtures; never reads personal media."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg',default=shutil.which('ffmpeg'))
    parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[1]/'fireos3-probe/src/main/assets/url_formats')
    args=parser.parse_args()
    if not args.ffmpeg:parser.error('Install FFmpeg or pass --ffmpeg.')
    args.output.mkdir(parents=True,exist_ok=True)
    def run(*options):
        subprocess.run([args.ffmpeg,'-hide_banner','-loglevel','error','-y',*options],cwd=args.output,check=True)
    run('-f','lavfi','-i','testsrc=size=160x90:rate=15','-t','8','-an','-c:v','libx264','-pix_fmt','yuv420p',
        '-profile:v','baseline','-g','30','-keyint_min','30','-sc_threshold','0','-bf','0','-movflags','+faststart','sample.mp4')
    run('-i','sample.mp4','-c','copy','-hls_time','2','-hls_list_size','0','-hls_segment_filename','hls-%02d.ts','stream.m3u8')
    run('-i','sample.mp4','-c','copy','-f','dash','-seg_duration','2','-use_template','1','-use_timeline','1',
        '-init_seg_name','dash-init.mp4','-media_seg_name','dash-$Number$.m4s','stream.mpd')
    run('-f','lavfi','-i','anullsrc=r=44100:cl=stereo','-t','8','-c:a','aac','-profile:a','aac_low','-b:a','96k',
        '-movflags','+faststart','silence.m4a')
    names=['sample.mp4','stream.m3u8','stream.mpd','dash-init.mp4','silence.m4a']
    # Eight seconds at two-second GOP/segment boundaries produces four chunks.
    # Do not inventory unrelated files that happen to be in a custom output dir.
    names+=[f'hls-{index:02d}.ts' for index in range(4)]
    names+=[f'dash-{index}.m4s' for index in range(1,5)]
    manifest={'scope':'Synthetic test pattern H.264 and silent AAC, 8 seconds; no third-party media',
        'sha256':{name:hashlib.sha256((args.output/name).read_bytes()).hexdigest() for name in names}}
    (args.output/'fixtures.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(f'Generated {len(names)} synthetic resources.')


if __name__=='__main__':main()
