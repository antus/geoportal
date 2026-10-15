// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, restart, signIn } from '@mosaikit/testing/playwright';

/**
 * Issue #40: the catalog accepts files up to the smaller of the two limits of the kernel
 * (mosaikit.files.max-size and quarkus.http.limits.max-body-size), says it in the upload dialog, and
 * refuses a larger file with a clear message: in the language of the person on the page, in English
 * with the limit in bytes from the API. The bench restarts with a limit of 64 KB on files and a larger
 * one on bodies, so that the refusal is the catalog's and not the cut-off of the HTTP layer.
 */

const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const LIMIT = 64 * 1024;
const GEOJSON = JSON.stringify({
  type: 'FeatureCollection',
  features: [{ type: 'Feature', properties: { nome: 'Pozzo' }, geometry: { type: 'Point', coordinates: [12.38, 42.1] } }],
});
const EXTENSION = 'dev.geoportal.ext.catalog.geodata';
const owner = () => member(0);
const colleague = () => member(1);

/** Grants or revokes upload-data to the owner, through the colleague made administrator for the call. */
async function uploader(on: boolean): Promise<void> {
  const organization = bench().organization.slug;
  const roles = (admin: boolean) =>
    api(bench().admin, `/api/v1/organizations/${organization}/members/${colleague().user}`, {
      method: 'PUT',
      body: JSON.stringify({ roles: admin ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
    });
  expect((await roles(true)).status).toBe(200);
  try {
    const exception = `/api/v1/organizations/${organization}/permissions/members/${owner().user}/exceptions/${EXTENSION}:upload-data`;
    expect((await api(colleague(), exception, { method: on ? 'PUT' : 'DELETE' })).status).toBeLessThan(300);
  } finally {
    await roles(false);
  }
}

async function upload(name: string, content: string) {
  return api(owner(), `${UPLOADS}?${new URLSearchParams({ name })}`, {
    method: 'POST',
    headers: { 'content-type': 'application/octet-stream' },
    body: content,
  });
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.setTimeout(240_000);
  await restart({
    ...bench().settings,
    'geoportal.catalog.samples': 'false',
    'mosaikit.files.max-size': '64K',
    'quarkus.http.limits.max-body-size': '128K',
  });
  await uploader(true);
});

test.afterAll(async () => {
  test.setTimeout(240_000);
  // The tests of uploads.spec.ts start from an owner without the permission.
  await uploader(false);
  await restart(bench().settings);
});

test('issue #40 the catalog accepts a file of the largest size and refuses one byte more with 413 and the limit', async () => {
  const space = (await api(owner(), `${UPLOADS}/space`)).body as { maxFile: number };
  expect(space.maxFile).toBe(LIMIT);

  const largest = await upload('pozzi.geojson', GEOJSON.padEnd(LIMIT, ' '));
  expect(largest.status).toBe(201);
  expect((largest.body as { size: number }).size).toBe(LIMIT);
  await api(owner(), `${UPLOADS}/${(largest.body as { id: string }).id}`, { method: 'DELETE' });

  const larger = await upload('pozzi.geojson', GEOJSON.padEnd(LIMIT + 1, ' '));
  expect(larger.status).toBe(413);
  expect(larger.body).toMatchObject({
    status: 413,
    maxFile: LIMIT,
    detail: 'The file is larger than the 64 KB (65536 bytes) this installation accepts.',
  });
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('issue #40 the upload dialog says the largest file and refuses a larger one before sending it', async ({ page }) => {
    let sent = false;
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === UPLOADS) {
        sent = true;
      }
    });
    await signIn(page, owner());
    await openApp(page, 'Dati geografici');
    await page.getByRole('button', { name: 'Carica dati' }).click();
    const dialog = page.getByRole('dialog', { name: 'Carica dati' });
    await expect(dialog.getByText('Dimensione massima di un file: 64 KB.')).toBeVisible();

    await dialog.getByLabel('File da caricare').setInputFiles({
      name: 'pozzi.geojson',
      mimeType: 'application/geo+json',
      buffer: Buffer.from(GEOJSON.padEnd(LIMIT + 1, ' ')),
    });
    const refusal = dialog.getByRole('alert');
    await expect(refusal).toHaveText('Il file è troppo grande: si possono caricare file fino a 64 KB.');
    await dialog.getByRole('button', { name: 'Carica', exact: true }).click();
    await expect(refusal).toHaveText('Il file è troppo grande: si possono caricare file fino a 64 KB.');
    await expect(dialog).toBeVisible();
    await page.screenshot({ path: 'test-results/issue-40-too-large.png' });
    expect(sent).toBe(false);
  });
});
