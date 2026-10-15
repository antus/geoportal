// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-937 with the loader sidecar (ADR-0019): GDAL loads the upload after the request that stored it,
 * the dataset is loading until the catalog takes the outcome over, and a file with several layers asks
 * which one the dataset loads. Runs with GEOPORTAL_TEST_LOADER=1 and the profile loader of
 * tests/geodata/compose.yaml.
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  state: string;
  stateCode: string | null;
  progress: number | null;
  layer: string | null;
  layers: { name: string; features: number }[];
  loaded: { crs: string; crsSource: string; features: number; table: string | null } | null;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const LOADER = !!process.env.GEOPORTAL_TEST_LOADER;
const TWO_LAYERS = readFileSync('src/test/resources/load/due-livelli.gpkg');

const owner = (): Person => member(0);
const colleague = (): Person => member(1);
const organization = (): string => bench().organization.slug;
const permission = (): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:upload-data`;

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function credential(value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(colleague(), `${path}/geodata`);
  if (value === null) {
    if (existing.status === 200) {
      await api(colleague(), `${path}/geodata`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name: 'geodata', kind: 'password', value, description: 'GP-937 geodata', extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(colleague(), `${path}/geodata`, { method: 'PUT', body })
      : await api(colleague(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function settle(id: string): Promise<Upload> {
  for (let i = 0; i < 240; i++) {
    const d = (await api(owner(), `${UPLOADS}/${id}`)).body as Upload;
    if (d.state !== 'loading') {
      return d;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('still loading');
}

test.describe.configure({ mode: 'serial' });
test.skip(!LOADER, 'GEOPORTAL_TEST_LOADER=1 with the profile loader of tests/geodata/compose.yaml');

test.beforeAll(async () => {
  await administrator(colleague(), true);
  try {
    expect((await api(colleague(), permission(), { method: 'PUT' })).status).toBe(200);
    await credential('geoserver:catalog-test-db');
  } finally {
    await administrator(colleague(), false);
  }
});

test.afterAll(async () => {
  await administrator(colleague(), true);
  try {
    await api(colleague(), permission(), { method: 'DELETE' });
    await credential(null);
  } finally {
    await administrator(colleague(), false);
  }
});

test('GP-937 the loader loads a file after the request, and the dataset is loading until then', async () => {
  const query = new URLSearchParams({ name: 'poi-esempio.gpkg' });
  const response = await api(owner(), `${UPLOADS}?${query.toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: readFileSync('src/main/resources/samples/poi-esempio.gpkg'),
  });
  expect(response.status).toBe(201);
  const submitted = response.body as Upload;
  expect(submitted.state).toBe('loading');
  expect(submitted.progress).toBe(0);
  const d = await settle(submitted.id);
  try {
    expect(d.state, JSON.stringify(d)).toBe('loaded');
    expect(d.loaded).toMatchObject({ crs: 'EPSG:4326', crsSource: 'file', features: 5 });
    expect(d.layer).toBe('poi_esempio');
  } finally {
    await api(owner(), `${UPLOADS}/${submitted.id}`, { method: 'DELETE' });
  }
});

test('GP-937 a GeoPackage with two layers asks which one the dataset loads', async () => {
  const query = new URLSearchParams({ name: 'due-livelli.gpkg' });
  const response = await api(owner(), `${UPLOADS}?${query.toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: TWO_LAYERS,
  });
  const d = await settle((response.body as Upload).id);
  try {
    expect(d.state).toBe('failed');
    expect(d.stateCode).toBe('several-layers');
    expect(d.layers.map((l) => l.name)).toEqual(['aree', 'altre']);
    const chosen = await api(owner(), `${UPLOADS}/${d.id}/load?layer=altre`, { method: 'POST' });
    expect(chosen.status).toBe(200);
    const loaded = await settle(d.id);
    expect(loaded.state).toBe('loaded');
    expect(loaded.layer).toBe('altre');
    expect(loaded.loaded).toMatchObject({ crs: 'EPSG:32633', features: 1 });
  } finally {
    await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
  }
});

test('GP-937 a person who only views a dataset does not take its job over, nor own its COG; the owner does', async () => {
  const response = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: 'rilievo-esempio.tif' }).toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: readFileSync('src/main/resources/samples/rilievo-esempio.tif'),
  });
  expect(response.status).toBe(201);
  const id = (response.body as Upload).id;
  try {
    const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', email: colleague().user }, level: 'view' }),
    });
    expect(grant.status).toBeLessThan(300);
    // The colleague reads until the job is over: the dataset stays loading, at 100%.
    let seen = (await api(colleague(), `${UPLOADS}/${id}`)).body as Upload;
    for (let i = 0; i < 120 && seen.progress !== 100; i++) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      seen = (await api(colleague(), `${UPLOADS}/${id}`)).body as Upload;
    }
    expect(seen.state).toBe('loading');
    expect(seen.progress).toBe(100);
    type File = { id: string; name: string; owner: string };
    const files = async (): Promise<File[]> => (await api(colleague(), `/api/v1/files/${EXTENSION}?limit=500`)).body as File[];
    expect((await files()).filter((f) => f.name.includes(id) && f.name.endsWith('.cog.tif'))).toHaveLength(0);

    // The owner reads it: the outcome is taken over, and the COG is the owner's, attached to the dataset.
    const loaded = (await api(owner(), `${UPLOADS}/${id}`)).body as Upload;
    expect(loaded.state).toBe('loaded');
    const all = await files();
    const upload = all.find((f) => f.name.includes(id) && f.name.endsWith('.tif') && !f.name.endsWith('.cog.tif'));
    const cog = all.find((f) => f.name.includes(id) && f.name.endsWith('.cog.tif'));
    expect(cog, 'the colleague reads the COG through the dataset').toBeDefined();
    expect(cog?.owner).toBe(upload?.owner);
    expect((await api(colleague(), `/api/v1/files/${EXTENSION}/${cog?.id ?? ''}`, { method: 'DELETE' })).status).toBe(403);
  } finally {
    await api(owner(), `${UPLOADS}/${id}`, { method: 'DELETE' });
  }
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-937 the catalog page shows the loading and lets the person choose the layer', async ({ page }) => {
    const title = unique('Aree');
    await signIn(page, owner());
    await openApp(page, 'Dati geografici');
    await page.getByRole('button', { name: 'Carica dati' }).click();
    const dialog = page.getByRole('dialog', { name: 'Carica dati' });
    await dialog.getByLabel('File da caricare').setInputFiles({ name: 'due_livelli.gpkg', mimeType: 'application/octet-stream', buffer: TWO_LAYERS });
    await dialog.getByLabel('Titolo').fill(title);
    await dialog.getByRole('button', { name: 'Carica', exact: true }).click();
    await expect(page.getByText(/^In caricamento \(\d+%\)$/).first()).toBeVisible();
    await page.getByRole('region', { name: 'Dati caricati' }).screenshot({ path: 'test-results/gp-937-loading.png' });

    const detail = page.getByRole('complementary', { name: title });
    await expect(detail).toContainText('Non caricato', { timeout: 30_000 });
    await expect(detail).toContainText('Il file contiene più livelli');
    const layer = detail.getByLabel('Livello da caricare');
    await expect(layer).toBeVisible();
    await layer.selectOption('altre');
    await detail.screenshot({ path: 'test-results/gp-937-layers.png' });
    await detail.getByRole('button', { name: 'Carica di nuovo' }).click();
    await expect(detail).toContainText('Caricato, da pubblicare', { timeout: 30_000 });
    await expect(detail).toContainText('Livello');
    await expect(detail).toContainText('altre');
    await expect(detail).toContainText('EPSG:32633 · dichiarato nel file');
    await detail.screenshot({ path: 'test-results/gp-937-layer-loaded.png' });

    page.once('dialog', (confirm) => void confirm.accept());
    await detail.getByRole('button', { name: 'Elimina' }).click();
    await expect(page.getByText(`«${title}» è stato eliminato.`)).toBeVisible();
  });
});
