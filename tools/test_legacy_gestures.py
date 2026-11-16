"""Exercise held touch/DPAD gestures or native sender controls on an isolated API17 emulator."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--adb-server-port',type=int,default=5037)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--test-apk',type=Path,required=True)
    parser.add_argument('--controls',action='store_true',help='Exercise native sender identity, real mDNS/HTTP and UI/remote controls instead of held restart gestures.')
    parser.add_argument('--metadata',action='store_true',help='Also exercise synthetic native metadata/art/progress packets and save a render of the actual native Views; implies --controls.')
    parser.add_argument('--package',default='com.buzincast.tv.legacy.preview',choices=[
        'com.buzincast.tv.legacy.preview','com.buzincast.tv.deat.legacy.preview'])
    parser.add_argument('--output',type=Path,default=Path('outputs/legacy-gesture-checks'))
    args=parser.parse_args()
    if args.metadata:args.controls=True
    if not re.fullmatch(r'emulator-\d+',args.serial):parser.error('Only an isolated emulator is accepted.')
    if not 1<=args.adb_server_port<=65535:parser.error('Invalid ADB server port.')
    def adb(*command,timeout=60):
        return subprocess.check_output([args.adb,'-P',str(args.adb_server_port),'-s',args.serial,*map(str,command)],timeout=timeout).decode('utf-8','replace')
    if adb('shell','getprop','ro.build.version.sdk').strip()!='17' or adb('shell','getprop','ro.kernel.qemu').strip()!='1':
        parser.error('Use the isolated Android API17 emulator.')
    args.output.mkdir(parents=True,exist_ok=True)
    report={'status':'running','scope':('Native RTSP identity, real multicast DNS/HTTP, touch/media keys and replacement sender' if args.controls else 'Real touch/DPAD gestures during a native receiver stop/start')+'; not a physical sender interoperability test',
        'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'test_apk_sha256':hashlib.sha256(args.test_apk.read_bytes()).hexdigest()}
    def capture_native_view():
        payload=base64.b64decode(adb('shell','run-as',args.package,'cat','files/legacy-metadata.b64',timeout=30))
        if not payload.startswith(b'\x89PNG\r\n\x1a\n'):raise AssertionError('Native View render was not a valid PNG')
        (args.output/'metadata-ui.png').write_bytes(payload)
        from PIL import Image
        with Image.open(args.output/'metadata-ui.png') as frame:frame.verify()
        report['ui_render']='metadata-ui.png; actual native View hierarchy drawn on the emulator, without Surface/video capture'
    installed=[]
    try:
        adb('shell','input','keyevent',82)
        for package,path in [(args.package,args.apk),(args.package+'.test',args.test_apk)]:
            adb('install','-r',path.resolve());installed.append(package)
        adb('shell','pm','clear',args.package)
        options=['-e','checks','metadata' if args.metadata else 'controls'] if args.controls else []
        result=adb('shell','am','instrument','-w',*options,args.package+'.test/io.buzincast.legacy.LegacyGestureChecks',timeout=180)
        (args.output/'instrumentation.txt').write_text(result,encoding='utf-8')
        if 'INSTRUMENTATION_RESULT: status=pass' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
            raise AssertionError('Instrumentation did not pass; inspect instrumentation.txt')
        expected='controls=native_rtsp_mdns_http_touch_media_keys_and_sender_replacement' if args.controls else 'gestures=touch_and_dpad_across_receiver_restart'
        if 'INSTRUMENTATION_RESULT: '+expected not in result:raise AssertionError('Test APK did not run the requested scenario')
        if args.metadata:
            if 'INSTRUMENTATION_RESULT: metadata=synthetic_native_worker_packets_artwork_progress_pause_ui_and_sender_cleanup' not in result:
                raise AssertionError('Test APK did not run native metadata checks')
            capture_native_view()
            report['metadata']='synthetic packets through native worker callbacks, artwork pixels/size, unsigned progress, pause, invalid packets and source cleanup'
        report['status']='pass';report['sender_control_native_dns_http_touch_keys' if args.controls else 'touch_and_dpad_across_receiver_restart']='pass'
    except Exception as error:
        report['status']='failed';report['error']=str(error);raise
    finally:
        try:
            if args.metadata and args.package in installed and 'ui_render' not in report:
                try:capture_native_view()
                except Exception as error:report['ui_render_error']=type(error).__name__
            if args.package in installed:adb('shell','am','force-stop',args.package)
            for package in reversed(installed):adb('uninstall',package)
            report['cleanup']='uninstalled'
        except Exception as error:
            report['status']='failed';report['cleanup_error']=str(error);raise
        finally:(args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
