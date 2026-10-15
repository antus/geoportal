// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { bench, member, signIn } from '@mosaikit/testing/playwright';

test('the Geoportal theme is offered in the settings, and the shell follows it', async ({ page }) => {
  await signIn(page, member());
  const root = page.locator('html');
  await page.getByRole('button', { name: /^Account:/ }).click();
  await page.getByRole('dialog', { name: 'Account' }).getByRole('link', { name: 'Settings' }).click();
  await page.getByLabel('Theme').selectOption({ label: 'Geoportal' });
  await page.getByRole('radio', { name: 'Light' }).check();
  await expect(root).toHaveAttribute('data-mk-theme', 'dev.geoportal.theme');
  await expect
    .poll(() => root.evaluate((html) => getComputedStyle(html).getPropertyValue('--mk-accent').trim()))
    .toBe('#444791');
});

test('as the theme of the installation, the shell is called Geoportal and shows its logo', async ({ page }) => {
  const assets = '/api/v1/extension-assets/dev.geoportal.theme/web';
  await page.emulateMedia({ colorScheme: 'light' });
  await page.goto(bench().baseUrl);
  await expect(page).toHaveTitle('Geoportal');
  const signInPage = page.locator('mk-sign-in');
  await expect(signInPage.locator('.brand')).toHaveText('Geoportal');
  await expect(signInPage.locator('.brand img')).toHaveAttribute('src', `${assets}/logo.svg`);
  await expect(page.locator('link[rel="icon"]')).toHaveAttribute('href', `${assets}/logo.svg`);

  await signIn(page, member());
  const brand = page.locator('mk-shell').locator('.top .brand');
  await expect(brand.locator('.name')).toHaveText('Geoportal');
  await expect(brand.locator('img.logo')).toHaveAttribute('src', `${assets}/logo-dark.svg`);
});
