// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import { MANAGER } from './people.js';
import { param, png, stub, type Stub } from './stubs.js';

/**
 * GP-334, criterion 4: a connection (an external service of GP-939) shared "to use" lets another
 * person add its layers to a map without ever receiving its credentials in any answer. The service is
 * a stub WMS on 127.0.0.1 that wants HTTP Basic, advertises its own address in its capabilities and,
 * like a careless server, echoes the Authorization header it received.
 */

const ORG = () => bench().organization.slug;
const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const stranger = () => member(0);
const colleague = () => member(1);
const manager = () => member(2);

const USER = 'mapreader';
const PASSWORD = `gp334-pass-${Date.now().toString(36)}`;
const BASIC = Buffer.from(`${USER}:${PASSWORD}`).toString('base64');

let server: Stub;

/** A WMS that wants the credential, names itself in its capabilities and echoes the header it got. */
function protectedWms(): Parameters<typeof stub>[0] {
  return (request, response) => {
    if (request.headers.authorization !== `Basic ${BASIC}`) {
      response.writeHead(401, { 'WWW-Authenticate': 'Basic realm="stub"' }).end('Unauthorized');
      return;
    }
    const what = (param(request, 'request') ?? '').toLowerCase();
    if (what === 'getcapabilities') {
      const self = `${server.url}/geoserver/wms?`;
      response.writeHead(200, { 'Content-Type': 'text/xml; charset=UTF-8' }).end(`<?xml version="1.0" encoding="UTF-8"?>
<WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms" xmlns:xlink="http://www.w3.org/1999/xlink">
  <Service><Name>WMS</Name><Title>Protected</Title>
    <OnlineResource xlink:href="${server.url}/geoserver/ows"/></Service>
  <!-- debug: Authorization ${String(request.headers.authorization)} user ${USER}:${PASSWORD} -->
  <Capability>
    <Request>
      <GetCapabilities><Format>text/xml</Format><DCPType><HTTP><Get><OnlineResource xlink:href="${self}"/></Get></HTTP></DCPType></GetCapabilities>
      <GetMap><Format>image/png</Format><DCPType><HTTP><Get><OnlineResource xlink:href="${self}"/></Get></HTTP></DCPType></GetMap>
    </Request>
    <Layer><Title>Root</Title>
      <Layer queryable="1"><Name>reti:condotte</Name><Title>Condotte</Title>
        <EX_GeographicBoundingBox><westBoundLongitude>12.3</westBoundLongitude><eastBoundLongitude>12.6</eastBoundLongitude>
        <southBoundLatitude>41.8</southBoundLatitude><northBoundLatitude>42.0</northBoundLatitude></EX_GeographicBoundingBox></Layer>
    </Layer>
  </Capability>
</WMS_Capabilities>`);
    } else if (what === 'getmap') {
      response
        .writeHead(200, { 'Content-Type': 'image/png' })
        .end(png(Number(param(request, 'width') ?? 8), Number(param(request, 'height') ?? 8), [200, 40, 40]));
    } else {
      response.writeHead(400, { 'Content-Type': 'text/plain' }).end(`Unknown request; you sent ${String(request.headers.authorization)}`);
    }
  };
}

function leaks(text: string): string[] {
  return [PASSWORD, BASIC, `${USER}:${PASSWORD}`].filter((secret) => text.includes(secret));
}

async function accountOf(person: ReturnType<typeof member>): Promise<string> {
  const { body } = await api(person, '/api/v1/accounts/me');
  return (body as { id: string }).id;
}

let service: { id: string; proxyUrl: string; title: string };

test.beforeAll(async () => {
  server = await stub(protectedWms());
  const promoted = await api(bench().admin, `/api/v1/organizations/${ORG()}/members/${MANAGER.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-user', 'organization-admin'] }),
  });
  expect(promoted.status).toBeLessThan(300);
  const name = unique('reti-wms').replace(/\W+/g, '-').toLowerCase();
  const stored = await api(manager(), `/api/v1/organizations/${ORG()}/secrets`, {
    method: 'POST',
    body: JSON.stringify({ name, kind: 'password', value: `${USER}:${PASSWORD}`, description: 'Stub WMS', extension: EXTENSION }),
  });
  expect(stored.status, JSON.stringify(stored.body)).toBeLessThan(300);
  const created = await api(manager(), '/api/v1/ext/catalog-geodata/services', {
    method: 'POST',
    body: JSON.stringify({
      title: unique('Reti protette'), kind: 'wms', url: `${server.url}/geoserver/ows`, proxy: true,
      credential: name, authScheme: 'basic',
    }),
  });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  service = created.body as typeof service;
  const shared = await api(manager(), `/api/v1/sharing/resources/${EXTENSION}/service/${service.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'use' }),
  });
  expect(shared.status, JSON.stringify(shared.body)).toBeLessThan(300);
});

test.afterAll(async () => {
  await server?.close();
});

test('GP-334 a connection shared to use gives its layers through the proxy, and its credential is in no answer', async () => {
  // What the colleague reads of the connection: no credential.
  const view = await api(colleague(), `/api/v1/ext/catalog-geodata/services/${service.id}`);
  expect(view.status).toBe(200);
  expect(leaks(JSON.stringify(view.body))).toEqual([]);
  const list = await api(colleague(), '/api/v1/ext/catalog-geodata/services');
  expect(leaks(JSON.stringify(list.body))).toEqual([]);

  // The capabilities through the proxy: the layer, every address pointing at the proxy, nothing of the credential.
  const caps = await api(colleague(), `${service.proxyUrl}?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(caps.status).toBe(200);
  const xml = String(caps.body);
  expect(xml).toContain('<Name>reti:condotte</Name>');
  expect(leaks(xml)).toEqual([]);
  expect(xml).not.toContain(server.url);
  const getMap = /<GetMap>[\s\S]*?xlink:href="([^"]+)"/.exec(xml)?.[1];
  expect(getMap).toMatch(new RegExp(`/api/v1/ext/catalog-geodata/services/${service.id}/proxy\\?$`));
  expect(server.received.at(-1)!.headers.authorization).toBe(`Basic ${BASIC}`);

  // A map client follows the advertised address for the image, and gets it through the proxy.
  const mapUrl = `${getMap!}SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=reti:condotte&STYLES=&CRS=EPSG:4326`
    + '&BBOX=41.8,12.3,42.0,12.6&WIDTH=256&HEIGHT=128&FORMAT=image/png';
  const image = await fetch(mapUrl, {
    headers: {
      authorization: 'Basic ' + Buffer.from(`${colleague().user}:${colleague().password}`).toString('base64'),
      'X-Mosaikit-Organization': ORG(),
    },
  });
  expect(image.status).toBe(200);
  expect(image.headers.get('content-type')).toBe('image/png');
  const bytes = Buffer.from(await image.arrayBuffer());
  expect(bytes.subarray(1, 4).toString('ascii')).toBe('PNG');
  expect(bytes.readUInt32BE(16)).toBe(256);
  expect(leaks(bytes.toString('latin1'))).toEqual([]);
  for (const [, value] of image.headers) {
    expect(leaks(value)).toEqual([]);
  }

  // An error page of the service that echoes the header does not carry it to the browser either.
  const echoed = await api(colleague(), `${service.proxyUrl}?SERVICE=WMS&REQUEST=Nonsense`);
  expect(String(echoed.body)).toContain('[credential removed]');
  expect(leaks(String(echoed.body))).toEqual([]);

  // Someone it was not shared with gets nothing.
  const refused = await api(stranger(), `${service.proxyUrl}?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect([403, 404]).toContain(refused.status);
});

test('GP-334 in the catalog page the colleague sees the layers of the shared connection, and no answer holds its credential', async ({ page }) => {
  const bodies: string[] = [];
  page.on('response', async (response) => {
    try {
      bodies.push(response.url(), JSON.stringify(response.headers()), (await response.body()).toString('latin1'));
    } catch {
      // redirects and aborted requests have no body
    }
  });
  const direct: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith(server.url)) {
      direct.push(request.url());
    }
  });
  await signIn(page, colleague());
  await openApp(page, 'Geodata');
  const services = page.locator('catalog-services');
  await services.getByRole('row', { name: new RegExp(service.title) }).click();
  const detail = services.getByRole('region', { name: service.title });
  await expect(detail.getByRole('list', { name: 'Layers' }).getByRole('button', { name: 'Condotte' })).toBeVisible();
  const tile = detail.getByRole('img', { name: 'Preview of the layer Condotte' });
  await expect.poll(() => tile.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
    .toBe(512);
  await page.waitForLoadState('networkidle');
  expect(direct).toEqual([]);
  expect(leaks(bodies.join('\n'))).toEqual([]);
});
