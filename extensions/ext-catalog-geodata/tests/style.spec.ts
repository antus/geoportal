// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { readFileSync, writeFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-009 on the bench: the style editor of the publishing dialog is a facade over a document of
 * geoportal/style/v1, which the catalog checks when it is saved, keeps, translates into SLD and gives
 * back. Without GEOPORTAL_TEST_GEOSERVER=1 only the checks of the API run; with it, the PostGIS and
 * GeoServer of tests/geoserver/compose.yaml draw the layers.
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  technicalName: string;
  state: string;
  stateReason: string | null;
}
interface Problem {
  code: string;
  detail: string;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');

/** Stations of a test town in EPSG:32633, with a name to label them by. */
const STATIONS = JSON.stringify({
  type: 'FeatureCollection',
  crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
  features: [
    ['Centrale', 292719.039, 4641793.153],
    ['Nord', 295196.583, 4642777.113],
    ['Sud', 291273.415, 4638645.888],
  ].map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
});

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

async function credential(name: string, value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-009 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function upload(name: string): Promise<Upload> {
  const response = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name, title: name.replace(/\..*$/, '') })}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: STATIONS,
  });
  expect(response.status, JSON.stringify(response.body)).toBe(201);
  const d = response.body as Upload;
  expect(d.state, d.stateReason ?? '').toBe('loaded');
  return d;
}

async function remove(...items: Upload[]): Promise<void> {
  for (const d of items) {
    await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
  }
}

async function geoserver(path: string): Promise<Response> {
  return fetch(GEOSERVER_URL + path, { headers: { authorization: GS_AUTH } });
}

async function workspace(): Promise<string> {
  const plan = await api(manager(), '/api/v1/ext/catalog-geodata/geoserver/alignment');
  return (plan.body as { workspace: string }).workspace;
}

/** The map of a layer through the gate of the catalog, as the person sees it. */
async function map(layer: string, bbox = '290000,4637000,297000,4644000'): Promise<Buffer> {
  const answer = await fetch(bench().baseUrl + `${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&version=1.1.1&request=GetMap`
    + `&layers=${layer}&styles=&srs=EPSG:32633&bbox=${bbox}&width=256&height=256&format=image/png`, {
    headers: {
      authorization: 'Basic ' + Buffer.from(`${owner().user}:${owner().password}`).toString('base64'),
      'X-Mosaikit-Organization': organization(),
    },
  });
  expect(answer.headers.get('content-type')).toContain('image/png');
  return Buffer.from(await answer.arrayBuffer());
}

async function publishDialog(page: Page, d: Upload): Promise<Locator> {
  await page.getByRole('row', { name: new RegExp(d.title) }).click();
  await page.getByRole('complementary', { name: d.title }).getByRole('button', { name: 'Pubblica' }).click();
  return page.getByRole('dialog', { name: `Pubblica «${d.title}»` });
}

/** The values of the panel of the style editor of a dialog. */
async function panel(dialog: Locator, labels: Record<string, string>): Promise<Record<string, string>> {
  const values: Record<string, string> = {};
  for (const [key, label] of Object.entries(labels)) {
    values[key] = await dialog.getByLabel(label).inputValue();
  }
  return values;
}

const POINT_PANEL = {
  type: 'Tipo di simbolo',
  shape: 'Forma del simbolo',
  fill: 'Colore di riempimento',
  fillOpacity: 'Opacità del riempimento',
  stroke: 'Colore del bordo',
  strokeWidth: 'Spessore del bordo (px)',
  size: 'Dimensione del simbolo (px)',
  labelField: 'Etichetta (attributo)',
  labelColor: 'Colore dell’etichetta',
  labelSize: 'Dimensione dell’etichetta (px)',
};

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  await administrator(manager(), true);
  expect((await api(manager(), exception(owner(), 'upload-data'), { method: 'PUT' })).status).toBe(200);
  expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
  if (GEOSERVER) {
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
  }
});

test.afterAll(async () => {
  await api(manager(), exception(owner(), 'upload-data'), { method: 'DELETE' });
  await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
  if (GEOSERVER) {
    await credential('geodata', null);
  }
  await administrator(manager(), false);
});

test('GP-009 a style that is not a document of the grammar is refused when it is saved, with where and why', async () => {
  const bad = { grammar: 'geoportal/style/v1', type: 'line', config: {}, style: { stroke: 'red', strokeOpacity: 1, strokeWidth: 1 } };
  const someone = '00000000-0000-0000-0000-000000000000';
  // Checked before anything else is looked at: the dataset, the name, GeoServer.
  for (const [path, body] of [
    [PUBLICATIONS, { dataset: someone, layer: 'gp009_x', style: bad }],
    [`${PUBLICATIONS}/style-translation`, { dataset: someone, style: bad }],
  ] as const) {
    const answer = await api(owner(), path, { method: 'POST', body: JSON.stringify(body) });
    expect(answer.status, JSON.stringify(answer.body)).toBe(400);
    expect((answer.body as Problem).code).toBe('invalid-style');
    expect((answer.body as Problem).detail).toContain('/style/stroke');
  }
  // A style of the six values of before the grammar is still taken, migrated: the dataset is what is missing.
  const legacy = { fill: '#ff0000', fillOpacity: 1, stroke: '#000000', strokeWidth: 1, pointSize: 12, pointShape: 'square' };
  const old = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: someone, layer: 'gp009_x', style: legacy }) });
  expect(old.status).toBe(404);
});

test.describe('on GeoServer, in Italian', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(240_000);
  test.use({ locale: 'it-IT' });

  test('GP-009 a style built in the editor, exported as JSON and applied to another layer draws identically', async ({ page }) => {
    const a = await upload(`${unique('stazioni_a').toLowerCase()}.geojson`);
    const b = await upload(`${unique('stazioni_b').toLowerCase()}.geojson`);
    const first = `gp009_a_${Date.now()}`;
    const second = `gp009_b_${Date.now()}`;
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, a);
      await dialog.getByLabel('Forma del simbolo').selectOption('triangle');
      await dialog.getByLabel('Colore di riempimento').fill('#e76f51');
      await dialog.getByLabel('Opacità del riempimento').fill('0.8');
      await dialog.getByLabel('Dimensione del simbolo (px)').fill('20');
      await dialog.getByLabel('Etichetta (attributo)').fill('nome');
      const sld = dialog.getByLabel('Stile GeoServer (SLD tradotto)');
      await expect(sld).toContainText('<WellKnownName>triangle</WellKnownName>');
      await expect(sld).toContainText('<ogc:PropertyName>nome</ogc:PropertyName>');
      await dialog.getByLabel('Nome del livello').fill(first);
      const download = page.waitForEvent('download');
      await dialog.getByRole('button', { name: 'Esporta JSON' }).click();
      const file = await download;
      const exported = test.info().outputPath(file.suggestedFilename());
      await file.saveAs(exported);
      const doc = JSON.parse(readFileSync(exported, 'utf8'));
      expect(doc).toMatchObject({ grammar: 'geoportal/style/v1', type: 'point', config: { shape: 'triangle' } });
      expect(doc.style).toMatchObject({ fill: '#e76f51', fillOpacity: 0.8, size: 20 });
      expect(doc.label.text).toEqual({ field: 'nome' });
      await dialog.getByRole('button', { name: 'Pubblica' }).click();
      await expect(page.getByText(`Il livello ${first} è pubblicato.`)).toBeVisible({ timeout: 30_000 });

      // The file, imported in the dialog of another dataset.
      const other = await publishDialog(page, b);
      const chooser = page.waitForEvent('filechooser');
      await other.getByRole('button', { name: 'Importa JSON…' }).click();
      await (await chooser).setFiles(exported);
      await expect(other.getByLabel('Colore di riempimento')).toHaveValue('#e76f51');
      await expect(other.getByLabel('Forma del simbolo')).toHaveValue('triangle');
      await other.getByLabel('Nome del livello').fill(second);
      await page.screenshot({ path: 'test-results/gp-009-imported.png', fullPage: true });
      await other.getByRole('button', { name: 'Pubblica' }).click();
      await expect(page.getByText(`Il livello ${second} è pubblicato.`)).toBeVisible({ timeout: 30_000 });

      // The same document, the same SLD but for its name, the same picture of the same data.
      const kept = (await api(owner(), `${PUBLICATIONS}/${second}`)).body as { styleSource: unknown };
      expect(kept.styleSource).toEqual(doc);
      const ws = await workspace();
      const sldOf = async (layer: string) =>
        (await (await geoserver(`/rest/workspaces/${ws}/styles/${layer}.sld`)).text()).replaceAll(layer, 'LAYER');
      expect(await sldOf(second)).toBe(await sldOf(first));
      const [mapA, mapB] = [await map(first), await map(second)];
      writeFileSync('test-results/gp-009-map-a.png', mapA);
      writeFileSync('test-results/gp-009-map-b.png', mapB);
      expect(mapB.equals(mapA)).toBe(true);
      // Not an empty picture: where there are no stations, the map is another one.
      expect(mapA.equals(await map(first, '390000,4737000,397000,4744000'))).toBe(false);
    } finally {
      await remove(a, b);
    }
  });

  test('GP-009 converting a style editor to document to editor gives back the same panel values', async ({ page }) => {
    const d = await upload(`${unique('stazioni_c').toLowerCase()}.geojson`);
    const layer = `gp009_c_${Date.now()}`;
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, d);
      await dialog.getByLabel('Forma del simbolo').selectOption('star');
      await dialog.getByLabel('Colore di riempimento').fill('#2a9d8f');
      await dialog.getByLabel('Opacità del riempimento').fill('0.65');
      await dialog.getByLabel('Colore del bordo').fill('#264653');
      await dialog.getByLabel('Spessore del bordo (px)').fill('2.5');
      await dialog.getByLabel('Dimensione del simbolo (px)').fill('17');
      await dialog.getByLabel('Etichetta (attributo)').fill('nome');
      await dialog.getByLabel('Colore dell’etichetta').fill('#111111');
      await dialog.getByLabel('Dimensione dell’etichetta (px)').fill('13');
      const before = await panel(dialog, POINT_PANEL);
      expect(before).toEqual({
        type: 'point', shape: 'star', fill: '#2a9d8f', fillOpacity: '0.65', stroke: '#264653', strokeWidth: '2.5', size: '17',
        labelField: 'nome', labelColor: '#111111', labelSize: '13',
      });
      await dialog.getByLabel('Nome del livello').fill(layer);
      await dialog.getByRole('button', { name: 'Pubblica' }).click();
      await expect(page.getByText(`Il livello ${layer} è pubblicato.`)).toBeVisible({ timeout: 30_000 });

      // The document the catalog kept, opened again in the editor.
      await page.getByRole('row', { name: new RegExp(layer) }).click();
      await page.getByLabel(layer, { exact: true }).getByRole('button', { name: 'Ripubblica' }).click();
      const again = page.getByRole('dialog', { name: `Ripubblica il livello ${layer}` });
      await expect(again.getByLabel('Nome del livello')).toHaveValue(layer);
      expect(await panel(again, POINT_PANEL)).toEqual(before);
      await expect(again.locator('.note')).toBeHidden();
      await page.screenshot({ path: 'test-results/gp-009-reopened.png', fullPage: true });
    } finally {
      await remove(d);
    }
  });

  test('GP-009 switching a point style to a line style keeps colour, opacity and label and maps point size to line width', async ({ page }) => {
    const d = await upload(`${unique('stazioni_d').toLowerCase()}.geojson`);
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, d);
      await expect(dialog.getByLabel('Tipo di simbolo')).toHaveValue('point');
      await dialog.getByLabel('Colore di riempimento').fill('#e76f51');
      await dialog.getByLabel('Opacità del riempimento').fill('0.7');
      await dialog.getByLabel('Dimensione del simbolo (px)').fill('14');
      await dialog.getByLabel('Etichetta (attributo)').fill('nome');
      await dialog.getByLabel('Tipo di simbolo').selectOption('line');
      expect(await panel(dialog, {
        stroke: 'Colore della linea',
        strokeOpacity: 'Opacità della linea',
        strokeWidth: 'Spessore della linea (px)',
        labelField: 'Etichetta (attributo)',
      })).toEqual({ stroke: '#e76f51', strokeOpacity: '0.7', strokeWidth: '14', labelField: 'nome' });
      await expect(dialog.getByLabel('Colore di riempimento')).toBeHidden();
      await dialog.getByText('Documento di stile (JSON)').click();
      const doc = JSON.parse(await dialog.getByLabel('Documento di stile (JSON)').inputValue());
      expect(doc).toMatchObject({
        type: 'line',
        style: { stroke: '#e76f51', strokeOpacity: 0.7, strokeWidth: 14 },
        label: { text: { field: 'nome' } },
      });
      await page.screenshot({ path: 'test-results/gp-009-point-to-line.png', fullPage: true });
    } finally {
      await page.keyboard.press('Escape');
      await remove(d);
    }
  });
});
