// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { api, bench, member, unique } from '@mosaikit/testing/playwright';

/**
 * GP-334, criterion 1 on the bench with the GeoServer of tests/geoserver/compose.yaml
 * (GEOPORTAL_TEST_GEOSERVER=1): the catalog follows the life of an organization through the events of
 * Mosaikit (MK-085), with the credentials of GeoServer stored once for the installation (MK-127).
 * Creating an organization creates its workspace, store and schema; turning the catalog off withdraws
 * its publications without deleting anything, and turning it on serves them again.
 */

const admin = () => bench().admin;
const manager = member(1);
const slug = bench().organization.slug;
const GEOSERVER = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');
const PROJECT = process.env.GEOPORTAL_TEST_COMPOSE_PROJECT ?? 'geoportal-catalog-test';
const CATALOG = 'dev.geoportal.ext.catalog.geodata';

const workspaceOf = (id: string) => 'org_' + id.replaceAll('-', '').toLowerCase();

async function geoserver(path: string): Promise<Response> {
  return fetch(GEOSERVER + path, { headers: { authorization: GS_AUTH } });
}

/** A credential of the installation, for the catalog only (MK-127). */
async function installationCredential(name: string, value: string): Promise<void> {
  const body = { name, kind: 'password', value, description: `GP-334 ${name}`, extension: CATALOG };
  const existing = await api(admin(), `/api/v1/platform/credentials/${name}`);
  const { status } =
    existing.status === 200
      ? await api(admin(), `/api/v1/platform/credentials/${name}`, { method: 'PUT', body: JSON.stringify(body) })
      : await api(admin(), '/api/v1/platform/credentials', { method: 'POST', body: JSON.stringify(body) });
  expect(status).toBeLessThan(300);
}

async function setCatalog(organization: string, enabled: boolean): Promise<void> {
  const apps = (await api(admin(), `/api/v1/organizations/${organization}/apps`)).body as {
    extensionId: string;
    enabled: boolean;
  }[];
  expect(apps.some((a) => a.extensionId === CATALOG)).toBe(true);
  const changed = apps.map((a) => (a.extensionId === CATALOG ? { ...a, enabled } : a));
  const { status } = await api(admin(), `/api/v1/organizations/${organization}/apps`, {
    method: 'PUT',
    body: JSON.stringify(changed),
  });
  expect(status).toBe(200);
}

test.describe('on GeoServer', () => {
  test.skip(!process.env.GEOPORTAL_TEST_GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(180_000);

  test.beforeAll(async () => {
    await installationCredential('geoserver', 'gsadmin:catalog-test-admin');
    await installationCredential('geodata', 'geoserver:catalog-test-db');
    // The published views of Mosaikit, for the GeoFence rules (GP-335): the login of the database of the bench.
    const s = bench().settings;
    await installationCredential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);
  });

  test.afterAll(async () => {
    for (const name of ['geoserver', 'geodata', 'published-views']) {
      await api(admin(), `/api/v1/platform/credentials/${name}`, { method: 'DELETE' });
    }
  });

  test('GP-334 creating an organization through Mosaikit creates its GeoServer workspace, store, schema and base GeoFence rules', async () => {
    const newSlug = unique('gp334-org').toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 40).replace(/-+$/, '');
    const created = await api(admin(), '/api/v1/organizations', {
      method: 'POST',
      body: JSON.stringify({ slug: newSlug, name: 'GP-334 events' }),
    });
    expect(created.status).toBe(201);
    const ws = workspaceOf((created.body as { id: string }).id);

    // Nobody aligns: the handler of organization.created does, a few seconds after the creation.
    await expect
      .poll(async () => (await geoserver(`/rest/workspaces/${ws}.json`)).status, { timeout: 90_000, intervals: [1000] })
      .toBe(200);
    await expect
      .poll(async () => (await geoserver(`/rest/workspaces/${ws}/datastores/data.json`)).status, { timeout: 30_000 })
      .toBe(200);
    const store = (await (await geoserver(`/rest/workspaces/${ws}/datastores/data.json`)).json()) as {
      dataStore: { connectionParameters: { entry: { '@key': string; $: string }[] } };
    };
    const schema = store.dataStore.connectionParameters.entry.find((e) => e['@key'] === 'schema');
    expect(schema?.$).toBe(ws);
    const namespace = (await (await geoserver(`/rest/namespaces/${ws}.json`)).json()) as { namespace: { uri: string } };
    expect(namespace.namespace.uri).toBe(`urn:geoportal:organization:${(created.body as { id: string }).id}`);

    // The base rules of the workspace (GP-335): its administrators see it all, everybody else nothing.
    const rules = async () =>
      ((await (await geoserver('/rest/geofence/rules.json')).json()) as {
        rules: { workspace?: string; access: string; layer?: string; userName?: string; roleName?: string }[];
      }).rules.filter((r) => r.workspace === ws);
    await expect.poll(async () => (await rules()).length, { timeout: 60_000, intervals: [1000] }).toBeGreaterThan(0);
    const base = await rules();
    expect(base.some((r) => r.access === 'ALLOW' && !r.layer && r.roleName)).toBe(true);
    expect(base.some((r) => r.access === 'DENY' && !r.layer && !r.userName && !r.roleName)).toBe(true);

    // The organization holds none of the credentials: those of the installation were read, and audited.
    const audit = await api(admin(), `/api/v1/organizations/${newSlug}/audit-events?action=dev.geoportal.ext.catalog.geodata:`);
    expect(JSON.stringify(audit.body)).toContain('geoserver.aligned');
    expect(JSON.stringify(audit.body)).not.toContain('catalog-test-admin');
  });

  test('GP-334 turning the catalog off withdraws the publications of the organization, keeping the data', async () => {
    // The second person of the bench administers the organization for this test.
    await api(admin(), `/api/v1/organizations/${slug}/members/${manager.user}`, {
      method: 'PUT',
      body: JSON.stringify({ roles: ['organization-admin', 'organization-user'] }),
    });
    try {
      const first = (await api(manager, '/api/v1/ext/catalog-geodata/geoserver/alignment', { method: 'POST' })).body as {
        workspace: string;
        failed: boolean;
      };
      expect(first.failed).toBe(false);
      const ws = first.workspace;
      execFileSync('docker', ['exec', `${PROJECT}-postgres-1`, 'psql', '-U', 'geoserver', '-d', 'geodata', '-c',
        `drop table if exists ${ws}.gp334_off; create table ${ws}.gp334_off (id serial primary key,` +
        ` geom geometry(Point, 4326)); insert into ${ws}.gp334_off (geom) values (ST_SetSRID(ST_MakePoint(12.5, 41.9), 4326));`]);
      await api(manager, '/api/v1/ext/catalog-geodata/geoserver/layers/gp334_off', {
        method: 'PUT',
        body: JSON.stringify({ title: 'Off', srs: 'EPSG:4326' }),
      });
      expect(((await api(manager, '/api/v1/ext/catalog-geodata/geoserver/alignment', { method: 'POST' })).body as {
        failed: boolean;
      }).failed).toBe(false);
      const enabled = async () =>
        ((await (await geoserver(`/rest/workspaces/${ws}/featuretypes/gp334_off.json`)).json()) as {
          featureType: { enabled: boolean };
        }).featureType.enabled;
      expect(await enabled()).toBe(true);

      await setCatalog(slug, false);
      await expect.poll(enabled, { timeout: 90_000, intervals: [1000] }).toBe(false);
      const caps = await (await geoserver(`/${ws}/wms?service=WMS&version=1.3.0&request=GetCapabilities`)).text();
      expect(caps).not.toContain('<Name>gp334_off</Name>');
      // Nothing deleted: the workspace, the layer and the rows of its table.
      expect((await geoserver(`/rest/workspaces/${ws}.json`)).status).toBe(200);
      const rows = execFileSync('docker', ['exec', `${PROJECT}-postgres-1`, 'psql', '-U', 'geoserver', '-d', 'geodata',
        '-tAc', `select count(*) from ${ws}.gp334_off`]).toString().trim();
      expect(rows).toBe('1');

      await setCatalog(slug, true);
      await expect.poll(enabled, { timeout: 90_000, intervals: [1000] }).toBe(true);
      const audit = await api(admin(), `/api/v1/organizations/${slug}/audit-events?action=dev.geoportal.ext.catalog.geodata:`);
      expect(JSON.stringify(audit.body)).toContain('geoserver.withdrawn');

      await api(manager, '/api/v1/ext/catalog-geodata/geoserver/layers/gp334_off', { method: 'DELETE' });
      await api(manager, '/api/v1/ext/catalog-geodata/geoserver/alignment', { method: 'POST' });
    } finally {
      await setCatalog(slug, true).catch(() => undefined);
      await api(admin(), `/api/v1/organizations/${slug}/members/${manager.user}`, {
        method: 'PUT',
        body: JSON.stringify({ roles: ['organization-user'] }),
      });
    }
  });
});
