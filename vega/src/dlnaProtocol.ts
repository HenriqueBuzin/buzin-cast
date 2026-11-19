import {DOMParser, Element} from '@xmldom/xmldom';
import {DLNA_PHOTO_TYPES} from './dlnaPhoto';

const SOAP = 'http://schemas.xmlsoap.org/soap/envelope/';
const SERVICE = 'urn:schemas-upnp-org:service:';
const DEVICE = 'urn:schemas-upnp-org:device:MediaRenderer:1';
const TYPES = ['video/mp4', 'audio/mpeg', 'audio/mp4', ...DLNA_PHOTO_TYPES]
  .map(mime => `http-get:*:${mime}:*`).join(',');

type Args = Record<string, string>;
type ActionArguments = [name: string, type?: string][];
type Action = {input: ActionArguments; output: ActionArguments};
const action = (input: ActionArguments = [], output: ActionArguments = []): Action => ({input, output});
const instance: ActionArguments = [['InstanceID', 'ui4']];
const channel: ActionArguments = [...instance, ['Channel']];

const services = {
  avt: {name: 'AVTransport', actions: {
    SetAVTransportURI: action([...instance, ['CurrentURI', 'uri'], ['CurrentURIMetaData']]),
    Play: action([...instance, ['Speed']]),
    Pause: action(instance),
    Stop: action(instance),
    Seek: action([...instance, ['Unit'], ['Target']]),
    GetPositionInfo: action(instance, [['Track', 'ui4'], ['TrackDuration'], ['TrackMetaData'], ['TrackURI', 'uri'],
      ['RelTime'], ['AbsTime'], ['RelCount', 'i4'], ['AbsCount', 'i4']]),
    GetTransportInfo: action(instance, [['CurrentTransportState'], ['CurrentTransportStatus'], ['CurrentSpeed']]),
    GetMediaInfo: action(instance, [['NrTracks', 'ui4'], ['MediaDuration'], ['CurrentURI', 'uri'],
      ['CurrentURIMetaData'], ['NextURI', 'uri'], ['NextURIMetaData'], ['PlayMedium'], ['RecordMedium'], ['WriteStatus']]),
    GetCurrentTransportActions: action(instance, [['Actions']]),
  }},
  rcs: {name: 'RenderingControl', actions: {
    GetVolume: action(channel, [['CurrentVolume', 'ui2']]),
    SetVolume: action([...channel, ['DesiredVolume', 'ui2']]),
    GetMute: action(channel, [['CurrentMute', 'boolean']]),
    SetMute: action([...channel, ['DesiredMute', 'boolean']]),
    ListPresets: action(instance, [['CurrentPresetNameList']]),
    SelectPreset: action([...instance, ['PresetName']]),
  }},
  cmn: {name: 'ConnectionManager', actions: {
    GetProtocolInfo: action([], [['Source'], ['Sink']]),
    GetCurrentConnectionIDs: action([], [['ConnectionIDs']]),
    GetCurrentConnectionInfo: action([['ConnectionID', 'i4']], [['RcsID', 'i4'], ['AVTransportID', 'i4'],
      ['ProtocolInfo'], ['PeerConnectionManager'], ['PeerConnectionID', 'i4'], ['Direction'], ['Status']]),
  }},
};
export type DlnaService = keyof typeof services;
export type DlnaRequest = {method: string; path: string; soapAction: string; contentType: string; body: string};
export type DlnaResult = {status: number; body: string};
const xml = '<?xml version="1.0" encoding="UTF-8"?>';
const escape = (value: string) => value.replace(/[&<>"']/g, char => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;',
}[char]!));
const element = (name: string, value: string) => `<${name}>${escape(value)}</${name}>`;
const namespace = (id: DlnaService) => `${SERVICE}${services[id].name}:1`;
const version = '<specVersion><major>1</major><minor>0</minor></specVersion>';
const envelope = (body: string) => `${xml}<s:Envelope xmlns:s="${SOAP}" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>${body}</s:Body></s:Envelope>`;

export class DlnaFault extends Error {
  constructor(readonly code: number, message: string) { super(message); }
}

export function isDlnaPhoto(uri: string, metadata: string) {
  if (metadata && !metadata.includes('<!') && (metadata.match(/</g)?.length || 0) <= 256) {
    try {
      const doc = new DOMParser({onError: () => {throw new Error('Invalid DIDL metadata');}})
        .parseFromString(metadata, 'text/xml');
      for (const resource of Array.from(doc.getElementsByTagNameNS('*', 'res'))) {
        if (resource.textContent?.trim() !== uri) {continue;}
        const mime = resource.getAttribute('protocolInfo')?.split(':')[2]?.toLowerCase();
        if (mime?.startsWith('image/')) {
          if (DLNA_PHOTO_TYPES.some(type => type === mime)) {return true;}
          throw new DlnaFault(714, 'Unsupported photo format');
        }
      }
    } catch (error) {
      if (error instanceof DlnaFault) {throw error;}
      // Metadata is optional. Older senders report only the resource URI.
    }
  }
  return /\.(jpe?g|png)$/i.test(uri.split(/[?#]/, 1)[0]);
}

function failure(error: DlnaFault): DlnaResult {
  const detail = `<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">${element('errorCode', String(error.code))}${element('errorDescription', error.message)}</UPnPError>`;
  return {status: 500, body: envelope(`<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>${detail}</detail></s:Fault>`)};
}

function description(uuid: string, name: string) {
  const serviceList = (Object.keys(services) as DlnaService[]).map(id => `<service>
    ${element('serviceType', namespace(id))}
    ${element('serviceId', `urn:upnp-org:serviceId:${services[id].name}`)}
    ${element('SCPDURL', `/scpd/${id}.xml`)}
    ${element('controlURL', `/ctl/${id}`)}
    ${element('eventSubURL', `/evt/${id}`)}
  </service>`).join('');
  return `${xml}<root xmlns="urn:schemas-upnp-org:device-1-0">${version}<device>
    ${element('deviceType', DEVICE)}${element('friendlyName', `${name} (DLNA)`)}
    ${element('manufacturer', 'Buzin Cast')}${element('modelName', 'Buzin Cast')}
    ${element('UDN', uuid)}<serviceList>${serviceList}</serviceList>
  </device></root>`;
}

function scpd(id: DlnaService) {
  const variables = new Map<string, string>();
  const actions = Object.entries(services[id].actions).map(([name, spec]) => {
    const argumentsXml = ([['in', spec.input], ['out', spec.output]] as const).flatMap(([direction, args]) =>
      args.map(([arg, type = 'string']) => {
        const variable = `A_ARG_TYPE_${arg}`;
        variables.set(variable, type);
        return `<argument>${element('name', arg)}${element('direction', direction)}${element('relatedStateVariable', variable)}</argument>`;
      })).join('');
    return `<action>${element('name', name)}${argumentsXml ? `<argumentList>${argumentsXml}</argumentList>` : ''}</action>`;
  }).join('');
  const table = [...variables].map(([name, type]) =>
    `<stateVariable sendEvents="no">${element('name', name)}${element('dataType', type)}</stateVariable>`).join('');
  return `${xml}<scpd xmlns="urn:schemas-upnp-org:service-1-0">${version}<actionList>${actions}</actionList><serviceStateTable>${table}</serviceStateTable></scpd>`;
}

function parseArgs(id: DlnaService, actionName: string, definition: Action, body: string): Args {
  const invalid = () => new DlnaFault(402, 'Invalid Args');
  if (body.includes('<!') || (body.match(/</g)?.length || 0) > 64) { throw invalid(); }
  let root;
  try {
    root = new DOMParser({onError: () => {throw invalid();}}).parseFromString(body, 'text/xml').documentElement;
  } catch {throw invalid();}
  const soapBody = root && Array.from(root.childNodes).find(node =>
    node.nodeType === 1 && (node as Element).localName === 'Body' && (node as Element).namespaceURI === SOAP) as Element | undefined;
  const calls = soapBody && Array.from(soapBody.childNodes).filter(node => node.nodeType === 1) as Element[] | undefined;
  if (root?.localName !== 'Envelope' || root.namespaceURI !== SOAP || calls?.length !== 1 ||
      calls[0].localName !== actionName || calls[0].namespaceURI !== namespace(id)) {throw invalid();}
  const inputs = new Set(definition.input.map(([name]) => name));
  const result: Args = Object.create(null);
  for (const node of Array.from(calls[0].childNodes)) {
    if (node.nodeType !== 1) {continue;}
    const arg = node as Element;
    const key = arg.localName;
    if (!key || !inputs.has(key) || key in result ||
        Array.from(arg.childNodes).some(child => child.nodeType === 1)) {throw invalid();}
    result[key] = arg.textContent || '';
  }
  for (const name of inputs) {
    if (!(name in result) && name !== 'CurrentURIMetaData') {throw invalid();}
  }
  if ('InstanceID' in result && result.InstanceID !== '0') {throw new DlnaFault(id === 'rcs' ? 702 : 718, 'Invalid InstanceID');}
  if ('Channel' in result && result.Channel !== 'Master') {throw invalid();}
  return result;
}

export function routeDlna(request: DlnaRequest, uuid: string, name: string,
  dispatch: (service: DlnaService, actionName: string, args: Args) => Args,
): DlnaResult {
  const {method, path} = request;
  const id = /^\/(?:ctl|evt|scpd)\/(avt|rcs|cmn)(?:\.xml)?$/.exec(path)?.[1] as DlnaService | undefined;
  if (method === 'GET' || method === 'HEAD') {
    if (path === '/desc.xml') {return {status: 200, body: description(uuid, name)};}
    if (id && path === `/scpd/${id}.xml`) {return {status: 200, body: scpd(id)};}
    return {status: 404, body: ''};
  }
  if (id && path === `/evt/${id}` && (method === 'SUBSCRIBE' || method === 'UNSUBSCRIBE')) {
    return {status: 501, body: ''};
  }
  if (!id || path !== `/ctl/${id}`) {return {status: 404, body: ''};}
  if (method !== 'POST') {return {status: 405, body: ''};}
  if (!/^(text|application)\/xml(?:\s*;|$)/i.test(request.contentType)) {return {status: 415, body: ''};}
  const [uri, nameOfAction, extra] = request.soapAction.trim().replace(/^"([^"]*)"$/, '$1').split('#');
  const definitions: Record<string, Action> = services[id].actions;
  if (extra !== undefined || uri !== namespace(id) || !Object.hasOwn(definitions, nameOfAction)) {
    return failure(new DlnaFault(401, 'Invalid Action'));
  }
  try {
    const definition = definitions[nameOfAction];
    const result = dispatch(id, nameOfAction, parseArgs(id, nameOfAction, definition, request.body));
    const response = definition.output.map(([key]) => element(key, result[key] ?? '')).join('');
    return {status: 200, body: envelope(`<u:${nameOfAction}Response xmlns:u="${uri}">${response}</u:${nameOfAction}Response>`)};
  } catch (error) {
    return failure(error instanceof DlnaFault ? error : new DlnaFault(501, 'Action Failed'));
  }
}

export function dlnaConnectionInfo(name: string, args: Args): Args {
  if (name === 'GetProtocolInfo') {return {Source: '', Sink: TYPES};}
  if (name === 'GetCurrentConnectionIDs') {return {ConnectionIDs: '0'};}
  if (args.ConnectionID !== '0') {throw new DlnaFault(706, 'Invalid Connection Reference');}
  return {RcsID: '0', AVTransportID: '0', ProtocolInfo: 'http-get:*:video/mp4:*', PeerConnectionManager: '', PeerConnectionID: '-1',
    Direction: 'Input', Status: 'OK'};
}

export function formatDlnaTime(value: number) {
  const seconds = Math.floor(Number.isFinite(value) && value > 0 ? value : 0);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map(part => String(part).padStart(2, '0')).join(':');
}

export function parseDlnaTime(value: string) {
  const parts = /^(\d{1,4}):([0-5]\d):([0-5]\d(?:\.\d{1,3})?)$/.exec(value);
  if (!parts) {throw new DlnaFault(711, 'Illegal seek target');}
  return Number(parts[1]) * 3600 + Number(parts[2]) * 60 + Number(parts[3]);
}
