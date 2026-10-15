// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { createServer, type IncomingHttpHeaders, type Server, type ServerResponse } from 'node:http';
import { crc32, deflateSync } from 'node:zlib';

/**
 * Stub OGC servers for the tests of the proxy (GP-939, GP-297), on 127.0.0.1: they answer like a
 * WMS or a WFS and remember what they received, so that the tests check what reached the server.
 */

export interface Received {
  readonly method: string;
  readonly url: string;
  readonly query: URLSearchParams;
  readonly headers: IncomingHttpHeaders;
}

export interface Stub {
  readonly url: string;
  readonly received: Received[];
  close(): Promise<void>;
}

type Handler = (request: Received, response: ServerResponse) => void;

export async function stub(handler: Handler): Promise<Stub> {
  const received: Received[] = [];
  const server: Server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://stub');
    const request: Received = { method: req.method ?? 'GET', url: req.url ?? '/', query: url.searchParams, headers: req.headers };
    received.push(request);
    req.resume();
    req.on('end', () => handler(request, res));
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('The stub has no port');
  }
  return {
    url: `http://127.0.0.1:${String(address.port)}`,
    received,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

/** The value of a parameter whatever its case, as OGC servers read them. */
export function param(request: Received, name: string): string | null {
  for (const [key, value] of request.query) {
    if (key.toLowerCase() === name.toLowerCase()) {
      return value;
    }
  }
  return null;
}

/** A PNG of one color, width × height, built here so that the tests need no file. */
export function png(width: number, height: number, [r, g, b]: [number, number, number]): Buffer {
  const chunk = (type: string, data: Buffer): Buffer => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.writeUInt8(8, 8); // bit depth
  header.writeUInt8(2, 9); // truecolor
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(width * 3)]);
  for (let x = 0; x < width; x++) {
    row.writeUInt8(r, 1 + x * 3);
    row.writeUInt8(g, 2 + x * 3);
    row.writeUInt8(b, 3 + x * 3);
  }
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** WMS 1.3.0 capabilities with the given layers. */
export function wmsCapabilities(layers: { name: string; title: string }[]): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms">
  <Service><Name>WMS</Name><Title>Stub</Title></Service>
  <Capability>
    <Layer><Title>Root</Title>
      ${layers
        .map(
          (l) => `<Layer queryable="1"><Name>${l.name}</Name><Title>${l.title}</Title>
        <EX_GeographicBoundingBox><westBoundLongitude>12.3</westBoundLongitude><eastBoundLongitude>12.6</eastBoundLongitude>
        <southBoundLatitude>41.8</southBoundLatitude><northBoundLatitude>42.0</northBoundLatitude></EX_GeographicBoundingBox></Layer>`,
        )
        .join('\n')}
    </Layer>
  </Capability>
</WMS_Capabilities>`;
}

/**
 * A WMS: capabilities and maps, with CORS headers only when asked. Its maps are green PNGs; every
 * other request is an error page with status 200, as many portals answer.
 */
export function wms(options: { cors: boolean; layers?: { name: string; title: string }[] }): Handler {
  const layers = options.layers ?? [{ name: 'demo:roads', title: 'Strade' }];
  return (request, response) => {
    if (options.cors) {
      response.setHeader('Access-Control-Allow-Origin', '*');
    }
    // What a server could send that must never reach the origin of Geoportal.
    response.setHeader('Set-Cookie', 'stub-session=1; Path=/');
    const what = (param(request, 'request') ?? '').toLowerCase();
    if (what === 'getcapabilities') {
      response.writeHead(200, { 'Content-Type': 'text/xml' }).end(wmsCapabilities(layers));
    } else if (what === 'getmap') {
      response
        .writeHead(200, { 'Content-Type': 'image/png' })
        .end(png(Number(param(request, 'width') ?? 8), Number(param(request, 'height') ?? 8), [40, 160, 90]));
    } else {
      response.writeHead(200, { 'Content-Type': 'text/html' }).end('<html><body>Portale in manutenzione</body></html>');
    }
  };
}

/** A server that answers an error page with status 200 to everything, capabilities included. */
export function errorPage(): Handler {
  return (_request, response) => {
    response.setHeader('Access-Control-Allow-Origin', '*');
    response.writeHead(200, { 'Content-Type': 'text/html' }).end('<html><body>Pagina non trovata</body></html>');
  };
}

/**
 * A WFS that refuses the negotiation (GetCapabilities, DescribeFeatureType) as a server behind a
 * proxy or a virtual host often does, and answers GetFeature only when the reference system is
 * declared.
 */
export function strictWfs(): Handler {
  return (request, response) => {
    const what = (param(request, 'request') ?? '').toLowerCase();
    if (what === 'getfeature' && param(request, 'srsname')) {
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(
        JSON.stringify({
          type: 'FeatureCollection',
          crs: { type: 'name', properties: { name: param(request, 'srsname') } },
          features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [12.38, 42.1] }, properties: { nome: 'Torre Civica' } }],
        }),
      );
    } else if (what === 'getfeature') {
      response.writeHead(400, { 'Content-Type': 'text/xml' }).end('<ExceptionReport>srsName required</ExceptionReport>');
    } else {
      response.writeHead(403, { 'Content-Type': 'text/plain' }).end('Forbidden');
    }
  };
}

/** A server that redirects every request to the given address. */
export function redirectTo(location: string): Handler {
  return (_request, response) => {
    response.writeHead(302, { Location: location }).end();
  };
}
