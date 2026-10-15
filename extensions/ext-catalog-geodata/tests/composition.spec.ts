// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, apps, bench, member, openApp, signIn } from '@mosaikit/testing/playwright';
import { CONNECTIONS, FILE_MANAGER, GEODATA } from './composition.js';

/**
 * GP-983: the geospatial part of the catalog is the extension ext-catalog-geodata, installed next to the
 * file manager (ext-catalog) and the connections (ext-catalog-connection) of mosaikit-ext, which it
 * requires. The bench of every suite of this extension holds the three (tests/global-setup.ts), so the
 * suites of loading, publishing, GeoFence rules and the gateway run in this composition.
 */

type Extension = { id: string; version: string; status: string; problems: string[] };

test('GP-983 ext-catalog-geodata runs next to ext-catalog and ext-catalog-connection, with its own API and schema', async () => {
  const { status, body } = await api(bench().admin, '/api/v1/extensions');
  expect(status).toBe(200);
  const installed = new Map((body as Extension[]).map((e) => [e.id, e]));
  for (const id of [GEODATA, FILE_MANAGER, CONNECTIONS]) {
    expect(installed.get(id)?.status, `${id}: ${JSON.stringify(installed.get(id)?.problems)}`).toBe('ACTIVE');
  }
  // The old catalog is gone: one extension holds the geodata.
  expect(installed.has('dev.geoportal.catalog')).toBe(false);

  // Two APIs side by side: the geodata under catalog-geodata, the file manager under catalog.
  const space = await api(member(), '/api/v1/ext/catalog-geodata/uploads/space');
  expect(space.status, JSON.stringify(space.body)).toBe(200);
  const uploads = await api(member(), '/api/v1/ext/catalog-geodata/uploads');
  expect(uploads.status).toBe(200);
  const folders = await api(member(), '/api/v1/ext/catalog/folders');
  expect(folders.status, JSON.stringify(folders.body)).toBe(200);
});

test('GP-983 the app bar has the geodata and the file manager, each at its own address', async ({ page }) => {
  await signIn(page, member());
  await expect(apps(page).getByRole('link', { name: 'Catalog', exact: true })).toBeVisible();
  await openApp(page, 'Geodata');
  await expect(page).toHaveURL(/\/app\/geodata$/);
  await expect(page.getByRole('heading', { name: 'Geodata' })).toBeVisible();
  await page.screenshot({ path: 'test-results/gp983-geodata.png', fullPage: true });
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('GP-983 the geodata are Dati geografici, next to Catalogo of mosaikit-ext', async ({ page }) => {
    await signIn(page, member());
    await expect(apps(page).getByRole('link', { name: 'Catalogo', exact: true })).toBeVisible();
    await openApp(page, 'Dati geografici');
    await expect(page).toHaveURL(/\/app\/geodata$/);
    await expect(page.getByRole('heading', { name: 'Dati geografici' })).toBeVisible();
    await page.screenshot({ path: 'test-results/gp983-dati-geografici.png', fullPage: true });
  });
});
