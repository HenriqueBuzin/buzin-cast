#!/usr/bin/env python3
"""Exercise the DLNA control plane and Vega player on an isolated VVD."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from queue import Empty, Queue
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

COMPONENT = 'com.buzincast.tv.main'
MEDIA_PORT = 40882
SERVICES = {'avt': 'AVTransport', 'rcs': 'RenderingControl', 'cmn': 'ConnectionManager'}
SOAP = 'http://schemas.xmlsoap.org/soap/envelope/'
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--vpkg', type=Path, required=True)
    parser.add_argument('--jpeg', type=Path, help='Optional JPEG fixture for the extensionless DIDL resource')
    parser.add_argument('--approval', action='store_true', help='Verify approval via TV remote input')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-dlna')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    photo = (ROOT / 'store/amazon/images/icon-114.png').read_bytes()
    media = {
        '/clip.mp4': ((ROOT / 'vega/assets/raw/media/playback-sample.mp4').read_bytes(), 'video/mp4'),
        '/still.png': (photo, 'image/png'),
        '/photo-image': (args.jpeg.read_bytes(), 'image/jpeg') if args.jpeg else (photo, 'image/png'),
        '/broken.png': (b'Invalid image', 'image/png'),
        '/slow.png': (photo, 'image/png'),
    }
    fetched = threading.Event()
    slow_started = threading.Event()
    slow_release = threading.Event()
    events = Queue()
    result = {'passed': False, 'physicalValidation': False}
    forwards = []
    client = None
    saved_approval = None

    class Media(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def do_NOTIFY(self):
            content = self.rfile.read(int(self.headers['Content-Length']))
            events.put((self.path, dict(self.headers), ET.fromstring(content)))
            self.send_response(200)
            self.send_header('Content-Length', '0')
            self.end_headers()

        def serve(self, body):
            if self.path not in media:
                self.send_error(404)
                return
            payload, mime = media[self.path]
            if self.path == '/slow.png':
                slow_started.set()
                if not slow_release.wait(5):
                    return
            start, end = 0, len(payload) - 1
            requested = self.headers.get('Range')
            if requested:
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', requested)
                if not match:
                    self.send_error(416)
                    return
                start = int(match[1])
                if match[2]:
                    end = min(end, int(match[2]))
                if start > end:
                    self.send_error(416)
                    return
            self.send_response(206 if requested else 200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            if requested:
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(payload)}')
            self.end_headers()
            if body:
                if mime == 'video/mp4':
                    fetched.set()
                try:
                    self.wfile.write(payload[start:end + 1])
                except BrokenPipeError:
                    pass

    server = ThreadingHTTPServer(('127.0.0.1', MEDIA_PORT), Media)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def run(*command, timeout=30):
        completed = subprocess.run(['vega', *command], capture_output=True, text=True, timeout=timeout)
        if completed.returncode:
            raise RuntimeError(completed.stderr or completed.stdout)
        return completed.stdout

    def shell(*command):
        return run('exec', 'vda', '-s', args.serial, 'shell', *command)

    def diagnostic(path):
        return json.loads(shell('-c', COMPONENT, 'cat', path))

    def wait(check, seconds=15):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            time.sleep(0.2)
        raise RuntimeError('DLNA condition timed out')

    def startup():
        try:
            return diagnostic('/data/dlna-port.json')['port']
        except (RuntimeError, ValueError):
            return None

    def open_receiver():
        for key in ('KEY_ENTER', *(['KEY_DOWN'] * 4), 'KEY_ENTER'):
            shell('inputd-cli', 'button_press', key)
        return wait(startup)

    def forward(port, direction):
        run('device', 'start-port-forwarding', '-d', args.serial, '--port', str(port), '--forward', direction)
        forwards.append((port, direction))

    def request_http(method, path, body=None, headers=None, status=200):
        client.request(method, path, body, headers or {})
        reply = client.getresponse()
        content = reply.read()
        if reply.status != status:
            raise RuntimeError(f'{method} {path} returned {reply.status}: {content[:512]!r}')
        return ET.fromstring(content) if content else None

    def soap(service, action, status=200, **values):
        namespace = f'urn:schemas-upnp-org:service:{SERVICES[service]}:1'
        root = ET.Element(f'{{{SOAP}}}Envelope')
        body = ET.SubElement(root, f'{{{SOAP}}}Body')
        request = ET.SubElement(body, f'{{{namespace}}}{action}')
        if service != 'cmn':
            ET.SubElement(request, 'InstanceID').text = '0'
        for key, value in values.items():
            ET.SubElement(request, key).text = str(value)
        response = request_http('POST', f'/ctl/{service}', ET.tostring(root),
                        {'Content-Type': 'text/xml', 'SOAPACTION': f'"{namespace}#{action}"'}, status)
        if response is None:
            return {}
        response_body = response.find(f'{{{SOAP}}}Body')
        return {node.tag: node.text for node in response_body[0]}

    def transport(state):
        return soap('avt', 'GetTransportInfo')['CurrentTransportState'] == state

    def seconds():
        position = soap('avt', 'GetPositionInfo')['RelTime']
        hour, minute, second = map(int, position.split(':'))
        return hour * 3600 + minute * 60 + second

    def subscription(method, service, headers, expected=200):
        client.request(method, f'/evt/{service}', headers=headers)
        response = client.getresponse()
        response.read()
        if response.status != expected:
            raise RuntimeError(f'{method} {service} returned {response.status}, expected {expected}')
        return response.getheader('SID'), response.getheader('TIMEOUT')

    def notification(sid, match, seconds=5):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                path, headers, content = events.get(timeout=max(0, deadline - time.monotonic()))
            except Empty:
                break
            if headers.get('SID') != sid:
                continue
            assert path == '/events' and headers['NT'] == 'upnp:event' and headers['NTS'] == 'upnp:propchange'
            change = content.find('.//LastChange')
            values = ET.fromstring(change.text) if change is not None else content
            if match(values):
                return int(headers['SEQ'])
        raise RuntimeError('Expected DLNA event did not arrive')

    def last_change(field, value):
        return lambda event: any(node.tag.endswith('}' + field) and node.get('val') == value for node in event.iter())

    def answer_on_tv(action, allow):
        with ThreadPoolExecutor(max_workers=1) as pool:
            result = pool.submit(action)
            time.sleep(0.7)
            assert not result.done(), 'The command did not wait for approval'
            if allow:
                shell('inputd-cli', 'button_press', 'KEY_RIGHT')
            shell('inputd-cli', 'button_press', 'KEY_ENTER')
            return result.result(timeout=6)

    try:
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', run('exec', 'vda', 'devices'), re.M):
            raise RuntimeError('The VVD is not connected')
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
        if args.approval:
            saved = shell('-c', COMPONENT, 'if test -f /data/dlna-approval; then cat /data/dlna-approval; fi')
            if saved not in ('', '0', '1'):
                raise RuntimeError('Unexpected saved approval setting')
            saved_approval = saved
            shell('-c', COMPONENT, 'printf 1 > /data/dlna-approval')
        shell('-c', COMPONENT, 'rm', '-f', '/data/dlna-port.json')
        port = open_receiver()
        probe = args.vpkg.parent / 'bin/buzin-dlna-discovery-probe'
        probe_path = '/data/buzin-dlna-discovery-probe'
        subprocess.run(['vega', 'exec', 'vda', '-s', args.serial, 'shell', '-c', COMPONENT,
                        f'cat > {probe_path}'], input=probe.read_bytes(), check=True)
        try:
            shell('-c', COMPONENT, 'chmod', '700', probe_path)
            shell('-c', COMPONENT, probe_path, str(port))
            result['ssdpDiscovery'] = True
        finally:
            shell('-c', COMPONENT, 'rm', '-f', probe_path)
        forward(port, 'true')
        forward(MEDIA_PORT, 'false')
        client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        if args.approval:
            answer_on_tv(lambda: subscription('SUBSCRIBE', 'avt',
                         {'CALLBACK': f'<http://127.0.0.1:{MEDIA_PORT}/events>', 'NT': 'upnp:event'}, 403), False)
            soap('cmn', 'GetProtocolInfo', status=403)
            shell('inputd-cli', 'button_press', 'KEY_BACK')
            wait(lambda: diagnostic('/data/playback-state.json').get('state') == 'released')
            client.close()
            approved_port = open_receiver()
            if approved_port != port:
                forward(approved_port, 'true')
            port = approved_port
            client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
            answer_on_tv(lambda: soap('cmn', 'GetProtocolInfo'), True)
            result['remoteApprovalAndDenial'] = True
        desc = request_http('GET', '/desc.xml')
        ns = '{urn:schemas-upnp-org:device-1-0}'
        device = desc.find(f'{ns}device')
        assert device.findtext(f'{ns}deviceType') == 'urn:schemas-upnp-org:device:MediaRenderer:1'
        identity = device.findtext(f'{ns}UDN')
        assert identity.startswith('uuid:')
        for path in device.iter(f'{ns}SCPDURL'):
            assert request_http('GET', path.text).tag == '{urn:schemas-upnp-org:service-1-0}scpd'
        assert 'http-get:*:video/mp4:*' in soap('cmn', 'GetProtocolInfo')['Sink']
        assert transport('NO_MEDIA_PRESENT')
        callback = {'CALLBACK': f'<http://127.0.0.1:{MEDIA_PORT}/events>', 'NT': 'upnp:event'}
        subscription('SUBSCRIBE', 'avt', {**callback, 'CALLBACK': f'<http://127.0.0.2:{MEDIA_PORT}/events>'}, 412)
        requested_timeout = 'Second-infinite' if args.approval else 'Second-120'
        avt_sid, timeout = subscription('SUBSCRIBE', 'avt', {**callback, 'TIMEOUT': requested_timeout})
        assert avt_sid.startswith('uuid:')
        if args.approval:
            assert timeout.startswith('Second-') and 0 < int(timeout.split('-', 1)[1]) <= 300
            result['genaCappedByApproval'] = True
        else:
            assert timeout == 'Second-120'
        assert notification(avt_sid, last_change('TransportState', 'NO_MEDIA_PRESENT')) == 0
        rcs_sid, _ = subscription('SUBSCRIBE', 'rcs', callback)
        assert notification(rcs_sid, last_change('Volume', '100')) == 0
        cmn_sid, _ = subscription('SUBSCRIBE', 'cmn', {**callback, 'TIMEOUT': 'Second-1'})
        assert notification(cmn_sid, lambda event: event.findtext('.//CurrentConnectionIDs') == '0') == 0
        assert subscription('SUBSCRIBE', 'avt', {'SID': avt_sid, 'TIMEOUT': 'Second-180'}) == (avt_sid, 'Second-180')
        subscription('SUBSCRIBE', 'rcs', {'SID': avt_sid}, 412)
        soap('avt', 'SetAVTransportURI', status=500, CurrentURI='file:///test-only.mp4', CurrentURIMetaData='')
        assert transport('NO_MEDIA_PRESENT')
        soap('avt', 'SetAVTransportURI', CurrentURI=f'http://127.0.0.1:{MEDIA_PORT}/clip.mp4', CurrentURIMetaData='')
        wait(lambda: transport('PLAYING') and seconds() >= 1)
        assert notification(avt_sid, last_change('TransportState', 'PLAYING')) > 0
        subscription('SUBSCRIBE', 'cmn', {'SID': cmn_sid}, 412)
        soap('rcs', 'SetVolume', Channel='Master', DesiredVolume=35)
        assert soap('rcs', 'GetVolume', Channel='Master')['CurrentVolume'] == '35'
        soap('rcs', 'SetMute', Channel='Master', DesiredMute=1)
        assert soap('rcs', 'GetMute', Channel='Master')['CurrentMute'] == '1'
        assert notification(rcs_sid, last_change('Mute', '1')) > 0
        subscription('UNSUBSCRIBE', 'rcs', {'SID': rcs_sid})
        subscription('SUBSCRIBE', 'rcs', {'SID': rcs_sid}, 412)
        soap('avt', 'Pause')
        wait(lambda: transport('PAUSED_PLAYBACK'))
        assert notification(avt_sid, last_change('TransportState', 'PAUSED_PLAYBACK')) > 0
        paused = seconds()
        time.sleep(0.7)
        assert seconds() == paused
        soap('avt', 'Seek', Unit='REL_TIME', Target='00:00:03')
        wait(lambda: seconds() == 3)
        soap('avt', 'Play', Speed=1)
        wait(lambda: transport('PLAYING') and seconds() >= 4)
        soap('avt', 'Stop')
        assert transport('STOPPED')
        soap('avt', 'Play', Speed=1)
        wait(lambda: transport('PLAYING') and seconds() < 3)
        assert fetched.is_set()
        assert 'http-get:*:image/png:*' in soap('cmn', 'GetProtocolInfo')['Sink']
        photo_url = f'http://127.0.0.1:{MEDIA_PORT}/still.png'
        time.sleep(0.55)
        soap('avt', 'SetAVTransportURI', CurrentURI=photo_url, CurrentURIMetaData='')
        wait(lambda: transport('PLAYING') and soap('avt', 'GetPositionInfo')['TrackURI'] == photo_url)
        assert soap('avt', 'GetCurrentTransportActions')['Actions'] == 'Stop'
        assert seconds() == 0
        soap('avt', 'Pause', status=500)
        soap('avt', 'Seek', status=500, Unit='REL_TIME', Target='00:00:03')
        soap('avt', 'Stop')
        assert transport('STOPPED')
        time.sleep(0.55)
        alternate = f'http://127.0.0.1:{MEDIA_PORT}/photo-image'
        mime = media['/photo-image'][1]
        metadata = f'<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item><res protocolInfo="http-get:*:{mime}:*">{alternate}</res></item></DIDL-Lite>'
        soap('avt', 'SetAVTransportURI', CurrentURI=alternate, CurrentURIMetaData=metadata)
        wait(lambda: transport('PLAYING') and soap('avt', 'GetPositionInfo')['TrackURI'] == alternate)
        time.sleep(0.55)
        soap('avt', 'SetAVTransportURI', CurrentURI=f'http://127.0.0.1:{MEDIA_PORT}/broken.png', CurrentURIMetaData='')
        wait(lambda: soap('avt', 'GetTransportInfo')['CurrentTransportStatus'] == 'ERROR_OCCURRED')
        time.sleep(0.55)
        soap('avt', 'SetAVTransportURI', CurrentURI=f'http://127.0.0.1:{MEDIA_PORT}/slow.png', CurrentURIMetaData='')
        assert slow_started.wait(5) and transport('TRANSITIONING')
        soap('avt', 'Stop')
        slow_release.set()
        time.sleep(0.55)
        assert transport('STOPPED')
        soap('avt', 'SetAVTransportURI', CurrentURI=f'http://127.0.0.1:{MEDIA_PORT}/clip.mp4', CurrentURIMetaData='')
        wait(lambda: transport('PLAYING') and seconds() >= 1)
        shell('inputd-cli', 'button_press', 'KEY_BACK')
        wait(lambda: diagnostic('/data/playback-state.json').get('state') == 'released')
        assert startup() is None
        client.close()
        new_port = open_receiver()
        if new_port != port:
            forward(new_port, 'true')
        client = http.client.HTTPConnection('127.0.0.1', new_port, timeout=5)
        reopened = request_http('GET', '/desc.xml').findtext(f'{ns}device/{ns}UDN')
        if args.approval:
            answer_on_tv(lambda: soap('cmn', 'GetProtocolInfo'), True)
            result['approvalClearedOnExit'] = True
        assert reopened == identity and transport('NO_MEDIA_PRESENT')
        subscription('SUBSCRIBE', 'avt', {'SID': avt_sid}, 412)
        soap('avt', 'SetAVTransportURI', CurrentURI=photo_url, CurrentURIMetaData='')
        wait(lambda: transport('PLAYING'))
        shell('inputd-cli', 'button_press', 'KEY_HOMEPAGE')
        wait(lambda: diagnostic('/data/playback-state.json').get('state') == 'released')
        assert startup() is None
        result.update(passed=True, description=True, serviceDescriptions=True, prohibitedUriRejected=True,
                      httpMedia=True, autoPlay=True, pause=True, seek=True, resume=True, stop=True, replay=True,
                      volumeAndMute=True, stableIdentity=True, playerReleased=True, backgroundReleased=True,
                      pngLoaded=True, didlPhotoLoaded=True, jpegLoaded=bool(args.jpeg),
                      corruptPhotoRejected=True, pendingPhotoCancelledByStop=True, videoAfterPhoto=True,
                      genaInitialAndUpdates=True, genaRenewal=True, genaExpiry=True, genaUnsubscribe=True,
                      genaSamePeerEnforced=True, genaClearedOnExit=True)
        print(json.dumps(result, indent=2))
    except Exception as error:
        result['error'] = str(error)
        try:
            result['player'] = diagnostic('/data/playback-state.json')
            (args.output / 'receiver.log').write_text(shell('loggingctl', 'log', '-v', 'com.buzincast.tv'))
        except Exception:
            pass
        raise
    finally:
        slow_release.set()
        if client:
            client.close()
        if saved_approval is not None:
            restore = f'printf {saved_approval} > /data/dlna-approval' if saved_approval else 'rm -f /data/dlna-approval'
            shell('-c', COMPONENT, restore)
        for number, direction in forwards:
            try:
                run('device', 'stop-port-forwarding', '-d', args.serial, '--port', str(number), '--forward', direction)
            except RuntimeError:
                pass
        server.shutdown()
        server.server_close()
        (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
