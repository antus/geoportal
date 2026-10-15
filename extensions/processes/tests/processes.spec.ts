// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { member, openApp, signIn } from '@mosaikit/testing/playwright';

test('the processes of the space show how their last run went', async ({ page }) => {
  await signIn(page, member());
  await openApp(page, 'Processes');
  await expect(page.getByRole('heading', { name: /^(Processes|Processi)$/ })).toBeVisible();
  const rows = page.locator('tbody tr');
  await expect(rows).toHaveCount(5);
  await expect(rows.filter({ hasText: 'Catasto in GeoPackage' }).locator('.dot')).toHaveClass(/bad/);
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('the app is called Processi in the app bar', async ({ page }) => {
    await signIn(page, member());
    await openApp(page, 'Processi');
    await expect(page).toHaveURL(/\/app\/processes$/);
  });
});
