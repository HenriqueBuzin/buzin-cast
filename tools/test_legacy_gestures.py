"""Exercise held touch/DPAD gestures or native sender controls on an isolated API17 emulator."""
import argparse
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
    parser.add_argument('--package',default='com.buzincast.tv.legacy.preview',choices=[
        'com.buzincast.tv.legacy.preview','com.buzincast.tv.deat.legacy.preview'])
    parser.add_argument('--output',type=Path,default=Path('outputs/legacy-gesture-checks'))
    args=parser.parse_args()
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
    installed=[]
    try:
        adb('shell','input','keyevent',82)
        for package,path in [(args.package,args.apk),(args.package+'.test',args.test_apk)]:
            adb('install','-r',path.resolve());installed.append(package)
        adb('shell','pm','clear',args.package)
        options=['-e','checks','controls'] if args.controls else []
        result=adb('shell','am','instrument','-w',*options,args.package+'.test/io.buzincast.legacy.LegacyGestureChecks',timeout=180)
        (args.output/'instrumentation.txt').write_text(result,encoding='utf-8')
        if 'INSTRUMENTATION_RESULT: status=pass' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
            raise AssertionError('Instrumentation did not pass; inspect instrumentation.txt')
        report['status']='pass';report['sender_control_native_dns_http_touch_keys' if args.controls else 'touch_and_dpad_across_receiver_restart']='pass'
    except Exception as error:
        report['status']='failed';report['error']=str(error);raise
    finally:
        try:
            if args.package in installed:adb('shell','am','force-stop',args.package)
            for package in reversed(installed):adb('uninstall',package)
            report['cleanup']='uninstalled'
        except Exception as error:
            report['status']='failed';report['cleanup_error']=str(error);raise
        finally:(args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
