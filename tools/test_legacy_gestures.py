"""Exercise held touch/DPAD gestures or native sender controls on an isolated API17 emulator."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--adb-server-port',type=int,default=5037)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--test-apk',type=Path,required=True)
    parser.add_argument('--controls',action='store_true',help='Exercise native sender identity, real mDNS/HTTP and UI/remote controls instead of held restart gestures.')
    parser.add_argument('--metadata',action='store_true',help='Also exercise synthetic native metadata/art/progress packets and save a render of the actual native Views; implies --controls.')
    parser.add_argument('--system-media',action='store_true',help='Also verify platform metadata, audio focus, background media keys and three native mirror teardown/recovery cycles; implies --metadata.')
    parser.add_argument('--url-service',action='store_true',help='Verify native-to-URL handoff, platform playback, UI/media controls, HTTP failure and receiver recovery.')
    parser.add_argument('--fcast',action='store_true',help='Verify shared production FCast transport: TCP v2, DNS-SD/TXT, HTTP playback, controls and lifecycle.')
    parser.add_argument('--dlna',action='store_true',help='Verify shared DLNA SOAP playback, photos and lifecycle on API17.')
    parser.add_argument('--native-url',action='store_true',help='Verify native UxPlay HTTP URL commands, JNI snapshots, playback and shutdown.')
    parser.add_argument('--recording',action='store_true',help='Verify native H.264/PCM recording, real MKV/local playback/export/delete, or DE/AT recording disabled.')
    parser.add_argument('--download',action='store_true',help='Verify API17 MP4/HLS/DASH export, local playback, library, cancellation and restricted gate.')
    parser.add_argument('--approval',action='store_true',help='Verify real native/FCast/DLNA admission, modal focus, denial, protocol grants, timeout, stop and background prompt.')
    parser.add_argument('--pin',action='store_true',help='Verify real native SRP PIN proofs with an independent host client, focused UI, DNS-SD flags, background and lifecycle; requires cryptography.')
    parser.add_argument('--ffprobe',type=Path,help='Optional ffprobe executable for independent packet/track validation of the synthetic recording.')
    parser.add_argument('--ffmpeg',type=Path,help='Optional FFmpeg executable for independent decoding of exported download fixtures.')
    parser.add_argument('--package',default='com.buzincast.tv.legacy.preview',choices=[
        'com.buzincast.tv.legacy.preview','com.buzincast.tv.deat.legacy.preview'])
    parser.add_argument('--output',type=Path,default=Path('outputs/legacy-gesture-checks'))
    args=parser.parse_args()
    for path in (args.apk,args.test_apk):
        with ZipFile(path) as archive:
            if not {'AndroidManifest.xml','classes.dex'}.issubset(archive.namelist()) or archive.testzip() is not None:
                parser.error('APK is incomplete or corrupt: '+str(path))
    if sum((args.url_service,args.fcast,args.dlna,args.native_url,args.recording,args.download,args.approval,args.pin,bool(args.controls or args.metadata or args.system_media)))>1:parser.error('Transport and native-control scenarios must run separately.')
    if args.system_media:args.metadata=True
    if args.metadata:args.controls=True
    local=re.fullmatch(r'127\.0\.0\.1:(\d+)',args.serial)
    local_emulator=local and 5555<=int(local[1])<=5683 and int(local[1])%2==1
    if not re.fullmatch(r'emulator-\d+',args.serial) and not local_emulator:parser.error('Only an isolated emulator is accepted.')
    if not 1<=args.adb_server_port<=65535:parser.error('Invalid ADB server port.')
    def adb(*command,timeout=60):
        return subprocess.check_output([args.adb,'-P',str(args.adb_server_port),'-s',args.serial,*map(str,command)],timeout=timeout).decode('utf-8','replace')
    if adb('shell','getprop','ro.build.version.sdk').strip()!='17' or adb('shell','getprop','ro.kernel.qemu').strip()!='1':
        parser.error('Use the isolated Android API17 emulator.')
    display=None
    if args.system_media:
        size=re.search(r'init=(\d+)x(\d+)',adb('shell','dumpsys','window'))
        if not size or min(map(int,size.groups()))<720 or max(map(int,size.groups()))<1280:
            parser.error('System/mirror UI checks need a TV-sized emulator: -skin 1280x720 -dpi-device 160.')
        display=list(map(int,size.groups()))
    args.output.mkdir(parents=True,exist_ok=True)
    report={'status':'running','scope':('Native RTSP identity, real multicast DNS/HTTP, touch/media keys and replacement sender' if args.controls else 'Real touch/DPAD gestures during a native receiver stop/start')+'; not a physical sender interoperability test',
        'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'test_apk_sha256':hashlib.sha256(args.test_apk.read_bytes()).hexdigest()}
    if args.url_service:report['scope']='Real API17 receiver service to HTTP platform player handoff, UI/media keys, HTTP failure and native restart; synthetic MP4, not physical sender interoperability'
    if args.fcast:report['scope']='Shared production FCast v2 TCP transport, DNS-SD/TXT, synthetic HTTP MP4 playback, controls and lifecycle; not physical sender interoperability'
    if args.dlna:report['scope']='Shared DLNA SOAP transport, synthetic HTTP playback, photo controls and lifecycle on API17; not physical sender interoperability'
    if args.native_url:report['scope']='Native UxPlay HTTP URL callbacks through JNI/service/Media3 on API17; synthetic direct MP4/HLS/AAC and FCUP reverse-channel HLS, not a physical Apple sender test'
    if args.recording:report['scope']='Synthetic native H.264/ALAC through bounded capture/shared Matroska writer, actual local playback/export/delete; DE/AT negative gate; not physical mirroring'
    if args.download:report['scope']='Synthetic AVC/AAC MP4/HLS/DASH, repeated DASH periods and B-frame AVC -> MP4 export/local playback/library/cancellation; DE/AT disabled; not transcoding, physical or real HTTPS validation'
    if args.approval:report['scope']='Actual API17 native RTSP, FCast and DLNA admission and remote-focus UI; grants, refusal, timeout, lifecycle and background prompt; not PIN pairing or a physical sender test'
    if args.pin:report['scope']='Actual API17 native PIN/SRP, independent SHA1/SRP/AES-GCM/Ed25519/X25519 client, malformed input rejection, native UI, DNS-SD flags, background and stop; not a physical Apple sender or universal access-control certification'
    if display:report['display_init']=display
    def capture_native_view(source='legacy-metadata.b64',target='metadata-ui.png'):
        payload=base64.b64decode(adb('shell','run-as',args.package,'cat','files/'+source,timeout=30))
        if not payload.startswith(b'\x89PNG\r\n\x1a\n'):raise AssertionError('Native View render was not a valid PNG')
        (args.output/target).write_bytes(payload)
        from PIL import Image
        with Image.open(args.output/target) as frame:frame.verify()
        report['ui_render']=target+'; actual native View hierarchy drawn on the emulator, without Surface/video capture'
    def system_instrument(options):
        command=[args.adb,'-P',str(args.adb_server_port),'-s',args.serial,'shell','am','instrument','-w',*options,
            args.package+'.test/io.buzincast.legacy.LegacyGestureChecks']
        seen=[];keys={'pause':127,'play':126,'next':87,'previous':88,'toggle':85,'fast_forward':90,'rewind':89,'play_final':126,'recover_audio':85}
        with (args.output/'instrumentation.txt').open('wb') as output:
            process=subprocess.Popen(command,stdout=output,stderr=subprocess.STDOUT)
            try:
                deadline=time.monotonic()+240
                while process.poll() is None:
                    if time.monotonic()>deadline:raise TimeoutError('System media instrumentation timed out')
                    files=adb('shell','run-as',args.package,'ls','files')
                    if 'system-media-request' in files:
                        stage=adb('shell','run-as',args.package,'cat','files/system-media-request').strip()
                        if stage not in seen and stage in {*keys,'state','background_done','stopped'}:
                            if stage in keys:
                                if stage=='recover_audio':
                                    audio=adb('shell','dumpsys','audio')
                                    (args.output/'audio-before-explicit-recovery.txt').write_text(audio,encoding='utf-8')
                                adb('shell','input','keyevent',keys[stage])
                            elif stage=='background_done':
                                launch=adb('shell','am','start','-W','-f','0x10200000','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
                                    '-n',args.package+'/io.buzincast.legacy.LegacyActivity')
                                (args.output/'return-launch.txt').write_text(launch,encoding='utf-8')
                                (args.output/'return-window.txt').write_text(adb('shell','dumpsys','window'),encoding='utf-8')
                                adb('shell','rm','-f','/data/local/tmp/buzin-legacy-return.xml')
                                dump_result=adb('shell','uiautomator','dump','/data/local/tmp/buzin-legacy-return.xml')
                                (args.output/'return-dump.txt').write_text(dump_result,encoding='utf-8')
                                tree=adb('shell','cat','/data/local/tmp/buzin-legacy-return.xml')
                                (args.output/'return-ui.xml').write_text(tree,encoding='utf-8')
                                if not any(node.get('text')=='Test' for node in ET.fromstring(tree).iter('node')):
                                    raise AssertionError('Fresh audio metadata was not visible after returning from mirror mode')
                            elif stage in ('state','stopped'):
                                dump=adb('shell','dumpsys','audio')
                                (args.output/('audio-'+stage+'.txt')).write_text(dump,encoding='utf-8')
                                if stage=='state':
                                    clients=[line for line in dump.splitlines() if 'pi:' in line and args.package in line]
                                    if not clients or not any('-- client:' in line and '-- client: null' not in line for line in clients):
                                        raise AssertionError('Platform media button entry is not associated with a live remote control client')
                                elif args.package in dump:
                                    raise AssertionError('App retained audio focus or a platform client after receiver stop')
                                if stage=='stopped':
                                    def listening(sockets):
                                        return any(len(fields:=line.split())>3 and fields[1].endswith(':1B58') and fields[3]=='0A' for line in sockets.splitlines())
                                    until=time.monotonic()+10
                                    while listening(adb('shell','cat','/proc/net/tcp')):
                                        if time.monotonic()>until:raise AssertionError('RTSP listener did not finish stopping')
                                        time.sleep(.1)
                                    adb('shell','input','keyevent',126)
                                    time.sleep(.3)
                                    sockets=adb('shell','cat','/proc/net/tcp')
                                    (args.output/'stopped-tcp.txt').write_text(sockets,encoding='utf-8')
                                    if listening(sockets):
                                        raise AssertionError('Media Play resurrected the stopped RTSP receiver')
                            adb('shell','run-as',args.package,'mkdir','files/system-media-ack-'+stage)
                            seen.append(stage)
                    time.sleep(.2)
                if process.returncode!=0:raise AssertionError('System media instrumentation command failed')
            finally:
                if process.poll() is None:process.kill();process.wait(timeout=10)
        report['system_stages']=seen
        return (args.output/'instrumentation.txt').read_text(encoding='utf-8',errors='replace')
    installed=[]
    try:
        adb('shell','input','keyevent',82)
        for package,path in [(args.package,args.apk),(args.package+'.test',args.test_apk)]:
            result=adb('install','-r',path.resolve())
            if not re.search(r'(?m)^Success\s*$',result):raise AssertionError('APK installation failed: '+result.strip())
            installed.append(package)
        adb('shell','pm','clear',args.package)
        adb('logcat','-c')
        options=['-e','checks','download' if args.download else 'recording' if args.recording else 'native_url' if args.native_url else 'dlna' if args.dlna else 'fcast' if args.fcast else 'url' if args.url_service else 'system' if args.system_media else 'metadata' if args.metadata else 'controls'] if args.controls or args.url_service or args.fcast or args.dlna or args.native_url or args.recording or args.download else []
        if args.approval:options=['-e','checks','approval']
        if args.pin:
            from legacy_pin_client import run_instrument
            result=run_instrument(args,adb)
        else:result=system_instrument(options) if args.system_media else adb('shell','am','instrument','-w',*options,args.package+'.test/io.buzincast.legacy.LegacyGestureChecks',timeout=180)
        (args.output/'instrumentation.txt').write_text(result,encoding='utf-8')
        if 'INSTRUMENTATION_RESULT: status=pass' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
            raise AssertionError('Instrumentation did not pass; inspect instrumentation.txt')
        expected=('download=restricted_disabled' if args.package.endswith('deat.legacy.preview') else 'download=mp4_hls_dash_multiperiod_bframes_cancel_local_library') if args.download else ('recording=restricted_disabled' if args.package.endswith('deat.legacy.preview') else 'recording=native_h264_pcm_mkv_local_play_export_delete') if args.recording else 'native_url=native_http_play_info_rate_seek_stop_and_lifecycle' if args.native_url else 'dlna=shared_soap_playback_photos_and_lifecycle' if args.dlna else 'fcast=shared_transport_dns_txt_tcp_http_controls_and_lifecycle' if args.fcast else 'url_service=native_handoff_http_controls_error_recovery_and_cleanup' if args.url_service else 'controls=native_rtsp_mdns_http_touch_media_keys_and_sender_replacement' if args.controls else 'gestures=touch_and_dpad_across_receiver_restart'
        if args.approval:expected='approval=native_fcast_dlna_allow_deny_timeout_stop_background_and_protocol_grants'
        if args.pin:expected='pin=native_srp_proofs_ui_txt_background_disabled_and_stop'
        if 'INSTRUMENTATION_RESULT: '+expected not in result:raise AssertionError('Test APK did not run the requested scenario')
        if args.approval:capture_native_view('legacy-approval.b64','approval-ui.png')
        if args.pin:capture_native_view('legacy-pin.b64','pin-ui.png')
        if args.download and not args.package.endswith('deat.legacy.preview'):
            artifacts=[]
            for index in range(6):
                payload=base64.b64decode(adb('shell','run-as',args.package,'cat',f'files/legacy-download-{index}.b64',timeout=30))
                if payload[4:8]!=b'ftyp':raise AssertionError('Download artifact was not MP4')
                path=args.output/f'synthetic-download-{index}.mp4';path.write_bytes(payload)
                record={'file':path.name}
                if args.ffmpeg:
                    decoded=subprocess.run([str(args.ffmpeg),'-hide_banner','-loglevel','error','-xerror','-i',str(path),'-map','0:v:0','-map','0:a:0','-f','null','-'],check=True,timeout=30,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
                    if decoded.stderr.strip():raise AssertionError('Independent decode/timestamp errors in '+path.name+': '+decoded.stderr.decode('utf-8','replace')[:1200])
                    record['independent_decode']='pass'
                    if index>=4:
                        original=Path(__file__).resolve().parents[1]/'fireos3-probe/src/main/assets/download_formats/bframes.mp4'
                        def frames(media):
                            result=subprocess.check_output([str(args.ffmpeg),'-hide_banner','-loglevel','error','-i',str(media),'-map','0:v:0','-fps_mode','passthrough','-f','framemd5','-'],timeout=30)
                            return [line.rsplit(b',',1)[-1].strip() for line in result.splitlines() if not line.startswith(b'#')]
                        expected_frames=frames(original);actual_frames=frames(path)
                        if actual_frames!=expected_frames or len(actual_frames)!=120:raise AssertionError('B-frame export changed decoded video frames')
                        record['decoded_frame_hashes']='120 identical to the source'
                if args.ffprobe:
                    probe=json.loads(subprocess.check_output([str(args.ffprobe),'-v','error','-count_packets','-show_streams','-show_format','-of','json',str(path)],timeout=30))
                    video=next((s for s in probe['streams'] if s.get('codec_type')=='video'),{})
                    audio=next((s for s in probe['streams'] if s.get('codec_type')=='audio'),{})
                    if video.get('codec_name')!='h264' or int(video.get('nb_read_packets',0))<100 or audio.get('codec_name')!='aac' or int(audio.get('nb_read_packets',0))<300:
                        raise AssertionError('Independent download codec/packet checks failed')
                    record.update(video_packets=int(video['nb_read_packets']),aac_packets=int(audio['nb_read_packets']))
                    (args.output/f'ffprobe-{index}.json').write_text(json.dumps(probe,indent=2),encoding='utf-8')
                artifacts.append(record)
            report['download_artifacts']=artifacts
        if args.recording and not args.package.endswith('deat.legacy.preview'):
            payload=base64.b64decode(adb('shell','run-as',args.package,'cat','files/legacy-recording.b64',timeout=30))
            if not payload.startswith(bytes.fromhex('1a45dfa3')):raise AssertionError('Recording artifact was not Matroska')
            (args.output/'synthetic-recording.mkv').write_bytes(payload)
            report['recording_artifact']='synthetic-recording.mkv; native H.264/decoded PCM, API17 extractor and local player verified before deletion'
            if args.ffprobe:
                probe=json.loads(subprocess.check_output([str(args.ffprobe),'-v','error','-count_packets','-show_streams','-show_format','-of','json',str(args.output/'synthetic-recording.mkv')],timeout=30))
                video=next((s for s in probe['streams'] if s.get('codec_type')=='video'),{})
                audio=next((s for s in probe['streams'] if s.get('codec_type')=='audio'),{})
                if video.get('codec_name')!='h264' or int(video.get('nb_read_packets',0))<50 or audio.get('codec_name')!='pcm_s16le' or int(audio.get('nb_read_packets',0))<1:
                    raise AssertionError('Independent recording packet/codec checks failed')
                (args.output/'ffprobe.json').write_text(json.dumps(probe,indent=2),encoding='utf-8')
                report['independent_recording_check']={'video_packets':int(video['nb_read_packets']),'pcm_packets':int(audio['nb_read_packets'])}
        if args.native_url:
            if 'INSTRUMENTATION_RESULT: native_fcup=reverse_upgrade_playlists_local_hls_playback_and_controls' not in result:
                raise AssertionError('Test APK did not verify FCUP reverse-channel playback')
            report['native_fcup']='PTTH upgrade, real reverse /event requests, binary /action master/media playlists, local native HLS proxy, remote segments and playback controls'
        if args.url_service:
            if 'INSTRUMENTATION_RESULT: native_output=same_receiver_rtsp_surface_gate_and_native_audio_takeover' not in result:
                raise AssertionError('Test APK did not verify persistent native transport and output arbitration')
            report['native_output']='Same native receiver and RTSP listener during URL, retired native decoder/Surface before URL output, native ALAC takeover after URL close'
            if 'INSTRUMENTATION_RESULT: subtitles=content_import_srt_vtt_pause_caption_disable_and_bounds' not in result:
                raise AssertionError('Test APK did not run external subtitle checks')
            report['external_subtitles']='ContentResolver SRT/BOM VTT import, actual text cues and native caption UI, paused position, disable, invalid UTF8/format/size/remote rejection, picker lifetime without background playback'
            if 'INSTRUMENTATION_RESULT: post_url_audio=native_decode_focus_after_url_shutdown_in_same_process' not in result:
                raise AssertionError('Test APK did not verify native audio after URL cleanup')
            report['post_url_audio']='native ALAC decode, muted AudioTrack and focus recovery after URL playback in the same service/process'
        if args.metadata:
            if 'INSTRUMENTATION_RESULT: metadata=synthetic_native_worker_packets_artwork_progress_pause_ui_and_sender_cleanup' not in result:
                raise AssertionError('Test APK did not run native metadata checks')
            capture_native_view()
            report['metadata']='synthetic packets through native worker callbacks, artwork pixels/size, unsigned progress, pause, invalid packets and source cleanup'
        if args.system_media:
            if 'INSTRUMENTATION_RESULT: plain_audio=native_decode_focus_without_metadata_idle_permanent_loss_and_explicit_restart' not in result:
                raise AssertionError('Test APK did not run plain audio focus checks')
            report['plain_audio']='synthetic ALAC native decode and muted AudioTrack without metadata/identity; focus gain/duck/loss, idle focus release, permanent mute across idle/resume and explicit receiver restart'
            if 'INSTRUMENTATION_RESULT: system_media=platform_metadata_artwork_background_keys_and_cleanup' not in result:
                raise AssertionError('Test APK did not run system media checks')
            if 'INSTRUMENTATION_RESULT: mirror_modes=three_headless_native_packet_rtsp_teardown_metadata_and_focus_cycles' not in result:
                raise AssertionError('Test APK did not run mirror transition checks')
            report['mirror_modes']='three headless encoded-packet/native RTSP teardown cycles; metadata isolation, duck/transient/permanent audio focus without music commands, explicit Play focus recovery, fresh audio metadata, same native receiver; not decoded video or a real sender'
            report['system_media']='platform client metadata/artwork ownership, audio focus duck/loss/gain, real background media keys, launcher return, unregister and no RTSP resurrection after stop'
        report['status']='pass';report['download_lifecycle_or_restricted_gate' if args.download else 'recording_lifecycle_or_restricted_gate' if args.recording else 'native_http_url_callbacks_playback_lifecycle' if args.native_url else 'dlna_soap_photos_lifecycle' if args.dlna else 'fcast_transport_discovery_playback_lifecycle' if args.fcast else 'url_service_handoff_http_controls_recovery' if args.url_service else 'sender_control_native_dns_http_touch_keys' if args.controls else 'touch_and_dpad_across_receiver_restart']='pass'
        if args.approval:
            report.pop('touch_and_dpad_across_receiver_restart',None)
            report['connection_approval_transports_ui_lifecycle']='pass'
        if args.pin:
            report.pop('touch_and_dpad_across_receiver_restart',None)
            report['pin_srp_crypto_ui_lifecycle']='pass'
    except Exception as error:
        report['status']='failed';report['error']=str(error)
        try:
            (args.output/'failure-logcat.txt').write_text(adb('logcat','-d',timeout=30),encoding='utf-8')
            report['failure_logcat']='failure-logcat.txt'
        except Exception as capture_error:report['failure_logcat_error']=type(capture_error).__name__
        raise
    finally:
        try:
            if args.download and not args.package.endswith('deat.legacy.preview') and args.package in installed and report.get('status')!='pass':
                try:
                    available=adb('shell','run-as',args.package,'ls','files',timeout=30).split()
                    artifacts=[]
                    for index in range(6):
                        if f'legacy-download-{index}.b64' not in available:continue
                        payload=base64.b64decode(adb('shell','run-as',args.package,'cat',f'files/legacy-download-{index}.b64',timeout=30))
                        if payload[4:8]==b'ftyp':
                            path=args.output/f'synthetic-download-{index}.mp4';path.write_bytes(payload);artifacts.append(path.name)
                    report['diagnostic_downloads']=artifacts
                except Exception as diagnostic:report['diagnostic_download_error']=type(diagnostic).__name__
            if args.recording and not args.package.endswith('deat.legacy.preview') and args.package in installed and not (args.output/'synthetic-recording.mkv').exists():
                try:
                    payload=base64.b64decode(adb('shell','run-as',args.package,'cat','files/legacy-recording.b64',timeout=30))
                    if payload.startswith(bytes.fromhex('1a45dfa3')):
                        (args.output/'synthetic-recording.mkv').write_bytes(payload)
                        report['diagnostic_recording']='synthetic-recording.mkv'
                except Exception as diagnostic:report['diagnostic_recording_error']=type(diagnostic).__name__
            if args.metadata and args.package in installed and 'ui_render' not in report:
                try:capture_native_view()
                except Exception as error:report['ui_render_error']=type(error).__name__
            if args.package in installed:adb('shell','am','force-stop',args.package)
            for package in reversed(installed):adb('uninstall',package)
            if args.system_media:adb('shell','rm','-f','/data/local/tmp/buzin-legacy-return.xml')
            report['cleanup']='uninstalled'
        except Exception as error:
            report['status']='failed';report['cleanup_error']=str(error);raise
        finally:(args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
