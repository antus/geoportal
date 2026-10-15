// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { api, bench, member, unique } from '@mosaikit/testing/playwright';

/**
 * GP-309 on the bench with the GeoServer of tests/geoserver/compose.yaml (GEOPORTAL_TEST_GEOSERVER=1): a
 * raster dataset is published as a coverage of its Cloud Optimized GeoTIFF, served by the gate, and a data
 * rule with an area on it becomes a GeoFence LIMIT in CLIP mode for the person; rows and columns are refused
 * on a raster; unpublishing removes the coverage store. What the clipped images look like is proved by
 * RasterClipTest (mvn test -Pgeoserver).
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  state: string;
  stateReason: string | null;
  loaded: { crs: string; nativeExtent: number[] | null; geometryType: string } | null;
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

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function credential(name: string, value: string): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-309 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

async function geoserver(path: string): Promise<Response> {
  return fetch(GEOSERVER_URL + path, { headers: { authorization: GS_AUTH } });
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

test.describe('GP-309 rasters on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

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
    const tif = readFileSync('src/main/resources/samples/rilievo-esempio.tif');
    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('rilievo').toLowerCase()}.tif` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: tif,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    dataset = uploaded.body as Upload;
    expect(dataset.state, dataset.stateReason ?? '').toBe('loaded');
    ws = ((await api(manager(), '/api/v1/ext/catalog/geoserver/alignment')).body as { workspace: string }).workspace;
  });

  test.afterAll(async () => {
    if (dataset) {
      await api(owner(), `${UPLOADS}/${dataset.id}`, { method: 'DELETE' });
    }
  });

  test('GP-309 a raster is published as a coverage of its COG, served by the gate, and is not drift', async () => {
    layer = `gp309_${Date.now()}`;
    const published = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: dataset.id, layer }) });
    expect(published.status, JSON.stringify(published.body)).toBe(201);
    expect((published.body as { health: { status: string } }).health.status).toBe('ok');
    expect((await geoserver(`/rest/workspaces/${ws}/coveragestores/${layer}.json`)).status).toBe(200);

    const extent = dataset.loaded?.nativeExtent ?? [6, 36, 19, 48];
    const image = await gate(owner(), `${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&version=1.1.1&request=GetMap&layers=${layer}`
      + `&styles=&srs=${dataset.loaded?.crs}&bbox=${extent.join(',')}&width=64&height=64&format=image/png`);
    expect(image.status).toBe(200);
    expect(image.headers.get('content-type')).toContain('image/png');

    const reconciliation = (await api(manager(), '/api/v1/ext/catalog/geoserver/reconciliation')).body as {
      unknown: { kind: string; name: string }[];
    };
    expect(reconciliation.unknown.filter((u) => u.name === layer)).toEqual([]);
  });

  test('GP-309 an area rule on the raster becomes a GeoFence LIMIT that clips, and rows or columns are refused', async () => {
    const extent = dataset.loaded?.nativeExtent ?? [6, 36, 19, 48];
    const [minx, miny, maxx, maxy] = extent;
    const midx = (minx + maxx) / 2;
    const area = `POLYGON((${minx} ${miny},${midx} ${miny},${midx} ${maxy},${minx} ${maxy},${minx} ${miny}))`;
    const subject = { kind: 'person', email: colleague().user };
    const refused = await api(owner(), RULES, { method: 'POST', body: JSON.stringify({ layer, subject, cql: 'x = 1' }) });
    expect(refused.status).toBe(400);
    expect(JSON.stringify(refused.body)).toContain('area only');

    const listed = (await api(owner(), `${RULES}?layer=${layer}`)).body as { kind: string; columns: unknown[] };
    expect(listed).toMatchObject({ kind: 'raster', columns: [] });
    // A rule limits what a grant opens: the colleague reads the raster first.
    const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'download' }),
    });
    expect(grant.status, JSON.stringify(grant.body)).toBeLessThan(300);
    const created = await api(owner(), RULES, { method: 'POST', body: JSON.stringify({ layer, subject, area }) });
    expect(created.status, JSON.stringify(created.body)).toBe(201);
    expect((created.body as { geofence: { mismatches: string[] } }).geofence.mismatches).toEqual([]);

    const all = (await (await geoserver('/rest/geofence/rules.json')).json()) as {
      rules: { workspace: string; layer: string | null; userName: string | null; access: string;
        limits: { spatialFilterType: string | null } | null }[];
    };
    const limit = all.rules.filter((r) => r.workspace === ws && r.layer === layer && r.access === 'LIMIT');
    expect(limit).toEqual([expect.objectContaining({ userName: colleague().user, limits: expect.objectContaining({ spatialFilterType: 'CLIP' }) })]);
  });

  test('GP-309 unpublishing a raster removes its coverage store from GeoServer', async () => {
    expect((await api(owner(), `${PUBLICATIONS}/${layer}`, { method: 'DELETE' })).status).toBe(204);
    expect((await geoserver(`/rest/workspaces/${ws}/coveragestores/${layer}.json`)).status).toBe(404);
  });
});

test.describe('GP-937 a GeoTIFF on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(240_000);

  test('GP-937 an uploaded GeoTIFF is a COG published by GeoServer, whose WMS answer draws the image within its bounding box', async ({ page }) => {
    await administrator(manager(), true);
    for (const p of ['upload-data', 'publish']) {
      expect((await api(manager(), exception(owner(), p), { method: 'PUT' })).status).toBe(200);
    }
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
    const tif = readFileSync('src/main/resources/samples/rilievo-esempio.tif');
    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('cog').toLowerCase()}.tif` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: tif,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    const d = uploaded.body as Upload & { loaded: { cog: boolean; extent: number[] } | null };
    const layer = `gp937_${Date.now()}`;
    try {
      expect(d.state, d.stateReason ?? '').toBe('loaded');
      expect(d.loaded?.cog).toBe(true);
      const published = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: d.id, layer }) });
      expect(published.status, JSON.stringify(published.body)).toBe(201);
      const ws = ((await api(manager(), '/api/v1/ext/catalog/geoserver/alignment')).body as { workspace: string }).workspace;

      // GeoServer holds the COG as a GeoTIFF coverage store of the workspace of the organization.
      const store = (await (await geoserver(`/rest/workspaces/${ws}/coveragestores/${layer}.json`)).json()) as {
        coverageStore: { type: string };
      };
      expect(store.coverageStore.type).toBe('GeoTIFF');

      // WMS draws the image inside its bounding box, and nothing outside it (in WGS84 degrees).
      const [w, s, e, n] = d.loaded?.extent ?? [];
      const getMap = (bbox: number[]) => geoserver(`/${ws}/wms?service=WMS&version=1.1.1&request=GetMap&layers=${ws}:${layer}`
        + `&styles=&srs=EPSG:4326&bbox=${bbox.join(',')}&width=64&height=64&format=image/png&transparent=true`);
      const painted = async (response: Response): Promise<number> => {
        expect(response.status).toBe(200);
        expect(response.headers.get('content-type')).toContain('image/png');
        const png = Buffer.from(await response.arrayBuffer()).toString('base64');
        return page.evaluate(async (data) => {
          const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${data}`)).blob());
          const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
          const context = canvas.getContext('2d')!;
          context.drawImage(bitmap, 0, 0);
          const pixels = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
          let opaque = 0;
          for (let i = 3; i < pixels.length; i += 4) {
            if (pixels[i]! > 0) opaque++;
          }
          return opaque / (bitmap.width * bitmap.height);
        }, png);
      };
      const inside = [w! + (e! - w!) / 4, s! + (n! - s!) / 4, e! - (e! - w!) / 4, n! - (n! - s!) / 4];
      expect(await painted(await getMap(inside))).toBeGreaterThan(0.05);
      expect(await painted(await getMap([w! - 20, s! - 20, w! - 10, s! - 10]))).toBe(0);
    } finally {
      await api(owner(), `${PUBLICATIONS}/${layer}`, { method: 'DELETE' });
      await api(owner(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
    }
  });
});
