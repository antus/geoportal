// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { defineConfig, devices } from '@playwright/test';

/** The tests of the sample on a Mosaikit installation started by the bench (tests/global-setup.ts). */
export default defineConfig({
  testDir: './tests',
  testMatch: '*.spec.ts',
  globalSetup: './tests/global-setup.ts',
  workers: 1,
  timeout: 60_000,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['github']] : [['list']],
  use: { trace: 'retain-on-failure', ...devices['Desktop Chrome'] },
});
