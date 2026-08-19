import type { Page, Route } from "@playwright/test";

/**
 * Deterministic stand-ins for the two backends the browser talks to:
 * the FastAPI service under /api/v1 and the Spring predictor under /api/v3.
 * Stubbing at the network layer keeps the suite runnable in CI, where neither
 * backend nor the v3 database exists.
 */

export const publishedModels = [
  {
    modelDefinitionId: 39,
    legacyModelId: 21,
    name: "Mitochondrial uncoupler",
    algorithm: "RandomForest",
    featureProfile: "CDK_2_12_BASE",
    modelBuildId: 512,
    trainingCount: 1840,
    validationCount: 460,
    holdoutCount: 575,
    excludedCount: 12,
    publishedAt: "2026-08-12T09:15:00Z",
    holdoutAccuracy: 0.8734,
  },
  {
    modelDefinitionId: 98,
    legacyModelId: null,
    name: null,
    algorithm: "KNN",
    featureProfile: "CDK_2_12_SCAFFOLD",
    modelBuildId: 611,
    trainingCount: 320,
    validationCount: 80,
    holdoutCount: 100,
    excludedCount: 0,
    publishedAt: "2026-08-14T11:02:00Z",
    holdoutAccuracy: null,
  },
];

export const molecules = [
  {
    moleculeId: 4711,
    inchiKey: "RYYVLZVUVIJVGH-UHFFFAOYSA-N",
    canonicalSmiles: "Cn1c(=O)c2c(ncn2C)n(C)c1=O",
    name: "Caffeine",
    normalizationStatus: "NORMALIZED",
    sourceIdentifier: "ZINC000000000123",
  },
  {
    moleculeId: 4712,
    inchiKey: null,
    canonicalSmiles: "CC(=O)Oc1ccccc1C(=O)O",
    name: "Aspirin",
    normalizationStatus: "NORMALIZED",
    sourceIdentifier: "ZINC000000000456",
  },
];

export const prediction = {
  modelDefinitionId: 39,
  modelBuildId: 512,
  moleculeId: 4711,
  predictionJobId: 9001,
  predictedClass: "active",
  distribution: { active: 0.8123, inactive: 0.1877 },
  responseStrength: 0.8123,
  applicabilityScore: 0.71,
  inApplicabilityDomain: true,
  trainingScaffoldCount: 42,
};

export const moleculeDetail = {
  moleculeId: 4711,
  inchiKey: "RYYVLZVUVIJVGH-UHFFFAOYSA-N",
  canonicalSmiles: "Cn1c(=O)c2c(ncn2C)n(C)c1=O",
  name: "Caffeine",
  normalizationStatus: "NORMALIZED",
  datasetRegistrations: [
    { datasetId: 1, datasetName: "Uncoupler screen", sourceIdentifier: "ZINC000000000123" },
  ],
  murckoScaffoldSmiles: "c1ncc2[nH]cnc2n1",
};

export const moleculePredictions = [
  {
    predictionJobId: 9001,
    modelBuildId: 512,
    modelDefinitionId: 39,
    modelName: "Mitochondrial uncoupler",
    algorithm: "RandomForest",
    predictedClass: "active",
    distribution: { active: 0.8123, inactive: 0.1877 },
    confidenceScore: 0.8123,
    applicabilityScore: 0.71,
    inApplicabilityDomain: true,
    createdAt: "2026-08-17T10:00:00Z",
  },
];

// A minimal but valid depiction, standing in for the CDK-rendered SVG the real predictor
// returns. Content doesn't matter to the tests; only that <img> resolves instead of hitting
// the real network (there is no backend at all in this suite).
const STUB_STRUCTURE_SVG =
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 40 40'><circle cx='20' cy='20' r='10'/></svg>";

export const datasets = [
  {
    datasetId: 1,
    legacyBatchId: 1,
    uploadId: 7,
    name: "Uncoupler screen",
    originalFilename: "uncoupler.sdf",
    description: "Legacy batch migrated into the v3 model.",
    status: "READY",
    totalRecords: 2875,
    importedRecords: 2875,
    failedRecords: 0,
    notProcessedRecords: 0,
    partialAcknowledgementRequired: false,
    modelEligible: true,
    identifierProperty: "COMPOUND_ID",
    propertyCount: 14,
    modelDefinitionCount: 3,
    createdBy: "migration",
    createdAt: "2026-07-30T08:00:00Z",
    latestImport: { importRunId: 21, status: "COMPLETE", runstep: "FINALIZE" },
  },
  {
    datasetId: 2,
    legacyBatchId: null,
    uploadId: 9,
    name: "Kinase panel",
    originalFilename: "kinase_panel.sdf",
    description: null,
    status: "PARTIAL",
    totalRecords: 1200,
    importedRecords: 1150,
    failedRecords: 41,
    notProcessedRecords: 9,
    partialAcknowledgementRequired: true,
    modelEligible: false,
    identifierProperty: null,
    propertyCount: 8,
    modelDefinitionCount: 0,
    createdBy: "jwildenhain",
    createdAt: "2026-08-05T14:20:00Z",
    latestImport: { importRunId: 33, status: "PARTIAL", runstep: "IMPORT_RECORDS" },
  },
];

export const modelDatasets = [
  {
    datasetId: 1,
    name: "Uncoupler screen",
    originalFilename: "uncoupler.sdf",
    description: "Legacy batch migrated into the v3 model.",
    status: "READY",
    importedRecords: 2875,
    failedRecords: 0,
    notProcessedRecords: 0,
    partialAcknowledgementRequired: false,
    createdBy: "migration",
    createdAt: "2026-07-30T08:00:00Z",
    targets: [
      {
        propertyId: 88,
        name: "ACTIVITY",
        sqlType: "VARCHAR(32)",
        presentCount: 2875,
        blankCount: 0,
        distinctCount: 2,
      },
    ],
  },
];

export const modelReviewItems = [
  {
    modelDefinitionId: 39,
    modelName: "Mitochondrial uncoupler",
    status: "AWAITING_APPROVAL",
    dataset: { datasetId: 1, name: "Uncoupler screen" },
    targetProperty: "ACTIVITY",
    featureProfile: "CDK_2_12_BASE",
    algorithm: "RandomForest",
    featureSelection: "RELIEF_F",
    positiveClassLabel: "active",
    createdBy: "migration",
    createdAt: "2026-08-01T10:00:00Z",
    updatedAt: "2026-08-12T09:15:00Z",
    latestBuild: {
      modelBuildId: 512,
      status: "AWAITING_APPROVAL",
      runstep: "EVALUATE",
      generationNumber: 3,
      finishedAt: "2026-08-12T09:10:00Z",
      publishedAt: null,
    },
    approval: null,
  },
  {
    modelDefinitionId: 118,
    modelName: null,
    status: "AWAITING_APPROVAL",
    dataset: { datasetId: 2, name: "Kinase panel" },
    targetProperty: "IC50_CLASS",
    featureProfile: "CDK_2_12_SCAFFOLD",
    algorithm: "SMO",
    featureSelection: "CFS_SUBSET",
    positiveClassLabel: null,
    createdBy: "jwildenhain",
    createdAt: "2026-08-03T12:00:00Z",
    updatedAt: "2026-08-14T16:40:00Z",
    latestBuild: {
      modelBuildId: 640,
      status: "AWAITING_APPROVAL",
      runstep: "EVALUATE",
      generationNumber: 1,
      finishedAt: "2026-08-14T16:35:00Z",
      publishedAt: null,
    },
    approval: null,
  },
];

export const modelReview = {
  definition: {
    modelDefinitionId: 39,
    legacyModelId: 21,
    modelName: "Mitochondrial uncoupler",
    status: "AWAITING_APPROVAL",
    dataset: { datasetId: 1, name: "Uncoupler screen", status: "READY", modelEligible: true },
    targetProperty: { propertyId: 88, name: "ACTIVITY" },
    featureProfile: { featureProfileId: 4, code: "CDK_2_12_BASE", version: "1.0", status: "ACTIVE" },
    algorithm: { code: "RandomForest", options: { numTrees: 100 } },
    featureSelection: { code: "RELIEF_F", options: null },
    positiveClassLabel: "active",
    declaredClassLabels: ["active", "inactive"],
    publishedModelBuildId: null,
    createdBy: "migration",
    metadata: null,
    createdAt: "2026-08-01T10:00:00Z",
    updatedAt: "2026-08-12T09:15:00Z",
  },
  builds: [],
  latestBuild: {
    modelBuildId: 512,
    generationLabel: "gen-3",
    generationNumber: 3,
    status: "AWAITING_APPROVAL",
    runstep: "EVALUATE",
    versions: {
      java: "21.0.4",
      cdk: "2.12",
      weka: "3.8.6",
      codeRevision: "86505fa",
      databaseSchema: "V9",
    },
    randomSeed: 1337,
    splitStrategy: "STRATIFIED_SCAFFOLD",
    splitConfiguration: null,
    counts: { training: 1840, validation: 460, holdout: 575, excluded: 12 },
    manifest: null,
    manifestSha256: "3b1f0c9d5a2e4f6789abcdef0123456789abcdef0123456789abcdef01234567",
    error: null,
    createdAt: "2026-08-12T08:00:00Z",
    startedAt: "2026-08-12T08:05:00Z",
    finishedAt: "2026-08-12T09:10:00Z",
    publishedAt: null,
  },
  // Metric codes mirror V3ModelApproval.MANDATORY_METRIC_CODES: ACCURACY, KAPPA,
  // and the four WEIGHTED_* codes. The approval transaction always evaluates
  // weighted (not per-class) metrics, so classLabel stays null in practice.
  evaluations: [
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "ACCURACY", metricValue: 0.8734, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "KAPPA", metricValue: 0.7331, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "WEIGHTED_PRECISION", metricValue: 0.8650, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "WEIGHTED_RECALL", metricValue: 0.8734, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "WEIGHTED_F1", metricValue: 0.8012, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "HOLDOUT", foldNumber: null, classLabel: null, metricCode: "WEIGHTED_AUC", metricValue: 0.9123, supportCount: 575, details: null, createdAt: "2026-08-12T09:10:00Z" },
    { evaluationSet: "CROSS_VALIDATION", foldNumber: null, classLabel: null, metricCode: "ACCURACY", metricValue: 0.8501, supportCount: 2300, details: null, createdAt: "2026-08-12T09:05:00Z" },
    { evaluationSet: "CROSS_VALIDATION", foldNumber: 1, classLabel: null, metricCode: "ACCURACY", metricValue: 0.8600, supportCount: 230, details: null, createdAt: "2026-08-12T09:01:00Z" },
    { evaluationSet: "CROSS_VALIDATION", foldNumber: 2, classLabel: null, metricCode: "ACCURACY", metricValue: 0.8400, supportCount: 230, details: null, createdAt: "2026-08-12T09:02:00Z" },
  ],
  artifacts: [
    {
      modelArtifactId: 900,
      kind: "MODEL",
      format: "GZIP",
      mediaType: "application/octet-stream",
      size: 5_242_880,
      sha256: "aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233445566778899",
      createdAt: "2026-08-12T09:10:00Z",
    },
    {
      modelArtifactId: 901,
      kind: "HEADER",
      format: "GZIP",
      mediaType: "application/octet-stream",
      size: 4096,
      sha256: "99887766554433221100ffeeddccbbaa99887766554433221100ffeeddccbbaa",
      createdAt: "2026-08-12T09:10:00Z",
    },
  ],
  approval: null,
  approvalMutationAvailable: false as const,
};

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

export const routes = {
  publishedModels: /\/api\/v3\/models\?/,
  moleculeSearch: /\/api\/v3\/molecules\?/,
  moleculeStructure: /\/api\/v3\/molecules\/\d+\/structure\.svg$/,
  moleculeDetail: /\/api\/v3\/molecules\/\d+$/,
  moleculePredictions: /\/api\/v3\/molecules\/\d+\/predictions/,
  predict: /\/api\/v3\/models\/\d+\/molecules\/\d+\/predict$/,
  datasets: /\/api\/v1\/datasets\?/,
  modelDatasets: /\/api\/v1\/model-datasets(\?|$)/,
  modelReviews: /\/api\/v1\/model-reviews\?/,
  modelReviewDetail: /\/api\/v1\/model-definitions\/\d+\/review$/,
};

/** Serve every backend call this UI makes with a healthy, predictable payload. */
export async function stubBackend(page: Page) {
  await page.route(routes.publishedModels, (route) => json(route, publishedModels));
  await page.route(routes.moleculeSearch, (route) => json(route, molecules));
  await page.route(routes.moleculeStructure, (route) =>
    route.fulfill({ status: 200, contentType: "image/svg+xml", body: STUB_STRUCTURE_SVG }),
  );
  await page.route(routes.moleculePredictions, (route) => json(route, moleculePredictions));
  await page.route(routes.moleculeDetail, (route) => json(route, moleculeDetail));
  await page.route(routes.predict, (route) => json(route, prediction));
  await page.route(routes.datasets, (route) => json(route, { total: datasets.length, datasets }));
  await page.route(routes.modelDatasets, (route) => json(route, { datasets: modelDatasets }));
  await page.route(routes.modelReviews, (route) => json(route, { items: modelReviewItems }));
  await page.route(routes.modelReviewDetail, (route) => json(route, modelReview));
}

/** Replace one stubbed endpoint so error and empty states can be exercised. */
export async function stubEndpoint(page: Page, pattern: RegExp, body: unknown, status = 200) {
  await page.route(pattern, (route) => json(route, body, status));
}
