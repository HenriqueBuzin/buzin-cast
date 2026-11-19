import {DOMParser} from '@xmldom/xmldom';
import {DlnaRequest, dlnaRenderingEvent, dlnaTransportEvent, formatDlnaTime, parseDlnaTime, routeDlna} from '../src/dlnaProtocol';

const service = 'urn:schemas-upnp-org:service:AVTransport:1';
const soap = 'http://schemas.xmlsoap.org/soap/envelope/';
const dispatch = jest.fn(() => ({}));
const get = (path: string): DlnaRequest => ({method: 'GET', path, soapAction: '', contentType: '', body: ''});
const uri = (args: string): DlnaRequest => ({
  ...get('/ctl/avt'),
  method: 'POST',
  contentType: 'text/xml; charset="utf-8"',
  soapAction: `"${service}#SetAVTransportURI"`,
  body: `<s:Envelope xmlns:s="${soap}"><s:Body><u:SetAVTransportURI xmlns:u="${service}">${args}</u:SetAVTransportURI></s:Body></s:Envelope>`,
});
const route = (request: DlnaRequest) => routeDlna(request, 'uuid:test', 'Sala & TV', dispatch);
beforeEach(() => dispatch.mockClear());

test('descrição e SCPD apontam para as três rotas do renderer', () => {
  const device = route(get('/desc.xml'));
  expect(device.status).toBe(200);
  const body = new DOMParser().parseFromString(device.body, 'text/xml');
  expect(body.getElementsByTagName('friendlyName')[0].textContent).toBe('Sala & TV (DLNA)');
  for (const path of ['/scpd/avt.xml', '/scpd/rcs.xml', '/scpd/cmn.xml']) {
    const serviceXml = route(get(path));
    expect(serviceXml.status).toBe(200);
    expect(new DOMParser().parseFromString(serviceXml.body, 'text/xml').getElementsByTagName('action').length).toBeGreaterThan(0);
  }
});

test('despacha uma URI XML com escape e valida a estrutura antes de tocar', () => {
  const args = '<InstanceID>0</InstanceID><CurrentURI>http://example.invalid/a?x=1&amp;y=2</CurrentURI>';
  expect(route(uri(args)).status).toBe(200);
  expect(dispatch).toHaveBeenCalledWith('avt', 'SetAVTransportURI',
    {InstanceID: '0', CurrentURI: 'http://example.invalid/a?x=1&y=2'});
  dispatch.mockClear();
  expect(route({...uri(args), soapAction: `"${service}#Play"`}).status).toBe(500);
  expect(route(uri(args + '<CurrentURI>http://example.invalid/b</CurrentURI>')).status).toBe(500);
  expect(route(uri(args.replace('>0<', '>1<'))).body).toContain('<errorCode>718</errorCode>');
  expect(dispatch).not.toHaveBeenCalled();
  expect(formatDlnaTime(parseDlnaTime('01:02:03.250'))).toBe('01:02:03');
  expect(() => parseDlnaTime('00:99:00')).toThrow();
});

test('publica LastChange com o XML interno e anuncia a variável no SCPD', () => {
  const parser = new DOMParser();
  const trackUrl = 'http://example.invalid/clip?x=1&y=2';
  const outer = parser.parseFromString(dlnaTransportEvent('PLAYING', 'OK', trackUrl, '00:00:08', 'Pause,Stop'), 'text/xml');
  const inner = parser.parseFromString(outer.getElementsByTagName('LastChange')[0].textContent!, 'text/xml');
  expect(inner.getElementsByTagName('CurrentTrackURI')[0].getAttribute('val')).toBe(trackUrl);
  expect(inner.getElementsByTagName('TransportState')[0].getAttribute('val')).toBe('PLAYING');
  expect(dlnaRenderingEvent(35, true)).toContain('&lt;Mute channel=&quot;Master&quot; val=&quot;1&quot;/&gt;');
  expect(route(get('/scpd/avt.xml')).body).toContain('<stateVariable sendEvents="yes"><name>LastChange</name>');
});
