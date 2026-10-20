// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { existsSync, mkdirSync, readdirSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { startPostgres } from '@mosaikit/testing';
import { api, apps, bench, member, restart, signIn, unique } from '@mosaikit/testing/playwright';
import pg from 'pg';
import { CONNECTIONS, FILE_MANAGER, GEODATA } from './composition.js';

/**
 * GP-983, criterion 3: an installation with ext-catalog and without ext-catalog-geodata manages files and
 * connections, and the geodata files show the placeholder of an absent extension (MK-135, EXT-106),
 * keeping their kind and state; when ext-catalog-geodata comes back, they are geodata again. The package
 * of this extension leaves the installation of the bench, which restarts without it, and comes back.
 */

type Person = ReturnType<typeof member>;
interface Item {
  id: string;
  name: string;
  fileKind: { extension: string; name: string; state: string | null; active?: boolean } | null;
}
type Extension = { id: string; status: string };

const CATALOG = '/api/v1/ext/catalog';
const owner = (): Person => member(0);

const STOPS = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Piazza' }, geometry: { type: 'Point', coordinates: [12.4964, 41.9028] } }],
});

async function folder(name: string): Promise<Item> {
  const { status, body } = await api(owner(), `${CATALOG}/folders`, { method: 'POST', body: JSON.stringify({ name, parent: null }) });
  expect(status, JSON.stringify(body)).toBe(201);
  return body as Item;
}

async function store(parent: string, name: string, content: string, type: string): Promise<Item> {
  const { status, body } = await api(owner(), `${CATALOG}/folders/${parent}/files?name=${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'content-type': type },
    body: content,
  });
  expect(status, JSON.stringify(body)).toBe(201);
  return body as Item;
}

async function listed(parent: string, name: string): Promise<Item> {
  const found = ((await api(owner(), `${CATALOG}/folders/${parent}`)).body as { items: Item[] }).items.find((i) => i.name === name);
  expect(found, name).toBeTruthy();
  return found!;
}

async function active(id: string): Promise<boolean> {
  const { body } = await api(bench().admin, '/api/v1/extensions');
  return (body as Extension[]).some((e) => e.id === id && e.status === 'ACTIVE');
}

const app = (page: Page) => page.locator('mosaikit-ext-catalog');
const row = (page: Page, name: string) =>
  app(page).getByRole('table', { name: /^(Content of|Contenuto di) / }).getByRole('row').filter({ hasText: name });

test('GP-983 GP-986 without ext-catalog-geodata the installation manages files and connections, the geodata files show the placeholder of an absent extension, and a table with geometries is a plain piece without the use Map', async ({
  page,
}) => {
  test.setTimeout(400_000);
  // GP-986: a PostgreSQL connection with a table that holds geometries, as the connector names them.
  const source = await startPostgres({ directory: `.mosaikit-bench/gp986-absent-${Date.now().toString(36)}`, persistent: false, database: 'fonte' });
  const client = new pg.Client({ host: '127.0.0.1', port: source.port, user: source.user, password: source.password, database: 'fonte' });
  await client.connect();
  await client.query(`create type public.geometry as (x double precision, y double precision);
    create schema rete; create table rete.fermate (id integer primary key, nome text, posizione public.geometry);
    insert into rete.fermate values (1, 'Piazza', null)`);
  await client.end();
  const slug = bench().organization.slug;
  await api(bench().admin, `/api/v1/organizations/${slug}/members/${member(1).user}`, {
    method: 'PUT', body: JSON.stringify({ roles: ['organization-user', 'organization-admin'] }),
  });
  const secret = unique('gp986-absent').replaceAll(' ', '-');
  expect((await api(member(1), `/api/v1/organizations/${slug}/secrets`, {
    method: 'POST', body: JSON.stringify({ name: secret, kind: 'password', value: source.password }),
  })).status).toBe(201);
  const created = await api(owner(), '/api/v1/ext/connections/connections', {
    method: 'POST',
    body: JSON.stringify({ name: unique('Fonte assente'), type: 'postgres', credential: secret,
      settings: { host: '127.0.0.1', port: source.port, database: 'fonte', user: source.user, sslmode: 'disable' } }),
  });
  expect(created.status, JSON.stringify(created.body)).toBe(201);
  const conn = created.body as { id: string; name: string };
  const top = await folder(unique('Senza geodati'));
  const name = `${unique('fermate').toLowerCase().replace(/\W+/g, '_')}.geojson`;
  const geodata = await store(top.id, name, STOPS, 'application/geo+json');
  expect(geodata.fileKind).toEqual(expect.objectContaining({ extension: GEODATA, name: 'dataset' }));
  // The kind recorded its state on the file before the extension leaves.
  await expect.poll(async () => (await listed(top.id, name)).fileKind?.state ?? null, { timeout: 45_000 }).not.toBeNull();
  const state = (await listed(top.id, name)).fileKind!.state;

  const extensions = join(bench().installation, 'extensions');
  const aside = join(bench().work, 'removed-extensions');
  mkdirSync(aside, { recursive: true });
  const ours = readdirSync(extensions).filter((entry) => entry.startsWith('ext-catalog-geodata'));
  expect(ours.length).toBeGreaterThan(0);
  for (const entry of ours) {
    renameSync(join(extensions, entry), join(aside, entry));
  }
  await restart();
  try {
    expect(await active(GEODATA)).toBe(false);
    expect(await active(FILE_MANAGER)).toBe(true);
    expect(await active(CONNECTIONS)).toBe(true);

    // The geodata file keeps its kind and its state, not active; its content is still there.
    expect((await listed(top.id, name)).fileKind).toEqual(
      expect.objectContaining({ extension: GEODATA, name: 'dataset', state, active: false }),
    );
    const download = await api(owner(), `${CATALOG}/downloads`, { method: 'POST', body: JSON.stringify({ items: [geodata.id] }) });
    expect(download.status, JSON.stringify(download.body)).toBe(200);
    const url = (download.body as { entries: { url: string }[] }).entries[0]!.url;
    expect(await (await fetch(bench().baseUrl + url)).text()).toBe(STOPS);

    // Files are managed: a folder, a file stored (plain now, even a GeoJSON), renamed and deleted.
    const meanwhile = await store(top.id, 'nuove.geojson', STOPS, 'application/geo+json');
    expect(meanwhile.fileKind).toBeNull();
    const notes = await store(top.id, 'appunti.txt', 'Riunione alle dieci', 'text/plain');
    expect((await api(owner(), `${CATALOG}/items/${notes.id}/rename`, { method: 'POST', body: JSON.stringify({ name: 'verbale.txt' }) })).status)
      .toBeLessThan(300);
    expect((await api(owner(), `${CATALOG}/items/${notes.id}`, { method: 'DELETE' })).status).toBeLessThan(300);
    // Connections are managed: their list and the connectors installed answer.
    expect((await api(owner(), '/api/v1/ext/connections/connections')).status).toBe(200);
    expect((await api(owner(), '/api/v1/ext/connections/connectors')).status).toBe(200);
    // GP-986: the PostgreSQL connection lists its tables, the geometry column among the facts, and answers
    // queries, as any connection; the use Map is not offered (below, in its page).
    const contents = await api(owner(), `/api/v1/ext/connections/connections/${conn.id}/contents?parent=rete`);
    expect(contents.status, JSON.stringify(contents.body)).toBe(200);
    const fermate = (contents.body as { items: { name: string; facts: { geometry?: unknown[] } | null }[] }).items
      .find((p) => p.name === 'fermate');
    expect(fermate?.facts?.geometry).toBeTruthy();
    const query = await api(owner(), `/api/v1/ext/connections/connections/${conn.id}/query`, {
      method: 'POST', body: JSON.stringify({ statement: 'select nome from rete.fermate', limit: 10 }),
    });
    expect(query.status, JSON.stringify(query.body)).toBe(200);
    expect(JSON.stringify(query.body)).toContain('Piazza');
    // The API of the geodata is gone with them.
    expect((await api(owner(), '/api/v1/ext/catalog-geodata/uploads')).status).toBe(404);

    await signIn(page, owner());
    await expect(apps(page).getByRole('link', { name: 'Catalog', exact: true })).toBeVisible();
    await expect(apps(page).getByRole('link', { name: 'Connections', exact: true })).toBeVisible();
    await expect(apps(page).getByRole('link', { name: 'Geodata', exact: true })).toHaveCount(0);
    await page.goto(`${bench().baseUrl}/app/catalog?folder=${top.id}`);
    // The placeholder of the platform in place of the kind, its state and its actions (EXT-106).
    const placeholder = row(page, name).getByRole('note');
    await expect(placeholder).toContainText('Not available: dataset (kind of file)');
    await expect(row(page, name).getByRole('button', { name: /^Publish as a service/ })).toHaveCount(0);
    await expect(row(page, 'nuove.geojson').getByRole('note')).toHaveCount(0);
    await page.screenshot({ path: 'test-results/gp983-without-geodata.png', fullPage: true });

    // GP-986: in the page of the connections, the table with geometries offers no use Map.
    await page.goto(`${bench().baseUrl}/app/connections`);
    await page.getByRole('button', { name: conn.name, exact: true }).click();
    const region = page.getByRole('region', { name: `Contents of ${conn.name}` });
    await region.getByRole('button', { name: 'rete', exact: true }).click();
    await expect(region.locator('ul.entries > li[data-id="rete/fermate"]')).toBeVisible();
    await expect(region.locator(`ul.entries > li[data-id="rete/fermate"] li[data-use="${GEODATA}/map"]`)).toHaveCount(0);
    await page.screenshot({ path: 'test-results/gp986-without-geodata.png', fullPage: true });
  } finally {
    // Installed again whatever happened, for the other tests.
    for (const entry of ours) {
      if (existsSync(join(aside, entry))) {
        renameSync(join(aside, entry), join(extensions, entry));
      }
    }
    await restart();
    await source.stop();
  }

  // Back: the file it knew is geodata again, and the GeoJSON stored meanwhile is recognized.
  expect(await active(GEODATA)).toBe(true);
  await expect.poll(async () => (await listed(top.id, name)).fileKind?.active ?? null, { timeout: 45_000 }).toBe(true);
  await expect.poll(async () => (await listed(top.id, 'nuove.geojson')).fileKind?.name ?? null, { timeout: 60_000 })
    .toBe('dataset');
  const back = await listed(top.id, 'nuove.geojson');
  await expect.poll(async () => (await api(owner(), `/api/v1/ext/catalog-geodata/uploads/files/${back.id}`)).status,
    { timeout: 60_000 }).toBe(200);
});
