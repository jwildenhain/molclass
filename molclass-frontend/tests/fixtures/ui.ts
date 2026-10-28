import { test as base, expect } from '@playwright/test';

// Shared by the critical workflows in both the regular suite and production gate.
export const test = base.extend<{ browserHealth: void }>({
  browserHealth: [async ({ page }, use) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    // Registered first so each test's explicit backend fixtures take precedence.
    // An unmocked API call must fail here rather than reach a real backend.
    await page.route('**/api/**', async (route) => {
      errors.push(`Unmocked API request: ${route.request().method()} ${route.request().url()}`);
      await route.abort();
    });
    await use();
    expect(errors, 'Browser errors or unmocked backend requests').toEqual([]);
  }, { auto: true }],
});
export { expect };
