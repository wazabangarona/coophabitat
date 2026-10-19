import { defineConfig, devices } from '@playwright/test';

/**
 * Tests de bout en bout : application Angular servie sur :4200 (proxy vers l'API sur :8080) avec le jeu de
 * démonstration chargé (COOPHABITAT_DEMO_ENABLED=true). Voir docs/tests.md.
 */
const executablePath = process.env['PLAYWRIGHT_CHROMIUM_PATH'] || undefined;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    locale: 'fr-SN',
    timezoneId: 'Africa/Dakar',
    trace: 'retain-on-failure',
    launchOptions: { executablePath },
  },
  projects: [
    { name: 'ordinateur', use: { ...devices['Desktop Chrome'], channel: undefined } },
    { name: 'mobile', use: { ...devices['Pixel 7'], channel: undefined } },
  ],
});
