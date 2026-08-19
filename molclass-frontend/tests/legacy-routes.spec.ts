import { test, expect } from "@playwright/test";
import { stubBackend } from "./fixtures/backend";

/**
 * The legacy PHP surfaces (view_model.php, view_batch.php and their detail
 * pages) were replaced rather than reimplemented: the old paths now exist only
 * as redirects into the v3 registry views. These tests pin that contract so a
 * stray link or bookmark cannot quietly land on a dead route.
 *
 * Replaces the former legacy-parity suite, which asserted the PHP markup that
 * docs/V3_LEGACY_DEPRECATION.md retired.
 */

const REDIRECTS = [
  { from: "/prediction-list/batches", to: "/dataset-review" },
  { from: "/prediction-list/batches/1", to: "/dataset-review" },
  { from: "/prediction-list/1", to: "/prediction-list/models" },
  { from: "/prediction-list/models/1", to: "/prediction-list/models" },
];

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

for (const redirect of REDIRECTS) {
  test(`${redirect.from} redirects to ${redirect.to}`, async ({ page }) => {
    const response = await page.goto(redirect.from);
    expect(response?.status()).toBe(200);
    await expect(page).toHaveURL(new RegExp(`${redirect.to}$`));
  });
}

test("the prediction hub offers the registry views, not legacy batches", async ({ page }) => {
  await page.goto("/prediction-list");

  await expect(page.getByRole("heading", { level: 1, name: /Choose the registry/ })).toBeVisible();

  const modelCard = page.getByRole("link", { name: /Model and molecule search/ });
  const datasetCard = page.getByRole("link", { name: /Dataset registry/ });

  await expect(modelCard).toHaveAttribute("href", "/prediction-list/models");
  await expect(datasetCard).toHaveAttribute("href", "/dataset-review");
});

test("prediction runs are described as published-only", async ({ page }) => {
  await page.goto("/prediction-list");
  await expect(page.getByText("Predictions run only with explicitly published v3 builds.")).toBeVisible();
});
