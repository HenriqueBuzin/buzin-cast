#!/usr/bin/env python3
"""Exercise the DLNA control plane and Vega player on an isolated VVD."""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/vega-dlna')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only VVD is allowed')
    args.output.mkdir(parents=True, exist_ok=True)
    payload = (ROOT / 'vega/assets/raw/media/playback-sample.mp4').read_bytes()
    fetched = threading.Event()
    result = {'passed': False, 'physicalValidation': False}
    forwards = []
    client = None

    class Media(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            if self.path != '/clip.mp4':
                self.send_error(404)
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
            self.send_header('Content-Type', 'video/mp4')
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            if requested:
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(payload)}')
            self.end_headers()
            if body:
                fetched.set()
                self.wfile.write(payload[start:end + 1])

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
        response_body = response.find(f'{{{SOAP}}}Body')
        return {node.tag: node.text for node in response_body[0]}

    def transport(state):
        return soap('avt', 'GetTransportInfo')['CurrentTransportState'] == state

    def seconds():
        position = soap('avt', 'GetPositionInfo')['RelTime']
        hour, minute, second = map(int, position.split(':'))
        return hour * 3600 + minute * 60 + second

    try:
        if not re.search(rf'^{re.escape(args.serial)}\s+device\b', run('exec', 'vda', 'devices'), re.M):
            raise RuntimeError('The VVD is not connected')
        if COMPONENT in shell('vlcm', 'list', '--app-id', COMPONENT):
            shell('vlcm', 'terminate-app', '--pkg-id', 'com.buzincast.tv')
        run('run-app', str(args.vpkg.resolve()), COMPONENT, '-d', args.serial, timeout=120)
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
        soap('avt', 'SetAVTransportURI', status=500, CurrentURI='file:///test-only.mp4', CurrentURIMetaData='')
        assert transport('NO_MEDIA_PRESENT')
        soap('avt', 'SetAVTransportURI', CurrentURI=f'http://127.0.0.1:{MEDIA_PORT}/clip.mp4', CurrentURIMetaData='')
        wait(lambda: transport('PLAYING') and seconds() >= 1)
        soap('rcs', 'SetVolume', Channel='Master', DesiredVolume=35)
        assert soap('rcs', 'GetVolume', Channel='Master')['CurrentVolume'] == '35'
        soap('rcs', 'SetMute', Channel='Master', DesiredMute=1)
        assert soap('rcs', 'GetMute', Channel='Master')['CurrentMute'] == '1'
        soap('avt', 'Pause')
        wait(lambda: transport('PAUSED_PLAYBACK'))
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
        shell('inputd-cli', 'button_press', 'KEY_BACK')
        wait(lambda: diagnostic('/data/playback-state.json').get('state') == 'released')
        assert startup() is None
        client.close()
        new_port = open_receiver()
        if new_port != port:
            forward(new_port, 'true')
        client = http.client.HTTPConnection('127.0.0.1', new_port, timeout=5)
        reopened = request_http('GET', '/desc.xml').findtext(f'{ns}device/{ns}UDN')
        assert reopened == identity and transport('NO_MEDIA_PRESENT')
        shell('inputd-cli', 'button_press', 'KEY_HOMEPAGE')
        wait(lambda: diagnostic('/data/playback-state.json').get('state') == 'released')
        assert startup() is None
        result.update(passed=True, description=True, serviceDescriptions=True, prohibitedUriRejected=True,
                      httpMedia=True, autoPlay=True, pause=True, seek=True, resume=True, stop=True, replay=True,
                      volumeAndMute=True, stableIdentity=True, playerReleased=True, backgroundReleased=True)
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
        if client:
            client.close()
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
