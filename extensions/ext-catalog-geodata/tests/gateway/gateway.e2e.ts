// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { expect, test, type Browser } from '@playwright/test';

/**
 * GP-336 on the server stack (distribution/server, built in target/dist/geoportal-server): the data
 * gateway in front of GeoServer, with the session of Geoportal for browsers, personal keys for QGIS
 * (AuthKey), links of Geoportal and the quota of the organization. Started with
 *
 *   docker compose -p geoportal-f04-gp336 -f compose.yaml -f <this folder>/compose.test.yaml up -d --build
 *
 * with tests/gateway/test.env as its .env, and run with `npx playwright test -c playwright.gateway.config.ts`
 * (see README.md of the catalog).
 * The GeoFence rules are those the catalog generates from sharing (GP-335): ada administers the
 * organization and reads everything, dan is a member with no share and reads nothing.
 */

const GW = process.env.GEOPORTAL_GATEWAY_URL ?? 'http://localhost:18934';
const GS_ADMIN_DOOR = process.env.GEOPORTAL_GEOSERVER_ADMIN_URL ?? 'http://127.0.0.1:18935/geoserver';
const PROJECT = process.env.GEOPORTAL_TEST_COMPOSE_PROJECT ?? 'geoportal-f04-gp336';
const DIST = resolve(process.env.GEOPORTAL_SERVER_DIR ?? '../../target/dist/geoportal-server');
const FILES = ['-f', resolve(DIST, 'compose.yaml'), '-f', resolve(import.meta.dirname, 'compose.test.yaml')];
const ADMIN = { user: 'admin', password: process.env.GEOPORTAL_ADMIN_PASSWORD ?? 'gp336-admin-pw' };
const GS_ADMIN = `gsadmin:${process.env.GEOSERVER_ADMIN_PASSWORD ?? 'gp336-gs-admin'}`;
const GS_DB = `geoserver:${process.env.GEOSERVER_DB_PASSWORD ?? 'gp336-gs-db'}`;
const READER = `geoserver_reader:${process.env.GEOSERVER_READER_PASSWORD ?? 'gp336reader'}`;
const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const MEASURE = `${EXTENSION}/data-requests`;

const run = Date.now().toString(36);
const ORG = `gw${run}`;
const OTHER = `ot${run}`;
const PASSWORD = 'Gateway-test-2026';
const person = (name: string, org: string) => ({ user: `${name}.${run}@${org}.test`, password: PASSWORD, name });
/** Uploads and publishes; an administrator of the organization, who reads every layer of it. */
const ada = person('ada', ORG);
/** A member of the organization with no share: GeoServer gives him nothing. */
const dan = person('dan', ORG);
/** A person of another organization. */
const eve = person('eve', OTHER);

type Person = typeof ada;
const basic = (p: { user: string; password: string }) => 'Basic ' + Buffer.from(`${p.user}:${p.password}`).toString('base64');

/** A call of the API of Geoportal through the gateway, as a person signed in to an organization. */
async function api(
  who: { user: string; password: string },
  path: string,
  init: RequestInit & { org?: string } = {},
): Promise<{ status: number; body: any }> {
  const headers = new Headers(init.headers);
  headers.set('authorization', basic(who));
  if (init.org) {
    headers.set('X-Mosaikit-Organization', init.org);
  }
  if (init.body && !headers.has('content-type')) {
    headers.set('content-type', 'application/json');
  }
  const response = await fetch(GW + path, { ...init, headers });
  const text = await response.text();
  let body: any = text;
  try {
    body = JSON.parse(text);
  } catch {
    // not JSON
  }
  return { status: response.status, body };
}

/** The session cookie of a person, as the shell gets it when signing in. */
async function session(who: Person): Promise<string> {
  const response = await fetch(`${GW}/api/v1/accounts/session`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: who.user, password: who.password }),
    redirect: 'manual',
  });
  expect(response.status, await response.clone().text()).toBe(200);
  const cookie = response.headers.getSetCookie().find((c) => c.startsWith('mosaikit-session='));
  expect(cookie).toBeTruthy();
  return cookie!.split(';')[0]!;
}

/** A data request through the gateway, with a cookie, headers of a client, or nothing. */
async function data(path: string, headers: Record<string, string> = {}): Promise<Response> {
  return fetch(GW + path, { headers, redirect: 'manual' });
}

function compose(...args: string[]): string {
  return execFileSync('docker', ['compose', '-p', PROJECT, ...FILES, ...args], { cwd: DIST, encoding: 'utf8' });
}

/** Waits until a condition holds, polling. */
async function until(what: string, check: () => Promise<boolean>, seconds = 60): Promise<number> {
  const start = Date.now();
  while (Date.now() - start < seconds * 1000) {
    if (await check()) {
      return (Date.now() - start) / 1000;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`${what}: not within ${seconds} s`);
}

const features = async (response: Response): Promise<number> => {
  expect(response.status, await response.clone().text()).toBe(200);
  return ((await response.json()) as { features: unknown[] }).features.length;
};

const STATIONS = JSON.stringify({
  type: 'FeatureCollection',
  features: [
    ['Centrale', 12.5016, 41.9009],
    ['Nord', 12.5309, 41.9105],
    ['Sud', 12.4848, 41.8722],
  ].map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
});
const LINE = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Linea' }, geometry: { type: 'LineString', coordinates: [[12.49, 41.89], [12.51, 41.9]] } }],
});
const RED = { fill: '#ff0000', fillOpacity: 1, stroke: '#000000', strokeWidth: 1, pointSize: 14, pointShape: 'square' };

const state: {
  orgId: string;
  workspace: string;
  dataset: string;
  otherDataset: string;
  serviceKey: string;
  personalKey: string;
  personalKeyId: string;
} = {} as never;

const LAYER = `stazioni_${run}`;
const OTHER_LAYER = `linea_${run}`;
const getMap = (layer: string) =>
  `service=WMS&version=1.1.1&request=GetMap&layers=${layer}&styles=&srs=EPSG:4326&bbox=12.47,41.86,12.54,41.92&width=256&height=256&format=image/png`;
const getFeature = (layer: string) =>
  `service=WFS&version=2.0.0&request=GetFeature&typeNames=${layer}&outputFormat=application/json`;

async function upload(name: string, content: string): Promise<string> {
  const created = await api(ada, `/api/v1/ext/catalog-geodata/uploads?${new URLSearchParams({ name, title: name.replace(/\..*$/, '') })}`, {
    method: 'POST',
    org: ORG,
    headers: { 'content-type': 'application/octet-stream' },
    body: content,
  });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const id = created.body.id as string;
  await until(`${name} loaded`, async () => {
    const d = await api(ada, `/api/v1/ext/catalog-geodata/uploads/${id}`, { org: ORG });
    expect(d.body.state, JSON.stringify(d.body)).not.toBe('failed');
    return d.body.state === 'loaded';
  }, 120);
  return id;
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.setTimeout(600_000);
  await until('Geoportal through the gateway', async () => (await fetch(`${GW}/api/v1/accounts/registration-options`).catch(() => null))?.status === 200, 300);
  for (const slug of [ORG, OTHER]) {
    const made = await api(ADMIN, '/api/v1/organizations', { method: 'POST', body: JSON.stringify({ slug, name: `Gateway ${slug}`, selfRegistration: true }) });
    expect(made.status, JSON.stringify(made.body)).toBe(201);
  }
  for (const [p, org] of [[ada, ORG], [dan, ORG], [eve, OTHER]] as const) {
    const r = await fetch(`${GW}/api/v1/accounts/registrations`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ organization: org, email: p.user, displayName: p.name, password: p.password }),
    });
    expect(r.status, await r.text()).toBe(201);
  }
  // Ada administers the organization: she holds every permission of the catalog.
  expect((await api(ADMIN, `/api/v1/organizations/${ORG}/members/${ada.user}`, { method: 'PUT', body: JSON.stringify({ roles: ['organization-admin', 'organization-user'] }) })).status).toBe(200);
  const orgs = (await api(ADMIN, '/api/v1/organizations')).body as { id: string; slug: string }[];
  state.orgId = orgs.find((o) => o.slug === ORG)!.id;
  state.workspace = 'org_' + state.orgId.replaceAll('-', '');
  for (const [name, value] of [['geoserver', GS_ADMIN], ['geodata', GS_DB], ['published-views', READER]]) {
    const stored = await api(ada, `/api/v1/organizations/${ORG}/secrets`, { method: 'POST', org: ORG, body: JSON.stringify({ name, kind: 'password', value, description: `GP-336 ${name}`, extension: EXTENSION }) });
    expect(stored.status, JSON.stringify(stored.body)).toBeLessThan(300);
  }
  // The data: two layers of the organization.
  state.dataset = await upload(`stazioni-${run}.geojson`, STATIONS);
  state.otherDataset = await upload(`linea-${run}.geojson`, LINE);
  for (const [dataset, layer] of [[state.dataset, LAYER], [state.otherDataset, OTHER_LAYER]]) {
    const published = await api(ada, '/api/v1/ext/catalog-geodata/publications', { method: 'POST', org: ORG, body: JSON.stringify({ dataset, layer, style: RED }) });
    expect(published.status, JSON.stringify(published.body)).toBe(201);
  }
  // The GeoFence rules of the organization, generated by the catalog from sharing (GP-335).
  const rules = await api(ada, '/api/v1/ext/catalog-geodata/geoserver/rules', { method: 'POST', org: ORG });
  expect(rules.status, JSON.stringify(rules.body)).toBeLessThan(300);
  // The service identity of the gateway, and the gateway restarted with its key (as README.md says).
  const identity = await api(ADMIN, '/api/v1/platform/service-identities', {
    method: 'POST',
    body: JSON.stringify({ name: `gateway-${run}`, description: 'GP-336 test', apis: ['catalog-geodata'], endpoints: ['check-key'] }),
  });
  expect(identity.status, JSON.stringify(identity.body)).toBe(201);
  state.serviceKey = identity.body.keys?.[0]?.secret ?? identity.body.secret;
  expect(state.serviceKey).toMatch(/^mks_/);
  execFileSync('docker', ['compose', '-p', PROJECT, ...FILES, 'up', '-d', '--no-deps', 'gateway'], {
    cwd: DIST,
    env: { ...process.env, GATEWAY_SERVICE_KEY: state.serviceKey },
    stdio: 'ignore',
  });
  await until('the gateway again', async () => (await fetch(`${GW}/_gw_health`).catch(() => null))?.status === 204, 60);
  // Ada's personal key, as she makes it in her settings.
  const key = await api(ada, '/api/v1/accounts/me/access-keys', { method: 'POST', org: ORG, body: JSON.stringify({ name: 'QGIS', organization: ORG }) });
  expect(key.status, JSON.stringify(key.body)).toBe(201);
  state.personalKey = key.body.secret;
  state.personalKeyId = key.body.id ?? key.body.key?.id;
});

test('GP-336 a browser with the session reads WMS and WFS through the gateway, and sees its addresses in the catalog', async ({ browser }) => {
  const context = await signedIn(browser, ada);
  const page = await context.newPage();
  try {
    // A map image as MapLibre asks for it: the page sends the session cookie by itself.
    const map = await page.goto(`${GW}/gw/ows/${ORG}/${LAYER}/wms?${getMap(LAYER)}`);
    expect(map?.status()).toBe(200);
    expect(map?.headers()['content-type']).toContain('image/png');
    const wfs = await page.request.get(`${GW}/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`);
    expect(wfs.status(), await wfs.text()).toBe(200);
    expect(((await wfs.json()) as { features: unknown[] }).features).toHaveLength(3);
    // The capabilities name the gateway, never GeoServer.
    const caps = await (await page.request.get(`${GW}/gw/ows/${ORG}/wms?service=WMS&request=GetCapabilities`)).text();
    expect(caps).toContain(LAYER);
    expect(caps).toContain(`${GW}/gw/ows/${ORG}/${state.workspace}/ows`);
    expect(caps).not.toMatch(/geoserver:8080|gateway:8480/);
    // The catalog lists the services of the layer at the addresses of the gateway.
    await page.goto(`${GW}/app/geodata`);
    const row = page.locator('catalog-publications').getByText(LAYER, { exact: true });
    await row.click();
    const urls = page.locator('catalog-publications ul.urls code');
    await expect(urls.first()).toHaveText(`${GW}/gw/ows/${ORG}/${LAYER}/wms?service=WMS&request=GetCapabilities`);
    await expect(page.locator('catalog-publications p.desktop')).toBeVisible();
    await page.screenshot({ path: 'test-results/gp336-catalog.png', fullPage: true });
    // And a tile in a page of Geoportal loads like any image, with the cookie the browser sends by itself.
    const width = await page.evaluate(async (src) => {
      const img = new Image();
      img.src = src;
      await img.decode();
      return img.naturalWidth;
    }, `/gw/ows/${ORG}/${LAYER}/wms?${getMap(LAYER)}`);
    expect(width).toBe(256);
  } finally {
    await context.close();
  }
});

test('GP-336 without a session, with a forged user header or for another organization the gateway refuses', async () => {
  const cookie = await session(ada);
  const path = `/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`;
  expect(await features(await data(path, { cookie }))).toBe(3);
  // No session; a forged name header; a cookie that is not a session.
  expect((await data(path)).status).toBe(401);
  expect((await data(path, { 'sec-username': ada.user })).status).toBe(401);
  expect((await data(path, { cookie: 'mosaikit-session=forged', 'sec-username': ada.user })).status).toBe(401);
  // A member with no share who names ada: the gateway overwrites the header, GeoServer gives him nothing.
  const danCookie = await session(dan);
  const forged = await data(path, { cookie: danCookie, 'sec-username': ada.user });
  const forgedText = await forged.text();
  // GeoServer answers as for dan: the layer is unknown to him.
  expect(forgedText).not.toContain('Centrale');
  expect(forgedText).toMatch(/unknown|not find|No such/i);
  // A person of another organization: refused by Geoportal for this organization.
  const eveCookie = await session(eve);
  const refused = await data(path, { cookie: eveCookie });
  expect(refused.status).toBe(403);
  // Signing out ends the session for the gateway too, within the ten seconds of its go-ahead.
  const signedOut = await fetch(`${GW}/api/v1/accounts/session`, { method: 'DELETE', headers: { cookie } });
  expect(signedOut.status).toBeLessThan(300);
  const seconds = await until('the cookie refused after signing out', async () => (await data(path, { cookie })).status === 401, 20);
  expect(seconds).toBeLessThanOrEqual(12);
  // Only reading passes, and nothing by reference.
  const again = await session(ada);
  expect((await data(`/gw/ows/${ORG}/wfs?service=WFS&request=Transaction`, { cookie: again })).status).toBe(400);
  expect((await fetch(`${GW}/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`, { method: 'POST', headers: { cookie: again } })).status).toBe(403);
  expect((await data(`/gw/ows/${ORG}/wms?${getMap(LAYER)}&sld=http://example.org/x.sld`, { cookie: again })).status).toBe(400);
});

test('GP-336 GeoServer is not reachable from outside: no port, and Tomcat lets in only the gateway', async () => {
  // No published port: compose has none to tell.
  let published = '';
  try {
    published = compose('port', 'geoserver', '8080').trim();
  } catch (e) {
    expect(String(e)).toContain('no port 8080');
  }
  expect(published).toBe('');
  // A container on the network GeoServer shares with PostgreSQL, naming ada: Tomcat refuses before GeoServer.
  const network = `${PROJECT}_geodata`;
  const status = execFileSync(
    'docker',
    ['run', '--rm', '--network', network, 'curlimages/curl:8.16.0', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-H', `sec-username: ${ada.user}`,
      `http://geoserver:8080/geoserver/${state.workspace}/wfs?${getFeature(LAYER)}`],
    { encoding: 'utf8' },
  ).trim();
  expect(status).toBe('403');
  // The administration door (this computer only) strips the header: without the password nothing.
  const admin = await fetch(`${GS_ADMIN_DOOR}/${state.workspace}/wfs?${getFeature(LAYER)}`, { headers: { 'sec-username': ada.user } });
  expect(await admin.text()).not.toContain('Centrale');
  const rest = await fetch(`${GS_ADMIN_DOOR}/rest/workspaces.json`, { headers: { 'sec-username': 'gsadmin' } });
  expect(rest.status).toBe(401);
});

test('GP-336 QGIS with a personal key loads the layers of its owner; an unknown or revoked key is refused', async () => {
  const key = state.personalKey;
  // GetCapabilities as QGIS asks for it: the layer, with addresses of the gateway that keep the key.
  const caps = await data(`/gw/ows/${ORG}/wms?service=WMS&request=GetCapabilities&authkey=${key}`);
  expect(caps.status).toBe(200);
  const text = await caps.text();
  expect(text).toContain(`<Name>${LAYER}</Name>`);
  const online = /<OnlineResource[^>]*xlink:href="([^"]+)"/.exec(text.split('<GetMap>')[1] ?? '')?.[1]?.replaceAll('&amp;', '&');
  expect(online).toContain(`${GW}/gw/ows/${ORG}/`);
  expect(online).toContain(`authkey=${key}`);
  // QGIS follows that address for the map and for the features.
  const map = await fetch(`${online}${online!.endsWith('&') || online!.endsWith('?') ? '' : '&'}${getMap(LAYER)}`);
  expect(map.status).toBe(200);
  expect(map.headers.get('content-type')).toContain('image/png');
  expect(await features(await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}&authkey=${key}`))).toBe(3);
  // The key of dan sees nothing: GeoServer's AuthKey filter named him, not the gateway.
  const danKey = await api(dan, '/api/v1/accounts/me/access-keys', { method: 'POST', org: ORG, body: JSON.stringify({ name: 'QGIS', organization: ORG }) });
  expect(danKey.status).toBe(201);
  const danData = await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}&authkey=${danKey.body.secret}`);
  expect(await danData.text()).not.toContain('Centrale');
  // Unknown key.
  expect((await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}&authkey=mk_unknownunknownunknown`)).status).toBe(401);
  // A key of ada for another organization's address.
  expect((await data(`/gw/ows/${OTHER}/wfs?${getFeature(LAYER)}&authkey=${key}`)).status).toBe(403);
  // Revoked: refused at the next go-ahead, within ten seconds (GeoServer's own cache is 60 s, ADR-0016).
  expect((await api(ada, `/api/v1/accounts/me/access-keys/${state.personalKeyId}`, { method: 'DELETE', org: ORG })).status).toBeLessThan(300);
  const seconds = await until('the revoked key refused', async () => (await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}&authkey=${key}`)).status === 401, 30);
  expect(seconds).toBeLessThanOrEqual(12);
});

test('GP-336 a link opens only the layer of its dataset, also in the page of the link', async ({ browser }) => {
  const made = await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/links`, {
    method: 'POST',
    org: ORG,
    body: JSON.stringify({ level: 'view', expires: new Date(Date.now() + 86_400_000).toISOString() }),
  });
  expect(made.status, JSON.stringify(made.body)).toBe(201);
  const secret = made.body.token as string;
  const base = `/gw/link/${secret}/${state.dataset}`;
  // Without an account: the map and the features of that layer.
  const map = await data(`${base}/wms?${getMap(LAYER)}`);
  expect(map.status, await map.clone().text()).toBe(200);
  expect(map.headers.get('content-type')).toContain('image/png');
  // The features need the level download, which a link does not give (GP-335).
  expect((await data(`${base}/wfs?${getFeature(LAYER)}`)).status).toBe(403);
  const caps = await (await data(`${base}/wms?service=WMS&request=GetCapabilities`)).text();
  expect(caps).toContain(LAYER);
  expect(caps).not.toContain(OTHER_LAYER);
  expect(caps).toContain(`${GW}${base}`);
  // Not the other layer of the organization, not another dataset, not writing, not with a bad secret.
  expect((await data(`${base}/wms?${getMap(OTHER_LAYER)}`)).status).toBe(400);
  expect([400, 403]).toContain((await data(`${base}/wfs?${getFeature(OTHER_LAYER)}`)).status);
  expect([401, 403, 404]).toContain((await data(`/gw/link/${secret}/${state.otherDataset}/wms?${getMap(OTHER_LAYER)}`)).status);
  expect([400, 403]).toContain((await data(`${base}/wfs?service=WFS&request=Transaction`)).status);
  expect((await data(`/gw/link/${'x'.repeat(43)}/${state.dataset}/wms?${getMap(LAYER)}`)).status).toBe(401);
  // The page of the link, as the person who received it sees it: title, preview and addresses.
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${GW}/shared/${secret}`);
    const view = page.locator('catalog-shared-dataset');
    await expect(view.locator('h2')).toHaveText(`stazioni-${run}`, { timeout: 30_000 });
    const preview = view.locator('figure img');
    await expect(preview).toBeVisible();
    await expect.poll(async () => preview.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth), { timeout: 20_000 }).toBeGreaterThan(0);
    await expect(view.locator('ul code').first()).toHaveText(`${GW}${base}/wms?service=WMS&request=GetCapabilities`);
    await page.screenshot({ path: 'test-results/gp336-link.png', fullPage: true });
  } finally {
    await context.close();
  }
  // Revoked, the link opens nothing.
  expect((await api(ada, `/api/v1/sharing/grants/${made.body.grant.id}`, { method: 'DELETE', org: ORG })).status).toBeLessThan(300);
  expect([401, 404]).toContain((await data(`${base}/wms?${getMap(LAYER)}`)).status);
});

test('GP-335 GP-308 data rules hold the same for a person through the gateway and through AuthKey (QGIS)', async () => {
  // Dan, a member with no share until now, gets the stations at download, then two data rules: one keeps
  // the stations other than Sud, the other keeps an area around Centrale and hides the name.
  const danId = ((await api(dan, '/api/v1/accounts/me', { org: ORG })).body as { id: string }).id;
  const grant = await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/grants`, {
    method: 'PUT',
    org: ORG,
    body: JSON.stringify({ subject: { kind: 'person', id: danId }, level: 'read' }),
  });
  expect(grant.status, JSON.stringify(grant.body)).toBeLessThan(300);
  const rule = async (body: object): Promise<string> => {
    const made = await api(ada, '/api/v1/ext/catalog-geodata/data-rules', {
      method: 'POST',
      org: ORG,
      body: JSON.stringify({ layer: LAYER, subject: { kind: 'person', email: dan.user }, ...body }),
    });
    expect(made.status, JSON.stringify(made.body)).toBe(201);
    expect(made.body.geofence.outcome).toBe('written');
    return made.body.rule.id as string;
  };
  const rows = await rule({ cql: "nome <> 'Sud'" });
  const area = await rule({ area: 'POLYGON((12.49 41.89, 12.51 41.89, 12.51 41.91, 12.49 41.91, 12.49 41.89))', hidden: ['nome'] });
  try {
    type Collection = { features: { id: string; properties: Record<string, unknown>; geometry: { coordinates: number[] } }[] };
    const read = async (response: Response): Promise<Collection> => {
      expect(response.status, await response.clone().text()).toBe(200);
      return (await response.json()) as Collection;
    };
    // Through the gateway, with the session: only Centrale (both rules), without its name.
    const cookie = await session(dan);
    const browser = await read(await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`, { cookie }));
    expect(browser.features).toHaveLength(1);
    expect(browser.features[0]!.properties).not.toHaveProperty('nome');
    expect(browser.features[0]!.geometry.coordinates[0]).toBeCloseTo(12.5016, 3);
    // Through AuthKey, as QGIS: the same feature, fid by fid.
    const key = await api(dan, '/api/v1/accounts/me/access-keys', { method: 'POST', org: ORG, body: JSON.stringify({ name: 'QGIS dati', organization: ORG }) });
    expect(key.status).toBe(201);
    const qgis = await read(await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}&authkey=${key.body.secret}`));
    expect(qgis.features.map((f) => f.id)).toEqual(browser.features.map((f) => f.id));
    expect(qgis.features[0]!.properties).not.toHaveProperty('nome');
    // Ada, an administrator without rules, still reads the three stations with their names.
    const all = await read(await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`, { cookie: await session(ada) }));
    expect(all.features).toHaveLength(3);
    expect(all.features[0]!.properties).toHaveProperty('nome');
  } finally {
    for (const id of [rows, area]) {
      await api(ada, `/api/v1/ext/catalog-geodata/data-rules/${id}`, { method: 'DELETE', org: ORG });
    }
    const grants = (await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/grants`, { org: ORG })).body as { id: string; subject: { id?: string } }[];
    for (const g of grants.filter((x) => x.subject?.id === danId)) {
      await api(ada, `/api/v1/sharing/grants/${g.id}`, { method: 'DELETE', org: ORG });
    }
  }
});

test('GP-936 a dataset shared at view lets the person see its layer through GeoServer, and revoked GeoServer refuses it', async () => {
  // Dan, a member with no share: GeoServer, which reads the GeoFence rules of the catalog, does not know the layer for him.
  const danId = ((await api(dan, '/api/v1/accounts/me', { org: ORG })).body as { id: string }).id;
  const cookie = await session(dan);
  const map = () => data(`/gw/ows/${ORG}/${LAYER}/wms?${getMap(LAYER)}`, { cookie });
  const refusedByGeoServer = async (response: Response): Promise<void> => {
    expect(response.headers.get('content-type') ?? '').not.toContain('image/png');
    expect(await response.text()).toMatch(/LayerNotDefined|not find|unknown|No such|denied/i);
  };
  // The rules as the sharing is now: the test above unshared without waiting for the next rewrite.
  expect((await api(ada, '/api/v1/ext/catalog-geodata/geoserver/rules', { method: 'POST', org: ORG })).status).toBeLessThan(300);
  await refusedByGeoServer(await map());

  const grant = await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/grants`, {
    method: 'PUT',
    org: ORG,
    body: JSON.stringify({ subject: { kind: 'person', id: danId }, level: 'view' }),
  });
  expect(grant.status, JSON.stringify(grant.body)).toBeLessThan(300);
  try {
    // The share becomes a rule (GP-335): at the next rewrite, here asked for at once.
    expect((await api(ada, '/api/v1/ext/catalog-geodata/geoserver/rules', { method: 'POST', org: ORG })).status).toBeLessThan(300);
    const granted = await map();
    expect(granted.status, await granted.clone().text()).toBe(200);
    expect(granted.headers.get('content-type')).toContain('image/png');
    // View is the map, not the features.
    expect(await (await data(`/gw/ows/${ORG}/wfs?${getFeature(LAYER)}`, { cookie })).text()).not.toContain('Centrale');
  } finally {
    const grants = (await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/grants`, { org: ORG })).body as { id: string; subject: { id?: string } }[];
    for (const g of grants.filter((x) => x.subject?.id === danId)) {
      expect((await api(ada, `/api/v1/sharing/grants/${g.id}`, { method: 'DELETE', org: ORG })).status).toBeLessThan(300);
    }
  }
  // Revoked: the rule goes at the next rewrite, and GeoServer refuses the layer again.
  expect((await api(ada, '/api/v1/ext/catalog-geodata/geoserver/rules', { method: 'POST', org: ORG })).status).toBeLessThan(300);
  await refusedByGeoServer(await map());
});

test('GP-336 requests beyond the quota of the organization are refused', async () => {
  const cookie = await session(ada);
  const path = `/gw/ows/${ORG}/${LAYER}/wms?${getMap(LAYER)}`;
  // A few requests, counted at the next go-ahead.
  for (let i = 0; i < 5; i++) {
    expect((await data(path, { cookie })).status).toBe(200);
  }
  const before = (await api(ADMIN, `/api/v1/organizations/${ORG}/quotas/${MEASURE}`)).body as { used: number; unit: string };
  expect(before.unit).toBe('requests');
  // The platform administrator sets the limit just above what was used.
  const limit = before.used + 3;
  expect((await api(ADMIN, `/api/v1/organizations/${ORG}/quotas/${MEASURE}`, { method: 'PUT', body: JSON.stringify({ limit }) })).status).toBe(200);
  // Within the ten seconds of the go-ahead and the requests after them, the gateway refuses.
  let refusal: Response | undefined;
  await until('a request refused for the quota', async () => {
    const r = await data(path, { cookie });
    if (r.status === 403) {
      refusal = r;
      return true;
    }
    expect(r.status).toBe(200);
    await new Promise((resolve) => setTimeout(resolve, 500));
    return false;
  }, 40);
  expect(((await refusal!.json()) as { detail: string }).detail).toMatch(/quota/i);
  const after = (await api(ADMIN, `/api/v1/organizations/${ORG}/quotas/${MEASURE}`)).body as { used: number };
  expect(after.used).toBeGreaterThanOrEqual(limit);
  // The gate of a link of the same organization refuses too.
  const made = await api(ada, `/api/v1/sharing/resources/${EXTENSION}/dataset/${state.dataset}/links`, {
    method: 'POST',
    org: ORG,
    body: JSON.stringify({ level: 'view', expires: new Date(Date.now() + 86_400_000).toISOString() }),
  });
  expect((await data(`/gw/link/${made.body.token}/${state.dataset}/wms?${getMap(LAYER)}`)).status).toBe(403);
  // Raised again, the data flow.
  expect((await api(ADMIN, `/api/v1/organizations/${ORG}/quotas/${MEASURE}`, { method: 'PUT', body: JSON.stringify({ limit: null }) })).status).toBe(200);
  await until('the data again', async () => (await data(path, { cookie })).status === 200, 20);
});

async function signedIn(browser: Browser, who: Person) {
  const context = await browser.newContext({ baseURL: GW });
  const response = await context.request.post('/api/v1/accounts/session', { form: { username: who.user, password: who.password } });
  expect(response.status()).toBe(200);
  return context;
}

test('issue #40 through the gateway the largest upload passes and one byte more is refused with the limit', async () => {
  // GEOPORTAL_MAX_UPLOAD of test.env: 2M for the gateway and both limits of the kernel.
  const LIMIT = 2 * 1024 * 1024;
  const space = await api(ada, '/api/v1/ext/catalog-geodata/uploads/space', { org: ORG });
  expect(space.body.maxFile).toBe(LIMIT);
  const send = (size: number) =>
    api(ada, `/api/v1/ext/catalog-geodata/uploads?${new URLSearchParams({ name: `limite_${run}.geojson` })}`, {
      method: 'POST',
      org: ORG,
      headers: { 'content-type': 'application/octet-stream' },
      body: STATIONS.padEnd(size, ' '),
    });
  const largest = await send(LIMIT);
  expect(largest.status, JSON.stringify(largest.body)).toBe(201);
  expect(largest.body.size).toBe(LIMIT);
  await api(ada, `/api/v1/ext/catalog-geodata/uploads/${largest.body.id}`, { method: 'DELETE', org: ORG });

  const larger = await send(LIMIT + 1);
  expect(larger.status).toBe(413);
  expect(larger.body).toMatchObject({ status: 413, detail: 'The request is larger than the 2M this installation accepts.' });
});
