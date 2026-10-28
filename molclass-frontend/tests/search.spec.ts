import { test, expect } from "./fixtures/ui";
import { routes, stubBackend, stubEndpoint } from "./fixtures/backend";

/**
 * /search hosts both lookup surfaces as tabs: the registry structure search,
 * and the model + molecule prediction panel moved over from the prediction list.
 */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
  await stubEndpoint(page, routes.moleculePredictions, []);
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

test("predict selected opens model selection even when server navigation stalls", async ({ page }) => {
  await page.goto("/search?smiles=CCOc1ccccc1");
  await page.getByRole("button", { name: "Search registry", exact: true }).click();
  await page.locator("article").filter({ hasText: "Caffeine" }).getByRole("checkbox").check();

  // This tab transition needs no server-rendered data. A slow route response
  // must not leave the user stuck on the registry with a selected molecule.
  let releaseNavigation!: () => void;
  const navigationGate = new Promise<void>((resolve) => { releaseNavigation = resolve; });
  await page.route(/\/search\?.*_rsc=/, async (route) => {
    await navigationGate;
    await route.continue();
  });
  try {
    await page.getByRole("button", { name: "Predict selected", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Classification models", exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/search\?tab=models&molecules=4711$/);
    await expect(page.getByText("Caffeine", { exact: true })).toBeVisible();
    const runButton = page.getByRole("button", { name: "Run approved model" });
    await expect(runButton).toBeDisabled();
    await page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" }).getByRole("checkbox").check();
    await expect(runButton).toBeEnabled();
    await page.goBack();
    await expect(page.getByRole("tab", { name: /Structure search/ })).toHaveAttribute("aria-selected", "true");
  } finally {
    releaseNavigation();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("clears a normal registry search and its selection for a new molecule", async ({ page }) => {
  await page.goto("/search");
  const input = page.getByRole("textbox", { name: "Molecule search" });
  await input.fill("Caffeine");
  await page.getByRole("button", { name: "Search registry", exact: true }).click();
  await page.locator("article").filter({ hasText: "Caffeine" }).getByRole("checkbox").check();
  await page.getByRole("button", { name: "Clear search", exact: true }).click();
  await expect(input).toHaveValue("");
  await expect(input).toBeFocused();
  await expect(page.getByRole("heading", { name: "Registry matches" })).toHaveCount(0);
  await input.fill("Aspirin");
  await page.getByRole("button", { name: "Search registry", exact: true }).click();
  await expect(page.getByText("0 selected", { exact: true })).toBeVisible();
  await input.fill("another molecule");
  await expect(page.getByRole("heading", { name: "Registry matches" })).toHaveCount(0);
});

test("a delayed search response cannot restore a cleared molecule", async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  await page.route(routes.moleculeSearch, async (route) => {
    await gate;
    await route.fulfill({ contentType: "application/json", body: JSON.stringify([{ moleculeId: 4711, name: "Old molecule" }]) });
  });
  try {
    await page.goto("/search?smiles=CCO&scaffold=CC&molecules=4711");
    const request = page.waitForRequest(routes.moleculeSearch);
    await page.getByRole("button", { name: "Search registry", exact: true }).click();
    await request;
    await page.getByRole("button", { name: "Clear, back to search" }).click();
    const response = page.waitForResponse(routes.moleculeSearch);
    release();
    await response;
    await expect(page).toHaveURL(/\/search$/);
    await expect(page.getByRole("textbox", { name: "Molecule search" })).toHaveValue("");
    await expect(page.getByRole("heading", { name: "Registry matches" })).toHaveCount(0);
    await expect(page.getByText("Old molecule", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Browse registry", exact: true })).toBeEnabled();
  } finally {
    release();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("starts a fresh search from the prediction view without linked molecule parameters", async ({ page }) => {
  await page.goto("/search?tab=models&molecules=4711&smiles=CCO&scaffold=CC");
  await expect(page.getByRole("button", { name: "Remove molecule 4711" })).toBeVisible();
  await page.getByRole("button", { name: "Search another molecule" }).click();
  await expect(page).toHaveURL(/\/search$/);
  await expect(page.getByRole("textbox", { name: "Molecule search" })).toHaveValue("");
  await expect(page.getByRole("heading", { name: "Structures from ChemGrid" })).toHaveCount(0);
});
