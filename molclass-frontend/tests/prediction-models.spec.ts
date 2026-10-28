import { test, expect } from "./fixtures/ui";
import { moleculePredictions, prediction, publishedModels, routes, stubBackend, stubEndpoint } from "./fixtures/backend";

/** The published-model registry and the model + molecule prediction flow. */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
  await stubEndpoint(page, routes.moleculePredictions, []);
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

test("a model's display name can be renamed inline", async ({ page }) => {
  let lastBody: unknown = null;
  await page.route(routes.renameModelDefinition, async (route) => {
    lastBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ modelDefinitionId: 39, name: (lastBody as { model_name: string }).model_name }),
    });
  });

  await page.goto("/search?tab=models");
  // Scope by the build id, which stays put in edit mode — the row's own name
  // moves into an <input> whose value doesn't count as matchable text.
  const row = page.getByRole("row").filter({ hasText: "build #512" });
  await expect(row).toBeVisible();

  await row.getByRole("button", { name: "Mitochondrial uncoupler" }).click();
  const input = row.getByRole("textbox");
  await input.fill("Renamed uncoupler model");
  await row.getByRole("button", { name: "Save" }).click();

  await expect(row.getByText("Renamed uncoupler model")).toBeVisible();
  await expect.poll(() => lastBody).toEqual({ model_name: "Renamed uncoupler model" });
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

test("shows a model grid and streams results while slower models are still running", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  let releaseSlow!: () => void;
  const slowGate = new Promise<void>((resolve) => { releaseSlow = resolve; });
  await page.route(/\/api\/v3\/models\/98\/molecules\/4711\/predict$/, async (route) => {
    await slowGate;
    await route.fulfill({ status: 503, contentType: "text/plain", body: "Model temporarily unavailable" });
  });
  try {
    await page.goto("/search?tab=models&molecules=4711");
    await expect(page.getByText("Caffeine")).toBeVisible();
    await page.getByRole("checkbox").first().check();
    await page.getByRole("button", { name: "Run 1 molecule against 2 models" }).click();
    const fast = page.getByRole("group", { name: "Prediction for Mitochondrial uncoupler", exact: true });
    const slow = page.getByRole("group", { name: "Prediction for Model 98", exact: true });
    await expect(fast.getByText("81.2% confidence")).toBeVisible();
    await expect(slow.getByText("Predicting…")).toBeVisible();
    await expect(page.getByRole("status")).toContainText("1 of 2 predictions completed");
    await expect(page.getByRole("button", { name: "Working..." })).toBeDisabled();
    const fastBox = await fast.boundingBox();
    const slowBox = await slow.boundingBox();
    expect(fastBox).not.toBeNull();
    expect(slowBox).not.toBeNull();
    expect(Math.abs(fastBox!.y - slowBox!.y)).toBeLessThan(2);
    expect(slowBox!.x).toBeGreaterThan(fastBox!.x);

    releaseSlow();
    await expect(slow.getByText("Model temporarily unavailable")).toBeVisible();
    await expect(page.getByRole("status")).toContainText("2 of 2 predictions completed · 1 failed");
    await expect(fast.getByText("81.2% confidence")).toBeVisible();
    await expect(page.getByRole("button", { name: "Run approved model" })).toBeEnabled();

    await page.setViewportSize({ width: 390, height: 844 });
    const mobileFast = await fast.boundingBox();
    const mobileSlow = await slow.boundingBox();
    expect(mobileSlow!.y).toBeGreaterThan(mobileFast!.y);
    expect(mobileFast!.x + mobileFast!.width).toBeLessThanOrEqual(390);
    expect(mobileSlow!.x + mobileSlow!.width).toBeLessThanOrEqual(390);
  } finally {
    releaseSlow();
    await page.unrouteAll({ behavior: "wait" });
  }
});

test("shows saved results immediately and runs only the remaining model", async ({ page }) => {
  await stubEndpoint(page, routes.moleculePredictions, moleculePredictions);
  const submitted: string[] = [];
  await page.route(routes.predict, async (route) => {
    submitted.push(route.request().url());
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ ...prediction, modelDefinitionId: 98, modelBuildId: 611 }) });
  });
  await page.goto("/search?tab=models&molecules=4711");
  const saved = page.getByRole("group", { name: "Prediction for Mitochondrial uncoupler" });
  await expect(saved.getByText("81.2% confidence")).toBeVisible();
  await expect(saved.getByText("Saved prediction · build #512")).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect(page.locator("tbody tr")).toContainText("Model 98");
  expect(submitted).toEqual([]);
  await page.getByRole("checkbox").first().check();
  await expect(saved).toBeVisible();
  await page.getByRole("button", { name: "Run approved model" }).click();
  await expect(page.getByText(/All listed models already have predictions/)).toBeVisible();
  await expect(page.getByRole("group", { name: /Prediction for/ })).toHaveCount(2);
  expect(submitted).toHaveLength(1);
  expect(submitted[0]).toContain("/models/98/molecules/4711/predict");
  await expect(page.getByRole("button", { name: "Run approved model" })).toBeDisabled();
});

test("keeps an older build's result visible while offering the new model version", async ({ page }) => {
  await stubEndpoint(page, routes.moleculePredictions, [{ ...moleculePredictions[0], modelBuildId: 500 }]);
  await page.goto("/search?tab=models&molecules=4711");
  await expect(page.getByText("Saved prediction · build #500")).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" }).getByRole("checkbox").check();
  await page.getByRole("button", { name: "Run approved model" }).click();
  await expect(page.getByRole("group", { name: "Prediction for Mitochondrial uncoupler" })).toHaveCount(2);
  await expect(page.getByText("Saved prediction · build #500")).toBeVisible();
});

test("blocks new predictions until saved history can be checked and retried", async ({ page }) => {
  await stubEndpoint(page, routes.moleculePredictions, {}, 503);
  await page.goto("/search?tab=models&molecules=4711");
  await expect(page.getByRole("alert").filter({ hasText: "Could not load saved predictions" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Run approved model" })).toBeDisabled();
  await stubEndpoint(page, routes.moleculePredictions, moleculePredictions);
  await page.getByRole("button", { name: "Retry saved predictions" }).click();
  await expect(page.getByText("Saved prediction · build #512")).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(1);
});

test("for multiple molecules submits only missing pairs", async ({ page }) => {
  await page.route(routes.moleculeDetail, async (route) => {
    const id = Number(route.request().url().split("/").pop());
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ moleculeId: id, name: `Compound ${id}` }) });
  });
  await page.route(routes.moleculePredictions, async (route) => {
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(route.request().url().includes("/4711/") ? moleculePredictions : []) });
  });
  const submitted: string[] = [];
  await page.route(routes.predict, async (route) => {
    submitted.push(route.request().url());
    await route.fulfill({ contentType: "application/json", body: JSON.stringify({ ...prediction, moleculeId: 4712 }) });
  });
  await page.goto("/search?tab=models&molecules=4711,4712");
  await expect(page.getByText("Saved prediction · build #512")).toBeVisible();
  await page.getByRole("row").filter({ hasText: "Mitochondrial uncoupler" }).getByRole("checkbox").check();
  await page.getByRole("button", { name: "Run 2 molecules against 1 model" }).click();
  await expect(page.getByRole("group", { name: "Prediction for Mitochondrial uncoupler" })).toHaveCount(2);
  await expect(page.getByRole("status")).toContainText("1 of 1 predictions completed");
  expect(submitted).toHaveLength(1);
  expect(submitted[0]).toContain("/models/39/molecules/4712/predict");
});
