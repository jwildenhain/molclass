import { test, expect } from "./fixtures/ui";
import { stubBackend, stubEndpoint } from "./fixtures/backend";

test("updates generated model names and preserves an operator's custom name", async ({ page }) => {
  await stubBackend(page);
  await stubEndpoint(page, /\/api\/v1\/model-datasets\/1$/, {
    datasetId: 1, name: "Screen", status: "SUCCEEDED", importedRecords: 20,
    partialAcknowledgementRequired: false,
    targets: [{ propertyId: 1, name: "Activity", distinctCount: 2,
      classLabels: [{ label: "active", supportCount: 10 }, { label: "inactive", supportCount: 10 }] }],
  });
  await stubEndpoint(page, /\/api\/v1\/model-options$/, {
    algorithms: [{ code: "RandomForest", name: "Random forest" }, { code: "SMO", name: "SMO" }],
    featureSelections: [{ code: "CfsSubsetEval", name: "CFS" }],
    featureProfiles: [{ featureProfileId: 1, code: "CDK", version: "1", status: "READY" }],
  });
  await page.goto("/model-creation/configure?dataset_id=1");
  await expect(page.getByLabel("Model name")).toHaveValue("Screen - Activity - RandomForest - CDK - CfsSubsetEval");
  await page.getByLabel("Weka algorithm").selectOption("SMO");
  await expect(page.getByLabel("Model name")).toHaveValue("Screen - Activity - SMO - CDK - CfsSubsetEval");
  await page.getByLabel("Model name").fill("My reviewed model");
  await page.getByLabel("Weka algorithm").selectOption("RandomForest");
  await expect(page.getByLabel("Model name")).toHaveValue("My reviewed model");
});
