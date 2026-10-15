// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import { MANAGER } from './people.js';
import { errorPage, param, redirectTo, strictWfs, stub, wms, type Stub } from './stubs.js';

/**
 * GP-939: the proxy of external services. The stub servers listen on 127.0.0.1, which the bench
 * allows (geoportal.catalog.proxy.allowed-networks); the tests look at what the stub received, so
 * they prove what reached the server, not what Geoportal believes it sent.
 */

const ORG = () => bench().organization.slug;
const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const owner = () => member(0);
const colleague = () => member(1);
const manager = () => member(2);

interface Service {
  id: string;
  title: string;
  kind: string;
  url: string;
  proxy: boolean;
  crs: string | null;
  credential: string | null;
  authScheme: string | null;
  manage: boolean;
  proxyUrl: string;
}

const stubs: Stub[] = [];

async function started(handler: Parameters<typeof stub>[0]): Promise<Stub> {
  const s = await stub(handler);
  stubs.push(s);
  return s;
}

async function register(person = owner(), body: Record<string, unknown>): Promise<{ status: number; body: unknown }> {
  return api(person, '/api/v1/ext/catalog-geodata/services', { method: 'POST', body: JSON.stringify(body) });
}

async function registered(body: Record<string, unknown>, person = owner()): Promise<Service> {
  const { status, body: service } = await register(person, { title: unique('Servizio'), ...body });
  expect(status, JSON.stringify(service)).toBe(201);
  return service as Service;
}

type Person = ReturnType<typeof member>;

/** The account identifier of a person, which the sharing of Mosaikit names subjects by. */
async function accountOf(person: Person): Promise<string> {
  const { status, body } = await api(person, '/api/v1/accounts/me');
  expect(status).toBe(200);
  return (body as { id: string }).id;
}

async function share(person: Person, service: Service, to: Person, level: string): Promise<void> {
  const { status, body } = await api(person, `/api/v1/sharing/resources/${EXTENSION}/service/${service.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(to) }, level }),
  });
  expect(status, JSON.stringify(body)).toBeLessThan(300);
}

async function auditActions(prefix: string): Promise<{ action: string; outcome: string; subject: string | null }[]> {
  const { status, body } = await api(
    bench().admin,
    `/api/v1/organizations/${ORG()}/audit-events?action=${encodeURIComponent(prefix)}&limit=500`,
  );
  expect(status).toBe(200);
  const events = (Array.isArray(body) ? body : ((body as { items?: unknown[] }).items ?? [])) as {
    action: string;
    outcome: string;
    subject: string | null;
  }[];
  return events;
}

test.beforeAll(async () => {
  // The third person administers the organization: they grant permissions and keep credentials.
  const promoted = await api(bench().admin, `/api/v1/organizations/${ORG()}/members/${MANAGER.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-user', 'organization-admin'] }),
  });
  expect(promoted.status, JSON.stringify(promoted.body)).toBeLessThan(300);
  // The first person may register services; the second may not.
  const granted = await api(
    manager(),
    `/api/v1/organizations/${ORG()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:register-service`,
    { method: 'PUT' },
  );
  expect(granted.status, JSON.stringify(granted.body)).toBeLessThan(300);
});

test.afterAll(async () => {
  await Promise.all(stubs.map((s) => s.close()));
});

test('GP-939 a WMS without CORS headers, with the proxy on, returns its tiles to the map through the proxy address', async ({ page }) => {
  const server = await started(wms({ cors: false }));
  const direct: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith(server.url)) {
      direct.push(request.url());
    }
  });
  await signIn(page, owner());
  await openApp(page, 'Geodata');
  const services = page.locator('catalog-services');
  await services.getByRole('button', { name: 'Register a service' }).click();
  const title = unique('Carta di base');
  await services.getByLabel('Title').fill(title);
  await services.getByLabel('Kind').selectOption('wms');
  await services.getByLabel('Address').fill(`${server.url}/geoserver/ows`);
  await services.getByLabel('Use the proxy of Geoportal').check();
  await services.getByRole('button', { name: 'Save' }).click();

  const detail = services.getByRole('region', { name: title });
  await expect(detail).toContainText('Through the proxy of Geoportal');
  await expect(detail.getByRole('list', { name: 'Layers' }).getByRole('button', { name: 'Strade' })).toBeVisible();
  const tile = detail.getByRole('img', { name: 'Preview of the layer Strade' });
  await expect(tile).toBeVisible();
  // The image arrived and was decoded: a 512 × 256 PNG of the stub, through the address of Geoportal.
  await expect.poll(() => tile.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(512);
  expect(await tile.getAttribute('src')).toMatch(/^\/api\/v1\/ext\/catalog\/services\/[0-9a-f-]{36}\/proxy\?/);

  const maps = server.received.filter((r) => param(r, 'request') === 'GetMap');
  expect(maps.length).toBeGreaterThan(0);
  expect(param(maps[0]!, 'layers')).toBe('demo:roads');
  // Nothing of the person reached the other server, and the browser never called it.
  for (const request of server.received) {
    expect(request.headers.cookie).toBeUndefined();
    expect(request.headers.authorization).toBeUndefined();
  }
  expect(direct).toEqual([]);
});

test('GP-939 the proxy forwards only to the address registered for that source and refuses any other target', async () => {
  const server = await started(wms({ cors: false }));
  const elsewhere = await started(wms({ cors: false }));
  const service = await registered({ kind: 'wms', url: `${server.url}/ows?map=base.map`, proxy: true });
  const proxy = service.proxyUrl;

  // A query that names another server is only a query: it goes to the registered one.
  const answer = await api(owner(), `${proxy}?url=${encodeURIComponent(elsewhere.url)}&SERVICE=WMS&REQUEST=GetCapabilities&map=/etc/other.map`);
  expect(answer.status).toBe(200);
  expect(param(server.received.at(-1)!, 'map')).toBe('base.map');
  // Paths that would leave the registered address never reach a server: refused by the proxy, or
  // normalized by the HTTP layer of Geoportal before any route of the proxy sees them.
  const reached = server.received.length;
  for (const path of ['..%2F..%2Fadmin', `http:%2F%2F127.0.0.1:${new URL(elsewhere.url).port}%2F`, '%2e%2e', 'a%5c..%5cb', 'a/%2e%2e/%2e%2e/x']) {
    const refused = await api(owner(), `${proxy}/${path}`);
    expect(refused.status, path).not.toBe(502);
    expect(JSON.stringify(refused.body), path).not.toContain('WMS_Capabilities');
  }
  expect(server.received.length).toBe(reached);
  expect(elsewhere.received).toEqual([]);

  // A service registered on the metadata address, or not on http(s), is refused, and the refusal is audited.
  for (const url of ['http://169.254.169.254/latest/meta-data/', 'file:///etc/passwd', 'http://user:pw@example.org/']) {
    const { status } = await register(owner(), { title: unique('Metadati'), kind: 'wms', url });
    expect(status, url).toBe(400);
  }
  expect((await auditActions(`${EXTENSION}:service.refused`)).some((e) => e.outcome === 'DENIED')).toBe(true);

  // A registered server that redirects to the metadata address gets nowhere.
  const redirecting = await started(redirectTo('http://169.254.169.254/latest/meta-data/iam/security-credentials/'));
  const tricked = await registered({ kind: 'wms', url: `${redirecting.url}/ows`, proxy: true });
  const followed = await api(owner(), `${tricked.proxyUrl}?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(followed.status).toBe(502);
  expect(JSON.stringify(followed.body)).toContain('outside its registered address');

  // An address of the internal network that the installation does not allow is refused.
  const { status } = await register(owner(), { title: unique('Intranet'), kind: 'wms', url: 'http://10.1.2.3/wms' });
  expect(status).toBe(400);

  // Only GET, HEAD and the OGC POSTs pass.
  const before = server.received.length;
  for (const method of ['PUT', 'DELETE', 'PATCH']) {
    const refused = await api(owner(), proxy, { method, body: '{}' });
    expect([404, 405], method).toContain(refused.status);
  }
  expect(server.received.length).toBe(before);
  const json = await api(owner(), proxy, { method: 'POST', body: '{}' });
  expect(json.status).toBe(415);
  const xml = await api(owner(), proxy, {
    method: 'POST',
    headers: { 'content-type': 'text/xml' },
    body: '<GetCapabilities service="WMS"/>',
  });
  expect(xml.status).toBe(200);
  expect(server.received.at(-1)!.method).toBe('POST');
});

test('GP-939 only people allowed to use the source can call its proxy; others receive 403', async () => {
  const server = await started(wms({ cors: false }));
  const service = await registered({ kind: 'wms', url: `${server.url}/ows`, proxy: true });
  const capabilities = `${service.proxyUrl}?SERVICE=WMS&REQUEST=GetCapabilities`;

  expect((await api(colleague(), capabilities)).status).toBe(403);
  await share(owner(), service, colleague(), 'view');
  expect((await api(colleague(), capabilities)).status).toBe(403);
  await share(owner(), service, colleague(), 'use');
  const allowed = await api(colleague(), capabilities);
  expect(allowed.status).toBe(200);
  expect(String(allowed.body)).toContain('WMS_Capabilities');
  // The administrators of the organization hold every level.
  expect((await api(manager(), capabilities)).status).toBe(200);
  // Those who use it cannot change it.
  const change = await api(colleague(), `/api/v1/ext/catalog-geodata/services/${service.id}`, {
    method: 'PUT',
    body: JSON.stringify({ ...service, url: 'https://example.org/wms' }),
  });
  expect(change.status).toBe(403);
  // People without the permission cannot register services.
  expect((await register(colleague(), { title: unique('Altro'), kind: 'wms', url: `${server.url}/ows` })).status).toBe(403);

  // With the proxy off, those who use the service cannot call it; those who manage it can, to check it.
  const off = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`, {
    method: 'PUT',
    body: JSON.stringify({ ...service, proxy: false }),
  });
  expect(off.status).toBe(200);
  expect((await api(colleague(), capabilities)).status).toBe(409);
  expect((await api(owner(), capabilities)).status).toBe(200);
});

test('GP-939 for a feature service behind the proxy the reference system is declared and the negotiation that the server would block is skipped', async () => {
  const server = await started(strictWfs());
  const service = await registered({ kind: 'wfs', url: `${server.url}/geoserver/wfs`, proxy: true, crs: 'EPSG:3857' });
  expect(service.crs).toBe('EPSG:3857');

  // The negotiation is what this server blocks.
  expect((await api(owner(), `${service.proxyUrl}?SERVICE=WFS&REQUEST=GetCapabilities`)).status).toBe(403);
  server.received.length = 0;

  const features = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}/features?typeName=demo:poi&bbox=12,41,13,42&count=50`);
  expect(features.status).toBe(200);
  expect((features.body as { features: unknown[] }).features).toHaveLength(1);
  // One request only, GetFeature, with the declared reference system: no capabilities, no description.
  expect(server.received).toHaveLength(1);
  const sent = server.received[0]!;
  expect(param(sent, 'request')).toBe('GetFeature');
  expect(param(sent, 'srsName')).toBe('EPSG:3857');
  expect(param(sent, 'typeName')).toBe('demo:poi');
  expect(param(sent, 'bbox')).toBe('12,41,13,42,EPSG:3857');
  expect(param(sent, 'outputFormat')).toBe('application/json');

  // A GetFeature of the map through the proxy gets the reference system too.
  const plain = await api(owner(), `${service.proxyUrl}?service=WFS&version=1.1.0&request=GetFeature&typeName=demo:poi`);
  expect(plain.status).toBe(200);
  expect(param(server.received.at(-1)!, 'srsName')).toBe('EPSG:3857');
});

test('GP-939 the credential of a service comes from Mosaikit and never travels to or from the browser', async () => {
  const server = await started(wms({ cors: false }));
  const name = `stub-token-${Date.now().toString(36)}`;
  const value = 'proxy-test-token-0123456789';
  const stored = await api(manager(), `/api/v1/organizations/${ORG()}/secrets`, {
    method: 'POST',
    body: JSON.stringify({ name, kind: 'token', value, description: 'Stub WMS', extension: EXTENSION }),
  });
  expect(stored.status, JSON.stringify(stored.body)).toBeLessThan(300);

  // Without the permission service-credentials, nobody sends a credential of the organization to a server of their choice.
  const denied = await register(owner(), {
    title: unique('Con chiave'), kind: 'wms', url: `${server.url}/ows`, proxy: true, credential: name, authScheme: 'bearer',
  });
  expect(denied.status).toBe(403);

  const service = await registered(
    { kind: 'wms', url: `${server.url}/ows`, proxy: true, credential: name, authScheme: 'bearer' },
    manager(),
  );
  expect(JSON.stringify(service)).not.toContain(value);
  await share(manager(), service, owner(), 'use');
  const answer = await api(owner(), `${service.proxyUrl}?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(answer.status).toBe(200);
  const sent = server.received.at(-1)!;
  expect(sent.headers.authorization).toBe(`Bearer ${value}`);
  expect(sent.headers.cookie).toBeUndefined();
  // The person's own view of the service names the credential, never its value.
  const seen = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`);
  expect(JSON.stringify(seen.body)).not.toContain(value);

  // Moving a service with a credential to another address needs the permission too.
  await share(manager(), service, owner(), 'manage');
  const moved = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`, {
    method: 'PUT',
    body: JSON.stringify({ ...service, url: 'https://attacker.example.org/collect' }),
  });
  expect(moved.status).toBe(403);
});

test('GP-939 changes of a service are audited, the calls through its proxy are not', async () => {
  const server = await started(wms({ cors: false }));
  const service = await registered({ kind: 'wms', url: `${server.url}/ows`, proxy: false });
  const on = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`, {
    method: 'PUT',
    body: JSON.stringify({ ...service, proxy: true }),
  });
  expect(on.status).toBe(200);
  const before = await auditActions(`${EXTENSION}:`);
  const mine = before.filter((e) => e.subject === `service/${service.id}`).map((e) => e.action);
  expect(mine).toEqual(expect.arrayContaining([`${EXTENSION}:service.registered`, `${EXTENSION}:service.changed`]));
  for (let i = 0; i < 5; i++) {
    expect((await api(owner(), `${service.proxyUrl}?SERVICE=WMS&REQUEST=GetMap&LAYERS=demo:roads&WIDTH=4&HEIGHT=4`)).status).toBe(200);
  }
  expect((await auditActions(`${EXTENSION}:`)).length).toBe(before.length);

  const removed = await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`, { method: 'DELETE' });
  expect(removed.status).toBe(204);
  expect((await auditActions(`${EXTENSION}:service.removed`)).some((e) => e.subject === `service/${service.id}`)).toBe(true);
  expect((await api(owner(), service.proxyUrl)).status).toBe(404);
});

test('GP-939 an answer that is not capabilities is not shown as layers', async ({ page }) => {
  const server = await started(errorPage());
  const service = await registered({ kind: 'wms', url: `${server.url}/ows`, proxy: true });
  await signIn(page, owner());
  await openApp(page, 'Geodata');
  const services = page.locator('catalog-services');
  await services.getByRole('row', { name: new RegExp(service.title) }).click();
  await expect(services.getByRole('region', { name: service.title })).toContainText(
    'The capabilities of the service cannot be read.',
  );
});
