// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import { categorize, graduate, paletteColors, sortValues } from '../web/style-themes.js';

/**
 * GP-973 and GP-006 on the bench with the PostGIS and GeoServer of tests/geoserver/compose.yaml
 * (GEOPORTAL_TEST_GEOSERVER=1): the style editor of the publishing dialog with its menu of symbol
 * types, the live preview and the preview map of the features of the dataset, a style per geometry,
 * categorized styles generated from the values of an attribute with a palette; and the layer cards of
 * the published services, which show a sample of their symbology inline.
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  technicalName: string;
  state: string;
  stateReason: string | null;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GRAMMAR = 'geoportal/style/v1';

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

/** A square of side d around x, y, as a GeoJSON polygon. */
const square = (x: number, y: number, d = 0.004) => ({
  type: 'Polygon',
  coordinates: [[[x - d, y - d], [x + d, y - d], [x + d, y + d], [x - d, y + d], [x - d, y - d]]],
});

/** Three stations of a test town, points in WGS84. */
const STATIONS = {
  type: 'FeatureCollection',
  features: [
    ['Centrale', 12.30, 42.05],
    ['Nord', 12.33, 42.07],
    ['Sud', 12.28, 42.02],
  ].map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
};

/** Points and polygons in one dataset. */
const MIXED = {
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: { nome: 'Pozzo' }, geometry: { type: 'Point', coordinates: [12.30, 42.05] } },
    { type: 'Feature', properties: { nome: 'Fonte' }, geometry: { type: 'Point', coordinates: [12.34, 42.06] } },
    { type: 'Feature', properties: { nome: 'Lotto A' }, geometry: square(12.31, 42.03) },
    { type: 'Feature', properties: { nome: 'Lotto B' }, geometry: square(12.33, 42.04) },
  ],
};

/** Twelve areas of four kinds, with the people who live there. */
const KINDS = ['prato', 'bosco', 'orto', 'cava'];
const AREAS = {
  type: 'FeatureCollection',
  features: Array.from({ length: 12 }, (_, i) => ({
    type: 'Feature',
    properties: { nome: `Area ${i + 1}`, tipo: KINDS[i % 4], abitanti: 100 * (i + 1) },
    geometry: square(12.3 + (i % 4) * 0.01, 42.0 + Math.floor(i / 4) * 0.01),
  })),
};

async function credential(name: string, value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-973 ${name}`, extension: EXTENSION });
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

async function upload(prefix: string, content: object | Buffer, extension = 'geojson'): Promise<Upload> {
  const name = `${unique(prefix).toLowerCase()}.${extension}`;
  const response = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name, title: name.replace(/\..*$/, '') })}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: Buffer.isBuffer(content) ? content : JSON.stringify(content),
  });
  expect(response.status, JSON.stringify(response.body)).toBe(201);
  const d = response.body as Upload;
  expect(d.state, d.stateReason ?? '').toBe('loaded');
  return d;
}

async function remove(...items: (Upload | undefined)[]): Promise<void> {
  for (const d of items) {
    if (d) {
      await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
    }
  }
}

async function publish(d: Upload, layer: string, style?: object): Promise<void> {
  const answer = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: d.id, layer, style }) });
  expect(answer.status, JSON.stringify(answer.body)).toBe(201);
}

async function publishDialog(page: Page, d: Upload): Promise<Locator> {
  await page.getByRole('row', { name: new RegExp(d.title) }).click();
  await page.getByRole('complementary', { name: d.title }).getByRole('button', { name: 'Pubblica' }).click();
  return page.getByRole('dialog', { name: `Pubblica «${d.title}»` });
}

/** The document of the dialog, as its JSON says. */
async function documentOf(dialog: Locator): Promise<any> {
  return JSON.parse(await dialog.getByLabel('Documento di stile (JSON)').inputValue());
}

/** The preview map of a dialog, once the sample of the features is drawn. */
async function previewMap(dialog: Locator, features: number): Promise<Locator> {
  const map = dialog.getByRole('img', { name: 'Anteprima sulla mappa' });
  await expect(map).toHaveAttribute('data-features', String(features), { timeout: 20_000 });
  return map;
}

test.describe.configure({ mode: 'serial' });

test.describe('GP-973 and GP-006 on GeoServer, in Italian', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(240_000);
  test.use({ locale: 'it-IT' });

  test.beforeAll(async () => {
    await administrator(manager(), true);
    for (const p of ['upload-data', 'publish']) {
      expect((await api(manager(), exception(owner(), p), { method: 'PUT' })).status).toBe(200);
    }
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
  });

  test.afterAll(async () => {
    for (const p of ['upload-data', 'publish']) {
      await api(manager(), exception(owner(), p), { method: 'DELETE' });
    }
    await credential('geodata', null);
    await administrator(manager(), false);
  });

  test('GP-973 choosing a symbol type updates the live preview and the map', async ({ page }) => {
    const d = await upload('stazioni_gp973', STATIONS);
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, d);
      const map = await previewMap(dialog, 3);
      await expect(map.locator('path.feature[data-shape=circle]')).toHaveCount(3);
      const menu = dialog.getByRole('radiogroup', { name: 'Simbolo' });
      await expect(menu.getByRole('radio')).toHaveCount(6);
      await expect(menu.getByRole('radio', { name: 'Cerchio' })).toHaveAttribute('aria-checked', 'true');

      await menu.getByRole('radio', { name: 'Stella' }).click();
      await expect(menu.getByRole('radio', { name: 'Stella' })).toHaveAttribute('aria-checked', 'true');
      // The live preview of the symbol, the map, the panel, the document and the SLD follow.
      const preview = dialog.getByRole('img', { name: 'Anteprima del simbolo' });
      await expect(preview.locator('path[data-shape=star]')).toHaveCount(1);
      await expect(map.locator('path.feature[data-shape=star]')).toHaveCount(3);
      await expect(dialog.getByLabel('Forma del simbolo')).toHaveValue('star');
      await expect(dialog.getByLabel('Stile GeoServer (SLD tradotto)')).toContainText('<WellKnownName>star</WellKnownName>');
      // A colour of the panel is drawn on the map too.
      await dialog.getByLabel('Colore di riempimento').fill('#e76f51');
      await expect(map.locator('path.feature[fill="#e76f51"]')).toHaveCount(3);
      await expect(preview.locator('path[fill="#e76f51"]')).toHaveCount(1);

      // The symbol types of a line: a dashed line is drawn dashed.
      await dialog.getByLabel('Tipo di simbolo').selectOption('line');
      await expect(menu.getByRole('radio')).toHaveCount(3);
      await menu.getByRole('radio', { name: 'Linea tratteggiata' }).click();
      expect((await documentOf(dialog)).config.dash).toEqual([8, 4]);
      await expect(preview.locator('path[stroke-dasharray="8 4"]')).toHaveCount(1);
      await expect(dialog.getByLabel('Stile GeoServer (SLD tradotto)')).toContainText('stroke-dasharray');
      await page.screenshot({ path: 'test-results/gp-973-symbol-types.png', fullPage: true });
    } finally {
      await page.keyboard.press('Escape');
      await remove(d);
    }
  });

  test('GP-973 a layer with points and polygons can have a different style for each geometry', async ({ page }) => {
    const d = await upload('misti_gp973', MIXED);
    const layer = `gp973_mixed_${Date.now()}`;
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, d);
      const map = await previewMap(dialog, 4);
      await dialog.getByLabel('Tipo di simbolo').selectOption('mixed');
      const geometry = dialog.getByLabel('Geometria da modificare');
      await geometry.selectOption('point');
      await dialog.getByRole('radio', { name: 'Stella' }).click();
      await dialog.getByLabel('Colore di riempimento').fill('#d62828');
      await geometry.selectOption('polygon');
      await expect(dialog.getByRole('radio')).toHaveCount(2);
      await dialog.getByLabel('Colore di riempimento').fill('#2a9d8f');
      await dialog.getByLabel('Colore del bordo').fill('#264653');
      // Back on the points, their own values.
      await geometry.selectOption('point');
      await expect(dialog.getByLabel('Colore di riempimento')).toHaveValue('#d62828');
      await expect(dialog.getByLabel('Forma del simbolo')).toHaveValue('star');

      const doc = await documentOf(dialog);
      expect(doc.type).toBe('mixed');
      expect(doc.geometries.point).toMatchObject({ config: { shape: 'star' }, style: { fill: '#d62828' } });
      expect(doc.geometries.polygon.style).toMatchObject({ fill: '#2a9d8f', stroke: '#264653' });
      await expect(map.locator('g[data-kind=point] path.feature[fill="#d62828"][data-shape=star]')).toHaveCount(2);
      await expect(map.locator('g[data-kind=polygon] path.feature[fill="#2a9d8f"]')).toHaveCount(2);
      await page.screenshot({ path: 'test-results/gp-973-per-geometry.png', fullPage: true });

      await dialog.getByLabel('Nome del livello').fill(layer);
      await dialog.getByRole('button', { name: 'Pubblica' }).click();
      await expect(page.getByText(`Il livello ${layer} è pubblicato.`)).toBeVisible({ timeout: 30_000 });
      const kept = (await api(owner(), `${PUBLICATIONS}/${layer}`)).body as { styleSource: any };
      expect(kept.styleSource.geometries.point.style.fill).toBe('#d62828');
      expect(kept.styleSource.geometries.polygon.style.fill).toBe('#2a9d8f');
    } finally {
      await remove(d);
    }
  });

  test('GP-973 a categorized style generates one class per distinct value with colours from the chosen palette', async ({ page }) => {
    const d = await upload('aree_gp973', AREAS);
    const layer = `gp973_cat_${Date.now()}`;
    try {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const dialog = await publishDialog(page, d);
      const map = await previewMap(dialog, 12);
      const theme = dialog.getByRole('group', { name: 'Stile tematico' });
      await expect(theme.getByLabel('Proprietà')).toHaveValue('fill');
      await theme.getByLabel('Valori', { exact: true }).selectOption('categories');
      await theme.getByLabel('Attributo', { exact: true }).selectOption('tipo');
      await theme.getByLabel('Tavolozza').selectOption('earth');
      const values = sortValues(KINDS);
      const colors = paletteColors('earth', values.length);
      await expect.poll(async () => (await documentOf(dialog)).style.fill?.categories?.map((c: any) => c.value), { timeout: 20_000 })
        .toEqual(colors);
      const fill = (await documentOf(dialog)).style.fill;
      expect(fill.field).toBe('tipo');
      expect(fill.categories.map((c: any) => c.equals)).toEqual(values);
      await expect(theme.getByRole('status')).toHaveText('4 categorie da «tipo», una per valore.');
      // One row per class and one for the other values, each with its colour.
      const classes = theme.getByRole('list', { name: 'Classi' }).getByRole('listitem');
      await expect(classes).toHaveCount(values.length + 1);
      await expect(theme.getByLabel('Valore della classe bosco')).toHaveValue(colors[values.indexOf('bosco')]!);
      // The panel says the colour comes from the data; the map draws each area with its class.
      await expect(dialog.getByLabel('Colore di riempimento')).toBeDisabled();
      await expect(dialog.locator('label[data-key=fill] .bound')).toHaveText('dal dato «tipo»');
      for (const [i, value] of values.entries()) {
        await expect(map.locator(`path.feature[fill="${colors[i]}"]`), value).toHaveCount(3);
      }
      // Another palette, other colours; a colour of a class changed by hand.
      await theme.getByLabel('Tavolozza').selectOption('vivid');
      await expect.poll(async () => (await documentOf(dialog)).style.fill.categories.map((c: any) => c.value))
        .toEqual(paletteColors('vivid', 4));
      await theme.getByLabel('Valore della classe orto').fill('#123456');
      await expect.poll(async () => (await documentOf(dialog)).style.fill.categories.find((c: any) => c.equals === 'orto').value)
        .toBe('#123456');
      await expect(map.locator('path.feature[fill="#123456"]')).toHaveCount(3);
      // The SLD has one rule per class, titled with its value: the legend of GeoServer shows them.
      const sld = dialog.getByLabel('Stile GeoServer (SLD tradotto)');
      for (const value of values) {
        await expect(sld).toContainText(`<Title>${value}</Title>`);
      }
      // Graduated by a number, with as many classes as asked.
      await theme.getByLabel('Valori', { exact: true }).selectOption('graduated');
      await theme.getByLabel('Attributo', { exact: true }).selectOption('abitanti');
      await theme.getByLabel('Numero di classi').fill('3');
      await theme.getByLabel('Numero di classi').dispatchEvent('change');
      await expect.poll(async () => (await documentOf(dialog)).style.fill.graduated?.breaks).toEqual([467, 833]);
      await page.screenshot({ path: 'test-results/gp-973-thematic.png', fullPage: true });

      await dialog.getByLabel('Nome del livello').fill(layer);
      await dialog.getByRole('button', { name: 'Pubblica' }).click();
      await expect(page.getByText(`Il livello ${layer} è pubblicato.`)).toBeVisible({ timeout: 30_000 });
    } finally {
      await remove(d);
    }
  });

  test.describe('GP-006 layer cards', () => {
    let areas: Upload | undefined;
    let raster: Upload | undefined;
    const graduatedLayer = `gp006_grad_${Date.now()}`;
    const categoriesLayer = `gp006_cat_${Date.now()}`;
    const rasterLayer = `gp006_wms_${Date.now()}`;
    const steps = paletteColors('blues', 5);
    const twelve = Array.from({ length: 12 }, (_, i) => `t${String(i + 1).padStart(2, '0')}`);
    const chips = paletteColors('vivid', 12);

    test.beforeAll(async () => {
      areas = await upload('aree_gp006', AREAS);
      const polygon = (fill: object) => ({
        grammar: GRAMMAR, type: 'polygon', config: {},
        style: { fill, fillOpacity: 0.7, stroke: '#333333', strokeWidth: 1 },
      });
      await publish(areas, graduatedLayer, polygon(graduate('abitanti', [300, 500, 700, 900], steps)));
      await publish(areas, categoriesLayer, polygon(categorize('tipo', twelve, chips, '#bbbbbb')));
      raster = await upload('rilievo_gp006', readFileSync('src/main/resources/samples/rilievo-esempio.tif'), 'tif');
      await publish(raster, rasterLayer);
    });

    test.afterAll(async () => {
      await remove(areas, raster);
    });

    test('GP-006 a graduated layer card shows a strip with the colours of its classes in order', async ({ page }) => {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const card = page.getByRole('row', { name: new RegExp(graduatedLayer) });
      const strip = card.getByRole('img', { name: /Classi di «abitanti»/ });
      await expect(strip).toBeVisible();
      await expect(strip.locator('.class')).toHaveCount(5);
      expect(await strip.locator('.class').evaluateAll((e) => e.map((x) => (x as HTMLElement).dataset.color))).toEqual(steps);
      // The colours are painted, not only named.
      const painted = await strip.locator('.class').evaluateAll((e) => e.map((x) => getComputedStyle(x).backgroundColor));
      expect(new Set(painted).size).toBe(5);
      await card.screenshot({ path: 'test-results/gp-006-graduated.png' });
    });

    test('GP-006 a categorized layer card shows one chip per category colour, up to a visible limit', async ({ page }) => {
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      const card = page.getByRole('row', { name: new RegExp(categoriesLayer) });
      const box = card.getByRole('img', { name: /Categorie di «tipo»/ });
      await expect(box.locator('.chip')).toHaveCount(8);
      expect(await box.locator('.chip').evaluateAll((e) => e.map((x) => (x as HTMLElement).dataset.color))).toEqual(chips.slice(0, 8));
      await expect(box.locator('.more')).toHaveText('+4');
      await expect(box).toHaveAttribute('aria-label', `Categorie di «tipo»: ${twelve.join(', ')}`);
      await card.screenshot({ path: 'test-results/gp-006-categories.png' });
    });

    test('GP-006 a WMS layer card shows the image returned by GetLegendGraphic for that layer', async ({ page }) => {
      await signIn(page, owner());
      const legend = page.waitForResponse((r) => r.url().includes('request=GetLegendGraphic') && r.url().includes(`layer=${rasterLayer}`));
      await openApp(page, 'Dati geografici');
      const answer = await legend;
      expect(answer.status()).toBe(200);
      expect(answer.headers()['content-type']).toContain('image/png');
      const card = page.getByRole('row', { name: new RegExp(rasterLayer) });
      const image = card.getByRole('img', { name: `Legenda di ${rasterLayer} dal server delle mappe` });
      await expect(image).toBeVisible();
      await expect.poll(() => image.evaluate((i) => (i as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
      await card.screenshot({ path: 'test-results/gp-006-wms.png' });
      // The detail of a layer of Geoportal shows the legend of GeoServer too, with the titles of its classes.
      await page.getByRole('row', { name: new RegExp(categoriesLayer) }).click();
      const detail = page.getByLabel(categoriesLayer, { exact: true });
      const full = detail.getByRole('img', { name: `Legenda di ${categoriesLayer} dal server delle mappe` });
      await expect.poll(() => full.evaluate((i) => (i as HTMLImageElement).naturalHeight), { timeout: 20_000 }).toBeGreaterThan(100);
      await page.screenshot({ path: 'test-results/gp-006-legend.png', fullPage: true });
    });
  });
});
