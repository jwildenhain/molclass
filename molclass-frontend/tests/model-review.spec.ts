import { test, expect } from "@playwright/test";
import { routes, stubBackend, stubEndpoint, modelReview } from "./fixtures/backend";

/**
 * The human release gate. Approve/Reject controls always render, but stay
 * disabled unless the API reports a live approval bridge and the selected
 * build is genuinely eligible (AWAITING_APPROVAL, no existing decision).
 */

test.beforeEach(async ({ page }) => {
  await stubBackend(page);
});

test("lists definitions awaiting approval by default", async ({ page }) => {
  await page.goto("/model-review");

  await expect(page.getByRole("heading", { level: 1, name: "Model build review" })).toBeVisible();
  await expect(page.getByLabel("Definition state")).toHaveValue("AWAITING_APPROVAL");

  await expect(page.getByRole("button", { name: /Mitochondrial uncoupler/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /SMO on Kinase panel/ })).toBeVisible();
});

test("filters definitions by free text", async ({ page }) => {
  await page.goto("/model-review");
  // Wait for the fetched list: typing before hydration is discarded by the controlled input.
  await expect(page.getByRole("button", { name: /Mitochondrial uncoupler/ })).toBeVisible();

  await page.getByLabel("Search definitions").fill("kinase");

  await expect(page.getByRole("button", { name: /SMO on Kinase panel/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Mitochondrial uncoupler/ })).toHaveCount(0);
});

test("opens immutable build evidence and shows model statistics for a selected definition", async ({ page }) => {
  await page.goto("/model-review");

  await expect(page.getByRole("heading", { level: 2, name: "Choose a definition to inspect its evidence." })).toBeVisible();

  await page.getByRole("button", { name: /Mitochondrial uncoupler/ }).click();

  await expect(page.getByRole("heading", { level: 3, name: "Build 512" })).toBeVisible();
  await expect(page.getByText("not published")).toBeVisible();

  // Build provenance
  await expect(page.getByText("STRATIFIED_SCAFFOLD")).toBeVisible();
  await expect(page.getByText("1337")).toBeVisible();
  await expect(page.getByText("21.0.4 / 2.12 / 3.8.6")).toBeVisible();

  // Model statistics: one card per evaluation set, weighted metrics as percentages.
  await expect(page.getByRole("heading", { level: 3, name: "Model statistics" })).toBeVisible();
  const holdoutCard = page.getByRole("heading", { level: 4, name: "Holdout" }).locator("../..");
  await expect(holdoutCard).toContainText("n=575");
  await expect(holdoutCard).toContainText("87.3%"); // ACCURACY 0.8734
  await expect(holdoutCard).toContainText("0.733"); // KAPPA 0.7331, not a percentage

  // Per-fold cross-validation breakdown.
  const foldRow = page.getByRole("row").filter({ hasText: /^1/ });
  await expect(foldRow).toContainText("230");
  await expect(foldRow).toContainText("86.0%");

  // Artifact integrity
  await expect(page.getByRole("heading", { level: 3, name: "Artifact integrity" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 4, name: "MODEL" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 4, name: "HEADER" })).toBeVisible();
  await expect(page.getByText("5.00 MiB")).toBeVisible();
  await expect(page.getByText(/SHA-256 aa11bb22/)).toBeVisible();

  await expect(page.getByText("No approval record exists for the latest build.")).toBeVisible();
});

test("disables the decision controls when the approval bridge is unavailable", async ({ page }) => {
  await page.goto("/model-review");
  await page.getByRole("button", { name: /Mitochondrial uncoupler/ }).click();

  await expect(page.getByText(/Approval is disabled on this API instance/)).toBeVisible();

  const approve = page.getByRole("button", { name: "Approve & publish" });
  const reject = page.getByRole("button", { name: "Reject build" });
  await expect(approve).toBeVisible();
  await expect(approve).toBeDisabled();
  await expect(reject).toBeDisabled();
  await expect(page.getByLabel("Reviewer identity")).toBeDisabled();
});

test("submits an approval decision through the guarded bridge when eligible", async ({ page }) => {
  await stubEndpoint(page, routes.modelReviewDetail, { ...modelReview, approvalMutationAvailable: true });

  let decisionRequestBody: Record<string, unknown> | null = null;
  await page.route(/\/api\/v1\/model-builds\/512\/decision$/, async (route) => {
    decisionRequestBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ modelBuildId: 512, decision: "APPROVE", buildStatus: "PUBLISHED" }),
    });
  });
  page.on("dialog", (dialog) => dialog.accept());

  await page.goto("/model-review");
  await page.getByRole("button", { name: /Mitochondrial uncoupler/ }).click();

  const approve = page.getByRole("button", { name: "Approve & publish" });
  await expect(approve).toBeEnabled();

  await page.getByLabel("Reviewer identity").fill("release-reviewer");
  await page.getByLabel("Review token").fill("secret-token");
  await page.getByLabel("Decision note").fill("Reviewed AUC, F1, and holdout evidence.");
  await approve.click();

  await expect(page.getByText("Build 512 is now PUBLISHED. Reloading review data.")).toBeVisible();
  expect(decisionRequestBody).toMatchObject({
    decision: "APPROVE",
    reviewer: "release-reviewer",
    note: "Reviewed AUC, F1, and holdout evidence.",
  });
});

test("shows a review registry outage as an error", async ({ page }) => {
  await stubEndpoint(page, routes.modelReviews, { detail: "unavailable" }, 503);
  await page.goto("/model-review");

  await expect(page.getByText("Model review registry failed (503).")).toBeVisible();
});
