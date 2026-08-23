import { test, expect } from "@playwright/test";
import { stubBackend } from "./fixtures/backend";

/**
 * /search hosts both lookup surfaces as tabs: the registry structure search,
 * and the model + molecule prediction panel moved over from the prediction list.
 */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

test("defaults to structure search", async ({ page }) => {
  await page.goto("/search");

  await expect(page.getByRole("heading", { level: 1, name: "Search" })).toBeVisible();
  await expect(page.getByRole("tab", { name: /Structure search/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { level: 2, name: "Find a molecule without loading a model." })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Classification models" })).toHaveCount(0);
});

test("switches to the model and molecule panel", async ({ page }) => {
  await page.goto("/search");

  await page.getByRole("tab", { name: /Model & molecule search/ }).click();

  await expect(page).toHaveURL(/\/search\?tab=models$/);
  await expect(page.getByRole("heading", { level: 2, name: "Classification models" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Find a molecule without loading a model." })).toHaveCount(0);
});

test("a deep link opens the requested tab directly", async ({ page }) => {
  await page.goto("/search?tab=models");

  await expect(page.getByRole("tab", { name: /Model & molecule search/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { level: 2, name: "Classification models" })).toBeVisible();
});

test("an unknown tab falls back to structure search", async ({ page }) => {
  await page.goto("/search?tab=nonsense");

  await expect(page.getByRole("tab", { name: /Structure search/ })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { level: 2, name: "Find a molecule without loading a model." })).toBeVisible();
});
