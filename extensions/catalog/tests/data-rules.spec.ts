// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-308 on the bench with the GeoServer of tests/geoserver/compose.yaml (GEOPORTAL_TEST_GEOSERVER=1):
 * data rules of a published layer written in the page, in Italian (subject, area drawn on the map, CQL
 * row filter, hidden columns), checked before they are kept, and become the GeoFence rules of the person
 * (GP-335). What GeoServer then answers to the person is proved by GeoFenceRulesTest (mvn test -Pgeoserver).
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  state: string;
  stateReason: string | null;
}
interface Problem {
  code: string;
  detail: string;
}
interface GeoFenceRule {
  id: number;
  priority: number;
  userName: string | null;
  workspace: string | null;
  layer: string | null;
  service: string | null;
  access: string;
  limits: { allowedArea: string | null } | null;
  layerDetails: { cqlFilterRead: string | null; attributes: { name: string; accessType: string }[] } | null;
}

const EXTENSION = 'dev.geoportal.catalog';
const UPLOADS = '/api/v1/ext/catalog/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog/publications';
const RULES = '/api/v1/ext/catalog/data-rules';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const colleague = (): Person => member(2);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

/** Three stations of a test town (Centrale, Nord, Sud) in EPSG:32633, with a reserved column. */
const STATIONS = JSON.stringify({
  type: 'FeatureCollection',
  crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
  features: [
    ['Centrale', 292719.039, 4641793.153],
    ['Nord', 295196.583, 4642777.113],
    ['Sud', 291273.415, 4638645.888],
  ].map(([nome, x, y]) => ({
    type: 'Feature',
    properties: { nome, riservato: `segreto ${String(nome)}` },
    geometry: { type: 'Point', coordinates: [x, y] },
  })),
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
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-308 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function geofence(ws: string): Promise<GeoFenceRule[]> {
  const r = await fetch(`${GEOSERVER_URL}/rest/geofence/rules.json`, { headers: { authorization: GS_AUTH } });
  const all = (await r.json()) as { rules: GeoFenceRule[] };
  return all.rules.filter((x) => x.workspace === ws).sort((a, b) => a.priority - b.priority);
}

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

/** A call of the gate of the catalog as a person. */
async function gate(person: Person, path: string): Promise<Response> {
  return fetch(bench().baseUrl + path, {
    headers: {
      authorization: 'Basic ' + Buffer.from(`${person.user}:${person.password}`).toString('base64'),
      'X-Mosaikit-Organization': organization(),
    },
  });
}

test.describe('GP-308 on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);
  test.use({ locale: 'it-IT' });

  let dataset: Upload;
  let layer: string;
  let ws: string;

  test.beforeAll(async () => {
    await administrator(colleague(), false);
    await administrator(manager(), true);
    for (const p of ['upload-data', 'publish']) {
      expect((await api(manager(), exception(owner(), p), { method: 'PUT' })).status).toBe(200);
    }
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
    const s = bench().settings;
    await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);
    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('stazioni').toLowerCase()}.geojson` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: STATIONS,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    dataset = uploaded.body as Upload;
    expect(dataset.state, dataset.stateReason ?? '').toBe('loaded');
    layer = `gp308_${Date.now()}`;
    const published = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: dataset.id, layer }) });
    expect(published.status, JSON.stringify(published.body)).toBe(201);
    const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'download' }),
    });
    expect(grant.status).toBeLessThan(300);
    ws = ((await api(manager(), '/api/v1/ext/catalog/geoserver/alignment')).body as { workspace: string }).workspace;
  });

  test.afterAll(async () => {
    if (dataset) {
      await api(owner(), `${UPLOADS}/${dataset.id}`, { method: 'DELETE' });
    }
    for (const p of ['upload-data', 'publish']) {
      await api(manager(), exception(owner(), p), { method: 'DELETE' });
    }
    await credential('published-views', null);
    await credential('geodata', null);
    await administrator(manager(), false);
  });

  test('GP-308 a rule is checked before it is kept: subject, area, row filter, columns', async () => {
    const post = (body: object) => api(owner(), RULES, { method: 'POST', body: JSON.stringify({ layer, ...body }) });
    const person = { kind: 'person', email: colleague().user };
    const refused = async (body: object, code: string): Promise<void> => {
      const r = await post(body);
      expect(r.status, JSON.stringify(r.body)).toBe(400);
      expect((r.body as Problem).code).toBe(code);
    };
    await refused({ subject: person, cql: "nome = 'Centrale' AND" }, 'invalid-filter');
    await refused({ subject: person, cql: 'colonna_inesistente = 1' }, 'invalid-filter');
    await refused({ subject: person, area: 'LINESTRING(290000 4640000, 293000 4643000)' }, 'invalid-area');
    await refused({ subject: person, area: 'SRID=4326;POLYGON((12 41,13 41,13 42,12 41))' }, 'invalid-area');
    await refused({ subject: person, hidden: ['geom'] }, 'invalid');
    await refused({ subject: person, hidden: ['nessuna'] }, 'invalid');
    await refused({ subject: person }, 'invalid');
    await refused({ subject: { kind: 'person', email: 'nobody@example.test' }, cql: "nome = 'x'" }, 'invalid');
    // Only those who manage the dataset.
    expect((await api(colleague(), `${RULES}?layer=${layer}`)).status).toBe(403);
    expect((await api(colleague(), RULES, { method: 'POST', body: JSON.stringify({ layer, subject: person, cql: "nome = 'x'" }) })).status).toBe(403);
  });

  test('GP-308 a rule written in the page, with an area drawn on the map, becomes the GeoFence rules of the person', async ({ page }) => {
    await signIn(page, owner());
    await openApp(page, 'Catalogo');
    await page.locator('tr.row', { hasText: layer }).click();
    const section = page.getByRole('region', { name: 'Regole sui dati' });
    await expect(section).toContainText('Nessuna regola');
    await section.getByRole('button', { name: 'Nuova regola' }).click();
    const dialog = page.getByRole('dialog', { name: 'Nuova regola sui dati' });
    await dialog.getByLabel('Indirizzo e-mail della persona').fill(colleague().user);
    await dialog.getByLabel('Descrizione (facoltativa)').fill('Solo la zona della stazione Centrale, senza il campo riservato');
    // A triangle around the western stations, drawn with three clicks on the map.
    const map = dialog.locator('.draw');
    await expect(dialog.getByRole('img', { name: 'Mappa del livello su cui disegnare l’area' })).toBeVisible();
    const box = (await map.boundingBox())!;
    for (const [fx, fy] of [[0.02, 0.02], [0.6, 0.02], [0.02, 0.98]] as const) {
      await map.click({ position: { x: box.width * fx, y: box.height * fy } });
    }
    await expect(dialog.getByLabel('Area in WKT')).toHaveValue(/^POLYGON\(\(/);
    await dialog.getByLabel('Filtro sulle righe (CQL)').fill("nome <> 'Sud'");
    await dialog.getByLabel('riservato').check();
    await page.screenshot({ path: 'test-results/gp-308-rule-dialog.png', fullPage: true });
    await dialog.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByText('La regola è stata salvata e le regole di accesso riscritte.')).toBeVisible();
    const item = section.getByRole('listitem').filter({ hasText: colleague().user });
    await expect(item).toContainText('area limitata');
    await expect(item).toContainText("righe: nome <> 'Sud'");
    await expect(item).toContainText('colonne nascoste: riservato');
    await page.screenshot({ path: 'test-results/gp-308-rules.png', fullPage: true });

    // GeoFence: a LIMIT with the area and ALLOWs with the filter and the hidden column, for the person.
    const held = (await geofence(ws)).filter((r) => r.userName === colleague().user && r.layer === layer);
    const limit = held.find((r) => r.access === 'LIMIT');
    expect(limit?.limits?.allowedArea).toMatch(/^SRID=32633;MULTIPOLYGON/);
    const wfs = held.find((r) => r.access === 'ALLOW' && r.service === 'WFS');
    expect(wfs?.layerDetails?.cqlFilterRead).toBe("nome <> 'Sud'");
    expect(wfs?.layerDetails?.attributes).toContainEqual(expect.objectContaining({ name: 'riservato', accessType: 'NONE' }));
    expect(held.at(-1)?.access).toBe('DENY');

    // The gate reaches GeoServer as its administrator: it refuses the person instead of ignoring the rule.
    const refused = await gate(colleague(), `${PUBLICATIONS}/${layer}/ogc/wfs?service=WFS&version=2.0.0&request=GetFeature`
      + `&typeNames=${ws}:${layer}&outputFormat=application/json`);
    expect(refused.status).toBe(403);
    expect(((await refused.json()) as Problem).code).toBe('data-rules');
    expect((await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&request=GetCapabilities`)).status).toBe(200);

    // Changed in the page: the filter goes, the rules follow.
    await item.getByRole('button', { name: `Modifica ${colleague().user}` }).click();
    const edit = page.getByRole('dialog', { name: 'Modifica la regola sui dati' });
    await expect(edit.getByLabel('Filtro sulle righe (CQL)')).toHaveValue("nome <> 'Sud'");
    await edit.getByLabel('Filtro sulle righe (CQL)').fill('');
    await edit.getByRole('button', { name: 'Salva' }).click();
    await expect(section.getByRole('listitem').filter({ hasText: colleague().user })).not.toContainText('righe:');
    const after = (await geofence(ws)).find((r) => r.userName === colleague().user && r.service === 'WFS' && r.access === 'ALLOW');
    expect(after?.layerDetails?.cqlFilterRead ?? null).toBeNull();

    // Deleted: the person has the plain rules of their grant again.
    page.once('dialog', (confirm) => void confirm.accept());
    await section.getByRole('button', { name: `Elimina ${colleague().user}` }).click();
    await expect(section).toContainText('Nessuna regola');
    const plain = (await geofence(ws)).filter((r) => r.userName === colleague().user);
    expect(plain.map((r) => r.access)).toEqual(['ALLOW', 'ALLOW', 'ALLOW', 'ALLOW']);
    const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:data-rule.created`);
    expect(JSON.stringify(audit.body)).toContain('data-rule/');
  });
});
