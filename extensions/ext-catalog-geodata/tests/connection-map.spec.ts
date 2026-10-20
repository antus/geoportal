// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test, type Locator, type Page } from '@playwright/test';
import { startPostgres, type Database } from '@mosaikit/testing';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import pg from 'pg';
import { CONNECTIONS, GEODATA } from './composition.js';

/**
 * GP-986, ADR-0030: a table or view with a geometry column of a PostgreSQL connection (the connector of
 * mosaikit-ext, EXT-040) offers the use "map" of ext-catalog-geodata (EXT-019), and choosing it makes a
 * layer read where the table is. Without GeoServer the source is an embedded PostgreSQL whose type
 * `geometry` is the one PostgreSQL names as PostGIS does; with GEOPORTAL_TEST_GEOSERVER=1 it is the PostGIS
 * of tests/geoserver/compose.yaml, which GeoServer reads through a store of the connection.
 */

type Person = ReturnType<typeof member>;
interface Piece {
  id: string;
  name: string;
  kind: string;
  facts: { geometry?: unknown[] } | null;
}
interface GeoFenceRule {
  userName: string | null;
  workspace: string | null;
  layer: string | null;
  layerDetails: { cqlFilterRead: string | null } | null;
}

const CONNECTIONS_API = '/api/v1/ext/connections';
const LAYERS = '/api/v1/ext/catalog-geodata/connection-layers';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const RULES = '/api/v1/ext/catalog-geodata/data-rules';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const colleague = (): Person => member(2);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${GEODATA}:${permission}`;

let source: Database;

async function sql(port: number, user: string, password: string, database: string, text: string, values: unknown[] = []) {
  const client = new pg.Client({ host: '127.0.0.1', port, user, password, database });
  await client.connect();
  try {
    return (await client.query(text, values)).rows;
  } finally {
    await client.end();
  }
}

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

/** A credential of the organization, meant for one extension or, with null, for every one. */
async function credential(name: string, value: string | null, extension: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-986 ${name}`, ...(extension ? { extension } : {}) });
  const { status } = existing.status === 200
    ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
    : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function connection(settings: Record<string, unknown>, secret: string): Promise<{ id: string; name: string }> {
  const name = unique('Fonte');
  const { status, body } = await api(owner(), `${CONNECTIONS_API}/connections`, {
    method: 'POST',
    body: JSON.stringify({ name, type: 'postgres', settings, credential: secret }),
  });
  expect(status, JSON.stringify(body)).toBe(201);
  return body as { id: string; name: string };
}

async function pieces(id: string, parent: string): Promise<Piece[]> {
  const { status, body } = await api(owner(), `${CONNECTIONS_API}/connections/${id}/contents?parent=${encodeURIComponent(parent)}`);
  expect(status, JSON.stringify(body)).toBe(200);
  return (body as { items: Piece[] }).items;
}

/** The reference the source picker gives for a table (EXT-026). */
function reference(connectionId: string, schema: string, table: string) {
  return {
    connection: { extension: CONNECTIONS, type: 'connection', id: connectionId },
    part: { id: `${schema}/${table}`, parents: [schema], name: table, kind: 'table' },
    uses: ['map'],
  };
}

/** The pieces of a schema of a connection in the app of the connections, opened from its card. */
async function schemaIn(page: Page, name: string, schema: string): Promise<Locator> {
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name, exact: true }).click();
  const region = page.getByRole('region', { name: new RegExp(`^(Contents of|Contenuto di) ${name}$`) });
  await region.getByRole('button', { name: schema, exact: true }).click();
  return region;
}

test.describe.configure({ mode: 'serial', timeout: 180_000 });

test.beforeAll(async () => {
  // An embedded PostgreSQL is made for the source: the first one of a run takes a while.
  test.setTimeout(240_000);
  await administrator(manager(), true);
  source = await startPostgres({ directory: `.mosaikit-bench/gp986-source-${Date.now().toString(36)}`, persistent: false, database: 'fonte' });
  // The type geometry as PostGIS names it: what the connector reads to say a column holds geometries.
  await sql(source.port, source.user, source.password, 'fonte', `
    create type public.geometry as (x double precision, y double precision);
    create schema rete;
    create table rete.fermate (id integer primary key, nome text, posizione public.geometry);
    create table rete.orari (id integer primary key, partenza time);`);
  await credential('gp986-fonte', source.password, null);
});

test.afterAll(async () => {
  await credential('gp986-fonte', null, null);
  await administrator(manager(), false);
  await source?.stop();
});

test('GP-986 a table with a geometry column of a PostgreSQL connection offers the use Map of the geodata, a table without one does not', async ({ page }) => {
  const c = await connection({ host: '127.0.0.1', port: source.port, database: 'fonte', user: source.user, sslmode: 'disable' }, 'gp986-fonte');
  const found = await pieces(c.id, 'rete');
  const fermate = found.find((p) => p.name === 'fermate')!;
  expect(fermate.facts?.geometry).toBeTruthy();
  expect(found.find((p) => p.name === 'orari')!.facts?.geometry ?? []).toEqual([]);

  await signIn(page, owner());
  await openApp(page, 'Connections');
  const region = await schemaIn(page, c.name, 'rete');
  const uses = region.locator('ul.entries > li[data-id="rete/fermate"] ul.uses');
  await expect(uses.locator(`li[data-use="${GEODATA}/map"]`)).toHaveText('Map');
  await expect(uses.locator(`li[data-use="${GEODATA}/map"]`)).toHaveAttribute('title',
    'The rows of a table with a geometry, as a layer of the maps, read where they are.');
  await expect(region.locator(`ul.entries > li[data-id="rete/orari"] li[data-use="${GEODATA}/map"]`)).toHaveCount(0);
  await page.screenshot({ path: 'test-results/gp986-use-map.png', fullPage: true });
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-986 la tabella con una geometria offre l\'uso Mappa', async ({ page }) => {
    const c = await connection({ host: '127.0.0.1', port: source.port, database: 'fonte', user: source.user, sslmode: 'disable' }, 'gp986-fonte');
    await signIn(page, owner());
    await openApp(page, 'Connessioni');
    const region = await schemaIn(page, c.name, 'rete');
    await expect(region.locator(`ul.entries > li[data-id="rete/fermate"] li[data-use="${GEODATA}/map"]`)).toHaveText('Mappa');
  });
});

test('GP-986 mapping a table needs the permission publish, a connection the person may use and a credential the geodata may read', async () => {
  const settings = { host: '127.0.0.1', port: source.port, database: 'fonte', user: source.user, sslmode: 'disable' };
  const c = await connection(settings, 'gp986-fonte');
  const body = JSON.stringify({ reference: reference(c.id, 'rete', 'fermate') });
  // Without the permission to publish.
  expect((await api(owner(), LAYERS, { method: 'POST', body })).status).toBe(403);
  expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
  try {
    // A connection the colleague may not use is not found for them.
    expect((await api(manager(), exception(colleague(), 'publish'), { method: 'PUT' })).status).toBe(200);
    const theirs = await api(colleague(), LAYERS, { method: 'POST', body });
    expect(theirs.status, JSON.stringify(theirs.body)).toBe(404);

    // A credential meant for the connections only: the geodata may not read it, and say so.
    await credential('gp986-solo-connessioni', source.password, CONNECTIONS);
    const reserved = await connection(settings, 'gp986-solo-connessioni');
    const refused = await api(owner(), LAYERS, { method: 'POST', body: JSON.stringify({ reference: reference(reserved.id, 'rete', 'fermate') }) });
    expect(refused.status, JSON.stringify(refused.body)).toBe(409);
    expect(refused.body).toMatchObject({ code: 'credential-not-readable' });
    expect(JSON.stringify(refused.body)).not.toContain(source.password);

    // A table without a geometry column is not a layer.
    const plain = await api(owner(), LAYERS, { method: 'POST', body: JSON.stringify({ reference: reference(c.id, 'rete', 'orari') }) });
    expect(plain.status, JSON.stringify(plain.body)).toBe(409);
    expect(plain.body).toMatchObject({ code: 'no-geometry' });
  } finally {
    await credential('gp986-solo-connessioni', null, null);
    await api(manager(), exception(colleague(), 'publish'), { method: 'DELETE' });
    await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
  }
});

test('GP-986 the geodata open the source picker of the connections for the use Map, and say why a table is not mapped', async ({ page }) => {
  const c = await connection({ host: '127.0.0.1', port: source.port, database: 'fonte', user: source.user, sslmode: 'disable' }, 'gp986-fonte');
  expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
  try {
    await signIn(page, owner());
    await openApp(page, 'Geodata');
    const publications = page.locator('catalog-publications');
    await publications.getByRole('button', { name: 'Map a table of a connection' }).click();
    const dialog = page.getByRole('dialog', { name: 'Choose a source' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: c.name, exact: true }).click();
    const region = dialog.getByRole('region', { name: `Contents of ${c.name}` });
    await region.getByRole('button', { name: 'rete', exact: true }).click();
    // Only the table with geometries can be chosen for a map.
    await expect(region.getByRole('radio', { name: 'Choose orari' })).toHaveCount(0);
    await region.getByRole('radio', { name: 'Choose fermate' }).check();
    await dialog.getByRole('button', { name: 'Choose', exact: true }).click();
    await expect(dialog).toBeHidden();
    // The source of this test has no PostGIS: the geodata say so, and nothing is published.
    await expect(publications.locator('.note')).toContainText('The table is not published:');
    await expect(publications.locator('.note')).toContainText('PostGIS');
    await page.screenshot({ path: 'test-results/gp986-picker-refused.png', fullPage: true });
  } finally {
    await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
  }
});

test.describe('on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(240_000);

  const GEODATA_DB = { port: 15434, user: 'geoserver', password: 'catalog-test-db', database: 'geodata' };
  const schema = `gp986_${Date.now().toString(36)}`;

  async function geodata(text: string, values: unknown[] = []) {
    return sql(GEODATA_DB.port, GEODATA_DB.user, GEODATA_DB.password, GEODATA_DB.database, text, values);
  }

  async function gate(person: Person, path: string): Promise<Response> {
    return fetch(bench().baseUrl + path, {
      headers: {
        authorization: 'Basic ' + Buffer.from(`${person.user}:${person.password}`).toString('base64'),
        'X-Mosaikit-Organization': organization(),
      },
    });
  }

  test.beforeAll(async () => {
    expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
    await credential('geoserver', 'gsadmin:catalog-test-admin', GEODATA);
    await credential('geodata', 'geoserver:catalog-test-db', GEODATA);
    const s = bench().settings;
    await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`, GEODATA);
    await credential('gp986-geodata', GEODATA_DB.password, null);
    await geodata(`create schema ${schema}`);
    await geodata(`create table ${schema}.fermate (id integer primary key, nome text, geom geometry(Point, 4326))`);
    await geodata(`insert into ${schema}.fermate values (1, 'Piazza', 'SRID=4326;POINT(12.4964 41.9028)'),
      (2, 'Stazione', 'SRID=4326;POINT(12.5011 41.9009)'), (3, 'Porto', 'SRID=4326;POINT(12.4889 41.8986)')`);
  });

  test.afterAll(async () => {
    await geodata(`drop schema if exists ${schema} cascade`);
    await credential('gp986-geodata', null, null);
    await credential('geodata', null, null);
    await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
  });

  test('GP-986 choosing Map makes a layer read where the table is, with a link to its connection and table, and the data rules applied', async ({ page }) => {
    const c = await connection({ host: '127.0.0.1', port: GEODATA_DB.port, database: GEODATA_DB.database, user: GEODATA_DB.user,
      sslmode: 'disable' }, 'gp986-geodata');
    expect((await pieces(c.id, schema)).find((p) => p.name === 'fermate')?.facts?.geometry).toBeTruthy();

    const layer = unique('gp986_fermate').toLowerCase().replace(/[^a-z0-9_]/g, '_');
    const mapped = await api(owner(), LAYERS, { method: 'POST', body: JSON.stringify({ reference: reference(c.id, schema, 'fermate'), layer }) });
    expect(mapped.status, JSON.stringify(mapped.body)).toBe(201);
    const { dataset } = mapped.body as { dataset: string; layer: string };

    // The link to the connection and the table, never the credential.
    const link = await api(owner(), `${LAYERS}/${dataset}`);
    expect(link.body).toMatchObject({ connection: c.id, schema, table: 'fermate', geometryColumn: 'geom', credential: 'gp986-geodata',
      reference: { connection: { id: c.id }, part: { id: `${schema}/fermate` } } });
    expect(JSON.stringify(link.body)).not.toContain(GEODATA_DB.password);

    // A layer of the geodata, read through the gate of Geoportal, as the owner.
    const ws = ((await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/alignment')).body as { workspace: string }).workspace;
    const wfs = `${PUBLICATIONS}/${layer}/ogc/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=${ws}:${layer}&outputFormat=application/json`;
    const first = await gate(owner(), wfs);
    expect(first.status, await first.clone().text()).toBe(200);
    expect(((await first.json()) as { features: unknown[] }).features).toHaveLength(3);
    // Not copied: a row added to the source is in the layer at once, and no table of the geodata holds it.
    await geodata(`insert into ${schema}.fermate values (4, 'Mercato', 'SRID=4326;POINT(12.4930 41.8950)')`);
    expect(((await (await gate(owner(), wfs)).json()) as { features: unknown[] }).features).toHaveLength(4);
    const copies = await geodata(`select count(*)::int as n from information_schema.tables
      where table_schema like 'org_%' and table_name in ('fermate', $1)`, [layer]);
    expect(copies[0].n).toBe(0);

    // The store of the connection in GeoServer, its password never shown in clear.
    const stores = await (await fetch(`${GEOSERVER_URL}/rest/workspaces/${ws}/datastores.json`, { headers: { authorization: GS_AUTH } })).json();
    const store = (stores as { dataStores: { dataStore: { name: string }[] } }).dataStores.dataStore.map((s) => s.name)
      .find((n) => n.startsWith('conn_'));
    expect(store).toBeTruthy();
    const held = await (await fetch(`${GEOSERVER_URL}/rest/workspaces/${ws}/datastores/${store}.json`, { headers: { authorization: GS_AUTH } })).text();
    expect(held).not.toContain(GEODATA_DB.password);

    // The data rules apply: shared with the colleague, limited to one stop, written into GeoFence.
    const colleagueId = ((await api(colleague(), '/api/v1/accounts/me')).body as { id: string }).id;
    const shared = await api(owner(), `/api/v1/sharing/resources/${GEODATA}/dataset/${dataset}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', id: colleagueId }, level: 'read' }),
    });
    expect(shared.status).toBeLessThan(300);
    const rule = await api(owner(), RULES, { method: 'POST', body: JSON.stringify({ layer, subject: { kind: 'person', email: colleague().user },
      cql: "nome = 'Piazza'" }) });
    expect(rule.status, JSON.stringify(rule.body)).toBeLessThan(300);
    await expect.poll(async () => {
      const r = await fetch(`${GEOSERVER_URL}/rest/geofence/rules.json`, { headers: { authorization: GS_AUTH } });
      const rules = ((await r.json()) as { rules: GeoFenceRule[] }).rules;
      return rules.some((x) => x.workspace === ws && x.layer === layer && x.userName === colleague().user
        && x.layerDetails?.cqlFilterRead === "nome = 'Piazza'");
    }, { timeout: 60_000 }).toBe(true);

    // Read only: its table is not assigned to surveyors.
    const assign = await api(owner(), `/api/v1/ext/catalog-geodata/editing/layers/${layer}/assignments/${colleagueId}`, { method: 'PUT' });
    expect(assign.status, JSON.stringify(assign.body)).toBe(409);

    // In the page of the geodata, the layer is listed with the others.
    await signIn(page, owner());
    await openApp(page, 'Geodata');
    await expect(page.locator('catalog-publications tr.row', { hasText: layer })).toBeVisible();
    await page.screenshot({ path: 'test-results/gp986-layer.png', fullPage: true });

    // Deleting the layer leaves the table where it is, and its store goes from GeoServer.
    expect((await api(owner(), `${UPLOADS}/${dataset}`, { method: 'DELETE' })).status).toBe(204);
    expect((await geodata(`select count(*)::int as n from ${schema}.fermate`))[0].n).toBe(4);
    await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/reconciliation', { method: 'POST' });
    const after = await (await fetch(`${GEOSERVER_URL}/rest/workspaces/${ws}/datastores.json`, { headers: { authorization: GS_AUTH } })).json();
    expect(JSON.stringify(after)).not.toContain(store!);
  });
});
