"""Generate small synthetic AVC/AAC download fixtures, without reading personal media."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg',default=shutil.which('ffmpeg'))
    parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[1]/'fireos3-probe/src/main/assets/download_formats')
    subset=parser.add_mutually_exclusive_group()
    subset.add_argument('--transcode-only',action='store_true',help='Add conversion fixtures without regenerating the established remux fixtures.')
    subset.add_argument('--pcm-only',action='store_true',help='Add resampling/downmix fixtures while preserving the established media.')
    args=parser.parse_args()
    if not args.ffmpeg:parser.error('Install FFmpeg or pass --ffmpeg.')
    args.output.mkdir(parents=True,exist_ok=True)
    def run(*options):
        subprocess.run([args.ffmpeg,'-hide_banner','-loglevel','error','-y',*options],cwd=args.output,check=True)
    def conversion_fixtures():
        run('-f','lavfi','-i','testsrc=size=160x90:rate=15','-f','lavfi','-i','sine=frequency=880:sample_rate=44100',
            '-t','8','-ac','2','-c:v','libvpx','-b:v','256k','-g','30','-pix_fmt','yuv420p','-c:a','libvorbis','-q:a','4','vp8-vorbis.webm')
        run('-i','movie.mp4','-i','vp8-vorbis.webm','-map','0:v:0','-map','1:a:0','-c','copy','avc-vorbis.mkv')
        run('-i','vp8-vorbis.webm','-i','movie.mp4','-map','0:v:0','-map','1:a:0','-c','copy','vp8-aac.mkv')
        return ['vp8-vorbis.webm','avc-vorbis.mkv','vp8-aac.mkv']
    def pcm_fixtures():
        high='aevalsrc=0.12*sin(2*PI*880*t)+0.12*sin(2*PI*30000*t)|0.12*sin(2*PI*1760*t)+0.12*sin(2*PI*30000*t):s=96000:d=8:c=stereo'
        run('-i','movie.mp4','-f','lavfi','-i',high,'-map','0:v:0','-map','1:a:0','-t','8','-c:v','copy','-c:a','libvorbis','-q:a','6','-cutoff','45000','avc-vorbis-96k.mkv')
        tones=[440,550,660,110,770,880]
        expressions=[f'0.5*sin(2*PI*{tone}*t)*between(t\\,{channel}\\,{channel+1})+0.7*sin(2*PI*880*t)*gte(t\\,6)' for channel,tone in enumerate(tones)]
        surround='aevalsrc='+'|'.join(expressions)+':s=48000:d=8:c=5.1'
        run('-i','movie.mp4','-f','lavfi','-i',surround,'-map','0:v:0','-map','1:a:0','-t','8','-c:v','copy','-c:a','libvorbis','-q:a','6','avc-vorbis-51.mkv')
        return ['avc-vorbis-96k.mkv','avc-vorbis-51.mkv']
    if args.pcm_only:
        names=pcm_fixtures();path=args.output/'fixtures.json';manifest=json.loads(path.read_text(encoding='utf-8'))
        manifest['pcm_scope']='Synthetic 96 kHz stereo with above-Nyquist component; 5.1 isolated speakers and correlated loud tail'
        manifest['sha256'].update({name:hashlib.sha256((args.output/name).read_bytes()).hexdigest() for name in names})
        path.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');print('Generated PCM fixtures.');return
    if args.transcode_only:
        names=conversion_fixtures();path=args.output/'fixtures.json';manifest=json.loads(path.read_text(encoding='utf-8'))
        manifest['conversion_scope']='Eight-second synthetic VP8/Vorbis pattern and 880 Hz tone, plus mixed passthrough/conversion tracks'
        manifest['sha256'].update({name:hashlib.sha256((args.output/name).read_bytes()).hexdigest() for name in names})
        path.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');print('Generated conversion fixtures.');return
    run('-f','lavfi','-i','testsrc=size=160x90:rate=15','-f','lavfi','-i','anullsrc=r=44100:cl=stereo',
        '-t','8','-c:v','libx264','-pix_fmt','yuv420p','-profile:v','baseline','-g','30','-keyint_min','30',
        '-sc_threshold','0','-bf','0','-c:a','aac','-b:a','64k','-movflags','+faststart','movie.mp4')
    run('-i','movie.mp4','-c','copy','-hls_time','2','-hls_list_size','0','-hls_segment_filename','hls-%02d.ts','movie.m3u8')
    run('-i','movie.mp4','-c','copy','-f','dash','-seg_duration','2','-use_template','1','-use_timeline','1',
        '-init_seg_name','init-$RepresentationID$.mp4','-media_seg_name','chunk-$RepresentationID$-$Number$.m4s',
        '-adaptation_sets','id=0,streams=v id=1,streams=a','movie.mpd')
    run('-i','movie.mp4','-c:v','libx264','-pix_fmt','yuv420p','-profile:v','main','-g','30','-bf','2',
        '-c:a','copy','-movflags','+faststart','bframes.mp4')
    ns='{urn:mpeg:dash:schema:mpd:2011}'
    tree=ET.parse(args.output/'movie.mpd');root=tree.getroot();period=root.find(ns+'Period')
    for adaptation in period.findall(ns+'AdaptationSet'):
        if adaptation.get('contentType')=='audio':adaptation.set('lang','en-US')
    ET.register_namespace('',ns[1:-1]);tree.write(args.output/'movie.mpd',encoding='utf-8',xml_declaration=True)
    period.set('duration','PT8S');second=copy.deepcopy(period);second.set('id','1');second.set('start','PT8S')
    root.append(second);root.set('mediaPresentationDuration','PT16S')
    tree.write(args.output/'two-periods.mpd',encoding='utf-8',xml_declaration=True)
    names=['movie.mp4','movie.m3u8','movie.mpd','two-periods.mpd','bframes.mp4']
    names += [f'hls-{i:02d}.ts' for i in range(4)]
    names += [f'init-{track}.mp4' for track in range(2)]
    names += sorted(p.name for p in args.output.glob('chunk-*.m4s'))
    names += conversion_fixtures()
    names += pcm_fixtures()
    # ElementTree preserves the CRLF found in FFmpeg's MPD text nodes. Git's
    # LF policy normalizes those bytes on commit, so hash the canonical form.
    for name in names:
        if name.endswith(('.mpd','.m3u8')):
            path=args.output/name
            path.write_bytes(path.read_bytes().replace(b'\r\n',b'\n'))
    manifest={'scope':'Synthetic test pattern AVC plus silent AAC; eight seconds, duplicated DASH periods sixteen seconds',
        'sha256':{name:hashlib.sha256((args.output/name).read_bytes()).hexdigest() for name in names}}
    (args.output/'fixtures.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(f'Generated {len(names)} synthetic resources.')


if __name__=='__main__':main()
