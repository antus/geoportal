// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, restart, signIn, unique } from '@mosaikit/testing/playwright';
import { MANAGER } from './people.js';
import { errorPage, param, stub, wms, type Stub } from './stubs.js';

/**
 * GP-297: saving a service without the proxy, the catalog checks from the server whether the browser
 * could read it directly: CORS headers for the origin of the shell, and the origin of the service in
 * the content security policy of the shell (MK-128). The bench is restarted with the policy on and
 * three of the stubs listed as external origins; the fourth is not listed. The tests in the browser
 * show that the decision matches what the browser can actually do.
 */

const ORG = () => bench().organization.slug;
const EXTENSION = 'dev.geoportal.catalog';
const owner = () => member(0);
const manager = () => member(2);

interface Access {
  outcome: string;
  reasons: string[];
  origin: string;
  proxyTurnedOn: boolean;
}
interface Service {
  id: string;
  title: string;
  kind: string;
  url: string;
  proxy: boolean;
  access: Access | null;
}

let withCors: Stub;
let withoutCors: Stub;
let unlisted: Stub;
let broken: Stub;
let switching: Stub;
let switchingCors = true;

async function register(body: Record<string, unknown>): Promise<Service> {
  const { status, body: service } = await api(owner(), '/api/v1/ext/catalog/services', {
    method: 'POST',
    body: JSON.stringify({ title: unique('Servizio'), kind: 'wms', proxy: false, ...body }),
  });
  expect(status, JSON.stringify(service)).toBe(201);
  return service as Service;
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.setTimeout(240_000);
  withCors = await stub(wms({ cors: true }));
  withoutCors = await stub(wms({ cors: false }));
  unlisted = await stub(wms({ cors: true }));
  broken = await stub(errorPage());
  const cors = wms({ cors: true });
  const bare = wms({ cors: false });
  switching = await stub((request, response) => (switchingCors ? cors : bare)(request, response));
  // The policy of production, with the external origins an administrator would list. A kernel that
  // refuses these settings is started again as before, so that the suites after this one still run.
  try {
    await restart({
      ...bench().settings,
      'mosaikit.content-security-policy.enabled': 'true',
      'mosaikit.content-security-policy.allowed-origins': [withCors, withoutCors, broken, switching].map((s) => s.url).join(','),
    });
  } catch (e) {
    await restart(bench().settings);
    throw e;
  }
  const promoted = await api(bench().admin, `/api/v1/organizations/${ORG()}/members/${MANAGER.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-user', 'organization-admin'] }),
  });
  expect(promoted.status).toBeLessThan(300);
  const granted = await api(
    manager(),
    `/api/v1/organizations/${ORG()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:register-service`,
    { method: 'PUT' },
  );
  expect(granted.status, JSON.stringify(granted.body)).toBeLessThan(300);
});

test.afterAll(async () => {
  test.setTimeout(240_000);
  await restart(bench().settings);
  await Promise.all([withCors, withoutCors, unlisted, broken, switching].filter(Boolean).map((s) => s.close()));
});

test('GP-297 the shell is served with the policy that lists the external origins', async () => {
  const shell = await fetch(bench().baseUrl + '/');
  const policy = shell.headers.get('content-security-policy') ?? '';
  expect(policy).toContain(withCors.url);
  expect(policy).not.toContain(unlisted.url);
});

test('GP-297 saving a service whose server sends CORS headers keeps the proxy off and its layers are listed', async ({ page }) => {
  const fromBrowser: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith(withCors.url)) {
      fromBrowser.push(request.url());
    }
  });
  await signIn(page, owner());
  await openApp(page, 'Catalog');
  const services = page.locator('catalog-services');
  await services.getByRole('button', { name: 'Register a service' }).click();
  const title = unique('Con CORS');
  await services.getByLabel('Title').fill(title);
  await services.getByLabel('Kind').selectOption('wms');
  await services.getByLabel('Address').fill(`${withCors.url}/ows`);
  await services.getByRole('button', { name: 'Save' }).click();

  const detail = services.getByRole('region', { name: title });
  await expect(detail).toContainText('Directly from the server of the service');
  await expect(detail.getByRole('list', { name: 'Layers' }).getByRole('button', { name: 'Strade' })).toBeVisible();
  await expect(services.locator('.notice')).not.toContainText('proxy');
  // The browser itself read the capabilities from the server of the service.
  expect(fromBrowser.some((u) => /GetCapabilities/i.test(u))).toBe(true);
  // The check asked as the page does, with the origin of the shell.
  const probe = withCors.received.find((r) => param(r, 'request') === 'GetCapabilities' && r.headers.origin);
  expect(probe?.headers.origin).toBe(new URL(bench().baseUrl).origin);
});

test('GP-297 saving a service without CORS headers turns the proxy on and says so; its layers are listed through the proxy', async ({ page }) => {
  const fromBrowser: string[] = [];
  page.on('request', (request) => {
    if (request.url().startsWith(withoutCors.url)) {
      fromBrowser.push(request.url());
    }
  });
  await signIn(page, owner());
  await openApp(page, 'Catalog');
  const services = page.locator('catalog-services');
  await services.getByRole('button', { name: 'Register a service' }).click();
  const title = unique('Senza CORS');
  await services.getByLabel('Title').fill(title);
  await services.getByLabel('Kind').selectOption('wms');
  await services.getByLabel('Address').fill(`${withoutCors.url}/ows`);
  await services.getByRole('button', { name: 'Save' }).click();

  await expect(services.locator('.notice')).toContainText('The proxy was turned on');
  await expect(services.locator('.notice')).toContainText('CORS');
  const detail = services.getByRole('region', { name: title });
  await expect(detail).toContainText('Through the proxy of Geoportal');
  await expect(detail.getByRole('list', { name: 'Layers' }).getByRole('button', { name: 'Strade' })).toBeVisible();
  await expect(services.getByRole('row', { name: new RegExp(title) })).toContainText('On');
  expect(fromBrowser).toEqual([]);
});

test('GP-297 the answer says why the proxy was turned on: CORS, the policy of the shell, or both', async () => {
  const bare = await register({ url: `${withoutCors.url}/ows` });
  expect(bare.proxy).toBe(true);
  expect(bare.access).toMatchObject({ outcome: 'proxy', reasons: ['cors'], proxyTurnedOn: true, origin: withoutCors.url });

  const notListed = await register({ url: `${unlisted.url}/ows` });
  expect(notListed.proxy).toBe(true);
  expect(notListed.access).toMatchObject({ outcome: 'proxy', reasons: ['csp'], proxyTurnedOn: true });

  const direct = await register({ url: `${withCors.url}/ows` });
  expect(direct.proxy).toBe(false);
  expect(direct.access).toMatchObject({ outcome: 'direct', reasons: [], proxyTurnedOn: false });

  // Asked with the proxy on, nothing is checked; the list does not carry the check.
  const asked = await register({ url: `${withoutCors.url}/ows`, proxy: true });
  expect(asked.access).toBeNull();
  const listed = (await api(owner(), '/api/v1/ext/catalog/services')).body as Service[];
  expect(listed.find((s) => s.id === bare.id)).toMatchObject({ proxy: true, access: null });

  const { body } = await api(bench().admin, `/api/v1/organizations/${ORG()}/audit-events?action=${EXTENSION}:service.registered&limit=50`);
  expect(JSON.stringify(body)).toContain('proxyTurnedOn');
});

test('GP-297 in the browser a service whose origin the policy does not list is blocked, so the proxy is the right choice', async ({ page }) => {
  await signIn(page, owner());
  // The same capabilities the server read: the browser of the shell is not allowed to fetch them.
  const blocked = await page.evaluate(async (url) => {
    try {
      await fetch(url, { mode: 'cors', credentials: 'omit' });
      return false;
    } catch {
      return true;
    }
  }, `${unlisted.url}/ows?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(blocked).toBe(true);
  const allowed = await page.evaluate(async (url) => (await fetch(url, { mode: 'cors', credentials: 'omit' })).ok,
    `${withCors.url}/ows?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(allowed).toBe(true);
});

test('GP-297 a server that answers with an error page instead of capabilities is not considered readable, from the browser or the proxy', async ({ page }) => {
  const service = await register({ url: `${broken.url}/ows` });
  expect(service.access).toMatchObject({ outcome: 'unreadable', proxyTurnedOn: false });
  expect(service.proxy).toBe(false);

  await signIn(page, owner());
  await openApp(page, 'Catalog');
  const services = page.locator('catalog-services');
  await services.getByRole('row', { name: new RegExp(service.title) }).click();
  const detail = services.getByRole('region', { name: service.title });
  await expect(detail).toContainText('The capabilities of the service cannot be read.');
  await detail.getByRole('button', { name: 'Check the access' }).click();
  await expect(services.locator('.notice')).toContainText('does not answer with its capabilities');
  // Through the proxy it is no better: still not capabilities.
  await detail.getByRole('button', { name: 'Turn the proxy on' }).click();
  await expect(services.getByRole('region', { name: service.title })).toContainText('Through the proxy of Geoportal');
  await expect(services.getByRole('region', { name: service.title })).toContainText('The capabilities of the service cannot be read.');
});

test('GP-297 checking again on demand turns the proxy on when the browser can no longer read the service', async ({ page }) => {
  switchingCors = true;
  const service = await register({ url: `${switching.url}/ows` });
  expect(service.proxy).toBe(false);
  switchingCors = false;
  try {
    await signIn(page, owner());
    await openApp(page, 'Catalog');
    const services = page.locator('catalog-services');
    await services.getByRole('row', { name: new RegExp(service.title) }).click();
    const detail = services.getByRole('region', { name: service.title });
    await detail.getByRole('button', { name: 'Check the access' }).click();
    await expect(services.locator('.notice')).toContainText('The proxy was turned on');
    await expect(services.getByRole('region', { name: service.title })).toContainText('Through the proxy of Geoportal');
    await expect(
      services.getByRole('region', { name: service.title }).getByRole('list', { name: 'Layers' }).getByRole('button', { name: 'Strade' }),
    ).toBeVisible();

    // A person who only uses the service may not check it.
    expect((await api(member(1), `/api/v1/ext/catalog/services/${service.id}/access`, { method: 'POST' })).status).toBeGreaterThanOrEqual(403);
    const { body } = await api(bench().admin, `/api/v1/organizations/${ORG()}/audit-events?action=${EXTENSION}:service.proxy-turned-on`);
    expect(JSON.stringify(body)).toContain(`service/${service.id}`);
  } finally {
    switchingCors = true;
  }
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-297 the notice of the proxy turned on is in Italian', async ({ page }) => {
    await signIn(page, owner());
    await openApp(page, 'Catalogo');
    const services = page.locator('catalog-services');
    await services.getByRole('button', { name: 'Registra un servizio' }).click();
    await services.getByLabel('Titolo').fill(unique('Non elencato'));
    await services.getByLabel('Tipo').selectOption('wms');
    await services.getByLabel('Indirizzo').fill(`${unlisted.url}/ows`);
    await services.getByRole('button', { name: 'Salva' }).click();
    await expect(services.locator('.notice')).toContainText('Il proxy è stato acceso');
    await expect(services.locator('.notice')).toContainText('non è tra le origini esterne della piattaforma');
  });
});
