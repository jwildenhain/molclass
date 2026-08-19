import { test, expect } from "@playwright/test";
import { datasets, routes, stubBackend, stubEndpoint } from "./fixtures/backend";

/** The dataset registry that replaced the legacy batch list. */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

test("summarises imports, eligibility, and records needing attention", async ({ page }) => {
  await page.goto("/dataset-review");

  await expect(page.getByRole("heading", { level: 1, name: "Review what was actually imported." })).toBeVisible();

  const metrics = page.locator("section").filter({ hasText: "Datasets" }).first();
  await expect(metrics).toContainText("Datasets");
  await expect(metrics).toContainText("Model eligible");
  await expect(metrics).toContainText("Need attention");

  for (const dataset of datasets) {
    await expect(page.getByRole("heading", { level: 2, name: dataset.name })).toBeVisible();
  }

  const partial = page.locator("article").filter({ hasText: "Kinase panel" });
  await expect(partial).toContainText("PARTIAL");
  await expect(partial).toContainText("kinase_panel.sdf");
});

test("filters the catalogue by import state", async ({ page }) => {
  await page.goto("/dataset-review");
  await expect(page.getByRole("heading", { level: 2, name: "Uncoupler screen" })).toBeVisible();

  await page.getByLabel("Import state").selectOption("PARTIAL");

  await expect(page.getByRole("heading", { level: 2, name: "Kinase panel" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Uncoupler screen" })).toHaveCount(0);
});

test("filters the catalogue by free text", async ({ page }) => {
  await page.goto("/dataset-review");
  // Wait for the fetched rows: typing before hydration is discarded by the controlled input.
  await expect(page.getByRole("heading", { level: 2, name: "Kinase panel" })).toBeVisible();

  await page.getByPlaceholder("Name, file, identifier, or id").fill("uncoupler");

  await expect(page.getByRole("heading", { level: 2, name: "Uncoupler screen" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Kinase panel" })).toHaveCount(0);
});

test("explains an empty filter result rather than rendering nothing", async ({ page }) => {
  await page.goto("/dataset-review");
  await expect(page.getByRole("heading", { level: 2, name: "Uncoupler screen" })).toBeVisible();

  await page.getByPlaceholder("Name, file, identifier, or id").fill("no-such-dataset");

  await expect(page.getByText("No dataset matches these filters.")).toBeVisible();
});

test("shows a catalogue outage as an error", async ({ page }) => {
  await stubEndpoint(page, routes.datasets, { detail: "database unavailable" }, 500);
  await page.goto("/dataset-review");

  await expect(page.getByText("The v3 dataset catalogue is unavailable.")).toBeVisible();
});
