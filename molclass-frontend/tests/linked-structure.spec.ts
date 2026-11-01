import { test, expect } from "./fixtures/ui";
import { moleculeDetail, routes, stubBackend, stubEndpoint } from "./fixtures/backend";

/**
 * A chemgrid.org compound page links here with ?smiles=...&scaffold=..., which renders the
 * "Structures from ChemGrid" panel (LinkedStructurePreview, loaded client-side only via
 * next/dynamic with ssr:false). Both of its actions -- "Clear, back to search" and searching
 * the linked structure -- were reported broken in production even though they'd been verified
 * with a direct DOM click dispatch, which bypasses real hit-testing. Playwright's .click()
 * does full actionability checks (scroll-into-view, hit-testing) like a real user click, so
 * this is the right tool to reproduce it.
 */

const LINKED_URL = "/search?smiles=CCOc1ccccc1&scaffold=c1ccccc1";

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
  await stubEndpoint(page, routes.moleculePredictions, []);
});

test("a linked structure from ChemGrid can be cleared back to the native search state", async ({ page }) => {
  await page.goto(LINKED_URL);

  await expect(page.getByRole("heading", { name: "Structures from ChemGrid" })).toBeVisible();
  const queryBox = page.getByRole("textbox", { name: "Molecule search" });
  await expect(queryBox).toHaveValue("CCOc1ccccc1");

  await page.getByRole("button", { name: "Clear, back to search" }).click();

  await expect(page).toHaveURL(/\/search$/);
  await expect(page.getByRole("heading", { name: "Structures from ChemGrid" })).toHaveCount(0);
  await expect(queryBox).toHaveValue("");
});

test("clicking the linked molecule searches for it", async ({ page }) => {
  await stubEndpoint(page, routes.moleculeSearch, []);

  await page.goto(LINKED_URL);
  await page.getByRole("button", { name: "Find this molecule" }).click();

  await expect(page.getByRole("heading", { name: "No matching molecules" })).toBeVisible();
});

test("a new structure with no exact match can be registered and predicted", async ({ page }) => {
  await stubEndpoint(page, routes.moleculeSearch, []);
  await stubEndpoint(page, routes.moleculeDetail, { ...moleculeDetail, moleculeId: 999 });
  let submitted: Record<string, unknown> | null = null;
  await page.route(routes.adhocMoleculePredict, (route) => {
    submitted = route.request().postDataJSON() as Record<string, unknown>;
    route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({ jobId: 555, status: "QUEUED" }),
    });
  });
  await page.route(routes.adhocJob, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        job_id: 555,
        status: "SUCCEEDED",
        result: {
          moleculeId: 999,
          modelDefinitionId: 39,
          prediction: { predictedClass: "active" },
        },
      }),
    }),
  );

  await page.goto(LINKED_URL);
  await page.getByRole("button", { name: "Find this molecule" }).click();
  await expect(page.getByRole("heading", { name: "No matching molecules" })).toBeVisible();

  await page.getByRole("button", { name: "Register this structure and predict" }).click();

  await expect(page).toHaveURL(/\/search\?tab=models&molecules=999$/);
  await expect(page.getByRole("heading", { name: "Classification models", exact: true })).toBeVisible();
  await expect(page.getByText("1 selected", { exact: true })).toBeVisible();
  expect(submitted).toMatchObject({ smiles: "CCOc1ccccc1", model_definition_id: expect.any(Number) });
});

test("registration monitoring survives thirty seconds, refresh and progresses to completion", async ({ page }) => {
  await page.clock.install();
  let state = { status: "QUEUED", progress: { stage: "QUEUED", percent: 0, completed: 0, total: 1, detail: "Waiting for a worker" }, result: null as null | { moleculeId: number } };
  let posts = 0;
  page.on("request", (request) => { if (request.method() === "POST") posts++; });
  await page.route(routes.adhocJob, (route) => route.fulfill({ json: state }));
  await page.goto("/search?registrationJob=555");
  const progress = page.getByRole("progressbar", { name: "Registration completion" });
  await expect(progress).toHaveAttribute("value", "0");
  await page.clock.fastForward(31000);
  await expect(page.getByText(/No need to submit again/)).toBeVisible();
  // Let Next navigation prefetches settle before deliberately unloading WebKit.
  await page.waitForLoadState("networkidle");
  await page.reload();
  await expect(progress).toHaveAttribute("value", "0");
  state = { ...state, status: "RUNNING", progress: { stage: "COMPUTE_DESCRIPTORS", percent: 30, completed: 20, total: 40, detail: "Calculating properties" } };
  await page.clock.runFor(1600);
  await expect(progress).toHaveAttribute("value", "30");
  await expect(page.getByText("20 / 40", { exact: true })).toBeVisible();
  state = { ...state, progress: { stage: "COMPUTE_FINGERPRINTS", percent: 70, completed: 6, total: 10, detail: "MACCS" } };
  await page.clock.runFor(1600);
  await expect(progress).toHaveAttribute("value", "70");
  await expect(page.getByText("6 / 10", { exact: true })).toBeVisible();
  state = { ...state, status: "SUCCEEDED", result: { moleculeId: 4711 }, progress: { ...state.progress, percent: 100 } };
  await page.clock.runFor(1600);
  await expect(progress).toHaveAttribute("value", "100");
  await page.clock.runFor(700);
  await expect(page).toHaveURL(/tab=models&molecules=4711$/);
  expect(posts).toBe(0);
});

test("registration monitor retries connection failures without resubmitting", async ({ page }) => {
  let unavailable = true;
  await page.route(routes.adhocJob, (route) => route.fulfill(unavailable ? { status: 503, json: {} } : { json: { status: "FAILED", error_message: "Invalid structure", progress: { stage: "REGISTER_MOLECULE", percent: 0, completed: 0, total: 1 } } }));
  await page.goto("/search?registrationJob=555");
  await expect(page.getByText(/Connection interrupted/)).toBeVisible();
  unavailable = false;
  await expect(page.getByText("Invalid structure", { exact: true })).toBeVisible();
  await expect(page.getByRole("progressbar", { name: "Registration completion" })).toHaveAttribute("value", "0");
  await page.getByRole("button", { name: "Clear search", exact: true }).click();
  await expect(page.getByRole("region", { name: "Registration progress" })).toHaveCount(0);
});
