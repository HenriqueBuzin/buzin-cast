"""Exercise real interface loss and address replacement on an isolated API17 emulator."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import plistlib
import re
import socket
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--adb-server-port',type=int,default=5037)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--probe-apk',type=Path,required=True)
    parser.add_argument('--package',default='com.buzincast.tv.legacy.preview',choices=[
        'com.buzincast.tv.legacy.preview','com.buzincast.tv.deat.legacy.preview'])
    parser.add_argument('--output',type=Path,default=Path('outputs/legacy-network-checks'))
    args=parser.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial):parser.error('Only an isolated emulator is accepted.')
    if not 1<=args.adb_server_port<=65535:parser.error('Invalid ADB server port.')

    def adb(*command,timeout=60):
        return subprocess.check_output([args.adb,'-P',str(args.adb_server_port),'-s',args.serial,*map(str,command)],timeout=timeout).decode('utf-8','replace')

    if adb('shell','getprop','ro.build.version.sdk').strip()!='17' or adb('shell','getprop','ro.kernel.qemu').strip()!='1':
        parser.error('Use the isolated Android API17 emulator.')
    if 'uid=0(root)' not in adb('shell','su','0','id'):parser.error('Emulator root is required.')
    interfaces=adb('shell','netcfg')
    match=re.search(r'^eth0\s+UP\s+(10\.0\.2\.15)/24\s',interfaces,re.M)
    if not match:parser.error('Expected the default emulator eth0 interface, UP with its original address.')
    original=match.group(1);alternate=str(ipaddress.ip_address(original)+16)
    args.output.mkdir(parents=True,exist_ok=True)
    report={'status':'running','scope':'API17 emulator real interface transitions, RTSP and multicast DNS; not a physical sender test',
        'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'probe_sha256':hashlib.sha256(args.probe_apk.read_bytes()).hexdigest(),'checks':[]}
    installed=[];forward=None
    resources=Path(__file__).resolve().parents[1]/'app/src/main/res/values'
    labels={node.get('name'):''.join(node.itertext()).replace("\\'", "'")
        for file in resources.glob('*.xml') for node in ET.parse(file).getroot().findall('string')}

    def ui():
        for _ in range(3):
            adb('shell','rm','-f','/data/local/tmp/buzin-network-ui.xml')
            adb('shell','uiautomator','dump','/data/local/tmp/buzin-network-ui.xml')
            try:return ET.fromstring(adb('shell','cat','/data/local/tmp/buzin-network-ui.xml'))
            except ET.ParseError:time.sleep(.5)
        raise AssertionError('Fresh UI hierarchy unavailable')

    def expect(text,timeout=40):
        deadline=time.monotonic()+timeout;actual=[]
        while time.monotonic()<deadline:
            tree=ui();actual=[node.get('text','') for node in tree.iter()]
            if text in actual:return tree
            time.sleep(.3)
        raise AssertionError('Missing '+repr(text)+'; actual='+repr(actual))

    def tap(text):
        for _ in range(7):
            tree=ui();parents={child:parent for parent in tree.iter() for child in parent}
            for node in tree.iter():
                value=node.get('text','')
                if value!=text and not value.startswith(text+' · '):continue
                while node.get('clickable')!='true' and node in parents:node=parents[node]
                if node.get('clickable')=='true':
                    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
                    if x2-x1>2 and y2-y1>2:
                        adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2);return
            scrolls=[n for n in tree.iter() if n.get('class')=='android.widget.ScrollView' and n.get('scrollable')=='true']
            if len(scrolls)!=1:break
            x1,y1,x2,y2=map(int,re.findall(r'\d+',scrolls[0].get('bounds')))
            adb('shell','input','swipe',(x1+x2)//2,y2-30,(x1+x2)//2,y1+60,350)
        raise AssertionError('No clickable control '+text)

    def launch():
        adb('shell','am','start','-n',args.package+'/io.buzincast.legacy.LegacyActivity','--es','buzin_test_locale','en')

    def request(connection,path='*'):
        connection.sendall(('OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n' if path=='*' else
            'GET /info RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n').encode())
        data=b''
        while b'\r\n\r\n' not in data:
            chunk=connection.recv(4096)
            if not chunk:raise ConnectionResetError('Receiver closed connection')
            data+=chunk
        header,body=data.split(b'\r\n\r\n',1)
        if not header.startswith(b'RTSP/1.0 200 OK'):raise AssertionError('RTSP request failed')
        length=re.search(br'content-length:\s*(\d+)',header,re.I)
        size=int(length.group(1)) if length else 0
        while len(body)<size:
            chunk=connection.recv(size-len(body))
            if not chunk:raise ConnectionResetError('Truncated RTSP body')
            body+=chunk
        return plistlib.loads(body) if path!='*' else None

    def info():
        with socket.create_connection(('127.0.0.1',forward),timeout=3) as connection:return request(connection,'/info')

    def wait_listener(present):
        deadline=time.monotonic()+35
        while time.monotonic()<deadline:
            try:info();actual=True
            except (OSError,ValueError,plistlib.InvalidFileException):actual=False
            if actual==present:return
            time.sleep(.3)
        raise AssertionError('RTSP availability did not become '+str(present))

    def address(value):
        result=adb('shell','su','0','ifconfig','eth0',value,'netmask','255.255.255.0','up')
        if result.strip():raise AssertionError('Interface address command failed: '+result)

    def down():
        result=adb('shell','su','0','netcfg','eth0','down')
        if result.strip():raise AssertionError('Interface shutdown failed: '+result)

    def dns(value):
        adb('shell','am','force-stop','io.buzincast.fireos3probe')
        adb('shell','am','start','-n','io.buzincast.fireos3probe/.ProbeActivity',
            '--es','external_dns_address',value,'--ei','external_dns_port','7000')
        expect('External DNS check passed',timeout=25)
        launch()

    def check(name):
        report['checks'].append(name);print('PASS '+name,flush=True)

    try:
        adb('shell','input','keyevent',82)
        for package,path in [(args.package,args.apk),('io.buzincast.fireos3probe',args.probe_apk)]:
            adb('install','-r',path.resolve());installed.append(package)
        adb('shell','pm','clear',args.package)
        forward=int(adb('forward','tcp:0','tcp:7000').strip())
        launch();wait_listener(True);baseline=info()
        with socket.create_connection(('127.0.0.1',forward),timeout=3) as continuous:
            request(continuous);time.sleep(6);request(continuous)
            check('unchanged_network_preserves_actual_tcp_session')
            address(alternate)
            deadline=time.monotonic()+35
            while True:
                try:request(continuous)
                except OSError:break
                if time.monotonic()>deadline:raise AssertionError('Old native session survived an IP replacement')
                time.sleep(.5)
        wait_listener(True)
        if info()!=baseline:raise AssertionError('Receiver identity changed after address replacement')
        dns(alternate);check('address_replacement_rebinds_rtsp_and_dns_without_identity_change')
        down();wait_listener(False);expect(labels['network_waiting'])
        if 'buzincast:legacy-receiver' in adb('shell','dumpsys','power'):
            raise AssertionError('Native CPU wake lock survived network loss')
        check('offline_state_releases_native_listener_and_cpu_wakelock')
        address(original);wait_listener(True);dns(original)
        if info()!=baseline:raise AssertionError('Receiver identity changed after network restoration')
        check('network_restoration_recovers_actual_discovery_and_listener')
        down();wait_listener(False);expect(labels['network_waiting']);tap(labels['btn_stop'])
        address(original);time.sleep(5);wait_listener(False)
        check('stop_while_offline_cancels_automatic_recovery')
        down();tap(labels['btn_start']);expect(labels['network_waiting']);wait_listener(False)
        expect(labels['btn_stop']);address(original);wait_listener(True)
        check('start_without_network_waits_and_recovers')
        tap(labels['tab_settings']);tap(labels['setting_network_recovery']);tap(labels['tab_overview'])
        down();wait_listener(False);address(original);time.sleep(5);wait_listener(False)
        expect(labels['btn_start']);tap(labels['btn_start']);wait_listener(True)
        check('recovery_opt_out_requires_explicit_restart')
        report['status']='pass'
    except Exception as error:
        report['status']='failed';report['error']=str(error)
        try:(args.output/'failure-ui.xml').write_text(ET.tostring(ui(),encoding='unicode'),encoding='utf-8')
        except Exception:pass
        raise
    finally:
        try:
            address(original)
            if not re.search(r'^eth0\s+UP\s+'+re.escape(original)+r'/24\s',adb('shell','netcfg'),re.M):
                raise AssertionError('Original emulator interface was not restored')
            report['network_cleanup']='original_interface_restored'
            if forward is not None:adb('forward','--remove','tcp:'+str(forward))
            for package in reversed(installed):adb('shell','am','force-stop',package);adb('uninstall',package)
            report['app_cleanup']='uninstalled'
        except Exception as error:report['status']='failed';report['cleanup_error']=str(error);raise
        finally:(args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
