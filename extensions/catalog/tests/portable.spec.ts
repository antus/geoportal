// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, restart, unique } from '@mosaikit/testing/playwright';

/**
 * Issue #50: the catalog on an installation configured like the portable, where nothing publishes nor
 * loads: no master key for the credentials, no GeoServer, no geodata database, no loader. The first
 * visit of an administrator of a new organization loads the sample datasets (GP-936) stored and not
 * loaded, and the catalog answers 200 on that visit and the next ones.
 *
 * Before the fix, the kernel refused the read of the credential of the geodata database (no master
 * key) and that refusal, caught by the catalog, marked the transaction of the request for rollback:
 * the file of the second sample could not be stored, the request failed with 500, and the claim of the
 * samples rolled back with it, so every later visit failed the same way.
 */

const UPLOADS = '/api/v1/ext/catalog/uploads';
const MASTER_KEY = 'mosaikit.secrets.master-key';

interface Upload {
  title: string;
  format: string;
  state: string;
  stateCode: string | null;
  sample: boolean;
}

const administrator = () => member(1);

/** The settings of the bench as the portable has them: no master key, and nothing to publish to. */
function portable(): Record<string, string> {
  const settings: Record<string, string> = { ...bench().settings };
  delete settings[MASTER_KEY];
  for (const name of Object.keys(settings)) {
    if (name.startsWith('geoportal.geodata.') || name.startsWith('geoportal.catalog.loader.')) {
      delete settings[name];
    }
  }
  // An address where no GeoServer answers, as the default http://geoserver:8080 on the portable.
  settings['geoportal.geoserver.url'] = 'http://127.0.0.1:9/geoserver';
  return settings;
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.setTimeout(240_000);
  await restart(portable());
});

test.afterAll(async () => {
  test.setTimeout(240_000);
  await restart(bench().settings);
});

test('issue #50 without a master key nor GeoServer, the first visit of an administrator loads the samples and answers 200', async () => {
  test.setTimeout(240_000);
  // A new organization, which never had its samples, with an administrator.
  const slug = unique('portable').toLowerCase().replace(/[^a-z0-9-]/g, '-');
  const created = await api(bench().admin, '/api/v1/organizations', {
    method: 'POST',
    body: JSON.stringify({ slug, name: 'Issue 50 portable' }),
  });
  expect(created.status).toBe(201);
  const added = await api(bench().admin, `/api/v1/organizations/${slug}/members/${administrator().user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: ['organization-admin', 'organization-user'] }),
  });
  expect(added.status).toBe(200);
  try {
    await visits(slug);
  } finally {
    // The person signs in to the organization of the bench in the other suites: not a member of two.
    await api(bench().admin, `/api/v1/organizations/${slug}/members/${administrator().user}`, { method: 'DELETE' });
  }
});

async function visits(slug: string): Promise<void> {
  const first = await api(administrator(), UPLOADS, {}, slug);
  expect(first.status).toBe(200);
  const samples = (first.body as Upload[]).filter((d) => d.sample);
  expect(samples.length).toBeGreaterThan(20);
  // Stored, not loaded: nothing to load into, and the page says why.
  for (const sample of samples) {
    expect(sample.state).toBe('uploaded');
    expect(sample.stateCode).toBe('geodata-missing');
  }

  // Later visits answer too, and add nothing.
  for (let visit = 0; visit < 2; visit++) {
    const again = await api(administrator(), UPLOADS, {}, slug);
    expect(again.status).toBe(200);
    expect((again.body as Upload[]).filter((d) => d.sample)).toHaveLength(samples.length);
  }
  // The other pages of the catalog answer as well.
  expect((await api(administrator(), `${UPLOADS}/space`, {}, slug)).status).toBe(200);
}
