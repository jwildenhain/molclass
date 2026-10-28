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
