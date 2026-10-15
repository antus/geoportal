// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test, type Page } from '@playwright/test';
import { api, bench, member, signIn, unique } from '@mosaikit/testing/playwright';
import { FILE_MANAGER, GEODATA } from './composition.js';

/**
 * GP-983, criterion 2 (ADR-0028): a geodata file is a file of the file manager of mosaikit-ext, of the
 * kind dataset of ext-catalog-geodata (EXT-105): recognized when stored, made into a dataset of the
 * geodata in a job of the organization, with its state on the file, its geospatial preview and the
 * action Publish; the levels on the file and on its folders reach the dataset (MK-136); replacing the
 * file loads it again and deleting it deletes the dataset. With GEOPORTAL_TEST_GEOSERVER=1 the file is
 * loaded into the PostGIS of tests/geoserver/compose.yaml and published from the catalog.
 */

type Person = ReturnType<typeof member>;
interface Item {
  id: string;
  kind: 'folder' | 'file';
  name: string;
  fileKind: { extension: string; name: string; state: string | null; stateDetail: string | null } | null;
}
interface Folder {
  items: Item[];
}
interface Dataset {
  id: string;
  title: string;
  technicalName: string;
  format: string;
  state: string;
  stateReason: string | null;
  level: string;
  version: number;
  catalogFile: string | null;
}

const CATALOG = '/api/v1/ext/catalog';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;

const owner = (): Person => member(0);
const colleague = (): Person => member(1);
const manager = (): Person => member(2);
const organization = (): string => bench().organization.slug;
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${GEODATA}:${permission}`;

/** Three stops of a fictitious town, in WGS84. */
const STOPS = JSON.stringify({
  type: 'FeatureCollection',
  features: [
    ['Piazza', 12.4964, 41.9028],
    ['Stazione', 12.5011, 41.9009],
    ['Porto', 12.4889, 41.8986],
  ].map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
});
const LINE = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Linea' }, geometry: { type: 'LineString', coordinates: [[12.49, 41.89], [12.51, 41.9]] } }],
});

async function folder(person: Person, name: string): Promise<Item> {
  const { status, body } = await api(person, `${CATALOG}/folders`, { method: 'POST', body: JSON.stringify({ name, parent: null }) });
  expect(status, JSON.stringify(body)).toBe(201);
  return body as Item;
}

async function store(person: Person, parent: string, name: string, content: string, type = 'application/geo+json'): Promise<Item> {
  const { status, body } = await api(person, `${CATALOG}/folders/${parent}/files?name=${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'content-type': type },
    body: content,
  });
  expect(status, JSON.stringify(body)).toBe(201);
  return body as Item;
}

async function listed(person: Person, parent: string, name: string): Promise<Item | undefined> {
  const { body } = await api(person, `${CATALOG}/folders/${parent}`);
  return (body as Folder).items?.find((item) => item.name === name);
}

/** The dataset of a file once the job of the catalog has made it, as a person sees it. */
async function datasetOf(person: Person, file: string, state?: string): Promise<Dataset> {
  await expect
    .poll(async () => {
      const { status, body } = await api(person, `${UPLOADS}/files/${file}`);
      return status === 200 && (!state || (body as Dataset).state === state) ? 'ready' : `${status} ${(body as Dataset).state ?? ''}`;
    }, { timeout: 45_000 })
    .toBe('ready');
  return (await api(person, `${UPLOADS}/files/${file}`)).body as Dataset;
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
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-983 ${name}`, extension: GEODATA });
  const { status } = existing.status === 200
    ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
    : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

/** The account of a person, by which the sharing of Mosaikit names a person. */
async function accountOf(person: Person): Promise<string> {
  const { status, body } = await api(person, '/api/v1/accounts/me');
  expect(status).toBe(200);
  return (body as { id: string }).id;
}

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

const app = (page: Page) => page.locator('mosaikit-ext-catalog');
const row = (page: Page, name: string) =>
  app(page).getByRole('table', { name: /^(Content of|Contenuto di) / }).getByRole('row').filter({ hasText: name });
const detail = (page: Page) => app(page).locator('section.detail');

async function openFolder(page: Page, id: string): Promise<void> {
  await page.goto(`${bench().baseUrl}/app/catalog?folder=${id}`);
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  await administrator(manager(), true);
  if (GEOSERVER) {
    expect((await api(manager(), exception(owner(), 'publish'), { method: 'PUT' })).status).toBe(200);
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
  }
});

test.afterAll(async () => {
  if (GEOSERVER) {
    await api(manager(), exception(owner(), 'publish'), { method: 'DELETE' });
    await credential('geodata', null);
  }
  await administrator(manager(), false);
});

test('GP-983 a GeoJSON stored in the file manager is a geodata file, with its state and its geospatial preview', async ({ page }) => {
  const top = await folder(owner(), unique('Geodati'));
  const name = `${unique('fermate').toLowerCase().replace(/\W+/g, '_')}.geojson`;
  const stored = await store(owner(), top.id, name, STOPS);
  // Recognized while it is stored (EXT-105): the kind dataset of the geodata.
  expect(stored.fileKind).toEqual(expect.objectContaining({ extension: GEODATA, name: 'dataset' }));
  // A file that is not geodata stays a plain file.
  const plain = await store(owner(), top.id, 'appunti.txt', 'Riunione alle dieci', 'text/plain');
  expect(plain.fileKind).toBeNull();

  // The job of the catalog made the dataset of the file: its parent is the file, the owner of the file manages it.
  const d = await datasetOf(owner(), stored.id);
  expect(d).toMatchObject({ catalogFile: stored.id, format: 'geojson', level: 'manage' });
  const all = (await api(owner(), UPLOADS)).body as Dataset[];
  expect(all.map((x) => x.id)).toContain(d.id);
  // Its state is on the file, as the catalog lists it.
  await expect.poll(async () => (await listed(owner(), top.id, name))?.fileKind?.state ?? null, { timeout: 30_000 })
    .toBe(GEOSERVER ? 'loaded' : d.state);

  await signIn(page, owner());
  await openFolder(page, top.id);
  await expect(row(page, name).getByText('Geodata', { exact: true })).toBeVisible();
  await expect(row(page, name).locator('img.kind')).toHaveAttribute('src', `/api/v1/extension-assets/${GEODATA}/web/dataset.svg`);
  await row(page, name).getByRole('button', { name: `Details of ${name}` }).click();
  const preview = detail(page).getByRole('region', { name: `Geospatial preview of ${name}` });
  await expect(preview).toBeVisible();
  await expect(preview).toContainText('GeoJSON');
  // The footprint: the extent and the three stops of the file.
  const footprint = preview.getByRole('img', { name: `Footprint of ${name}` });
  await expect(footprint).toBeVisible();
  await expect(footprint.locator('[data-part="feature"]')).toHaveCount(3);
  await page.screenshot({ path: 'test-results/gp983-catalog-preview.png', fullPage: true });
});

test('GP-983 the levels on a folder of the catalog reach the datasets of its files (ADR-0028)', async () => {
  const top = await folder(owner(), unique('Condivisi'));
  const stored = await store(owner(), top.id, `${unique('linea').toLowerCase().replace(/\W+/g, '_')}.geojson`, LINE);
  const d = await datasetOf(owner(), stored.id);
  // The colleague reads neither the folder nor the dataset.
  expect((await api(colleague(), `${UPLOADS}/files/${stored.id}`)).status).toBe(404);
  expect((await api(colleague(), `${UPLOADS}/${d.id}`)).status).toBe(404);

  // Shared at read in the catalog: the dataset is theirs at read (view and download), not to edit.
  const shared = await api(owner(), `/api/v1/sharing/resources/${FILE_MANAGER}/folder/${top.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'read' }),
  });
  expect(shared.status, JSON.stringify(shared.body)).toBeLessThan(300);
  const seen = await datasetOf(colleague(), stored.id);
  expect(seen.level).toBe('read');
  expect(((await api(colleague(), UPLOADS)).body as Dataset[]).map((x) => x.id)).toContain(d.id);
  expect((await api(colleague(), `${UPLOADS}/${d.id}`, { method: 'PATCH', body: JSON.stringify({ title: 'Mio' }) })).status).toBe(403);

  // At edit, they edit the dataset too.
  await api(owner(), `/api/v1/sharing/resources/${FILE_MANAGER}/folder/${top.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'edit' }),
  });
  expect((await api(colleague(), `${UPLOADS}/files/${stored.id}`)).body).toMatchObject({ level: 'edit' });
});

test('GP-983 replacing the file loads its dataset again, deleting it deletes the dataset, and the geodata leave both to the catalog', async () => {
  const top = await folder(owner(), unique('Ciclo'));
  const stored = await store(owner(), top.id, `${unique('ciclo').toLowerCase().replace(/\W+/g, '_')}.geojson`, STOPS);
  const d = await datasetOf(owner(), stored.id);
  expect(d.version).toBe(1);

  // The geodata do not replace or delete what is a file of the catalog: 409, with what to do.
  const replace = await api(owner(), `${UPLOADS}/${d.id}/content?name=x.geojson`, { method: 'PUT', body: LINE });
  expect([403, 409]).toContain(replace.status);
  const remove = await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
  expect(remove.status, JSON.stringify(remove.body)).toBe(409);

  const replaced = await api(owner(), `${CATALOG}/items/${stored.id}/content`, {
    method: 'PUT',
    headers: { 'content-type': 'application/geo+json' },
    body: LINE,
  });
  expect(replaced.status, JSON.stringify(replaced.body)).toBeLessThan(300);
  await expect.poll(async () => ((await api(owner(), `${UPLOADS}/files/${stored.id}`)).body as Dataset).version, { timeout: 45_000 })
    .toBe(2);
  expect(((await api(owner(), `${UPLOADS}/files/${stored.id}`)).body as Dataset).id).toBe(d.id);

  expect((await api(owner(), `${CATALOG}/items/${stored.id}`, { method: 'DELETE' })).status).toBeLessThan(300);
  await expect.poll(async () => (await api(owner(), `${UPLOADS}/${d.id}`)).status, { timeout: 45_000 }).toBe(404);
  expect((await api(owner(), `${UPLOADS}/files/${stored.id}`)).status).toBe(404);
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-983 il file geografico del catalogo ha la sua anteprima e l\'azione Pubblica come servizio', async ({ page }) => {
    const top = await folder(owner(), unique('Anteprima'));
    const name = `${unique('fermate_it').toLowerCase().replace(/\W+/g, '_')}.geojson`;
    const stored = await store(owner(), top.id, name, STOPS);
    await datasetOf(owner(), stored.id, GEOSERVER ? 'loaded' : undefined);

    await signIn(page, owner());
    await openFolder(page, top.id);
    await expect(row(page, name).getByText('Dato geografico', { exact: true })).toBeVisible();
    await row(page, name).getByRole('button', { name: new RegExp(`^(Dettagli di|Details of) ${name}$`) }).click();
    const preview = detail(page).getByRole('region', { name: `Anteprima geografica di ${name}` });
    await expect(preview.getByRole('img', { name: `Impronta di ${name}` })).toBeVisible();

    // The action of the kind: without a loaded dataset it says when it can publish.
    await row(page, name).getByRole('button', { name: new RegExp(`^Pubblica come servizio: ${name}$`) }).click();
    const dialog = app(page).getByRole('dialog');
    if (!GEOSERVER) {
      await expect(dialog).toContainText('Il dato si pubblica quando è caricato.');
      await page.screenshot({ path: 'test-results/gp983-catalog-pubblica.png', fullPage: true });
      return;
    }
    const layer = unique('gp983_layer').toLowerCase().replace(/[^a-z0-9_]/g, '_');
    await dialog.getByLabel('Nome del livello').fill(layer);
    await dialog.getByRole('button', { name: 'Pubblica', exact: true }).click();
    await expect(dialog).toContainText(`Pubblicato come livello ${layer}.`);
    await page.screenshot({ path: 'test-results/gp983-catalog-pubblicato.png', fullPage: true });
    // The publication is a service of the geodata, and its state is on the file of the catalog.
    const publications = (await api(owner(), PUBLICATIONS)).body as { name: string }[];
    expect(publications.map((p) => p.name)).toContain(layer);
    await expect.poll(async () => (await listed(owner(), top.id, name))?.fileKind?.state ?? null, { timeout: 30_000 })
      .toBe('published');
    // Deleting the file withdraws its layer.
    expect((await api(owner(), `${CATALOG}/items/${stored.id}`, { method: 'DELETE' })).status).toBeLessThan(300);
    await expect.poll(async () => ((await api(owner(), PUBLICATIONS)).body as { name: string }[]).map((p) => p.name),
      { timeout: 45_000 }).not.toContain(layer);
  });
});
