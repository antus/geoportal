// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-937, GP-321, GP-324 on the bench: an uploaded file is loaded into the geodata database of the
 * organization (a table of PostGIS, or a COG for a raster), with its reference system and extent, or
 * refused with the reason; the page says which. Without GEOPORTAL_TEST_GEODATA=1 only the path of an
 * organization without geodata database runs; with it, the PostGIS of tests/geodata/compose.yaml. With
 * GEOPORTAL_TEST_GEOSERVER=1, the PostGIS and GeoServer of tests/geoserver/compose.yaml, and the layers
 * of the loaded files are published and read back through WFS.
 */

type Person = ReturnType<typeof member>;
interface Loaded {
  crs: string;
  crsSource: string;
  geometryType: string;
  z: boolean;
  features: number;
  extent: number[] | null;
  nativeExtent: number[] | null;
  table: string | null;
  cog: boolean;
  space: number;
}
interface Upload {
  id: string;
  title: string;
  technicalName: string;
  state: string;
  stateCode: string | null;
  stateReason: string | null;
  loaded: Loaded | null;
}

const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
/** With the loader sidecar (ADR-0019) the loading ends after the request: the tests wait for it. */
const LOADER = !!process.env.GEOPORTAL_TEST_LOADER;
const GEODATA = GEOSERVER || LOADER || !!process.env.GEOPORTAL_TEST_GEODATA;
/** The container of the geodata database, in tests/geoserver/compose.yaml or tests/geodata/compose.yaml. */
const POSTGRES = GEOSERVER
  ? `${process.env.GEOPORTAL_TEST_COMPOSE_PROJECT ?? 'geoportal-catalog-test'}-postgres-1`
  : `${process.env.GEOPORTAL_TEST_GEODATA_PROJECT ?? 'geoportal-f03-gp937'}-postgres-1`;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');
const SAMPLES = 'src/main/resources/samples/';

/** The three stations of Valverde, the invented town of the samples, in EPSG:32633. */
const STATIONS: [string, number, number][] = [
  ['Valverde Centrale', 283467.75, 4664797.939],
  ['Valverde Nord', 285939.081, 4665778.456],
  ['Valverde Sud', 282021.59, 4661652.477],
];
const UTM_GEOJSON = JSON.stringify({
  type: 'FeatureCollection',
  crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
  features: STATIONS.map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
});
const UTM_CSV = 'nome;x;y\n' + STATIONS.map(([n, x, y]) => `${n};${String(x).replace('.', ',')};${String(y).replace('.', ',')}`).join('\n');

const owner = (): Person => member(0);
const colleague = (): Person => member(1);
const organization = (): string => bench().organization.slug;
const permission = (): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:upload-data`;

async function upload(name: string, content: string | Buffer, crs?: string): Promise<Upload> {
  const query = new URLSearchParams({ name, ...(crs ? { crs } : {}) });
  const response = await api(owner(), `${UPLOADS}?${query.toString()}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: content,
  });
  expect(response.status, JSON.stringify(response.body)).toBe(201);
  return settle(response.body as Upload);
}

/** The dataset once its loading ended: at once with the readers of the catalog, later with the loader. */
async function settle(d: Upload): Promise<Upload> {
  let current = d;
  for (let i = 0; current.state === 'loading' && i < 240; i++) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = (await api(owner(), `${UPLOADS}/${d.id}`)).body as Upload;
  }
  return current;
}

async function reload(d: Upload, query: string): Promise<{ status: number; body: Upload }> {
  const response = await api(owner(), `${UPLOADS}/${d.id}/load?${query}`, { method: 'POST' });
  return { status: response.status, body: response.status === 200 ? await settle(response.body as Upload) : (response.body as Upload) };
}

async function remove(d: Upload): Promise<void> {
  expect((await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' })).status).toBe(204);
}

/** One value from the geodata database, through psql in its container. */
function sql(query: string): string {
  return execFileSync('docker', ['exec', POSTGRES, 'psql', '-U', 'geoserver', '-d', 'geodata', '-At', '-c', query], {
    encoding: 'utf8',
  }).trim();
}

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function credential(value: string | null, name = 'geodata'): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(colleague(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      expect((await api(colleague(), `${path}/${name}`, { method: 'DELETE' })).status).toBeLessThan(300);
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-937 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(colleague(), `${path}/${name}`, { method: 'PUT', body })
      : await api(colleague(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  // The colleague administers the organization for a moment: grants the owner the upload, and sets
  // or removes the credential of the geodata database.
  await administrator(colleague(), true);
  try {
    expect((await api(colleague(), permission(), { method: 'PUT' })).status).toBe(200);
    await credential(GEODATA ? 'geoserver:catalog-test-db' : null);
  } finally {
    await administrator(colleague(), false);
  }
});

test.afterAll(async () => {
  await administrator(colleague(), true);
  try {
    // Back as the suite of GP-936 expects: no upload for the owner, no geodata database.
    await api(colleague(), permission(), { method: 'DELETE' });
    await credential(null);
  } finally {
    await administrator(colleague(), false);
  }
});

test('GP-937 without a geodata database a file is stored, not loaded, and the dataset says why', async () => {
  test.skip(GEODATA, 'the organization has a geodata database in this run');
  const d = await upload('stazioni.geojson', UTM_GEOJSON);
  try {
    expect(d.state).toBe('uploaded');
    expect(d.stateCode).toBe('geodata-missing');
    expect(d.loaded).toBeNull();
  } finally {
    await remove(d);
  }
});

test.describe('with the geodata database', () => {
  test.skip(!GEODATA, 'GEOPORTAL_TEST_GEODATA=1 with tests/geodata/compose.yaml');

  test('GP-937 GP-936 a vector file becomes a table of the schema of the organization, its space in PostGIS counted', async () => {
    const before = ((await api(owner(), `${UPLOADS}/space`)).body as { used: number }).used;
    const d = await upload('poi-esempio.gpkg', readFileSync(SAMPLES + 'poi-esempio.gpkg'));
    expect(d.state, d.stateReason ?? '').toBe('loaded');
    expect(d.loaded).toMatchObject({ crs: 'EPSG:4326', crsSource: 'file', geometryType: 'Point', features: 5 });
    const table = d.loaded?.table ?? '';
    expect(table).toMatch(/^org_[0-9a-f]{32}\.poi_esempio(_\d+)?$/);
    expect(sql(`select count(*) from ${table}`)).toBe('5');
    expect(sql(`select Find_SRID('${table.split('.')[0]}', '${table.split('.')[1]}', 'geom')`)).toBe('4326');
    const after = ((await api(owner(), `${UPLOADS}/space`)).body as { used: number }).used;
    expect(after - before).toBeGreaterThanOrEqual((d.loaded?.space ?? 0) + readFileSync(SAMPLES + 'poi-esempio.gpkg').length);

    await remove(d);
    expect(sql(`select to_regclass('${table}') is null`)).toBe('t');
  });

  test('GP-321 the extent of a loaded source is in the catalog answer, in WGS84 degrees', async () => {
    const d = await upload('poi-esempio.kml', readFileSync(SAMPLES + 'poi-esempio.kml'));
    try {
      const [w, s, e, n] = d.loaded?.extent ?? [];
      expect(w).toBeCloseTo(10.874512, 5);
      expect(s).toBeCloseTo(40.917644, 5);
      expect(e).toBeCloseTo(15.103327, 5);
      expect(n).toBeCloseTo(45.802519, 5);
      expect(((await api(owner(), `${UPLOADS}/${d.id}`)).body as Upload).loaded?.extent).toEqual(d.loaded?.extent);
    } finally {
      await remove(d);
    }
  });

  test('GP-324 a projected GeoJSON keeps EPSG:32633 and its extent in degrees is around the stations', async () => {
    const d = await upload('stazioni-utm.geojson', UTM_GEOJSON);
    try {
      expect(d.state, JSON.stringify(d)).toBe('loaded');
      expect(d.loaded).toMatchObject({ crs: 'EPSG:32633', crsSource: 'file' });
      const table = d.loaded?.table ?? '';
      expect(sql(`select Find_SRID('${table.split('.')[0]}', '${table.split('.')[1]}', 'geom')`)).toBe('32633');
      const [w, s, e, n] = d.loaded?.extent ?? [];
      expect(w).toBeCloseTo(12.3649, 3);
      expect(s).toBeCloseTo(42.0767, 3);
      expect(e).toBeCloseTo(12.4107, 3);
      expect(n).toBeCloseTo(42.1149, 3);
    } finally {
      await remove(d);
    }
  });

  test('GP-324 a file without reference system is refused with the reason, then loaded with the declared one', async () => {
    const d = await upload('stazioni-xy.csv', UTM_CSV);
    try {
      expect(d.state).toBe('failed');
      expect(d.stateCode).toBe('crs-missing');
      expect(d.stateReason).toContain('EPSG');
      expect(d.loaded).toBeNull();
      expect(sql(`select count(*) from information_schema.tables where table_name like '${d.technicalName}%'`)).toBe('0');

      const notACode = await reload(d, 'crs=UTM');
      expect(notACode.status).toBe(400);
      const loaded = await reload(d, 'crs=EPSG:32633');
      expect(loaded.status).toBe(200);
      expect((loaded.body as Upload).loaded).toMatchObject({ crs: 'EPSG:32633', crsSource: 'declared', features: 3 });
    } finally {
      await remove(d);
    }
  });

  test('GP-937 a file that cannot be loaded is refused with the reason and leaves no table', async () => {
    const broken = JSON.stringify({
      type: 'FeatureCollection',
      features: [
        { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [12.49, 41.89] } },
        { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[[12.49]]] } },
      ],
    });
    const d = await upload('rotto.geojson', broken);
    try {
      expect(d.state).toBe('failed');
      // The readers of the catalog name the geometry; GDAL may refuse the whole file.
      expect(d.stateCode).toMatch(/^(geometry-invalid|unreadable)$/);
      expect(sql(`select count(*) from information_schema.tables where table_name = '${d.technicalName}'`)).toBe('0');
    } finally {
      await remove(d);
    }
  });

  test('GP-937 GP-936 a GeoTIFF becomes a Cloud Optimized GeoTIFF in the file service, with its extent, its space counted', async () => {
    const tif = readFileSync(SAMPLES + 'rilievo-esempio.tif');
    const before = ((await api(owner(), `${UPLOADS}/space`)).body as { used: number }).used;
    const d = await upload('rilievo-esempio.tif', tif);
    try {
      expect(d.state, d.stateReason ?? '').toBe('loaded');
      // The upload and its COG, both files of the organization, count on its storage.
      const after = ((await api(owner(), `${UPLOADS}/space`)).body as { used: number }).used;
      expect(after - before).toBeGreaterThan(tif.length);
      expect(d.loaded).toMatchObject({ geometryType: 'Raster', cog: true, table: null });
      const [w, s, e, n] = d.loaded?.extent ?? [];
      expect(w).toBeGreaterThan(5);
      expect(e).toBeLessThan(20);
      expect(s).toBeGreaterThan(35);
      expect(n).toBeLessThan(48);
    } finally {
      await remove(d);
    }
  });

  test('GP-937 every vector format becomes a GeoServer layer whose WFS answer has its features', async () => {
    test.skip(!GEOSERVER, 'GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml');
    test.setTimeout(180_000);
    const files = ['poi-esempio.geojson', 'poi-esempio.csv', 'poi-esempio.gml', 'poi-esempio.kml', 'poi-esempio.kmz',
      'poi-esempio.gpkg', 'poi-esempio-shp.zip', 'poi-esempio.gpx', 'poi-esempio.czml'];
    const loaded: Upload[] = [];
    try {
      for (const file of files) {
        const d = await upload(file, readFileSync(SAMPLES + file));
        loaded.push(d);
        expect(d.state, `${file}: ${d.stateReason ?? ''}`).toBe('loaded');
      }
      const publish = `/api/v1/organizations/${organization()}/permissions/members/${owner().user}/exceptions/${EXTENSION}:publish`;
      await administrator(colleague(), true);
      try {
        await credential('gsadmin:catalog-test-admin', 'geoserver');
        expect((await api(colleague(), publish, { method: 'PUT' })).status).toBe(200);
      } finally {
        await administrator(colleague(), false);
      }
      try {
        // The one publishing path of GP-114: each loaded dataset becomes the layer of its table.
        for (const d of loaded) {
          const published = await api(owner(), '/api/v1/ext/catalog-geodata/publications', {
            method: 'POST',
            body: JSON.stringify({ dataset: d.id, layer: d.technicalName }),
          });
          expect(published.status, JSON.stringify(published.body)).toBe(201);
        }
      } finally {
        await administrator(colleague(), true);
        await api(colleague(), publish, { method: 'DELETE' });
        await administrator(colleague(), false);
      }
      for (const d of loaded) {
        const [workspace, layer] = (d.loaded?.table ?? '').split('.');
        const wfs = await fetch(`${GEOSERVER_URL}/${workspace}/ows?service=WFS&version=2.0.0&request=GetFeature`
          + `&typeNames=${workspace}:${layer}&outputFormat=application/json`, { headers: { authorization: GS_AUTH } });
        expect(wfs.status, d.technicalName).toBe(200);
        const body = (await wfs.json()) as { features: { geometry: { coordinates: number[] } }[] };
        expect(body.features, d.technicalName).toHaveLength(5);
      }
    } finally {
      for (const d of loaded) {
        await remove(d);
      }
    }
  });

  test.describe('in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-324 the catalog page says why a file was not loaded and loads it with the declared system', async ({ page }) => {
      const title = unique('Stazioni');
      await signIn(page, owner());
      await openApp(page, 'Dati geografici');
      await page.getByRole('button', { name: 'Carica dati' }).click();
      const dialog = page.getByRole('dialog', { name: 'Carica dati' });
      await dialog.getByLabel('File da caricare').setInputFiles({ name: 'stazioni_xy.csv', mimeType: 'text/csv', buffer: Buffer.from(UTM_CSV) });
      await dialog.getByLabel('Titolo').fill(title);
      await dialog.getByRole('button', { name: 'Carica', exact: true }).click();
      if (!LOADER) {
        await expect(page.getByText(`«${title}» è archiviato ma non caricato: Il file non dichiara il suo sistema di riferimento`)).toBeVisible();
      }

      const detail = page.getByRole('complementary', { name: title });
      await expect(detail).toContainText('Non caricato', { timeout: 30_000 });
      await expect(detail).toContainText('indica il codice EPSG');
      await page.screenshot({ path: 'test-results/gp-937-failed.png', fullPage: true });

      await detail.getByLabel('Codice EPSG (solo se il file non dichiara il sistema)').fill('EPSG:32633');
      await detail.getByRole('button', { name: 'Carica di nuovo' }).click();
      if (!LOADER) {
        await expect(page.getByText(`«${title}» è stato caricato.`)).toBeVisible();
      }
      await expect(detail).toContainText('Caricato, da pubblicare', { timeout: 30_000 });
      await expect(detail).toContainText('EPSG:32633 · dichiarato a mano');
      await expect(detail).toContainText('Point');
      await expect(detail).toContainText(/O 12,36\d* · S 42,07\d* · E 12,41\d* · N 42,11\d*/);
      await expect(detail).toContainText('Tabella PostGIS');
      await page.screenshot({ path: 'test-results/gp-937-loaded.png', fullPage: true });

      page.once('dialog', (confirm) => void confirm.accept());
      await detail.getByRole('button', { name: 'Elimina' }).click();
      await expect(page.getByText(`«${title}» è stato eliminato.`)).toBeVisible();
    });
  });
});
