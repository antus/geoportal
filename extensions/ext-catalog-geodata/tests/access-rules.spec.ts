// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-335 on the bench with the GeoServer of tests/geoserver/compose.yaml (GEOPORTAL_TEST_GEOSERVER=1):
 * the sharing of a published dataset in Mosaikit becomes the GeoFence rules of the workspace of the
 * organization, written as a whole, read back and compared; a rule added by hand is reported and goes
 * at the next rewrite; the reconciliation shows the rules in the page. What GeoServer answers to each
 * person with these rules is proved by GeoFenceRulesTest (mvn test -Pgeoserver).
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  technicalName: string;
  state: string;
  stateReason: string | null;
}
interface Report {
  outcome: string;
  rules: number;
  removed: string[];
  mismatches: string[];
  warnings: string[];
  error: string | null;
}
interface GeoFenceRule {
  id: number;
  priority: number;
  userName: string | null;
  roleName: string | null;
  workspace: string | null;
  layer: string | null;
  service: string | null;
  access: string;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER_API = '/api/v1/ext/catalog-geodata/geoserver';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const colleague = (): Person => member(2);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

const POINTS = JSON.stringify({
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: { nome: 'Centrale' }, geometry: { type: 'Point', coordinates: [12.5018, 41.9009] } },
    { type: 'Feature', properties: { nome: 'Nord' }, geometry: { type: 'Point', coordinates: [12.5308, 41.9105] } },
  ],
});

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function credential(name: string, value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-335 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function geoserver(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('authorization', GS_AUTH);
  return fetch(GEOSERVER_URL + path, { ...init, headers });
}

async function geofence(ws: string): Promise<GeoFenceRule[]> {
  const all = (await (await geoserver('/rest/geofence/rules.json')).json()) as { rules: GeoFenceRule[] };
  return all.rules.filter((r) => r.workspace === ws).sort((a, b) => a.priority - b.priority);
}

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

/** The identifier of the organization, from the name of its workspace (org_ and 32 hexadecimal digits). */
const organizationId = (ws: string): string =>
  ws.slice(4).replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');

test.describe('GP-335 on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  let dataset: Upload;
  let layer: string;
  let ws: string;

  test.beforeAll(async () => {
    await administrator(colleague(), false);
    await administrator(manager(), true);
    expect((await api(manager(), exception(owner(), 'upload-data'), { method: 'PUT' })).status).toBe(200);
    expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
    expect((await api(manager(), exception(owner(), 'manage-geoserver'), { method: 'PUT' })).status).toBe(200);
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
    // The published views of Mosaikit, read here with the login of the database of the bench.
    const s = bench().settings;
    await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);

    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('regole').toLowerCase()}.geojson` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: POINTS,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    dataset = uploaded.body as Upload;
    expect(dataset.state, dataset.stateReason ?? '').toBe('loaded');
    layer = `gp335_${Date.now()}`;
    const published = await api(owner(), PUBLICATIONS, {
      method: 'POST',
      body: JSON.stringify({ dataset: dataset.id, layer }),
    });
    expect(published.status, JSON.stringify(published.body)).toBe(201);
    ws = ((await api(manager(), `${GEOSERVER_API}/alignment`)).body as { workspace: string }).workspace;
  });

  test.afterAll(async () => {
    if (dataset) {
      await api(owner(), `${UPLOADS}/${dataset.id}`, { method: 'DELETE' });
    }
    for (const p of ['upload-data', 'publish', 'manage-geoserver']) {
      await api(manager(), exception(owner(), p), { method: 'DELETE' });
    }
    await credential('published-views', null);
    await credential('geodata', null);
    await administrator(manager(), false);
  });

  test('GP-335 publishing writes the rules of the workspace: the owner by name, the administrators by role, deny at the end', async () => {
    const generated = (await api(manager(), `${GEOSERVER_API}/rules`)).body as { rules: string[]; warnings: string[]; report: Report };
    expect(generated.warnings).toEqual([]);
    expect(generated.rules).toContain(`ALLOW user ${owner().user} on ${layer} WMS`);
    expect(generated.rules).toContain(`ALLOW user ${owner().user} on ${layer} WFS`);
    expect(generated.rules).toContain(`ALLOW role ${organizationId(ws)}:admin on ${ws}`);
    expect(generated.rules.at(-1)).toBe(`DENY everyone on ${ws}`);
    expect(generated.rules.join('\n')).not.toContain(colleague().user);
    expect(generated.report.outcome).toBe('matching');

    const held = await geofence(ws);
    expect(held).toHaveLength(generated.rules.length);
    expect(held.some((r) => r.userName === owner().user && r.layer === layer && r.service === 'WFS')).toBe(true);
    expect(held.at(-1)).toMatchObject({ access: 'DENY', layer: null, userName: null, roleName: null });
  });

  test('GP-335 a share with a person or the organization becomes a rule, and goes when unshared', async () => {
    const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'view' }),
    });
    expect(grant.status, JSON.stringify(grant.body)).toBeLessThan(300);
    const written = (await api(manager(), `${GEOSERVER_API}/rules`, { method: 'POST' })).body as Report;
    expect(written).toMatchObject({ outcome: 'written', mismatches: [], removed: [] });
    let held = await geofence(ws);
    // view: the map and its tiles, not the features.
    expect(held.filter((r) => r.userName === colleague().user).map((r) => r.service).sort()).toEqual(['GWC', 'WMS']);

    // Shared with the organization in the dialog of Mosaikit: the next request that lists the
    // publications, after the check interval, writes it.
    const org = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'organization' }, level: 'read' }),
    });
    expect(org.status, JSON.stringify(org.body)).toBeLessThan(300);
    await new Promise((resolve) => setTimeout(resolve, 16_000));
    expect((await api(colleague(), PUBLICATIONS)).status).toBe(200);
    held = await geofence(ws);
    expect(held.filter((r) => r.roleName === organizationId(ws) && r.layer === layer).map((r) => r.service).sort())
      .toEqual(['GWC', 'WCS', 'WFS', 'WMS']);

    // Unshared: both rules are gone at the next rewrite.
    const grants = (await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`)).body as { id: string }[];
    for (const g of grants) {
      expect((await api(owner(), `/api/v1/sharing/grants/${g.id}`, { method: 'DELETE' })).status).toBeLessThan(300);
    }
    expect(((await api(manager(), `${GEOSERVER_API}/rules`, { method: 'POST' })).body as Report).outcome).toBe('written');
    held = await geofence(ws);
    expect(held.some((r) => r.userName === colleague().user)).toBe(false);
    expect(held.some((r) => r.roleName === organizationId(ws))).toBe(false);
    const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:geofence.rewritten`);
    expect(JSON.stringify(audit.body)).toContain(`workspace/${ws}`);
  });

  test('GP-118 unpublishing a layer removes its GeoFence rules with it, and leaves those of the other layers', async () => {
    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('ritiro').toLowerCase()}.geojson` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: POINTS,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    const other = uploaded.body as Upload;
    const withdrawn = `gp118_${Date.now()}`;
    try {
      const published = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: other.id, layer: withdrawn }) });
      expect(published.status, JSON.stringify(published.body)).toBe(201);
      const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${other.id}/grants`, {
        method: 'PUT',
        body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'view' }),
      });
      expect(grant.status, JSON.stringify(grant.body)).toBeLessThan(300);
      expect(((await api(manager(), `${GEOSERVER_API}/rules`, { method: 'POST' })).body as Report).outcome).toBe('written');
      let held = await geofence(ws);
      expect(held.filter((r) => r.layer === withdrawn).map((r) => r.userName)).toEqual(
        expect.arrayContaining([owner().user, colleague().user]),
      );

      // Unpublished: the alignment that withdraws the layer writes the rules again without it, no rewrite by hand.
      expect((await api(owner(), `${PUBLICATIONS}/${withdrawn}`, { method: 'DELETE' })).status).toBe(204);
      held = await geofence(ws);
      expect(held.filter((r) => r.layer === withdrawn)).toEqual([]);
      expect(held.some((r) => r.layer === layer && r.userName === owner().user)).toBe(true);
      expect(held.at(-1)).toMatchObject({ access: 'DENY', layer: null });
      expect((await geoserver(`/rest/workspaces/${ws}/layers/${withdrawn}.json`)).status).toBe(404);
      const generated = (await api(manager(), `${GEOSERVER_API}/rules`)).body as { rules: string[]; report: Report };
      expect(generated.rules.join('\n')).not.toContain(withdrawn);
      expect(generated.report.outcome).toBe('matching');
    } finally {
      await api(owner(), `${UPLOADS}/${other.id}`, { method: 'DELETE' });
    }
  });

  test.describe('in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-335 a rule added by hand is a difference in the reconciliation and disappears when it is aligned', async ({ page }) => {
      const first = (await geofence(ws))[0]!;
      const added = await geoserver('/rest/geofence/rules', {
        method: 'POST',
        headers: { 'content-type': 'application/xml' },
        body: `<Rule><priority>${first.priority - 1}</priority><workspace>${ws}</workspace><layer>${layer}</layer>`
          + '<service>WFS</service><access>ALLOW</access></Rule>',
      });
      expect(added.status).toBe(201);

      const report = (await api(owner(), `${GEOSERVER_API}/reconciliation`)).body as { rules: Report; needsPerson: number };
      expect(report.rules.outcome).toBe('mismatch');
      expect(report.rules.mismatches.join('\n')).toContain('not written by the catalog: ALLOW everyone on ' + layer + ' WFS');
      expect(report.needsPerson).toBeGreaterThanOrEqual(1);

      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const panel = page.getByLabel('Riconciliazione con GeoServer');
      await panel.getByRole('button', { name: 'Confronta' }).click();
      const rules = panel.getByRole('group', { name: 'Regole di accesso (GeoFence)' });
      await expect(rules).toContainText('GeoFence ha regole diverse da quelle generate.');
      await expect(rules).toContainText(`ALLOW everyone on ${layer} WFS`);
      await page.screenshot({ path: 'test-results/gp-335-rules-mismatch.png', fullPage: true });

      await panel.getByRole('button', { name: 'Allinea' }).click();
      await expect(rules).toContainText('Regole aggiunte a mano, tolte dall’ultima riscrittura (1)');
      await expect(rules).toContainText('Riscritte e rilette: GeoFence ha esattamente queste regole.');
      await page.screenshot({ path: 'test-results/gp-335-rules-aligned.png', fullPage: true });
      expect((await geofence(ws)).some((r) => r.userName === null && r.roleName === null && r.layer === layer)).toBe(false);

      await panel.getByRole('button', { name: 'Confronta' }).click();
      await expect(rules).toContainText('GeoFence ha esattamente queste regole.');
    });
  });
});
