// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-114, GP-115, GP-118 on the bench: one path from a loaded dataset to a service published by
 * GeoServer and listed in the catalog with its health; failures explained where they started; and
 * republish, repoint, unpublish and reconciliation without orphans. Without GEOPORTAL_TEST_GEOSERVER=1
 * only the permissions run; with it, the PostGIS and GeoServer of tests/geoserver/compose.yaml.
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  technicalName: string;
  state: string;
  stateReason: string | null;
  loaded: { table: string | null } | null;
}
interface Publication {
  name: string;
  dataset: string;
  datasetTitle: string;
  health: { status: string; code: string | null; reason: string | null };
  services: Record<string, string>;
  canPublish: boolean;
}
interface Problem {
  code: string;
  action: string;
  cause: string | null;
  reference: string;
  detail: string;
}
interface Item {
  kind: string;
  name: string;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const PROJECT = process.env.GEOPORTAL_TEST_COMPOSE_PROJECT ?? 'geoportal-catalog-test';
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');
const LOG = '.mosaikit-bench/installation.log';

/** Three stations of a test town (Centrale, Nord, Sud) in EPSG:32633. */
const STATIONS: [string, number, number][] = [
  ['Centrale', 292719.039, 4641793.153],
  ['Nord', 295196.583, 4642777.113],
  ['Sud', 291273.415, 4638645.888],
];
const geojson = (stations: [string, number, number][]): string =>
  JSON.stringify({
    type: 'FeatureCollection',
    crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
    features: stations.map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
  });
const LINE = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Linea' }, geometry: { type: 'LineString', coordinates: [[12.49, 41.89], [12.51, 41.9]] } }],
});
const RED = { fill: '#ff0000', fillOpacity: 1, stroke: '#000000', strokeWidth: 1, pointSize: 12, pointShape: 'square' };
const BLUE = { ...RED, fill: '#0000ff' };

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

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
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-114 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function upload(name: string, content: string): Promise<Upload> {
  const response = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name, title: name.replace(/\..*$/, '') })}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: content,
  });
  expect(response.status, JSON.stringify(response.body)).toBe(201);
  const d = response.body as Upload;
  expect(d.state, d.stateReason ?? '').toBe('loaded');
  return d;
}

async function dataset(id: string): Promise<Upload> {
  return (await api(owner(), `${UPLOADS}/${id}`)).body as Upload;
}

async function publish(d: Upload, layer: string, style: object = RED): Promise<{ status: number; body: unknown }> {
  return api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: d.id, layer, style }) });
}

async function geoserver(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('authorization', GS_AUTH);
  return fetch(GEOSERVER_URL + path, { ...init, headers });
}

/** A call of the gate of the catalog as a person, with the raw answer (images too). */
async function gate(person: Person, path: string): Promise<Response> {
  return fetch(bench().baseUrl + path, {
    headers: {
      authorization: 'Basic ' + Buffer.from(`${person.user}:${person.password}`).toString('base64'),
      'X-Mosaikit-Organization': organization(),
    },
  });
}

function sql(query: string): string {
  return execFileSync('docker', ['exec', `${PROJECT}-postgres-1`, 'psql', '-U', 'geoserver', '-d', 'geodata', '-At', '-c', query], {
    encoding: 'utf8',
  }).trim();
}

async function workspace(): Promise<string> {
  const plan = await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/alignment');
  return (plan.body as { workspace: string }).workspace;
}

/** Removes what a test left: its datasets (their layers go with them). */
async function remove(...items: Upload[]): Promise<void> {
  for (const d of items) {
    await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
  }
}

async function openCatalog(page: Page): Promise<void> {
  await signIn(page, owner());
  await openApp(page, 'Dati geografici');
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  // The third person sees nothing of the owner: a plain member.
  await administrator(member(2), false);
  await administrator(manager(), true);
  expect((await api(manager(), exception(owner(), 'upload-data'), { method: 'PUT' })).status).toBe(200);
  expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
  // GP-036: replacing the data of a published dataset needs the permission to edit layer data too.
  expect((await api(manager(), exception(owner(), 'edit-layer-data'), { method: 'PUT' })).status).toBe(200);
  if (GEOSERVER) {
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
  }
});

test.afterAll(async () => {
  await api(manager(), exception(owner(), 'upload-data'), { method: 'DELETE' });
  await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
  await api(manager(), exception(owner(), 'edit-layer-data'), { method: 'DELETE' });
  if (GEOSERVER) {
    await credential('geodata', null);
  }
  await administrator(manager(), false);
});

test('GP-114 publishing needs the permission publish and the level manage on the dataset', async () => {
  const someone = { dataset: '00000000-0000-0000-0000-000000000000', layer: 'gp114_x' };
  // Without the permission: refused before anything else.
  expect((await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' })).status).toBeLessThan(300);
  try {
    expect((await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify(someone) })).status).toBe(403);
    expect((await api(owner(), `${PUBLICATIONS}/nothing`, { method: 'DELETE' })).status).toBe(403);
  } finally {
    expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
  }
  // With it, but without the level on the dataset (here, no such dataset).
  expect((await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify(someone) })).status).toBe(404);
  expect((await api(owner(), `${PUBLICATIONS}/nothing`, { method: 'DELETE' })).status).toBe(404);
});

test.describe('on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(180_000);

  test('GP-114 a dataset published with a chosen name is a layer of the workspace, listed by WMS GetCapabilities and served through Geoportal', async () => {
    const d = await upload(`${unique('stazioni').toLowerCase()}.geojson`, geojson(STATIONS));
    const layer = unique('gp114_layer').toLowerCase().replace(/[^a-z0-9_]/g, '_');
    try {
      const published = await publish(d, layer);
      expect(published.status, JSON.stringify(published.body)).toBe(201);
      const p = published.body as Publication;
      expect(p).toMatchObject({ name: layer, dataset: d.id, canPublish: true });
      expect(p.health.status).toBe('ok');
      expect((await dataset(d.id)).state).toBe('published');

      const ws = await workspace();
      const caps = await (await geoserver(`/${ws}/wms?service=WMS&version=1.3.0&request=GetCapabilities`)).text();
      expect(caps).toContain(`<Name>${layer}</Name>`);
      const sld = await (await geoserver(`/rest/workspaces/${ws}/styles/${layer}.sld`)).text();
      expect(sld).toContain('#ff0000');
      expect((await (await geoserver(`/rest/workspaces/${ws}/layers/${layer}.json`)).json()).layer.defaultStyle.name).toContain(layer);

      // The service in the catalog, at addresses of Geoportal, never of GeoServer.
      const list = (await api(owner(), PUBLICATIONS)).body as Publication[];
      const listed = list.find((x) => x.name === layer);
      expect(listed?.services.wms).toBe(`${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&request=GetCapabilities`);
      const wms = await gate(owner(), listed!.services.wms!);
      expect(wms.status).toBe(200);
      const wmsText = await wms.text();
      expect(wmsText).toContain(`<Name>${layer}</Name>`);
      expect(wmsText).not.toContain('18680');
      expect(wmsText).toContain(`/api/v1/ext/catalog-geodata/publications/${layer}/ogc`);
      const map = await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&version=1.1.1&request=GetMap&layers=${layer}`
        + '&styles=&srs=EPSG:32633&bbox=290000,4637000,297000,4644000&width=64&height=64&format=image/png');
      expect(map.headers.get('content-type')).toContain('image/png');
      const wfs = await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wfs?service=WFS&version=2.0.0&request=GetFeature`
        + `&typeNames=${ws}:${layer}&outputFormat=application/json`);
      expect(((await wfs.json()) as { features: unknown[] }).features).toHaveLength(3);
      const wmts = await gate(owner(), listed!.services.wmts!);
      expect(wmts.status).toBe(200);
      const wmtsText = await wmts.text();
      expect(wmtsText).toContain(layer);
      expect(wmtsText).not.toContain('18680');

      // The gate serves read requests of this layer only, and only to those who see the dataset.
      expect((await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wfs?service=WFS&request=Transaction`)).status).toBe(400);
      expect((await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wms?request=GetMap&layers=${ws}:other`)).status).toBe(400);
      expect((await gate(member(2), listed!.services.wms!)).status).toBe(404);
      expect(((await api(member(2), PUBLICATIONS)).body as Publication[]).map((x) => x.name)).not.toContain(layer);

      // The same name for other data is refused.
      const other = await upload(`${unique('altre').toLowerCase()}.geojson`, geojson(STATIONS.slice(0, 1)));
      try {
        const taken = await publish(other, layer);
        expect(taken.status).toBe(409);
        expect((taken.body as Problem).code).toBe('name-in-use');
      } finally {
        await remove(other);
      }
      const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:layer.published`);
      expect(JSON.stringify(audit.body)).toContain(layer);
    } finally {
      await remove(d);
    }
  });

  test.describe('in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-114 the publishing dialog shows the GeoServer style next to the Geoportal style, and the service its health', async ({ page }) => {
      const d = await upload(`${unique('fermate').toLowerCase()}.geojson`, geojson(STATIONS));
      const layer = `gp114_${Date.now()}`;
      try {
        await openCatalog(page);
        await page.getByRole('row', { name: new RegExp(d.title) }).click();
        const detail = page.getByRole('complementary', { name: d.title });
        await detail.getByRole('button', { name: 'Pubblica' }).click();
        const dialog = page.getByRole('dialog', { name: `Pubblica «${d.title}»` });
        await expect(dialog.getByText('Workspace GeoServer dell’organizzazione')).toBeVisible();
        await expect(dialog.getByText('Stile Geoportal')).toBeVisible();
        const sld = dialog.getByLabel('Stile GeoServer (SLD tradotto)');
        await expect(sld).toContainText('<PointSymbolizer>');
        await dialog.getByLabel('Colore di riempimento').fill('#ff8800');
        await expect(sld).toContainText('<CssParameter name="fill">#ff8800</CssParameter>');
        await dialog.getByLabel('Forma del simbolo').selectOption('triangle');
        await expect(sld).toContainText('<WellKnownName>triangle</WellKnownName>');
        await dialog.getByLabel('Nome del livello').fill(layer);
        await page.screenshot({ path: 'test-results/gp-114-dialog.png', fullPage: true });
        await dialog.getByRole('button', { name: 'Pubblica' }).click();
        await expect(page.getByText(`Il livello ${layer} è pubblicato.`)).toBeVisible({ timeout: 30_000 });

        const row = page.getByRole('row', { name: new RegExp(layer) });
        await expect(row).toContainText('Risponde');
        await row.click();
        const panel = page.getByLabel(layer, { exact: true });
        await expect(panel).toContainText('Indirizzi del servizio');
        await expect(panel).toContainText(`/api/v1/ext/catalog-geodata/publications/${layer}/ogc/wms`);
        await expect(panel).not.toContainText('18680');
        await page.screenshot({ path: 'test-results/gp-114-published.png', fullPage: true });

        // GeoServer stops answering for the layer: removed by hand.
        const ws = await workspace();
        expect((await geoserver(`/rest/workspaces/${ws}/datastores/data/featuretypes/${layer}?recurse=true`, { method: 'DELETE' })).status).toBe(200);
        await panel.getByRole('button', { name: 'Controlla ora' }).click();
        await expect(row).toContainText('Non risponde');
        await expect(row).toContainText('GeoServer non conosce più questo livello.');
        await page.screenshot({ path: 'test-results/gp-114-failed.png', fullPage: true });
        const health = await api(owner(), `${PUBLICATIONS}/${layer}`);
        expect((health.body as Publication).health).toMatchObject({ status: 'failed', code: 'layer-missing' });
        const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:layer.health-failed`);
        expect(JSON.stringify(audit.body)).toContain(layer);

        // GP-118: republished under the same name, it answers again.
        await panel.getByRole('button', { name: 'Ripubblica' }).click();
        const again = page.getByRole('dialog', { name: `Ripubblica il livello ${layer}` });
        await expect(again.getByLabel('Nome del livello')).toHaveValue(layer);
        await again.getByRole('button', { name: 'Ripubblica' }).click();
        await expect(page.getByText(`Il livello ${layer} è stato ripubblicato.`)).toBeVisible({ timeout: 30_000 });
        await expect(page.getByRole('row', { name: new RegExp(layer) })).toContainText('Risponde');
      } finally {
        await remove(d);
      }
    });

    test('GP-115 a publication GeoServer refuses is explained in the dialog, with its reason, a retry and the reference of the log', async ({ page }) => {
      const d = await upload(`${unique('rifiutato').toLowerCase()}.geojson`, geojson(STATIONS));
      const layer = `gp115_${Date.now()}`;
      const ws = await workspace();
      try {
        // The table disappears behind the catalog: GeoServer cannot publish it.
        sql(`drop table ${ws}.${d.technicalName}`);
        await openCatalog(page);
        await page.getByRole('row', { name: new RegExp(d.title) }).click();
        await page.getByRole('complementary', { name: d.title }).getByRole('button', { name: 'Pubblica' }).click();
        const dialog = page.getByRole('dialog', { name: `Pubblica «${d.title}»` });
        await dialog.getByLabel('Nome del livello').fill(layer);
        await dialog.getByRole('button', { name: 'Pubblica' }).click();
        const alert = dialog.getByRole('alert');
        await expect(alert).toContainText('GeoServer ha rifiutato l’operazione.', { timeout: 30_000 });
        await expect(alert).toContainText('Risposta di GeoServer');
        await expect(alert).toContainText('Correggi e riprova.');
        await expect(alert.getByRole('button', { name: 'Riprova' })).toBeVisible();
        const text = (await alert.innerText()).trim();
        expect(text).not.toMatch(/<\w|\sat (org|java)\./);
        const reference = /GP-[0-9A-F]{8}/.exec(text)?.[0];
        expect(reference, text).toBeTruthy();
        await page.screenshot({ path: 'test-results/gp-115-refused.png', fullPage: true });

        // In the log and in the audit with the same reference; nothing left in GeoServer.
        expect(readFileSync(LOG, 'utf8')).toContain(reference!);
        const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:layer.publish-failed`);
        expect(JSON.stringify(audit.body)).toContain(reference!);
        expect((await geoserver(`/rest/workspaces/${ws}/styles/${layer}.json`)).status).toBe(404);
        expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/${layer}.json`)).status).toBe(404);
        expect((await dataset(d.id)).state).toBe('loaded');

        // Corrected (the data loaded again), the retry publishes it.
        expect((await api(owner(), `${UPLOADS}/${d.id}/load`, { method: 'POST' })).status).toBe(200);
        await alert.getByRole('button', { name: 'Riprova' }).click();
        await expect(page.getByText(`Il livello ${layer} è pubblicato.`)).toBeVisible({ timeout: 30_000 });
      } finally {
        await remove(d);
      }
    });
  });

  test('GP-118 republishing new data and a new style keeps the name and forgets the cached tiles', async () => {
    const d = await upload(`${unique('ripubblica').toLowerCase()}.geojson`, geojson(STATIONS));
    const layer = `gp118_re_${Date.now()}`;
    try {
      expect((await publish(d, layer, RED)).status).toBe(201);
      const ws = await workspace();
      const tile = `${PUBLICATIONS}/${layer}/ogc/gwc/service/wmts?service=WMTS&version=1.0.0&request=GetTile&layer=${ws}:${layer}`
        + '&style=&tilematrixset=EPSG:4326&tilematrix=EPSG:4326:9&tilerow=136&tilecol=547&format=image/png';
      const read = async (): Promise<Buffer> => {
        const answer = await gate(owner(), tile);
        expect(answer.headers.get('content-type')).toContain('image/png');
        return Buffer.from(await answer.arrayBuffer());
      };
      const before = await read();
      expect((await read()).equals(before)).toBe(true);

      // New data: a fourth station, by replacing the file.
      const replaced = await api(owner(), `${UPLOADS}/${d.id}/content?name=stazioni4.geojson`, {
        method: 'PUT',
        headers: { 'content-type': 'application/octet-stream' },
        body: geojson([...STATIONS, ['Ovest', 290006.0, 4640512.0]]),
      });
      expect(replaced.status).toBe(200);
      expect((replaced.body as Upload).state).toBe('published');
      const wfs = await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wfs?service=WFS&version=2.0.0&request=GetFeature`
        + `&typeNames=${ws}:${layer}&outputFormat=application/json`);
      expect(((await wfs.json()) as { features: unknown[] }).features).toHaveLength(4);
      // The tile of the cache is gone: drawn again, with the fourth station.
      const redrawn = await read();
      writeFileSync('test-results/gp-118-tile-before.png', before);
      writeFileSync('test-results/gp-118-tile-redrawn.png', redrawn);
      expect(redrawn.equals(before)).toBe(false);

      // New style, same name: the tile is drawn again, not taken from the cache.
      const again = await api(owner(), `${PUBLICATIONS}/${layer}`, { method: 'PUT', body: JSON.stringify({ style: BLUE }) });
      expect(again.status, JSON.stringify(again.body)).toBe(200);
      expect((again.body as Publication).name).toBe(layer);
      const after = await read();
      expect(after.equals(redrawn)).toBe(false);
      expect(await (await geoserver(`/rest/workspaces/${ws}/styles/${layer}.sld`)).text()).toContain('#0000ff');
    } finally {
      await remove(d);
    }
  });

  test('GP-118 a layer repointed to another dataset keeps its name and serves the new data', async () => {
    const a = await upload(`${unique('punti').toLowerCase()}.geojson`, geojson(STATIONS));
    const b = await upload(`${unique('linea').toLowerCase()}.geojson`, LINE);
    const layer = `gp118_rp_${Date.now()}`;
    try {
      expect((await publish(a, layer)).status).toBe(201);
      const moved = await api(owner(), `${PUBLICATIONS}/${layer}/repoint`, { method: 'POST', body: JSON.stringify({ dataset: b.id }) });
      expect(moved.status, JSON.stringify(moved.body)).toBe(200);
      expect(moved.body).toMatchObject({ name: layer, dataset: b.id });
      expect((moved.body as Publication).health.status).toBe('ok');
      const ws = await workspace();
      const ft = (await (await geoserver(`/rest/workspaces/${ws}/featuretypes/${layer}.json`)).json()) as {
        featureType: { nativeName: string; srs: string };
      };
      expect(ft.featureType).toMatchObject({ nativeName: b.technicalName, srs: 'EPSG:4326' });
      expect(await (await geoserver(`/rest/workspaces/${ws}/styles/${layer}.sld`)).text()).toContain('LineSymbolizer');
      expect((await dataset(a.id)).state).toBe('loaded');
      expect((await dataset(b.id)).state).toBe('published');
    } finally {
      await remove(a, b);
    }
  });

  test.describe('unpublishing, in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-118 unpublishing removes the layer, its style and its cached tiles from GeoServer, the data stay', async ({ page }) => {
      const d = await upload(`${unique('ritira').toLowerCase()}.geojson`, geojson(STATIONS));
      const layer = `gp118_un_${Date.now()}`;
      try {
        expect((await publish(d, layer)).status).toBe(201);
        const ws = await workspace();
        const tiled = async (): Promise<boolean> =>
          (await (await geoserver(`/${ws}/gwc/service/wmts?service=WMTS&request=GetCapabilities`)).text()).includes(`${ws}:${layer}<`);
        expect(await tiled()).toBe(true);

        await signIn(page, owner());
        await openApp(page, 'Dati geografici');
        await page.getByRole('row', { name: new RegExp(layer) }).click();
        await page.getByLabel(layer, { exact: true }).getByRole('button', { name: 'Ritira' }).click();
        const dialog = page.getByRole('dialog', { name: `Ritirare il livello ${layer}?` });
        await expect(dialog).toContainText('il livello, il suo stile e le tessere in cache');
        await page.screenshot({ path: 'test-results/gp-118-unpublish.png', fullPage: true });
        await dialog.getByRole('button', { name: 'Ritira' }).click();
        await expect(page.getByText(`Il livello ${layer} è stato ritirato.`)).toBeVisible({ timeout: 30_000 });

        expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/${layer}.json`)).status).toBe(404);
        expect((await geoserver(`/rest/workspaces/${ws}/styles/${layer}.json`)).status).toBe(404);
        expect(await tiled()).toBe(false);
        expect((await dataset(d.id)).state).toBe('loaded');
        expect(sql(`select count(*) from ${ws}.${d.technicalName}`)).toBe('3');
        const plan = (await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/reconciliation')).body as { unknown: Item[] };
        expect(plan.unknown.map((i) => i.name)).not.toContain(layer);
      } finally {
        await remove(d);
      }
    });
  });

  test('GP-114 the gate lets through only the reading of its layer, and never shows GeoServer or its credentials', async () => {
    test.setTimeout(240_000);
    const a = await upload(`${unique('cancello').toLowerCase()}.geojson`, geojson(STATIONS));
    const b = await upload(`${unique('altro').toLowerCase()}.geojson`, geojson(STATIONS.slice(0, 2)));
    const layer = `gp114_gate_${Date.now()}`;
    const other = `gp114_other_${Date.now()}`;
    const ws = await workspace();
    const base = `${PUBLICATIONS}/${layer}/ogc`;
    const secrets = ['18680', '127.0.0.1', 'catalog-test-admin', 'gsadmin', GS_AUTH.slice(6)];
    const clean = (text: string, what: string): void => {
      for (const s of secrets) {
        expect(text.includes(s), `${what} shows ${s}`).toBe(false);
      }
    };
    try {
      expect((await publish(a, layer)).status).toBe(201);
      expect((await publish(b, other)).status).toBe(201);
      const features = async (): Promise<number> =>
        ((await (await geoserver(`/${ws}/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=${ws}:${other}`
          + '&outputFormat=application/json')).json()) as { features: unknown[] }).features.length;
      expect(await features()).toBe(2);

      const tile = 'tilematrixset=EPSG:4326&tilematrix=EPSG:4326:9&tilerow=136&tilecol=547';
      const reads: [string, string][] = [
        ['wms', 'service=WMS&version=1.3.0&request=GetCapabilities'],
        ['wms', `service=WMS&version=1.1.1&request=GetMap&layers=${layer}&styles=&srs=EPSG:32633`
          + '&bbox=290000,4637000,297000,4644000&width=64&height=64&format=image/png'],
        ['wms', `service=WMS&version=1.1.1&request=GetFeatureInfo&layers=${layer}&query_layers=${ws}:${layer}&styles=`
          + '&srs=EPSG:32633&bbox=290000,4637000,297000,4644000&width=64&height=64&x=32&y=32&info_format=application/json'],
        ['wms', `service=WMS&version=1.1.1&request=GetLegendGraphic&layer=${layer}&format=image/png`],
        ['wms', `service=WMS&version=1.1.1&request=DescribeLayer&layers=${layer}`],
        ['wfs', 'service=WFS&version=2.0.0&request=GetCapabilities'],
        ['wfs', `service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=${layer}`],
        ['wfs', `service=WFS&version=2.0.0&request=GetFeature&typeNames=${ws}:${layer}&outputFormat=application/json`],
        ['wfs', `service=WFS&version=2.0.0&request=GetPropertyValue&typeNames=${layer}&valueReference=nome`],
        ['ows', `service=WFS&version=2.0.0&request=GetFeature&typeNames=${layer}&count=1`],
        ['gwc/service/wmts', 'service=WMTS&version=1.0.0&request=GetCapabilities'],
        ['gwc/service/wmts', `service=WMTS&version=1.0.0&request=GetTile&layer=${ws}:${layer}&style=&${tile}&format=image/png`],
        ['gwc/service/wmts', `service=WMTS&version=1.0.0&request=GetFeatureInfo&layer=${ws}:${layer}&style=&${tile}`
          + '&format=image/png&infoformat=application/json&i=128&j=128'],
      ];
      for (const [path, query] of reads) {
        const answer = await gate(owner(), `${base}/${path}?${query}`);
        const body = await answer.text();
        expect(answer.status, `${path}?${query}: ${body.slice(0, 300)}`).toBe(200);
        clean(body, `${path}?${query}`);
        clean(JSON.stringify([...answer.headers]), `headers of ${path}?${query}`);
        if (query.includes('GetCapabilities')) {
          expect(body, `${path} capabilities`).toContain(layer);
          expect(body, `${path} capabilities list only the layer`).not.toContain(other);
        }
      }

      const refused: string[] = [
        // Writes and other services.
        `wfs?service=WFS&version=2.0.0&request=Transaction`,
        `wfs?service=WFS&version=2.0.0&request=LockFeature&typeNames=${layer}`,
        `wfs?service=WFS&version=2.0.0&request=GetFeatureWithLock&typeNames=${layer}`,
        `ows?service=WPS&version=1.0.0&request=GetCapabilities`,
        `ows?service=WPS&version=1.0.0&request=Execute&identifier=gs:Bounds`,
        // Other paths of GeoServer.
        'rest/about/version.json?request=GetCapabilities',
        'gwc/rest/layers?request=GetCapabilities',
        'web/?request=GetCapabilities',
        'wms/../../rest/workspaces.json?request=GetCapabilities',
        'wms/%2e%2e/%2e%2e/rest/workspaces.json?request=GetCapabilities',
        // Spelling and duplicates.
        `wms?service=WMS&request=getmap&layers=${layer}`,
        `wms?service=WMS&request=GETMAP&layers=${layer}`,
        `wms?service=WMS&request=GetMap&request=GetMap&layers=${layer}`,
        `wfs?service=WFS&request=GetFeature&REQUEST=Transaction&typeNames=${layer}`,
        // Addresses fetched by GeoServer.
        `wms?service=WMS&request=GetMap&layers=${layer}&SLD=http%3A%2F%2Fexample.org%2Fx.sld`,
        `wms?service=WMS&request=GetMap&layers=${layer}&SLD_BODY=%3CStyledLayerDescriptor%2F%3E`,
        `wms?service=WMS&request=GetLegendGraphic&layer=${layer}&sld=http%3A%2F%2Fexample.org%2Fx.sld`,
        `wms?service=WMS&request=GetMap&layers=${layer}&REMOTE_OWS_TYPE=WFS&REMOTE_OWS_URL=http%3A%2F%2Fexample.org%2Fwfs`,
        // Other layers.
        `wms?service=WMS&request=GetMap&layers=${other}`,
        `wms?service=WMS&request=GetMap&layers=${ws}:${other}`,
        `wms?service=WMS&request=GetMap&layers=${layer},${other}`,
        `wms?service=WMS&request=GetMap&layers=*`,
        `wms?service=WMS&request=GetMap&layers=${ws}`,
        `wms?service=WMS&request=GetFeatureInfo&layers=${layer}&query_layers=${other}`,
        `wms?service=WMS&request=DescribeLayer&layers=${other}`,
        `wfs?service=WFS&request=GetFeature&typeNames=${other}`,
        `wfs?service=WFS&request=GetFeature&typeName=${ws}:${other}`,
        `wfs?service=WFS&request=GetFeature&typeNames=${layer},${other}`,
        `wfs?service=WFS&request=GetFeature&resourceId=${other}.1`,
        `wfs?service=WFS&request=GetFeature&STOREDQUERY_ID=urn:ogc:def:query:OGC-WFS::GetFeatureById&ID=${other}.1`,
        `wfs?service=WFS&request=GetFeature&typeNames=${layer}&CQL_FILTER=`
          + encodeURIComponent(`INTERSECTS(geom, querySingle('${ws}:${other}', 'geom', 'INCLUDE'))`),
        `wfs?service=WFS&request=GetFeature&typeNames=${layer}&cql_filter=`
          + encodeURIComponent(`intersects(geom, collectGeometries(queryCollection('${other}','geom','INCLUDE')))`),
        `gwc/service/wmts?service=WMTS&request=GetTile&layer=${ws}:${other}&${tile}&format=image/png`,
      ];
      for (const path of refused) {
        const answer = await gate(owner(), `${base}/${path}`);
        const body = await answer.text();
        expect([400, 404], `${path}: ${answer.status} ${body.slice(0, 200)}`).toContain(answer.status);
        if (answer.status === 400) {
          expect(JSON.parse(body), path).toMatchObject({ code: 'invalid', action: 'correct' });
        }
        clean(body, path);
      }

      // A Transaction in a body, whatever its casing, has no route: only GET and HEAD reach the gate.
      for (const operation of ['Transaction', 'transaction', 'TRANSACTION']) {
        const insert = `<wfs:${operation} service="WFS" version="1.1.0" xmlns:wfs="http://www.opengis.net/wfs">`
          + `<wfs:Insert><${ws}:${other} xmlns:${ws}="urn:x"><${ws}:nome>x</${ws}:nome></${ws}:${other}></wfs:Insert></wfs:${operation}>`;
        for (const path of ['wfs', 'ows', `wfs?service=WFS&request=GetCapabilities`]) {
          const posted = await fetch(`${bench().baseUrl}${base}/${path}`, {
            method: 'POST',
            headers: {
              authorization: 'Basic ' + Buffer.from(`${owner().user}:${owner().password}`).toString('base64'),
              'X-Mosaikit-Organization': organization(),
              'content-type': 'text/xml',
            },
            body: insert,
          });
          expect(posted.status, `POST ${path}`).toBeGreaterThanOrEqual(400);
          expect(posted.status, `POST ${path}`).toBeLessThan(500);
          clean(await posted.text(), `POST ${path}`);
        }
      }
      expect(await features()).toBe(2);
    } finally {
      await remove(a, b);
    }
  });

  test.describe('reconciliation, in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-118 the reconciliation lists layers unknown to Geoportal and Geoportal layers missing from GeoServer', async ({ page }) => {
      const d = await upload(`${unique('riconcilia').toLowerCase()}.geojson`, geojson(STATIONS));
      const layer = `gp118_rc_${Date.now()}`;
      const stray = `gp118_stray_${Date.now()}`;
      const ws = await workspace();
      try {
        expect((await publish(d, layer)).status).toBe(201);
        // By hand in GeoServer: a layer the catalog does not know, and the published one deleted.
        const made = await geoserver(`/rest/workspaces/${ws}/datastores/data/featuretypes`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ featureType: { name: stray, nativeName: d.technicalName, srs: 'EPSG:32633' } }),
        });
        expect(made.status).toBe(201);
        await geoserver(`/rest/workspaces/${ws}/datastores/data/featuretypes/${layer}?recurse=true`, { method: 'DELETE' });

        const report = (await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/reconciliation')).body as {
          unknown: Item[];
          missing: Item[];
          needsPerson: number;
        };
        expect(report.unknown).toContainEqual(expect.objectContaining({ kind: 'layer', name: stray }));
        expect(report.missing).toContainEqual(expect.objectContaining({ kind: 'layer', name: layer }));
        expect(report.needsPerson).toBeGreaterThanOrEqual(1);
        // Not for those who do not manage the GeoServer publication; the owner is given it here
        // (not an administrator, who would load the samples of the organization by opening the catalog).
        expect((await api(owner(), '/api/v1/ext/catalog-geodata/geoserver/reconciliation')).status).toBe(403);
        expect((await api(manager(), exception(owner(), 'manage-geoserver'), { method: 'PUT' })).status).toBe(200);

        await signIn(page, owner());
        await openApp(page, 'Dati geografici');
        const panel = page.getByLabel('Riconciliazione con GeoServer');
        await panel.getByRole('button', { name: 'Confronta' }).click();
        const unknown = panel.getByRole('group', { name: 'In GeoServer ma sconosciuti al catalogo (serve una persona)' });
        await expect(unknown).toContainText(stray);
        await expect(panel.getByRole('group', { name: 'Mancano in GeoServer' })).toContainText(layer);
        await page.screenshot({ path: 'test-results/gp-118-reconciliation.png', fullPage: true });

        // Aligned: the missing layer is back; the unknown one stays until a person decides.
        await panel.getByRole('button', { name: 'Allinea' }).click();
        await expect(panel.getByRole('group', { name: 'Mancavano in GeoServer: ricreati' })).toContainText(layer);
        expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/${layer}.json`)).status).toBe(200);
        expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/${stray}.json`)).status).toBe(200);

        // The person removes it.
        page.once('dialog', (confirm) => void confirm.accept());
        await unknown.getByRole('listitem').filter({ hasText: stray }).getByRole('button', { name: 'Rimuovi da GeoServer' }).click();
        await expect(page.getByText(`${stray} è stato rimosso da GeoServer.`)).toBeVisible();
        expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/${stray}.json`)).status).toBe(404);
      } finally {
        await api(manager(), exception(owner(), 'manage-geoserver'), { method: 'DELETE' });
        await geoserver(`/rest/workspaces/${ws}/datastores/data/featuretypes/${stray}?recurse=true`, { method: 'DELETE' });
        await remove(d);
      }
    });
  });

  test('GP-114 GP-115 when GeoServer stops answering, the health turns failed and publishing says to retry', async () => {
    const d = await upload(`${unique('spento').toLowerCase()}.geojson`, geojson(STATIONS));
    const layer = `gp115_down_${Date.now()}`;
    const container = `${PROJECT}-geoserver-1`;
    try {
      expect((await publish(d, layer)).status).toBe(201);
      execFileSync('docker', ['stop', container]);
      const health = await api(owner(), `${PUBLICATIONS}/${layer}/health`, { method: 'POST' });
      expect((health.body as Publication).health).toMatchObject({ status: 'failed', code: 'geoserver-unreachable' });
      const other = await upload(`${unique('spento2').toLowerCase()}.geojson`, geojson(STATIONS));
      try {
        const refused = await publish(other, `${layer}_b`);
        expect(refused.status).toBe(503);
        expect(refused.body).toMatchObject({ code: 'geoserver-unreachable', action: 'retry' });
        expect(JSON.stringify(refused.body)).not.toContain('18680');
      } finally {
        await remove(other);
      }
    } finally {
      execFileSync('docker', ['start', container]);
      for (let i = 0; i < 60; i++) {
        try {
          if ((await geoserver('/rest/about/version.json')).status === 200) {
            break;
          }
        } catch {
          // still starting
        }
        await new Promise((r) => setTimeout(r, 3000));
      }
      await remove(d);
    }
  });
});
