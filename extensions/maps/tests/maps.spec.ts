// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { member, openApp, signIn } from '@mosaikit/testing/playwright';

test('the maps of the space are shown as cards', async ({ page }) => {
  await signIn(page, member());
  await openApp(page, 'Maps');
  await expect(page.getByRole('heading', { name: /^(Maps|Mappe)$/ })).toBeVisible();
  await expect(page.getByRole('listitem')).toHaveCount(6);
  await expect(page.getByRole('heading', { name: 'Rete di monitoraggio, Valverde' })).toBeVisible();
});

test.describe('in Italian', () => {
  test.use({ locale: 'it-IT' });

  test('the app is called Mappe in the app bar', async ({ page }) => {
    await signIn(page, member());
    await openApp(page, 'Mappe');
    await expect(page).toHaveURL(/\/app\/maps$/);
  });
});
