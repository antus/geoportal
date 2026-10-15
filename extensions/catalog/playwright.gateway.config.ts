// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { defineConfig, devices } from '@playwright/test';

/**
 * GP-336: the test of the data gateway on the server stack (tests/gateway/gateway.e2e.ts), apart from the
 * bench of the other tests: Geoportal, GeoServer and the gateway run in Docker (tests/gateway/compose.test.yaml).
 */
export default defineConfig({
  testDir: './tests/gateway',
  testMatch: '*.e2e.ts',
  workers: 1,
  timeout: 120_000,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['github']] : [['list']],
  use: { trace: 'retain-on-failure', ...devices['Desktop Chrome'] },
});
