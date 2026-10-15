// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test } from '@playwright/test';
import { api, member, openApp, signIn } from '@mosaikit/testing/playwright';

test('the catalog shows the datasets of the space, searchable and with their details', async ({ page }) => {
  await signIn(page, member());
  await openApp(page, 'Geodata');
  await expect(page.getByRole('heading', { name: /^(Geodata|Dati geografici)$/ })).toBeVisible();
  await expect(page.getByRole('status')).toHaveText(/^14 datasets?$/);

  await page.getByRole('searchbox').fill('Valverde');
  await expect(page.getByRole('status')).toHaveText(/^2 datasets?$/);
  await page.getByText('Livello del Valverde, sensori').click();
  await expect(page.getByRole('complementary', { name: 'Livello del Valverde, sensori' })).toContainText('WebSocket');

  // A colleague of the same space reads the same catalog through the API of the extension.
  const { status, body } = await api(member(1), '/api/v1/ext/catalog-geodata/datasets?q=ortofoto');
  expect(status).toBe(200);
  expect((body as { title: string }[]).map((d) => d.title)).toEqual(['Ortofoto 2025, 20 cm']);
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('the app is called Dati geografici in the app bar', async ({ page }) => {
    await signIn(page, member());
    await openApp(page, 'Dati geografici');
    await expect(page).toHaveURL(/\/app\/geodata$/);
  });
});
