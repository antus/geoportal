// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, restart, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-936: uploaded data is a resource of the organization, with a readable title, a lifecycle,
 * sharing through Mosaikit and the storage quota. The GeoServer side of the sharing criterion and
 * the space in PostGIS or as COG come with the loading of GP-937.
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  description: string;
  technicalName: string;
  format: string;
  fileName: string;
  size: number;
  state: string;
  version: number;
  sample: boolean;
  level: string;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const GEOJSON = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Torre Civica' }, geometry: { type: 'Point', coordinates: [12.3812, 42.1054] } }],
});
const KML = '<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><name>Fontana Vecchia</name>'
  + '<Point><coordinates>12.3786,42.1031</coordinates></Point></Placemark></kml>';

const owner = (): Person => member(0);
const colleague = (): Person => member(1);
const organization = (): string => bench().organization.slug;

async function upload(person: Person, name: string, content: string, title?: string) {
  const query = new URLSearchParams({ name, ...(title ? { title } : {}) });
  return api(person, `${UPLOADS}?${query.toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: content,
  });
}

/** Makes the colleague an administrator of the organization, or a member again. */
async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

test.describe.configure({ mode: 'serial' });

test('GP-936 uploading data needs the permission upload-data, which administrators grant', async () => {
  const refused = await upload(owner(), 'stazioni.geojson', GEOJSON);
  expect(refused.status).toBe(403);

  await administrator(colleague(), true);
  try {
    const granted = await api(
      colleague(),
      `/api/v1/organizations/${organization()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:upload-data`,
      { method: 'PUT' },
    );
    expect(granted.status).toBe(200);
  } finally {
    await administrator(colleague(), false);
  }
  const space = await api(owner(), `${UPLOADS}/space`);
  expect((space.body as { canUpload: boolean }).canUpload).toBe(true);
  expect((await api(colleague(), `${UPLOADS}/space`)).body).toMatchObject({ canUpload: false });
});

test('GP-936 a dataset has a title distinct from its technical name, and is renamed, replaced and deleted', async () => {
  const first = await upload(owner(), 'Monumenti di Valverde.geojson', GEOJSON, 'Monumenti, il centro');
  expect(first.status).toBe(201);
  const data = first.body as Upload;
  expect(data).toMatchObject({
    title: 'Monumenti, il centro',
    technicalName: 'monumenti_di_valverde',
    format: 'geojson',
    fileName: 'Monumenti di Valverde.geojson',
    state: 'uploaded',
    version: 1,
    level: 'manage',
  });
  // The technical name is unique in the organization; the title needs not be.
  const second = (await upload(owner(), 'monumenti-di-valverde.geojson', GEOJSON, 'Monumenti, il centro')).body as Upload;
  expect(second.technicalName).toBe('monumenti_di_valverde_2');

  const renamed = await api(owner(), `${UPLOADS}/${data.id}`, {
    method: 'PATCH',
    body: JSON.stringify({ title: 'Monumenti del centro storico', description: 'Cinque luoghi noti.' }),
  });
  expect(renamed.status).toBe(200);
  expect(renamed.body).toMatchObject({ title: 'Monumenti del centro storico', technicalName: 'monumenti_di_valverde' });
  // The registry of Mosaikit knows the new title too: the sharing dialog and the search show it.
  const registered = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${data.id}`);
  expect(registered.status).toBe(200);
  expect(JSON.stringify(registered.body)).toContain('Monumenti del centro storico');

  const replaced = await api(owner(), `${UPLOADS}/${data.id}/content?name=fontana.kml`, {
    method: 'PUT',
    headers: { 'content-type': 'application/octet-stream' },
    body: KML,
  });
  expect(replaced.status).toBe(200);
  expect(replaced.body).toMatchObject({ format: 'kml', fileName: 'fontana.kml', version: 2, technicalName: 'monumenti_di_valverde' });
  const content = await api(owner(), `${UPLOADS}/${data.id}/content`);
  expect(content.status).toBe(200);
  expect(content.body).toBe(KML);

  // A file the catalog does not load as data is refused with the reason.
  const pdf = await upload(owner(), 'relazione.pdf', '%PDF-1.7');
  expect(pdf.status).toBe(400);
  expect(JSON.stringify(pdf.body)).toContain('.geojson');

  for (const id of [data.id, second.id]) {
    expect((await api(owner(), `${UPLOADS}/${id}`, { method: 'DELETE' })).status).toBe(204);
    expect((await api(owner(), `${UPLOADS}/${id}`)).status).toBe(404);
  }
  expect((await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${data.id}`)).status).toBe(404);

  // Every step is in the audit log of the organization.
  await administrator(colleague(), true);
  try {
    const log = await api(colleague(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:dataset.`);
    const actions = JSON.stringify(log.body);
    for (const action of ['uploaded', 'renamed', 'replaced', 'deleted']) {
      expect(actions).toContain(`${EXTENSION}:dataset.${action}`);
    }
  } finally {
    await administrator(colleague(), false);
  }
});

test('GP-936 sharing a dataset at view lets a colleague see it, and revoking it hides it again', async () => {
  const data = (await upload(owner(), 'riservato.geojson', GEOJSON, unique('Riservato'))).body as Upload;
  const listed = async () => ((await api(colleague(), UPLOADS)).body as Upload[]).map((d) => d.id);

  expect(await listed()).not.toContain(data.id);
  expect((await api(colleague(), `${UPLOADS}/${data.id}`)).status).toBe(404);

  const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${data.id}/grants`, {
    method: 'PUT',
    body: JSON.stringify({ subject: { kind: 'person', email: colleague().user }, level: 'view' }),
  });
  expect(grant.status).toBeLessThan(300);
  expect(await listed()).toContain(data.id);
  expect((await api(colleague(), `${UPLOADS}/${data.id}`)).body).toMatchObject({ level: 'view' });
  // View is not download nor edit.
  expect((await api(colleague(), `${UPLOADS}/${data.id}/content`)).status).toBe(403);
  const rename = await api(colleague(), `${UPLOADS}/${data.id}`, { method: 'PATCH', body: JSON.stringify({ title: 'Mio' }) });
  expect(rename.status).toBe(403);
  expect((await api(colleague(), `${UPLOADS}/${data.id}`, { method: 'DELETE' })).status).toBe(403);

  const revoked = await api(owner(), `/api/v1/sharing/grants/${(grant.body as { id: string }).id}`, { method: 'DELETE' });
  expect(revoked.status).toBeLessThan(300);
  expect(await listed()).not.toContain(data.id);
  expect((await api(colleague(), `${UPLOADS}/${data.id}`)).status).toBe(404);
});

test('GP-936 an upload beyond the storage quota of the organization is refused', async () => {
  const before = (await api(owner(), `${UPLOADS}/space`)).body as { used: number };
  const quota = `/api/v1/organizations/${organization()}/quotas/storage`;
  const limited = await api(bench().admin, quota, { method: 'PUT', body: JSON.stringify({ limit: before.used + 500 }) });
  expect(limited.status).toBe(200);
  try {
    expect(((await api(owner(), `${UPLOADS}/space`)).body as { limit: number }).limit).toBe(before.used + 500);
    const big = await upload(owner(), 'troppo.geojson', GEOJSON.padEnd(2000, ' '));
    expect(big.status).toBe(403);
    expect(big.body).toMatchObject({ quota: { measure: 'storage' } });
    const small = await upload(owner(), 'piccolo.geojson', GEOJSON);
    expect(small.status).toBe(201);
    // The file service counts the file of the dataset on the storage of the organization.
    const after = (await api(owner(), `${UPLOADS}/space`)).body as { used: number };
    expect(after.used).toBe(before.used + (small.body as Upload).size);
    await api(owner(), `${UPLOADS}/${(small.body as Upload).id}`, { method: 'DELETE' });
  } finally {
    await api(bench().admin, quota, { method: 'DELETE' });
  }
});

test('GP-936 the sample datasets load in every format unless a setting turns them off', async () => {
  test.setTimeout(240_000);
  const samples = async (person: Person) => ((await api(person, UPLOADS)).body as Upload[]).filter((d) => d.sample);
  await restart({ ...bench().settings, 'geoportal.catalog.samples': 'false' });
  await administrator(colleague(), true);
  try {
    expect(await samples(colleague())).toHaveLength(0);
    await restart(bench().settings);
    const loaded = await samples(colleague());
    expect(loaded.length).toBeGreaterThan(20);
    expect(new Set(loaded.map((d) => d.format))).toEqual(
      new Set(['geojson', 'shapefile', 'geopackage', 'gml', 'gpx', 'csv', 'kml', 'kmz', 'czml', 'geotiff']),
    );
    // Loaded once: listing again adds nothing.
    expect(await samples(colleague())).toHaveLength(loaded.length);
  } finally {
    await administrator(colleague(), false);
  }
  // Shared with the organization: every member sees and downloads them, nobody else edits them.
  const seen = await samples(owner());
  expect(seen.length).toBeGreaterThan(20);
  expect(seen[0]?.level).toBe('read');
  expect((await api(owner(), `${UPLOADS}/${seen[0]?.id ?? ''}/content`)).status).toBe(200);
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-936 the catalog uploads a file, renames it and shares it with the dialog of Mosaikit', async ({ page }) => {
    const title = unique('Fontane');
    await signIn(page, owner());
    await openApp(page, 'Dati geografici');
    const uploads = page.getByRole('region', { name: 'Dati caricati' });
    await expect(uploads.getByRole('heading', { name: 'Dati caricati' })).toBeVisible();
    await expect(uploads.getByText(/^Spazio usato: /)).toBeVisible();

    await page.getByRole('button', { name: 'Carica dati' }).click();
    const dialog = page.getByRole('dialog', { name: 'Carica dati' });
    await dialog.getByLabel('File da caricare').setInputFiles({
      name: 'fontane_storiche.geojson',
      mimeType: 'application/geo+json',
      buffer: Buffer.from(GEOJSON),
    });
    await expect(dialog.getByLabel('Titolo')).toHaveValue('Fontane storiche');
    await dialog.getByLabel('Titolo').fill(title);
    await page.screenshot({ path: 'test-results/gp-936-upload.png' });
    await dialog.getByRole('button', { name: 'Carica', exact: true }).click();
    await expect(page.getByText(`«${title}» è stato caricato.`)).toBeVisible();

    const detail = page.getByRole('complementary', { name: title });
    await expect(detail).toContainText('fontane_storiche');
    await expect(detail).toContainText('Archiviato, da caricare');
    await expect(detail).toContainText('Gestire');
    await page.screenshot({ path: 'test-results/gp-936-detail.png' });

    await detail.getByRole('button', { name: 'Rinomina' }).click();
    await detail.getByLabel('Titolo').fill(`${title} di Valverde`);
    await detail.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByText(`Il dato ora si chiama «${title} di Valverde».`)).toBeVisible();
    const renamed = page.getByRole('complementary', { name: `${title} di Valverde` });
    await expect(renamed).toContainText('fontane_storiche');

    await renamed.getByRole('button', { name: 'Condividi' }).click();
    await expect(page.getByRole('dialog').filter({ hasText: `${title} di Valverde` })).toBeVisible();
    await page.screenshot({ path: 'test-results/gp-936-sharing.png', fullPage: true });
    await page.keyboard.press('Escape');

    page.once('dialog', (confirm) => void confirm.accept());
    await renamed.getByRole('button', { name: 'Elimina' }).click();
    await expect(page.getByText(`«${title} di Valverde» è stato eliminato.`)).toBeVisible();
  });
});
