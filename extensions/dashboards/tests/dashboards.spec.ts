// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { member, openApp, signIn } from '@mosaikit/testing/playwright';

test('the dashboards of the space are shown as cards', async ({ page }) => {
  await signIn(page, member());
  await openApp(page, 'Dashboards');
  await expect(page.getByRole('heading', { name: 'Dashboard', exact: false }).first()).toBeVisible();
  await expect(page.getByRole('listitem')).toHaveCount(4);
  await expect(page.getByRole('heading', { name: 'Sala operativa traffico' })).toBeVisible();
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('the app is called Cruscotti in the app bar', async ({ page }) => {
    await signIn(page, member());
    await openApp(page, 'Cruscotti');
    await expect(page).toHaveURL(/\/app\/dashboards$/);
  });
});
