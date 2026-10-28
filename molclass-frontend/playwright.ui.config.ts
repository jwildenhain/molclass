import { defineConfig, devices } from '@playwright/test';

// Deliberately local: this suite simulates failures and must never submit live jobs.
const port = 3101;
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: './tests',
  testMatch: ['search.spec.ts', 'linked-structure.spec.ts', 'prediction-models.spec.ts', 'molecule-journey.spec.ts'],
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  workers: process.env.CI ? 2 : 4,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/ui', open: 'never' }]],
  outputDir: 'test-results/ui',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'desktop-firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'desktop-webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } },
    { name: 'mobile-webkit', use: { ...devices['iPhone 13'] } },
  ],
  webServer: {
    command: 'npm run build && node .next/standalone/server.js',
    env: { PORT: String(port), HOSTNAME: '127.0.0.1' },
    url: baseURL,
    reuseExistingServer: false,
    timeout: 180_000,
  },
});
