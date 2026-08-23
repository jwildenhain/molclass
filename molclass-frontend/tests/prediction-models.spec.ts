import { test, expect } from "@playwright/test";
import { publishedModels, routes, stubBackend, stubEndpoint } from "./fixtures/backend";

/** The published-model registry and the model + molecule prediction flow. */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

test("lists published builds with their split and holdout evidence", async ({ page }) => {
  await page.goto("/search?tab=models");

  await expect(page.getByRole("heading", { level: 1, name: "Search" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Classification models" })).toBeVisible();
  await expect(page.getByText(`${publishedModels.length} published`)).toBeVisible();

  const headers = page.getByRole("columnheader");
  await expect(headers.filter({ hasText: "Model" }).first()).toBeVisible();
  await expect(headers.filter({ hasText: "Algorithm" })).toBeVisible();
  await expect(headers.filter({ hasText: "Features" })).toBeVisible();
  await expect(headers.filter({ hasText: "Split" })).toBeVisible();
  await expect(headers.filter({ hasText: "Holdout" })).toBeVisible();

  const firstRow = page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" });
  await expect(firstRow).toContainText("RandomForest");
  await expect(firstRow).toContainText("1840/460/575");
  await expect(firstRow).toContainText("12 excluded");
  await expect(firstRow).toContainText("87.3%");

  // A build without a recorded holdout metric must say so rather than show 0%.
  const secondRow = page.getByRole("row").filter({ hasText: "Model 98" });
  await expect(secondRow).toContainText("n/a");
});

test("the model table's columns sort on click", async ({ page }) => {
  await page.goto("/search?tab=models");
  await expect(page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" })).toBeVisible();

  const algorithmHeader = page.getByRole("columnheader", { name: "Algorithm" });
  const dataRows = page.locator("tbody tr");

  // Ascending: "KNN" (Model 98) sorts before "RandomForest" (Mitochondrial uncoupler).
  await algorithmHeader.getByRole("button").click();
  await expect(dataRows.first()).toContainText("Model 98");

  // Clicking the same header again reverses to descending.
  await algorithmHeader.getByRole("button").click();
  await expect(dataRows.first()).toContainText("Mitochondrial uncoupler");
});

test("the search form sends the typed query to the registry", async ({ page }) => {
  await page.goto("/search?tab=models");
  await expect(page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" })).toBeVisible();

  const request = page.waitForRequest((candidate) =>
    routes.publishedModels.test(candidate.url()) && candidate.url().includes("query=RandomForest"),
  );

  await page.getByPlaceholder("e.g. RandomForest, MCAT, 102").fill("RandomForest");
  await page.getByRole("button", { name: "Search" }).first().click();
  await request;
});

test("explains an empty registry instead of showing a bare table", async ({ page }) => {
  await stubEndpoint(page, routes.publishedModels, []);
  await page.goto("/search?tab=models");

  await expect(page.getByRole("heading", { name: "No published v3 models" })).toBeVisible();
  await expect(page.getByText("Rebuilt models remain unavailable here until a human approves them.")).toBeVisible();
  await expect(page.getByRole("table")).toHaveCount(0);
});

test("surfaces a registry failure as an error, never as an empty success", async ({ page }) => {
  await stubEndpoint(page, routes.publishedModels, { detail: "predictor unavailable" }, 503);
  await page.goto("/search?tab=models");

  await expect(page.getByText(/predictor unavailable/)).toBeVisible();
  await expect(page.getByRole("table")).toHaveCount(0);
});

test("runs a prediction once a model and a molecule are selected", async ({ page }) => {
  // Molecules are picked on the structure-search tab and handed over via ?molecules=;
  // this panel has no inline molecule search of its own.
  await page.goto("/search?tab=models&molecules=4711");

  const runButton = page.getByRole("button", { name: "Run approved model" });
  await expect(page.getByText("Caffeine")).toBeVisible();
  await expect(runButton).toBeDisabled();

  const modelCheckbox = page.getByRole("row")
    .filter({ hasText: "Mitochondrial uncoupler" })
    .getByRole("checkbox");
  await modelCheckbox.check();
  await expect(modelCheckbox).toBeChecked();
  await expect(page.getByText("1 of 2 selected")).toBeVisible();
  await expect(runButton).toBeEnabled();

  await runButton.click();

  // The outcome card's kicker names the model that produced this result.
  await expect(page.getByText("Mitochondrial uncoupler · RandomForest")).toBeVisible();
  await expect(page.getByRole("paragraph").filter({ hasText: /^active$/ })).toBeVisible();
  await expect(page.getByText("81.2% confidence")).toBeVisible();
  await expect(page.getByText("81.23%")).toBeVisible();
  await expect(page.getByText("18.77%")).toBeVisible();
  await expect(page.getByText("In domain")).toBeVisible();
});

test("reports a failed prediction without clearing the selection", async ({ page }) => {
  await stubEndpoint(page, routes.predict, { detail: "no artifact published for build" }, 409);
  await page.goto("/search?tab=models&molecules=4711");
  await expect(page.getByText("Caffeine")).toBeVisible();

  const modelCheckbox = page.getByRole("row")
    .filter({ hasText: "Mitochondrial uncoupler" })
    .getByRole("checkbox");
  await modelCheckbox.check();
  await page.getByRole("button", { name: "Run approved model" }).click();

  await expect(page.getByText(/no artifact published for build/)).toBeVisible();
  await expect(page.getByText("Mitochondrial uncoupler · RandomForest")).toBeVisible();
  // The failed model stays selected: the user can retry without re-picking it.
  await expect(modelCheckbox).toBeChecked();
});
