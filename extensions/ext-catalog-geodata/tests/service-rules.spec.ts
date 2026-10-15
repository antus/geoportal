// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import { MANAGER } from './people.js';
import { param, stub, wms, type Stub } from './stubs.js';

/**
 * GP-309 on the bench: a data rule on a layer of an external service. The service is another server, which
 * cannot be made to filter what it answers: the rule makes the layer unavailable to the person it names,
 * the proxy refuses their requests for it and says why, and the page tells them in Italian. The stub WMS
 * listens on 127.0.0.1, which the bench allows; the test reads what reached it.
 */

const ORG = () => bench().organization.slug;
const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const RULES = '/api/v1/ext/catalog-geodata/service-rules';
const owner = () => member(0);
const colleague = () => member(1);
const manager = () => member(2);

type Person = ReturnType<typeof member>;
interface Service {
  id: string;
  title: string;
}

let server: Stub;
let service: Service;

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

async function credential(name: string, value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${ORG()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-309 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

const map = (person: Person, layer: string) =>
  api(person, `/api/v1/ext/catalog-geodata/services/${service.id}/proxy?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=${layer}`
    + '&STYLES=&SRS=EPSG:4326&BBOX=12,41,13,42&WIDTH=8&HEIGHT=8&FORMAT=image/png');

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  const promoted = await api(bench().admin, `/api/v1/organizations/${ORG()}/members/${MANAGER.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-user', 'organization-admin'] }),
  });
  expect(promoted.status, JSON.stringify(promoted.body)).toBeLessThan(300);
  const granted = await api(manager(),
    `/api/v1/organizations/${ORG()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:register-service`, { method: 'PUT' });
  expect(granted.status).toBeLessThan(300);
  // The subjects of rules are read from the published views of Mosaikit, with the login of the bench.
  const s = bench().settings;
  await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);

  server = await stub(wms({ cors: false, layers: [{ name: 'demo:roads', title: 'Strade' }, { name: 'demo:parcels', title: 'Particelle' }] }));
  const registered = await api(owner(), '/api/v1/ext/catalog-geodata/services', {
    method: 'POST',
    body: JSON.stringify({ title: unique('Servizio regionale'), kind: 'wms', url: `${server.url}/geoserver/ows`, proxy: true }),
  });
  expect(registered.status, JSON.stringify(registered.body)).toBe(201);
  service = registered.body as Service;
  const share = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/service/${service.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'use' }),
  });
  expect(share.status, JSON.stringify(share.body)).toBeLessThan(300);
});

test.afterAll(async () => {
  if (service) {
    await api(owner(), `/api/v1/ext/catalog-geodata/services/${service.id}`, { method: 'DELETE' });
  }
  await credential('published-views', null);
  await server?.close();
});

test('GP-309 a data rule on a layer of an external service makes it unavailable to its subject, and the proxy says why', async () => {
  expect((await map(colleague(), 'demo:roads')).status).toBe(200);

  // Only who manages the service adds rules.
  const body = { service: service.id, layer: 'demo:roads', subject: { kind: 'person', email: colleague().user }, cql: "comune = 'Valverde'" };
  expect((await api(colleague(), RULES, { method: 'POST', body: JSON.stringify(body) })).status).toBe(403);
  const added = await api(owner(), RULES, { method: 'POST', body: JSON.stringify(body) });
  expect(added.status, JSON.stringify(added.body)).toBe(201);
  expect(added.body).toMatchObject({ layer: 'demo:roads', effect: 'unavailable' });
  expect((added.body as { message: string }).message).toContain('cannot apply it');
  const rule = added.body as { id: string };

  // The subject: the layer is refused with the reason, qualified or not, and never reaches the server.
  const before = server.received.length;
  for (const name of ['demo:roads', 'roads', 'demo%3Aparcels,demo%3Aroads']) {
    const refused = await map(colleague(), name);
    expect(refused.status, name).toBe(403);
    expect(refused.body).toMatchObject({ code: 'data-rule-not-applicable', layer: 'demo:roads' });
    expect((refused.body as { message: string }).message).toContain('cannot apply it');
  }
  expect(server.received.slice(before).filter((r) => (param(r, 'layers') ?? '').includes('roads'))).toEqual([]);
  // Another layer, and the capabilities, still pass; the owner, whom the rule does not name, sees the layer.
  expect((await map(colleague(), 'demo:parcels')).status).toBe(200);
  expect((await api(colleague(), `/api/v1/ext/catalog-geodata/services/${service.id}/proxy?SERVICE=WMS&REQUEST=GetCapabilities`)).status).toBe(200);
  expect((await map(owner(), 'demo:roads')).status).toBe(200);

  const mine = await api(colleague(), `${RULES}/mine?service=${service.id}`);
  expect(mine.body).toEqual([{ layer: 'demo:roads', message: expect.stringContaining('cannot apply it') }]);
  expect((await api(owner(), `${RULES}/mine?service=${service.id}`)).body).toEqual([]);
  const audit = await api(bench().admin, `/api/v1/organizations/${ORG()}/audit-events?action=${EXTENSION}:service-rule.created`);
  expect(JSON.stringify(audit.body)).toContain(service.id);

  // Removed, the layer is available again at once.
  expect((await api(owner(), `${RULES}/${rule.id}`, { method: 'DELETE' })).status).toBe(204);
  expect((await map(colleague(), 'demo:roads')).status).toBe(200);
});

test('GP-309 a data rule on the whole external service makes it unavailable, the capabilities too', async () => {
  const added = await api(owner(), RULES, {
    method: 'POST',
    body: JSON.stringify({ service: service.id, subject: { kind: 'person', email: colleague().user }, hidden: ['codice_fiscale'] }),
  });
  expect(added.status, JSON.stringify(added.body)).toBe(201);
  const refused = await api(colleague(), `/api/v1/ext/catalog-geodata/services/${service.id}/proxy?SERVICE=WMS&REQUEST=GetCapabilities`);
  expect(refused.status).toBe(403);
  expect((refused.body as { message: string }).message).toContain('this service');
  expect((await api(owner(), `${RULES}/${(added.body as { id: string }).id}`, { method: 'DELETE' })).status).toBe(204);
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-309 the page tells the subject which layer is not available and why, and its manager sees the rule', async ({ page, browser }) => {
    const added = await api(owner(), RULES, {
      method: 'POST',
      body: JSON.stringify({ service: service.id, layer: 'demo:roads', subject: { kind: 'person', email: colleague().user }, area: 'POLYGON((12 41,13 41,13 42,12 42,12 41))' }),
    });
    expect(added.status, JSON.stringify(added.body)).toBe(201);
    try {
      await signIn(page, colleague());
      await openApp(page, 'Dati geografici');
      const services = page.locator('catalog-services');
      await services.getByRole('row', { name: new RegExp(service.title) }).click();
      const detail = services.getByRole('region', { name: service.title });
      const note = detail.getByRole('note');
      await expect(note).toContainText('Non disponibili per te');
      await expect(note).toContainText('Il livello demo:roads non è disponibile per te');
      await expect(note).toContainText('questo servizio esterno non la può applicare');
      await expect(detail.getByRole('button', { name: 'Strade' })).toBeDisabled();
      await expect(detail.getByRole('button', { name: 'Particelle' })).toBeEnabled();
      await page.screenshot({ path: 'test-results/gp-309-unavailable.png', fullPage: true });

      // The manager of the service sees the rule and its effect.
      const other = await browser.newPage({ locale: 'it-IT' });
      try {
        await signIn(other, owner());
        await openApp(other, 'Dati geografici');
        await other.locator('catalog-services').getByRole('row', { name: new RegExp(service.title) }).click();
        const rules = other.locator('catalog-services').getByRole('group', { name: 'Regole sui dati' });
        await expect(rules).toContainText('demo:roads');
        await expect(rules).toContainText('Effetto: non disponibile');
        await other.screenshot({ path: 'test-results/gp-309-rules.png', fullPage: true });
      } finally {
        await other.close();
      }
    } finally {
      await api(owner(), `${RULES}/${(added.body as { id: string }).id}`, { method: 'DELETE' });
    }
  });
});
