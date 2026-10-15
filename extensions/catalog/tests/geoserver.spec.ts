// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { api, bench, member, unique } from '@mosaikit/testing/playwright';

/**
 * GP-334 on the bench: the GeoServer API of the catalog, its permission and its credentials. With
 * GEOPORTAL_TEST_GEOSERVER=1 also the whole path on the GeoServer of tests/geoserver/compose.yaml:
 * credentials stored in Mosaikit, workspace of the organization, layers, hand changes, drift.
 */

const manager = member(1);
const slug = bench().organization.slug;
const GEOSERVER = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');
const GS_PASSWORD = 'catalog-test-admin';
/** The compose project of tests/geoserver/compose.yaml, for the PostGIS container. */
const PROJECT = process.env.GEOPORTAL_TEST_COMPOSE_PROJECT ?? 'geoportal-catalog-test';

const geoserverApi = (path: string, init: RequestInit = {}) =>
  api(manager, '/api/v1/ext/catalog/geoserver/' + path, init);

async function credential(name: string, value: string): Promise<void> {
  const body = { name, kind: 'password', value, description: `GP-334 ${name}`, extension: 'dev.geoportal.catalog' };
  const existing = await api(manager, `/api/v1/organizations/${slug}/secrets/${name}`);
  const { status } =
    existing.status === 200
      ? await api(manager, `/api/v1/organizations/${slug}/secrets/${name}`, { method: 'PUT', body: JSON.stringify(body) })
      : await api(manager, `/api/v1/organizations/${slug}/secrets`, { method: 'POST', body: JSON.stringify(body) });
  expect(status).toBeLessThan(300);
}

async function geoserver(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('authorization', GS_AUTH);
  return fetch(GEOSERVER + path, { ...init, headers });
}

test.beforeAll(async () => {
  // The second person of the bench administers the organization (the first stays a member).
  const { status } = await api(bench().admin, `/api/v1/organizations/${slug}/members/${manager.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-admin', 'organization-user'] }),
  });
  expect(status).toBe(200);
});

test.afterAll(async () => {
  // Back to a member, as the other suites expect.
  await api(bench().admin, `/api/v1/organizations/${slug}/members/${manager.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-user'] }),
  });
});

test('GP-334 only people who manage the GeoServer publication reach its API', async () => {
  for (const path of ['alignment', 'layers', 'styles']) {
    expect((await api(member(0), '/api/v1/ext/catalog/geoserver/' + path)).status).toBe(403);
  }
  expect((await api(member(0), '/api/v1/ext/catalog/geoserver/alignment', { method: 'POST' })).status).toBe(403);
});

test('GP-334 layers and styles are definitions of the catalog, checked before GeoServer sees them', async () => {
  const sld = '<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld"/>';
  expect((await geoserverApi('styles/gp334_dots', { method: 'PUT', body: JSON.stringify({ sld }) })).status).toBe(200);
  const unknownStyle = await geoserverApi('layers/gp334_layer', {
    method: 'PUT',
    body: JSON.stringify({ title: 'Layer', srs: 'EPSG:4326', style: 'nowhere' }),
  });
  expect(unknownStyle.status).toBe(400);
  expect((await geoserverApi('layers/Bad-Name', { method: 'PUT', body: JSON.stringify({ srs: 'EPSG:4326' }) })).status).toBe(400);
  expect((await geoserverApi('layers/gp334_layer', { method: 'PUT', body: JSON.stringify({ srs: '4326' }) })).status).toBe(400);

  const put = await geoserverApi('layers/gp334_layer', {
    method: 'PUT',
    body: JSON.stringify({ table: 'gp334_table', title: 'Layer', srs: 'EPSG:32633', style: 'gp334_dots' }),
  });
  expect(put.status).toBe(200);
  const layers = (await geoserverApi('layers')).body as { name: string; srs: string; style: string }[];
  expect(layers).toContainEqual(expect.objectContaining({ name: 'gp334_layer', srs: 'EPSG:32633', style: 'gp334_dots' }));

  // A style in use is not withdrawn; the layer is, once.
  expect((await geoserverApi('styles/gp334_dots', { method: 'DELETE' })).status).toBe(409);
  expect((await geoserverApi('layers/gp334_layer', { method: 'DELETE' })).status).toBe(204);
  expect((await geoserverApi('layers/gp334_layer', { method: 'DELETE' })).status).toBe(404);
  expect((await geoserverApi('styles/gp334_dots', { method: 'DELETE' })).status).toBe(204);
});

test('GP-334 the credentials of GeoServer come from Mosaikit and never appear in an answer', async () => {
  test.skip(!!process.env.GEOPORTAL_TEST_GEOSERVER, 'the GeoServer of the tests answers here');
  for (const name of ['geoserver', 'geodata']) {
    await api(manager, `/api/v1/organizations/${slug}/secrets/${name}`, { method: 'DELETE' });
  }
  const missing = await geoserverApi('alignment');
  expect(missing.status).toBe(409);
  expect(JSON.stringify(missing.body)).toContain('geoserver');

  await credential('geoserver', `gsadmin:${GS_PASSWORD}`);
  await credential('geodata', 'geoserver:catalog-test-db');
  const stored = await api(manager, `/api/v1/organizations/${slug}/secrets/geoserver`);
  expect(JSON.stringify(stored.body)).not.toContain(GS_PASSWORD);

  // The bench points the catalog at an address where nothing answers.
  const down = await geoserverApi('alignment', { method: 'POST' });
  expect([502]).toContain(down.status);
  expect(JSON.stringify(down.body)).not.toContain(GS_PASSWORD);
  expect(JSON.stringify(down.body)).not.toContain('catalog-test-db');
});

test.describe('on GeoServer', () => {
  test.skip(!process.env.GEOPORTAL_TEST_GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(180_000);

  test('GP-334 the organization gets its workspace, layers follow the catalog, hand changes come back, drift is reported', async () => {
    await credential('geoserver', `gsadmin:${GS_PASSWORD}`);
    await credential('geodata', 'geoserver:catalog-test-db');

    const first = (await geoserverApi('alignment', { method: 'POST' })).body as {
      workspace: string;
      changes: { kind: string; action: string; error: string | null }[];
    };
    const ws = first.workspace;
    expect(ws).toMatch(/^org_[0-9a-f]{32}$/);
    expect(first.changes.filter((c) => c.error)).toEqual([]);
    expect((await geoserver(`/rest/workspaces/${ws}.json`)).status).toBe(200);
    const again = (await geoserverApi('alignment', { method: 'POST' })).body as { aligned: boolean };
    expect(again.aligned).toBe(true);

    // The loader of the catalog writes the table into the schema of the organization (here by SQL).
    execFileSync('docker', ['exec', `${PROJECT}-postgres-1`, 'psql', '-U', 'geoserver', '-d', 'geodata', '-c',
      `drop table if exists ${ws}.gp334_points; create table ${ws}.gp334_points (id serial primary key, name text,` +
      ` geom geometry(Point, 32633)); insert into ${ws}.gp334_points (name, geom) values` +
      ` ('a', ST_GeomFromText('POINT(291474.53 4642175.29)', 32633));`]);
    const sld = `<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" xmlns:ogc="http://www.opengis.net/ogc">
  <NamedLayer><Name>p</Name><UserStyle><Title>p</Title><FeatureTypeStyle><Rule><PointSymbolizer><Graphic>
    <Mark><WellKnownName>circle</WellKnownName></Mark><Size>6</Size></Graphic></PointSymbolizer></Rule></FeatureTypeStyle>
  </UserStyle></NamedLayer>
</StyledLayerDescriptor>`;
    await geoserverApi('styles/gp334_points', { method: 'PUT', body: JSON.stringify({ sld }) });
    await geoserverApi('layers/gp334_points', {
      method: 'PUT',
      body: JSON.stringify({ title: 'Points', srs: 'EPSG:32633', style: 'gp334_points' }),
    });
    const published = (await geoserverApi('alignment', { method: 'POST' })).body as { failed: boolean };
    expect(published.failed).toBe(false);

    const caps = await (await geoserver(`/${ws}/wms?service=WMS&version=1.3.0&request=GetCapabilities`)).text();
    expect(caps).toContain('<Name>gp334_points</Name>');
    const wfs = (await (
      await geoserver(`/${ws}/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=gp334_points` +
        '&outputFormat=application/json&srsName=EPSG:32633')
    ).json()) as { features: { geometry: { coordinates: number[] } }[] };
    expect(wfs.features[0].geometry.coordinates[0]).toBeCloseTo(291474.53, 1);

    // By hand in GeoServer: another title, and a style of its own.
    await geoserver(`/rest/workspaces/${ws}/datastores/data/featuretypes/gp334_points`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ featureType: { title: 'Changed by hand' } }),
    });
    await geoserver(`/rest/workspaces/${ws}/styles?name=gp334_stray`, {
      method: 'POST',
      headers: { 'content-type': 'application/vnd.ogc.sld+xml' },
      body: sld,
    });
    const plan = (await geoserverApi('alignment')).body as { applied: boolean; changes: { name: string; reason: string }[] };
    expect(plan.applied).toBe(false);
    expect(plan.changes).toContainEqual(expect.objectContaining({ name: 'gp334_points', reason: 'title' }));
    const back = (await geoserverApi('alignment', { method: 'POST' })).body as { drift: { kind: string; name: string }[] };
    expect(back.drift).toContainEqual({ kind: 'style', name: 'gp334_stray' });
    const ft = (await (await geoserver(`/rest/workspaces/${ws}/featuretypes/gp334_points.json`)).json()) as {
      featureType: { title: string };
    };
    expect(ft.featureType.title).toBe('Points');
    expect((await geoserver(`/rest/workspaces/${ws}/styles/gp334_stray.json`)).status).toBe(200);

    // Withdrawn in the catalog: gone from GeoServer at the next alignment.
    await geoserverApi('layers/gp334_points', { method: 'DELETE' });
    await geoserverApi('styles/gp334_points', { method: 'DELETE' });
    await geoserverApi('alignment', { method: 'POST' });
    expect((await geoserver(`/rest/workspaces/${ws}/featuretypes/gp334_points.json`)).status).toBe(404);

    // Every alignment is in the audit log of the organization, and the credentials never are.
    const audit = await api(manager, `/api/v1/organizations/${slug}/audit-events?action=dev.geoportal.catalog:`);
    expect(JSON.stringify(audit.body)).toContain('geoserver.aligned');
    expect(JSON.stringify(audit.body)).not.toContain(GS_PASSWORD);
  });

  test('GP-334 a projected vector file loaded through Geoportal lands in PostGIS in its system and WFS returns the source coordinates', async () => {
    await credential('geoserver', `gsadmin:${GS_PASSWORD}`);
    await credential('geodata', 'geoserver:catalog-test-db');
    const owner = member(0);
    const exception = (permission: string) =>
      `/api/v1/organizations/${slug}/permissions/members/${owner.user}/exceptions/dev.geoportal.catalog:${permission}`;
    // Three stations of a test town (Centrale, Nord, Sud) in EPSG:32633.
    const stations: [string, number, number][] = [
      ['Centrale', 292719.039, 4641793.153],
      ['Nord', 295196.583, 4642777.113],
      ['Sud', 291273.415, 4638645.888],
    ];
    const file = JSON.stringify({
      type: 'FeatureCollection',
      crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
      features: stations.map(([nome, x, y]) => ({ type: 'Feature', properties: { nome }, geometry: { type: 'Point', coordinates: [x, y] } })),
    });
    for (const permission of ['upload-data', 'publish']) {
      expect((await api(manager, exception(permission), { method: 'PUT' })).status).toBe(200);
    }
    let id: string | undefined;
    try {
      const name = `${unique('gp334-stazioni').toLowerCase()}.geojson`;
      const uploaded = await api(owner, `/api/v1/ext/catalog/uploads?${new URLSearchParams({ name, title: 'Stazioni' })}`, {
        method: 'POST',
        headers: { 'content-type': 'application/octet-stream' },
        body: file,
      });
      expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
      const d = uploaded.body as { id: string; state: string; loaded: { table: string; crs: string } };
      id = d.id;
      expect(d.state).toBe('loaded');
      expect(d.loaded.crs).toBe('EPSG:32633');
      const [schema, table] = d.loaded.table.split('.');
      const srid = execFileSync('docker', ['exec', `${PROJECT}-postgres-1`, 'psql', '-U', 'geoserver', '-d', 'geodata', '-At',
        '-c', `select Find_SRID('${schema}', '${table}', 'geom')`], { encoding: 'utf8' }).trim();
      expect(srid).toBe('32633');

      const layer = unique('gp334_stazioni').toLowerCase().replace(/[^a-z0-9_]/g, '_');
      const published = await api(owner, '/api/v1/ext/catalog/publications', {
        method: 'POST',
        body: JSON.stringify({ dataset: d.id, layer }),
      });
      expect(published.status, JSON.stringify(published.body)).toBe(201);
      const wfs = (await (
        await geoserver(`/${schema}/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=${schema}:${layer}` +
          '&outputFormat=application/json&srsName=EPSG:32633')
      ).json()) as { features: { properties: { nome: string }; geometry: { coordinates: number[] } }[] };
      expect(wfs.features).toHaveLength(3);
      for (const [nome, x, y] of stations) {
        const f = wfs.features.find((feature) => feature.properties.nome === nome);
        expect(f?.geometry.coordinates[0]).toBeCloseTo(x, 2);
        expect(f?.geometry.coordinates[1]).toBeCloseTo(y, 2);
      }
    } finally {
      if (id) {
        await api(owner, `/api/v1/ext/catalog/uploads/${id}`, { method: 'DELETE' });
      }
      for (const permission of ['upload-data', 'publish']) {
        await api(manager, exception(permission), { method: 'DELETE' });
      }
    }
  });
});
