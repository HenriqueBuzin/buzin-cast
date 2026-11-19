"""Black-box smoke of a signed Fire OS candidate on an isolated Android emulator.

This does not replace an Appstore/LAT license test on a physical Fire Stick.
The SDK is real; no license response is forged or treated as an authorization.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from PIL import Image, ImageStat
from check_fireos_release import extract_signer_fingerprint


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',required=True,type=Path)
    parser.add_argument('--public-key',required=True,type=Path)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--adb-server-port',required=True,type=int)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--aapt',required=True)
    parser.add_argument('--apksigner-jar',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--target', choices=('legacy', 'compat', 'modern'), default='legacy')
    parser.add_argument('--device-sdk', type=int, help='Require this emulator API explicitly (e.g. 22 for the legacy APK on updated firmware)')
    args=parser.parse_args()
    local=re.fullmatch(r'127\.0\.0\.1:(\d+)',args.serial)
    if not local or not 5555<=int(local[1])<=5683 or int(local[1])%2!=1:
        parser.error('Use an isolated emulator TCP serial, never a physical Fire Stick.')
    if not 1<=args.adb_server_port<=65535:parser.error('Invalid ADB server port.')
    def adb(*command,timeout=60):
        return subprocess.check_output([args.adb,'-P',str(args.adb_server_port),'-s',args.serial,*map(str,command)],timeout=timeout).decode('utf-8','replace')
    min_sdk = {'legacy': 17, 'compat': 22, 'modern': 25}[args.target]
    expected_sdk = args.device_sdk if args.device_sdk is not None else (None if args.target == 'modern' else min_sdk)
    device_sdk = adb('shell','getprop','ro.build.version.sdk').strip()
    if (adb('shell','getprop','ro.kernel.qemu').strip()!='1' or not device_sdk.isdecimal()
            or int(device_sdk) < min_sdk or (expected_sdk is not None and int(device_sdk) != expected_sdk)):
        parser.error('Use an emulator matching the selected release target API.')
    if (adb('shell','getprop','persist.sys.language').strip() not in ('','en')
            or adb('shell','getprop','persist.sys.locale').strip().split('-')[0] not in ('','en')):
        parser.error('Use an English emulator for the black-box text assertions.')
    badging=subprocess.check_output([args.aapt,'dump','badging',str(args.apk)],timeout=30).decode()
    package=re.search(r"package: name='([^']+)'",badging).group(1)
    if package not in ('com.buzincast.tv','com.buzincast.tv.deat') or f"sdkVersion:'{min_sdk}'" not in badging or 'application-debuggable' in badging:
        parser.error('Expected a non-debuggable store candidate for the selected target.')
    if args.target == 'legacy':
        # API17's am start does not support selecting an intent package with -p.
        launcher = re.search(r"launchable-activity: name='([^']+)'", badging)
        if launcher is None:parser.error('Missing legacy launcher activity.')
        launch_args = ('-n', package + '/' + launcher[1])
    else:
        # TV launchers query the MAIN/LEANBACK filter, then start the selected
        # component explicitly. An implicit am start matches DEFAULT filters.
        launch_args = ('-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LEANBACK_LAUNCHER',
                       '-n', package + '/io.github.jqssun.airplay.TvLauncher')
    signature=subprocess.check_output(['java','-jar',str(args.apksigner_jar),'verify','--verbose','--print-certs','--min-sdk-version',str(min_sdk),'--max-sdk-version',device_sdk,str(args.apk)],timeout=60).decode()
    if 'CN=Android Debug' in signature or (min_sdk < 24 and 'Verified using v1 scheme (JAR signing): true' not in signature):
        raise AssertionError('Candidate must use the release identity and a compatible signature scheme.')
    with ZipFile(args.apk) as apk:
        if apk.testzip() is not None:raise AssertionError('Corrupt APK')
        if apk.read('assets/AppstoreAuthenticationKey.pem')!=args.public_key.read_bytes():
            raise AssertionError('The APK contains a different app public key.')
        required_abis = ('armeabi-v7a', 'x86') if args.target == 'legacy' else ('armeabi-v7a', 'arm64-v8a', 'x86_64')
        for abi in required_abis:
            if not any(name.startswith('lib/'+abi+'/') for name in apk.namelist()):raise AssertionError('Missing native ABI '+abi)
    args.output.mkdir(parents=True,exist_ok=True)
    certificate=extract_signer_fingerprint(signature)
    report={'status':'running','scope':'Release signature/key, R8 startup/native RTSP/UI on an Android emulator; Appstore licensing and physical ARM validation pending',
            'target':args.target,'device_sdk':int(device_sdk),
            'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'package':package,'signature':'pass','matching_public_key':'pass',
            'certificate_sha256':certificate}
    if min_sdk < 24:report['v1_signature']='pass'
    installed=False;forward=None
    def tree():
        adb('shell','uiautomator','dump','/data/local/tmp/legacy-store-ui.xml',timeout=30)
        xml=adb('shell','cat','/data/local/tmp/legacy-store-ui.xml')
        (args.output/'ui.xml').write_text(xml,encoding='utf-8')
        return ET.fromstring(xml)
    def click(text):
        for node in tree().iter('node'):
            if node.get('text')==text:
                bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
                if len(bounds)==4:
                    adb('shell','input','tap',(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2);return
        raise AssertionError('Missing visible release control: '+text)
    def screenshot(name):
        adb('shell','screencap','-p','/data/local/tmp/legacy-store.png')
        adb('pull','/data/local/tmp/legacy-store.png',args.output/name)
        if not (args.output/name).read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):raise AssertionError('Invalid release screenshot')
        def blank():
            with Image.open(args.output/name) as picture:
                return max(ImageStat.Stat(picture.convert('RGB')).stddev)<4
        backend='adb'
        if blank():
            # Some API17 guest framebuffer readers return black even while the
            # emulator compositor renders correctly. Read that same display via
            # the documented emulator console, without re-rendering app Views.
            directory=(args.output/'framebuffer'/Path(name).stem).resolve();directory.mkdir(parents=True,exist_ok=True)
            before=set(directory.glob('*.png'))
            with socket.create_connection(('127.0.0.1',int(local[1])-1),timeout=10) as console:
                def response():
                    data=b''
                    while b'OK\r' not in data and b'KO:' not in data:
                        part=console.recv(8192)
                        if not part or len(data)>65536:raise AssertionError('Invalid emulator console response')
                        data+=part
                    return data
                if b'Authentication required' in response():
                    token=(Path.home()/'.emulator_console_auth_token').read_bytes().strip()
                    if b'\n' in token or b'\r' in token:raise AssertionError('Invalid local emulator console token')
                    console.sendall(b'auth '+token+b'\n')
                    if b'KO:' in response():raise AssertionError('Emulator console authentication failed')
                console.sendall(('screenrecord screenshot '+directory.as_posix()+'\n').encode())
                if b'KO:' in response():raise AssertionError('Emulator framebuffer capture failed')
            frames=set(directory.glob('*.png'))-before
            if len(frames)!=1:raise AssertionError('Missing or ambiguous emulator framebuffer capture')
            shutil.copyfile(frames.pop(),args.output/name);backend='emulator console framebuffer'
        with Image.open(args.output/name) as picture:
            if picture.size!=(1280,720):raise AssertionError('Use a 1280x720 emulator for the release UI check')
        if blank():raise AssertionError('Blank release screenshot; check emulator graphics and unlock state')
        report.setdefault('screenshots',{})[name]=backend
    try:
        if re.search(r'^package:'+re.escape(package)+r'\s*$',adb('shell','pm','list','packages',package),re.M):
            raise AssertionError('Store package already installed in this emulator; do not overwrite it implicitly.')
        install=adb('install',args.apk,timeout=120)
        if 'Success' not in install:raise AssertionError('Release install failed: '+install)
        installed=True;adb('shell','input','keyevent','82');adb('logcat','-c')
        launch=adb('shell','am','start','-W',*launch_args)
        if 'Error:' in launch or 'Status: ok' not in launch:raise AssertionError('Release launch failed: '+launch)
        forward=adb('forward','tcp:0','tcp:7000').strip();deadline=time.monotonic()+30
        while True:
            try:
                with socket.create_connection(('127.0.0.1',int(forward)),timeout=2) as connection:
                    connection.sendall(b'OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n')
                    response=connection.recv(8192)
                if not response.startswith(b'RTSP/1.0 200'):raise AssertionError('Native release RTSP did not answer OPTIONS')
                break
            except OSError:
                if time.monotonic()>deadline:raise AssertionError('Native receiver did not start in the minified release')
                time.sleep(.2)
        report['native_rtsp']='pass';time.sleep(2)
        screenshot('home.png')
        for label,filename in (('Tools','tools.png'),('Settings','settings.png'),('Overview','overview.png')):
            click(label);time.sleep(.4);state=tree()
            # Compose exposes selection on the tab and its label on a child.
            if not any(node.get('selected') == 'true' and any(child.get('text') == label for child in node.iter('node'))
                       for node in state.iter('node')):
                raise AssertionError('Active menu selection missing in release: '+label)
            screenshot(filename)
        click('About BuzinCast');time.sleep(.4)
        state=tree()
        if not any(node.get('text')=='Close' for node in state.iter('node')):raise AssertionError('Release About dialog did not open')
        if not any('Buzin Solutions LTDA' in node.get('text','') for node in state.iter('node')):raise AssertionError('Release About footer missing')
        screenshot('about.png');click('Close')
        report['release_ui']='home, tools, settings, persistent menu and About pass'
        logs=adb('logcat','-d');(args.output/'logcat.txt').write_text(logs,encoding='utf-8')
        if any(marker in logs for marker in ('FATAL EXCEPTION','UnsatisfiedLinkError','VerifyError','NoSuchMethodError','NoClassDefFoundError')):
            raise AssertionError('Release runtime/linkage failure; inspect logcat.txt')
        if 'AppstoreBilling' not in logs:raise AssertionError('No evidence of the actual Appstore initializer')
        report['appstore_sdk']='initializer observed; no Appstore client/license grant in this emulator'
        report['status']='pass'
    except BaseException as error:
        report.update(status='failed',error=str(error));raise
    finally:
        cleanup_errors=[]
        if installed:
            try:(args.output/'logcat.txt').write_text(adb('logcat','-d'),encoding='utf-8')
            except (OSError,subprocess.SubprocessError) as error:cleanup_errors.append('logcat: '+str(error))
        if forward:
            try:adb('forward','--remove','tcp:'+forward)
            except (OSError,subprocess.SubprocessError) as error:cleanup_errors.append('forward: '+str(error))
        if installed:
            try:
                cleanup=adb('uninstall',package)
                if 'Success' not in cleanup:raise AssertionError('Release uninstall failed: '+cleanup)
                report['cleanup']='uninstalled'
            except (OSError,subprocess.SubprocessError,AssertionError) as error:cleanup_errors.append('uninstall: '+str(error))
        try:adb('shell','rm','-f','/data/local/tmp/legacy-store.png','/data/local/tmp/legacy-store-ui.xml')
        except (OSError,subprocess.SubprocessError) as error:cleanup_errors.append('temporary files: '+str(error))
        if cleanup_errors:report.update(status='failed',cleanup_errors=cleanup_errors)
        (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report,indent=2))
        if cleanup_errors:raise AssertionError('Release test cleanup incomplete; inspect report.json')


if __name__=='__main__':main()
