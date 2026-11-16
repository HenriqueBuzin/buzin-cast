"""Exercise the native API17 frontend and capture all locales, without touching a physical TV."""
import argparse
import datetime
import json
from pathlib import Path
import plistlib
import re
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from PIL import Image


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',default='adb')
    parser.add_argument('--adb-server-port',type=int,default=5037,help='ADB server port; isolate concurrent emulator runs when needed.')
    parser.add_argument('--serial',required=True)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--package',default='com.buzincast.tv.legacy.preview',choices=[
        'com.buzincast.tv.legacy.preview','com.buzincast.tv.deat.legacy.preview'])
    parser.add_argument('--locales',nargs='+',help='Omit to capture every app locale.')
    parser.add_argument('--output',type=Path,default=Path('outputs/legacy-ui-checks'))
    args=parser.parse_args()
    if not 1 <= args.adb_server_port <= 65535: parser.error('Invalid ADB server port.')
    if not args.serial.startswith('emulator-'): parser.error('Only an isolated emulator is accepted.')
    root=Path(__file__).resolve().parents[1]
    resources=root/'app/src/main/res'
    locales={'en':'values'}
    for folder in resources.glob('values-*'):
        suffix=folder.name[7:]
        if re.fullmatch(r'[a-z]{2}(-r[A-Z]{2})?',suffix): locales[suffix.replace('-r','-')]=folder.name
    wanted=args.locales or sorted(locales)
    if any(locale not in locales for locale in wanted): parser.error('Unsupported app locale.')
    args.output.mkdir(parents=True,exist_ok=True)
    report={'locales':{},'scope':'Native API17 UI and receiver control, not a real sender session','status':'running'}
    forwards=[]

    def adb(*command):
        return subprocess.check_output([args.adb,'-P',str(args.adb_server_port),'-s',args.serial,*map(str,command)],timeout=60).decode('utf-8','replace')

    def labels(locale):
        result={}
        for directory in ('values',locales[locale]):
            for file in (resources/directory).glob('*.xml'):
                for node in ET.parse(file).getroot().findall('string'):
                    result[node.get('name')]=''.join(node.itertext()).replace('\\n','\n').replace("\\'", "'")
        return result

    def ui():
        # Old UiAutomator sometimes cannot acquire the hierarchy during startup.
        # Remove the previous dump so retries cannot accidentally accept stale UI.
        last=''
        for attempt in range(3):
            adb('shell','rm','-f','/data/local/tmp/buzin-legacy-ui.xml')
            last=adb('shell','uiautomator','dump','/data/local/tmp/buzin-legacy-ui.xml')
            document=adb('shell','cat','/data/local/tmp/buzin-legacy-ui.xml')
            try:return ET.fromstring(document)
            except ET.ParseError:
                last+='; '+document[:200]
                time.sleep(.5)
        raise AssertionError('Could not obtain a fresh UI hierarchy: '+last)

    def expect(*texts,timeout=30,contains=False):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            tree=ui();actual={n.get('text') for n in tree.iter()}
            if all(any(value and text in value for value in actual) if contains else text in actual for text in texts):return tree
            time.sleep(.2)
        raise AssertionError('Missing UI: '+repr(texts)+'; actual='+repr(actual))

    def tap(label,prefix=False):
        tree=ui(); parents={child:parent for parent in tree.iter() for child in parent}
        candidates=[]
        for node in tree.iter():
            value=node.get('text','')
            if (value.startswith(label) if prefix else value==label):
                while node.get('clickable')!='true' and node in parents:node=parents[node]
                if node.get('clickable')=='true' and node not in candidates:candidates.append(node)
        if len(candidates)!=1:raise AssertionError('Expected one clickable control: '+label)
        x1,y1,x2,y2=map(int,re.findall(r'\d+',candidates[0].get('bounds')))
        adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)

    def capture(path):
        previous=None
        for attempt in range(8):
            with tempfile.TemporaryDirectory(dir=args.output.resolve()) as folder:
                adb('emu','screenrecord','screenshot',folder)
                images=list(Path(folder).glob('*.png'))
                if len(images)!=1:raise AssertionError('Emulator screenshot missing')
                path.write_bytes(images[0].read_bytes())
            with Image.open(path) as frame:pixels=frame.convert('RGB').tobytes()
            if pixels==previous:return
            previous=pixels;time.sleep(.25)
        raise AssertionError('UI pixels did not settle before capture: '+path.name)

    def selected_border(path,tree,overview):
        controls=[n for n in tree.iter() if n.get('text')==overview and n.get('selected')=='true']
        if len(controls)!=1:raise AssertionError('Active overview destination lost its selected state')
        bounds=list(map(int,re.findall(r'\d+',controls[0].get('bounds'))))
        with Image.open(path) as image:
            crop=image.convert('RGB').crop(tuple(bounds)); count=0
            for y in range(crop.height):
                for x in range(crop.width):
                    if x>4 and y>4 and x<crop.width-5 and y<crop.height-5:continue
                    r,g,b=crop.getpixel((x,y))
                    count+=g-r>20 and g-b>10 and g>40
            if count<40:raise AssertionError('The active sidebar border is not visible')

    def check_modal_corners(path,tree):
        panels=[node for node in tree.iter() if node.get('resource-id','').endswith(':id/legacy_modal_panel')]
        # API17's accessibility dump predates resource-id attributes. Locate the
        # same panel through its unique copyright footer and clickable ancestor.
        if not panels:
            parents={child:parent for parent in tree.iter() for child in parent}
            footers=[node for node in tree.iter() if node.get('text','').startswith('© ')]
            if len(footers)==1:
                panel=footers[0]
                while panel in parents and not (panel.get('class')=='android.widget.LinearLayout' and panel.get('clickable')=='true'):
                    panel=parents[panel]
                if panel.get('class')=='android.widget.LinearLayout' and panel.get('clickable')=='true':panels=[panel]
        if len(panels)!=1:raise AssertionError('Could not identify modal panel')
        window=panels[0]
        bounds=list(map(int,re.findall(r'\d+',window.get('bounds'))))
        with Image.open(path) as image:
            if bounds[2]-bounds[0]>=image.width:raise AssertionError('Could not identify modal bounds')
            for x,y in ((bounds[0]+1,bounds[1]+1),(bounds[2]-2,bounds[1]+1),
                        (bounds[0]+1,bounds[3]-2),(bounds[2]-2,bounds[3]-2)):
                if max(image.convert('RGB').getpixel((x,y)))<3:
                    raise AssertionError('Opaque pixels remain outside a rounded dialog corner')

    def rtsp(port,should_run=True):
        local=int(adb('forward','tcp:0','tcp:'+str(port)).strip());forwards.append(local)
        try:
            with socket.create_connection(('127.0.0.1',local),timeout=3) as connection:
                connection.sendall(b'GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n')
                data=b''
                while b'\r\n\r\n' not in data:
                    chunk=connection.recv(4096)
                    if not chunk:break
                    data+=chunk
                if not should_run:
                    if data:raise AssertionError('Receiver still answered after stop')
                    return None
                header,body=data.split(b'\r\n\r\n',1)
                if not header.startswith(b'RTSP/1.0 200 OK'):raise AssertionError('RTSP did not start')
                size=int(re.search(rb'(?i)Content-Length:\s*(\d+)',header).group(1))
                if size>65536:raise AssertionError('Unexpected RTSP body size')
                while len(body)<size:
                    chunk=connection.recv(size-len(body))
                    if not chunk:raise AssertionError('Truncated RTSP body')
                    body+=chunk
                return plistlib.loads(body)
        except (ConnectionResetError,ConnectionRefusedError,BrokenPipeError):
            if should_run:raise
            return None
        finally:adb('forward','--remove','tcp:'+str(local));forwards.remove(local)

    def saved_margin():
        preferences=ET.fromstring(adb('shell','run-as',args.package,'cat','shared_prefs/settings.xml'))
        values=[int(node.get('value')) for node in preferences.findall('int') if node.get('name')=='tv_margin']
        return values[0] if values else 2

    def calibration_corners(path,margin):
        # Independent pixel check: all four tips must move by the requested
        # percentage of the screen, not merely update the percentage label.
        with Image.open(path) as source:
            pixels=source.convert('RGB');width,height=pixels.size
            for x in (int(width*margin/100)+3,width-int(width*margin/100)-3):
                for y in (int(height*margin/100)+3,height-int(height*margin/100)-3):
                    found=False
                    for px in range(max(0,x-5),min(width,x+6)):
                        for py in range(max(0,y-5),min(height,y+6)):
                            r,g,b=pixels.getpixel((px,py))
                            found|=g>190 and g-r>60 and b-r>30
                    if not found:raise AssertionError('Calibration tip missing at '+repr((x,y,margin)))

    def calibrate(strings,locale,thorough):
        print('Checking calibration '+locale,flush=True)
        tap(strings['tab_tools']);expect(strings['tools_calibrate'])
        local=int(adb('forward','tcp:0','tcp:7000').strip());forwards.append(local)
        try:
            with socket.create_connection(('127.0.0.1',local),timeout=3) as continuous:
                def options():
                    continuous.sendall(b'OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n')
                    response=b''
                    while b'\r\n\r\n' not in response:
                        chunk=continuous.recv(4096)
                        if not chunk:raise AssertionError('Calibration restarted the active receiver connection')
                        response+=chunk
                    if not response.startswith(b'RTSP/1.0 200 OK'):raise AssertionError('Continuous RTSP OPTIONS failed')
                options()
                tap(strings['tools_calibrate']);expect(strings['calibration_save_hint'],'2%')
                options() # Same TCP connection must survive entering the overlay.
                picture=args.output/(locale+'-calibration.png');capture(picture);calibration_corners(picture,2)
                if thorough:
                    for _ in range(3):adb('shell','input','keyevent',22)
                    expect('5%');picture=args.output/'calibration-five-percent.png';capture(picture);calibration_corners(picture,5)
                    if saved_margin()!=2:raise AssertionError('Preview persisted an unconfirmed margin')
                adb('shell','input','keyevent',4);expect(strings['tools_calibrate']);options()
        finally:adb('forward','--remove','tcp:'+str(local));forwards.remove(local)
        if saved_margin()!=2:raise AssertionError('Back committed calibration')
        if not thorough:return
        tap(strings['tools_calibrate']);expect('2%');tap('+');expect('3%');tap(strings['tv_edit_save'])
        expect(strings['tools_calibrate'])
        if saved_margin()!=3:raise AssertionError('Touch Save did not persist calibration')
        tap(strings['tools_calibrate']);expect('3%')
        for _ in range(8):adb('shell','input','keyevent',21)
        expect('0%');picture=args.output/'calibration-zero-percent.png';capture(picture);calibration_corners(picture,0)
        for _ in range(14):adb('shell','input','keyevent',22)
        expect('10%');picture=args.output/'calibration-ten-percent.png';capture(picture);calibration_corners(picture,10)
        if saved_margin()!=3:raise AssertionError('Limit preview modified stored calibration')
        adb('shell','input','keyevent',4);tap(strings['tools_calibrate']);expect('3%')
        adb('shell','input','keyevent',22);expect('4%');adb('shell','input','keyevent',23)
        expect(strings['tools_calibrate'])
        if saved_margin()!=4:raise AssertionError('D-pad OK did not persist calibration')
        tap(strings['tools_calibrate']);expect('4%');tap('−');expect('3%');tap(strings['tv_edit_cancel'])
        expect(strings['tools_calibrate'])
        if saved_margin()!=4:raise AssertionError('Cancel committed calibration')
        tap(strings['tools_calibrate']);expect('4%')
        for _ in range(2):adb('shell','input','keyevent',21)
        expect('2%');tap(strings['tv_edit_save']);expect(strings['tools_calibrate'])
        if saved_margin()!=2:raise AssertionError('Could not restore default margin')
        report['calibration_touch_dpad_limits_save_cancel_continuous_receiver']='pass'

    if adb('shell','getprop','ro.build.version.sdk').strip()!='17':parser.error('Use API17.')
    adb('shell','input','keyevent',82) # Dismiss the fresh API17 emulator's keyguard.
    adb('install','-r',args.apk.resolve());adb('shell','pm','clear',args.package)
    try:
        for index,locale in enumerate(wanted):
            print('Checking '+locale,flush=True); strings=labels(locale)
            adb('shell','am','force-stop',args.package)
            adb('shell','am','start','-n',args.package+'/io.buzincast.legacy.LegacyActivity','--es','buzin_test_locale',locale)
            home_tree=expect(strings['buzin_ready'],strings['buzin_hero'],'Buzin Cast')
            if index==0 and rtsp(7000)['name']!='Buzin Cast':raise AssertionError('Native receiver name differs from UI')
            capture(args.output/(locale+'-home.png'))
            tap(strings['buzin_about']); tree=expect('© '+str(datetime.datetime.now().year)+' Buzin Solutions LTDA')
            close=[n for n in tree.iter() if n.get('text')==strings['buzin_close'] and n.get('focused')=='true']
            if len(close)!=1:raise AssertionError('About must focus Close')
            picture=args.output/(locale+'-about.png');capture(picture);selected_border(picture,home_tree,strings['tab_overview'])
            check_modal_corners(picture,tree)
            adb('shell','input','keyevent','4');tap(strings['tab_settings'])
            tree=expect(strings['setting_server_name'],strings['setting_server_port'])
            nav=[n for n in tree.iter() if n.get('text')==strings['tab_settings'] and n.get('selected')=='true' and n.get('focused')=='true']
            if len(nav)!=1:raise AssertionError('Touch did not move focus to the selected destination')
            x1,_,x2,_=map(int,re.findall(r'\d+',nav[0].get('bounds')))
            with Image.open(picture) as dimensions: direction=21 if (x1+x2)/2>dimensions.width/2 else 22
            adb('shell','input','keyevent',direction)
            tree=ui()
            focused=[n for n in tree.iter() if n.get('focused')=='true' and n.get('clickable')=='true' and
                     n.get('text') not in {strings['tab_overview'],strings['tab_tools'],strings['tab_settings'],strings['tab_logs']}]
            if len(focused)!=1:raise AssertionError('D-pad did not move focus into settings content')
            capture(args.output/(locale+'-settings.png'))
            if index==0:
                tap(strings['setting_developer_options'],prefix=True);expect(strings['tab_logs'])
                tap(strings['tab_logs']);expect(strings['diag_receiver_running'],contains=True)
                tap(strings['tab_settings']);tap(strings['setting_developer_options'],prefix=True)
                if strings['tab_logs'] in {n.get('text') for n in ui().iter()}:raise AssertionError('Advanced logs remained visible')
                for field,value in [('setting_server_name','ReceiverSmoke'),('setting_server_port','17001')]:
                    tap(strings[field]);adb('shell','input','text',value);tap(strings['tv_edit_save'])
                    tap(strings['tab_overview']);expect(strings['buzin_ready'],'ReceiverSmoke')
                    tap(strings['tab_settings'])
                if rtsp(17001)['name']!='ReceiverSmoke':raise AssertionError('Settings did not reach native receiver')
                for field,value in [('setting_server_name','Buzin%sCast'),('setting_server_port','7000')]:
                    tap(strings[field]);adb('shell','input','text',value);tap(strings['tv_edit_save'])
                    tap(strings['tab_overview']);expect(strings['buzin_ready'],'Buzin Cast');tap(strings['tab_settings'])
                report['native_configuration_restart']='pass'
                for label,key,automatic,cushion,step in [
                    ('setting_profile_cinema','Cinema',True,80,4),
                    ('setting_profile_presentation','Apresentação',True,80,2),
                    ('setting_profile_games','Jogos',False,25,2),
                    ('setting_profile_default','Padrão',True,40,3)]:
                    print('Checking profile '+key,flush=True)
                    tap(strings[label]);tap(strings['tab_overview']);expect(strings['buzin_ready'])
                    display=rtsp(7000)['displays'][0]
                    if (display['width'],display['height'],display['maxFPS'])!=(1280,720,30):
                        raise AssertionError('Emulator profile invented an unsupported mode')
                    prefs=ET.fromstring(adb('shell','run-as',args.package,'cat','shared_prefs/settings.xml'))
                    values={node.get('name'):node.get('value') if node.tag!='string' else node.text for node in prefs}
                    if (values.get('experience_profile'),values.get('audio_auto_buffer'),values.get('audio_cushion_ms'),values.get('audio_adaptive_step'))!=(key,str(automatic).lower(),str(cushion),str(step)):
                        raise AssertionError('Profile media preferences were not applied atomically')
                    tap(strings['tab_settings']);tree=expect(strings[label])
                    chosen=[n for n in tree.iter() if n.get('text')==strings[label] and n.get('selected')=='true']
                    if len(chosen)!=1:raise AssertionError('Profile selection lost its persistent state')
                report['profiles_preferences_restart_native_dimensions']='pass'
            calibrate(strings,locale,index==0)
            tap(strings['tab_overview']);expect(strings['buzin_ready']);tap(strings['btn_stop']);expect(strings['btn_start'],strings['buzin_stopped'])
            if index==0:rtsp(7000,False)
            report['locales'][locale]='pass'
        report['status']='pass'
    except Exception as error:
        report['status']='failed';report['error']=str(error)
        try:capture(args.output/'failure.png')
        except Exception:pass
        raise
    finally:
        try:
            for port in forwards:adb('forward','--remove','tcp:'+str(port))
            adb('shell','rm','-f','/data/local/tmp/buzin-legacy-ui.xml')
            adb('shell','am','force-stop',args.package);adb('uninstall',args.package)
            report['cleanup']='uninstalled'
        except Exception as error:
            report['status']='failed';report['cleanup_error']=str(error);raise
        finally:(args.output/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(report,indent=2,ensure_ascii=False))


if __name__=='__main__':main()
