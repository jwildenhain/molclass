"use client";

import { useEffect, useMemo, useState } from "react";
import { ClipboardList, Gauge, Layers, ShieldCheck } from "lucide-react";

type ReviewListItem = {
  modelDefinitionId: number;
  modelName: string | null;
  status: string;
  dataset: { datasetId: number; name: string };
  targetProperty: string;
  featureProfile: string;
  algorithm: string;
  featureSelection: string;
  positiveClassLabel: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  latestBuild: {
    modelBuildId: number;
    status: string;
    runstep: string;
    generationNumber: number;
    finishedAt: string | null;
    publishedAt: string | null;
  } | null;
  approval: {
    status: string;
    approvedBy: string;
    approvedAt: string;
  } | null;
};

type Build = {
  modelBuildId: number;
  generationLabel: string;
  generationNumber: number;
  status: string;
  runstep: string;
  versions: {
    java: string;
    cdk: string;
    weka: string;
    codeRevision: string;
    databaseSchema: string;
  };
  randomSeed: number;
  splitStrategy: string;
  splitConfiguration: unknown;
  counts: {
    training: number;
    validation: number;
    holdout: number;
    excluded: number;
  };
  manifest: unknown;
  manifestSha256: string | null;
  error: { code: string | null; message: string | null } | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  publishedAt: string | null;
};

type Evaluation = {
  evaluationSet: string;
  foldNumber: number | null;
  classLabel: string | null;
  metricCode: string;
  metricValue: number | null;
  supportCount: number | null;
  details: unknown;
  createdAt: string;
};

type Artifact = {
  modelArtifactId: number;
  kind: string;
  format: string;
  mediaType: string;
  size: number;
  sha256: string;
  createdAt: string;
};

type ModelReview = {
  definition: {
    modelDefinitionId: number;
    legacyModelId: number | null;
    modelName: string | null;
    status: string;
    dataset: {
      datasetId: number;
      name: string;
      status: string;
      modelEligible: boolean;
    };
    targetProperty: { propertyId: number; name: string };
    featureProfile: {
      featureProfileId: number;
      code: string;
      version: string;
      status: string;
    };
    algorithm: { code: string; options: unknown };
    featureSelection: { code: string; options: unknown };
    positiveClassLabel: string | null;
    declaredClassLabels: string[];
    publishedModelBuildId: number | null;
    createdBy: string;
    metadata: unknown;
    createdAt: string;
    updatedAt: string;
  };
  builds: Build[];
  latestBuild: Build | null;
  evaluations: Evaluation[];
  artifacts: Artifact[];
  approval: {
    status: string;
    approvedBy: string;
    note: string | null;
    approvedAt: string;
  } | null;
  approvalMutationAvailable: boolean;
};

// model_definition.status only ever takes these four values (see
// molclass.models.V3ModelRebuilder / V3ModelSupersession). There is no
// "APPROVED" or "PUBLISHED" definition status — those are model_build and
// model_approval statuses. A published definition's status is ACTIVE, with
// publishedModelBuildId set.
const FILTERS = [
  "ALL",
  "AWAITING_APPROVAL",
  "PENDING_REBUILD",
  "REBUILD_FAILED",
  "ACTIVE",
];

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: "published",
};

function statusTone(status: string) {
  if (["ACTIVE", "APPROVED", "PUBLISHED", "COMPLETE"].includes(status)) {
    return "bg-emerald-500/10 text-emerald-700 dark:text-emerald-300";
  }
  if (["REBUILD_FAILED", "FAILED", "INTERRUPTED"].includes(status)) {
    return "bg-red-500/10 text-red-700 dark:text-red-300";
  }
  if (["AWAITING_APPROVAL", "RUNNING"].includes(status)) {
    return "bg-amber-500/10 text-amber-700 dark:text-amber-300";
  }
  return "bg-muted text-muted-foreground";
}

function readable(value: string) {
  return STATUS_LABELS[value] || value.replaceAll("_", " ").toLowerCase();
}

function formatDate(value: string | null | undefined) {
  if (!value) return "Not recorded";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString();
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MiB`;
}

const METRIC_LABELS: Record<string, string> = {
  ACCURACY: "Accuracy",
  WEIGHTED_PRECISION: "Precision",
  WEIGHTED_RECALL: "Recall",
  WEIGHTED_F1: "F1",
  WEIGHTED_AUC: "AUC",
  KAPPA: "Kappa",
};
const METRIC_ORDER = ["ACCURACY", "WEIGHTED_PRECISION", "WEIGHTED_RECALL", "WEIGHTED_F1", "WEIGHTED_AUC", "KAPPA"];
const BAR_METRICS = new Set(["ACCURACY", "WEIGHTED_PRECISION", "WEIGHTED_RECALL", "WEIGHTED_F1", "WEIGHTED_AUC"]);
const SET_LABELS: Record<string, string> = {
  CROSS_VALIDATION: "Cross-validation",
  HOLDOUT: "Holdout",
  VALIDATION: "Validation",
  TRAIN: "Train",
};
const SET_ORDER = ["CROSS_VALIDATION", "HOLDOUT", "VALIDATION", "TRAIN"];

function formatMetric(code: string, value: number | null) {
  if (value === null || !Number.isFinite(value)) return "n/a";
  if (BAR_METRICS.has(code)) return `${(value * 100).toFixed(1)}%`;
  return value.toFixed(3);
}

function barWidth(value: number | null) {
  if (value === null || !Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, value * 100));
}

type SetSummary = {
  key: string;
  evaluationSet: string;
  classLabel: string | null;
  support: number | null;
  metrics: Map<string, number | null>;
};

function buildSetSummaries(evaluations: Evaluation[]): SetSummary[] {
  const groups = new Map<string, SetSummary>();
  for (const row of evaluations) {
    if (row.foldNumber !== null) continue;
    const key = `${row.evaluationSet}::${row.classLabel ?? ""}`;
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        evaluationSet: row.evaluationSet,
        classLabel: row.classLabel,
        support: row.supportCount,
        metrics: new Map(),
      };
      groups.set(key, group);
    }
    group.metrics.set(row.metricCode, row.metricValue);
  }
  return Array.from(groups.values()).sort((a, b) => {
    const orderA = SET_ORDER.indexOf(a.evaluationSet);
    const orderB = SET_ORDER.indexOf(b.evaluationSet);
    return (orderA === -1 ? 99 : orderA) - (orderB === -1 ? 99 : orderB);
  });
}

type FoldRow = { fold: number; support: number | null; metrics: Map<string, number | null> };

function buildFoldRows(evaluations: Evaluation[]): FoldRow[] {
  const byFold = new Map<number, FoldRow>();
  for (const row of evaluations) {
    if (row.evaluationSet !== "CROSS_VALIDATION" || row.foldNumber === null) continue;
    let fold = byFold.get(row.foldNumber);
    if (!fold) {
      fold = { fold: row.foldNumber, support: row.supportCount, metrics: new Map() };
      byFold.set(row.foldNumber, fold);
    }
    fold.metrics.set(row.metricCode, row.metricValue);
  }
  return Array.from(byFold.values()).sort((a, b) => a.fold - b.fold);
}

export default function ModelReviewPage() {
  const [items, setItems] = useState<ReviewListItem[]>([]);
  const [statusFilter, setStatusFilter] = useState("AWAITING_APPROVAL");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [review, setReview] = useState<ModelReview | null>(null);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingReview, setLoadingReview] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [reviewer, setReviewer] = useState("");
  const [reviewToken, setReviewToken] = useState("");
  const [decisionNote, setDecisionNote] = useState("");
  const [decisionPending, setDecisionPending] = useState(false);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [decisionNotice, setDecisionNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      setLoadingList(true);
      setError(null);
      try {
        const response = await fetch("/api/v1/model-reviews?limit=250", {
          cache: "no-store",
          headers: { Accept: "application/json" },
        });
        if (!response.ok) {
          throw new Error(`Model review registry failed (${response.status}).`);
        }
        const payload = (await response.json()) as { items?: ReviewListItem[] };
        if (!cancelled) setItems(Array.isArray(payload.items) ? payload.items : []);
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : "Model reviews could not be loaded.");
        }
      } finally {
        if (!cancelled) setLoadingList(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  const filteredItems = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    return items.filter((item) => {
      if (statusFilter !== "ALL" && item.status !== statusFilter) return false;
      if (!normalizedQuery) return true;
      return [
        item.modelDefinitionId.toString(),
        item.modelName || "",
        item.dataset.name,
        item.targetProperty,
        item.algorithm,
        item.featureProfile,
      ].some((value) => value.toLowerCase().includes(normalizedQuery));
    });
  }, [items, query, statusFilter]);

  const awaitingCount = useMemo(
    () => items.filter((item) => item.status === "AWAITING_APPROVAL").length,
    [items],
  );

  async function selectReview(modelDefinitionId: number) {
    setSelectedId(modelDefinitionId);
    setReview(null);
    setLoadingReview(true);
    setError(null);
    setDecisionError(null);
    setDecisionNotice(null);
    setDecisionNote("");
    setReviewToken("");
    try {
      const response = await fetch(
        `/api/v1/model-definitions/${modelDefinitionId}/review`,
        { cache: "no-store", headers: { Accept: "application/json" } },
      );
      if (!response.ok) {
        throw new Error(`Model review failed (${response.status}).`);
      }
      setReview((await response.json()) as ModelReview);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "Model review could not be loaded.");
    } finally {
      setLoadingReview(false);
    }
  }

  const setSummaries = useMemo(() => buildSetSummaries(review?.evaluations ?? []), [review]);
  const foldRows = useMemo(() => buildFoldRows(review?.evaluations ?? []), [review]);

  const decisionBuildId = review?.latestBuild?.modelBuildId ?? 0;
  const canDecide = Boolean(
    review?.approvalMutationAvailable &&
      review.latestBuild?.status === "AWAITING_APPROVAL" &&
      decisionBuildId > 0 &&
      !review.approval,
  );

  async function submitDecision(decision: "APPROVE" | "REJECT") {
    setDecisionError(null);
    setDecisionNotice(null);

    if (!canDecide) {
      setDecisionError("Only the latest build awaiting approval can receive a decision.");
      return;
    }
    if (!reviewer.trim() || !reviewToken || !decisionNote.trim()) {
      setDecisionError("Reviewer, review token, and decision note are required.");
      return;
    }

    const action =
      decision === "APPROVE"
        ? "approve and publish build " + decisionBuildId
        : "reject build " + decisionBuildId;
    if (
      !window.confirm(
        "Confirm that you want to " +
          action +
          ". This human decision is immutable and cannot be edited later.",
      )
    ) {
      return;
    }

    setDecisionPending(true);
    try {
      const response = await fetch(
        "/api/v1/model-builds/" + decisionBuildId + "/decision",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-MolClass-Review-Token": reviewToken,
          },
          body: JSON.stringify({
            decision,
            reviewer: reviewer.trim(),
            note: decisionNote.trim(),
          }),
        },
      );
      const payload = (await response.json().catch(() => ({}))) as {
        detail?: string;
        buildStatus?: string;
      };
      if (!response.ok) {
        throw new Error(payload.detail || "The model decision failed.");
      }

      setReviewToken("");
      setDecisionNotice(
        "Build " +
          decisionBuildId +
          " is now " +
          (payload.buildStatus || (decision === "APPROVE" ? "PUBLISHED" : "REJECTED")) +
          ". Reloading review data.",
      );
      window.setTimeout(() => window.location.reload(), 600);
    } catch (decisionFailure) {
      setDecisionError(
        decisionFailure instanceof Error
          ? decisionFailure.message
          : "The model decision failed.",
      );
    } finally {
      setDecisionPending(false);
    }
  }

  return (
    <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:py-14">
      <header className="mb-8">
        <div className="flex flex-col justify-between gap-5 lg:flex-row lg:items-end">
          <div>
            <p className="font-mono text-xs uppercase tracking-[0.28em] text-blue-500">Human release gate</p>
            <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Model build review</h1>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
              Inspect immutable build evidence, record a human decision, and publish only through the canonical
              transactional approval service.
            </p>
          </div>
          <div className="inline-flex items-center gap-2 self-start rounded-full border border-blue-500/30 bg-blue-500/10 px-4 py-2 text-sm text-blue-600 dark:text-blue-300">
            <ShieldCheck className="h-4 w-4" />
            Java transaction remains authoritative
          </div>
        </div>
      </header>

      <section className="mb-6 grid gap-3 sm:grid-cols-3">
        <StatTile label="Definitions" value={loadingList ? "--" : items.length.toLocaleString()} />
        <StatTile label="Awaiting approval" value={loadingList ? "--" : awaitingCount.toLocaleString()} tone={awaitingCount ? "amber" : "neutral"} />
        <StatTile
          label="Approval bridge"
          value={review ? (review.approvalMutationAvailable ? "Enabled" : "Disabled") : "Select a build"}
          tone={review ? (review.approvalMutationAvailable ? "emerald" : "amber") : "neutral"}
        />
      </section>

      {error && (
        <div role="alert" className="mb-6 rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-sm text-red-700 dark:text-red-200">
          {error}
        </div>
      )}

      <section className="grid gap-6 xl:grid-cols-[380px_1fr]">
        <aside className="rounded-2xl border border-border bg-card/60 shadow-2xl backdrop-blur-xl">
          <div className="grid gap-3 border-b border-border p-5">
            <label className="text-sm font-semibold text-foreground">
              Search definitions
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="ID, model, dataset, algorithm"
                className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal text-foreground outline-none transition focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
              />
            </label>
            <label className="text-sm font-semibold text-foreground">
              Definition state
              <select
                value={statusFilter}
                onChange={(event) => setStatusFilter(event.target.value)}
                className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal text-foreground outline-none transition focus:border-blue-400"
              >
                {FILTERS.map((filter) => (
                  <option key={filter} value={filter}>{readable(filter)}</option>
                ))}
              </select>
            </label>
          </div>

          <div className="flex items-center justify-between px-5 pt-4">
            <h2 className="text-sm font-bold uppercase tracking-wider text-foreground">Definitions</h2>
            <span className="text-xs text-muted-foreground">{loadingList ? "loading" : filteredItems.length}</span>
          </div>

          <div className="max-h-[640px] space-y-2 overflow-y-auto p-5">
            {!loadingList && filteredItems.length === 0 && (
              <p className="rounded-xl border border-dashed border-border px-4 py-10 text-center text-sm text-muted-foreground">
                No definitions match this filter.
              </p>
            )}
            {filteredItems.map((item) => (
              <button
                key={item.modelDefinitionId}
                type="button"
                onClick={() => void selectReview(item.modelDefinitionId)}
                className={`w-full rounded-xl border p-4 text-left transition-colors ${
                  selectedId === item.modelDefinitionId
                    ? "border-emerald-500 bg-emerald-500/10"
                    : "border-border hover:border-blue-500/60 hover:bg-muted/30"
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <p className="text-xs font-semibold uppercase tracking-wider text-blue-500">
                    Definition {item.modelDefinitionId}
                  </p>
                  <span className={`rounded-full px-2.5 py-0.5 text-[10px] font-semibold uppercase ${statusTone(item.status)}`}>
                    {readable(item.status)}
                  </span>
                </div>
                <h3 className="mt-2 line-clamp-2 text-base font-semibold text-foreground">
                  {item.modelName || `${item.algorithm} on ${item.dataset.name}`}
                </h3>
                <p className="mt-1 line-clamp-1 font-mono text-xs text-muted-foreground">{item.dataset.name}</p>
                <div className="mt-3 flex flex-wrap gap-1.5 text-[10px] uppercase text-muted-foreground">
                  <span className="rounded-full bg-blue-500/10 px-2 py-1 text-blue-600 dark:text-blue-300">{item.algorithm}</span>
                  <span className="rounded-full bg-muted px-2 py-1">{item.featureProfile}</span>
                  {item.latestBuild && <span className="rounded-full bg-muted px-2 py-1">build {item.latestBuild.modelBuildId}</span>}
                </div>
              </button>
            ))}
          </div>
        </aside>

        <section className="min-w-0 rounded-2xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          {!selectedId && !loadingReview && (
            <div className="grid min-h-[560px] place-items-center rounded-xl border border-dashed border-border p-8 text-center">
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-blue-500">No build selected</p>
                <h2 className="mt-3 text-2xl font-bold text-foreground">Choose a definition to inspect its evidence.</h2>
              </div>
            </div>
          )}

          {loadingReview && (
            <div className="grid min-h-[560px] place-items-center text-sm uppercase tracking-wider text-muted-foreground">
              Loading immutable build record...
            </div>
          )}

          {review && !loadingReview && (
            <div>
              <div className="flex flex-col justify-between gap-5 border-b border-border pb-6 lg:flex-row lg:items-start">
                <div className="min-w-0">
                  <p className="text-xs font-semibold uppercase tracking-wider text-blue-500">
                    Definition {review.definition.modelDefinitionId}
                  </p>
                  <h2 className="mt-2 break-words text-2xl font-bold text-foreground sm:text-3xl">
                    {review.definition.modelName || `${review.definition.algorithm.code} model`}
                  </h2>
                  <p className="mt-2 break-words font-mono text-xs text-muted-foreground">{review.definition.dataset.name}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <span className={`rounded-full px-3 py-1 text-xs font-semibold uppercase ${statusTone(review.definition.status)}`}>
                    {readable(review.definition.status)}
                  </span>
                  <span className={`rounded-full px-3 py-1 text-xs font-semibold uppercase ${review.definition.publishedModelBuildId ? statusTone("PUBLISHED") : statusTone("PENDING")}`}>
                    {review.definition.publishedModelBuildId ? `published build ${review.definition.publishedModelBuildId}` : "not published"}
                  </span>
                </div>
              </div>

              <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <DataPoint label="Algorithm" value={review.definition.algorithm.code} />
                <DataPoint label="Feature profile" value={`${review.definition.featureProfile.code} ${review.definition.featureProfile.version}`} />
                <DataPoint label="Feature selection" value={review.definition.featureSelection.code} />
                <DataPoint label="Target" value={review.definition.targetProperty.name} />
                <DataPoint label="Positive class" value={review.definition.positiveClassLabel || "Not declared"} />
                <DataPoint label="Class labels" value={review.definition.declaredClassLabels.join(", ") || "Not declared"} />
                <DataPoint label="Created by" value={review.definition.createdBy} />
                <DataPoint label="Dataset eligible" value={review.definition.dataset.modelEligible ? "Yes" : "No"} />
              </div>

              {review.latestBuild ? (
                <>
                  <section className="mt-8">
                    <div className="flex flex-wrap items-end justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <Layers className="h-5 w-5 text-blue-500" />
                        <div>
                          <p className="text-xs font-semibold uppercase tracking-wider text-blue-500">Latest generation</p>
                          <h3 className="mt-0.5 text-xl font-bold text-foreground">Build {review.latestBuild.modelBuildId}</h3>
                        </div>
                      </div>
                      <span className={`rounded-full px-3 py-1 text-xs font-semibold uppercase ${statusTone(review.latestBuild.status)}`}>
                        {readable(review.latestBuild.status)}
                      </span>
                    </div>

                    <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      {Object.entries(review.latestBuild.counts).map(([label, value]) => (
                        <div key={label} className="rounded-xl border border-border bg-background/50 px-4 py-3">
                          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</p>
                          <p className="mt-1 font-mono text-2xl font-bold text-foreground">{value.toLocaleString()}</p>
                        </div>
                      ))}
                    </div>

                    <div className="mt-4 grid gap-4 rounded-xl border border-border bg-background/50 p-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
                      <DataPoint label="Split strategy" value={review.latestBuild.splitStrategy} mono />
                      <DataPoint label="Random seed" value={String(review.latestBuild.randomSeed)} mono />
                      <DataPoint label="Finished" value={formatDate(review.latestBuild.finishedAt)} />
                      <DataPoint label="Java / CDK / Weka" value={`${review.latestBuild.versions.java} / ${review.latestBuild.versions.cdk} / ${review.latestBuild.versions.weka}`} mono />
                      <DataPoint label="Schema version" value={review.latestBuild.versions.databaseSchema} mono />
                      <DataPoint label="Code revision" value={review.latestBuild.versions.codeRevision} mono />
                    </div>
                  </section>

                  <section className="mt-8">
                    <div className="flex items-center gap-2">
                      <Gauge className="h-5 w-5 text-blue-500" />
                      <h3 className="text-xl font-bold text-foreground">Model statistics</h3>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">
                      Weighted metrics per evaluation partition. Cross-validation shows the aggregate across all folds.
                    </p>

                    {setSummaries.length === 0 ? (
                      <p className="mt-4 rounded-xl border border-dashed border-border px-4 py-10 text-center text-sm text-muted-foreground">
                        No evaluation evidence recorded for this build.
                      </p>
                    ) : (
                      <div className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                        {setSummaries.map((summary) => (
                          <div key={summary.key} className="rounded-2xl border border-border bg-background/50 p-5 shadow-sm">
                            <div className="flex items-center justify-between gap-2">
                              <h4 className="text-sm font-bold uppercase tracking-wider text-foreground">
                                {SET_LABELS[summary.evaluationSet] || readable(summary.evaluationSet)}
                              </h4>
                              <span className="text-xs text-muted-foreground">n={summary.support ?? "n/a"}</span>
                            </div>
                            {summary.classLabel && (
                              <p className="mt-1 text-xs text-muted-foreground">{summary.classLabel}</p>
                            )}
                            <div className="mt-4 space-y-3">
                              {METRIC_ORDER.filter((code) => summary.metrics.has(code)).map((code) => {
                                const value = summary.metrics.get(code) ?? null;
                                return (
                                  <div key={code}>
                                    <div className="flex items-center justify-between text-xs">
                                      <span className="text-muted-foreground">{METRIC_LABELS[code] || code}</span>
                                      <span className="font-mono font-semibold text-foreground">{formatMetric(code, value)}</span>
                                    </div>
                                    {BAR_METRICS.has(code) && (
                                      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
                                        <div className="h-full bg-blue-500" style={{ width: `${barWidth(value)}%` }} />
                                      </div>
                                    )}
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {foldRows.length > 0 && (
                      <div className="mt-5 overflow-x-auto rounded-xl border border-border">
                        <table className="w-full min-w-[640px] border-collapse text-left text-sm">
                          <thead>
                            <tr className="border-b border-border/50 bg-muted/50">
                              <th className="p-3 font-semibold text-muted-foreground">Fold</th>
                              <th className="p-3 text-right font-semibold text-muted-foreground">Support</th>
                              {METRIC_ORDER.map((code) => (
                                <th key={code} className="p-3 text-right font-semibold text-muted-foreground">{METRIC_LABELS[code] || code}</th>
                              ))}
                            </tr>
                          </thead>
                          <tbody>
                            {foldRows.map((row) => (
                              <tr key={row.fold} className="border-b border-border/50 last:border-0 hover:bg-muted/30">
                                <td className="p-3 font-mono text-xs text-foreground">{row.fold}</td>
                                <td className="p-3 text-right font-mono text-xs text-foreground">{row.support ?? "n/a"}</td>
                                {METRIC_ORDER.map((code) => (
                                  <td key={code} className="p-3 text-right font-mono text-xs text-foreground">
                                    {formatMetric(code, row.metrics.get(code) ?? null)}
                                  </td>
                                ))}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </section>

                  <section className="mt-8">
                    <div className="flex items-center gap-2">
                      <ClipboardList className="h-5 w-5 text-blue-500" />
                      <h3 className="text-xl font-bold text-foreground">Artifact integrity</h3>
                    </div>
                    <div className="mt-3 grid gap-3 lg:grid-cols-2">
                      {review.artifacts.map((artifact) => (
                        <article key={artifact.modelArtifactId} className="min-w-0 rounded-xl border border-border bg-background/50 p-4">
                          <div className="flex items-start justify-between gap-3">
                            <h4 className="text-xs font-semibold uppercase tracking-wider text-blue-500">{artifact.kind}</h4>
                            <span className="text-xs text-muted-foreground">{formatBytes(artifact.size)}</span>
                          </div>
                          <p className="mt-2 text-xs text-muted-foreground">{artifact.format} · {artifact.mediaType}</p>
                          <p className="mt-3 break-all rounded-lg bg-muted p-2 font-mono text-[10px] leading-5 text-muted-foreground">SHA-256 {artifact.sha256}</p>
                        </article>
                      ))}
                    </div>
                    <p className="mt-3 break-all font-mono text-[10px] leading-5 text-muted-foreground">
                      Manifest SHA-256: {review.latestBuild.manifestSha256 || "Not recorded"}
                    </p>
                  </section>
                </>
              ) : (
                <section className="mt-8 rounded-xl border border-dashed border-border p-10 text-center">
                  <h3 className="text-xl font-bold text-foreground">No build attempt recorded</h3>
                  <p className="mt-2 text-sm text-muted-foreground">This definition is still waiting for the rebuild worker.</p>
                </section>
              )}

              <section className="mt-8 rounded-2xl border border-border bg-gradient-to-br from-blue-500/10 via-card/70 to-emerald-500/10 p-6 shadow-lg">
                <div className="flex flex-col justify-between gap-2 sm:flex-row sm:items-end">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wider text-blue-500">
                      Human release decision
                    </p>
                    <h3 className="mt-1 text-xl font-bold text-foreground">Approve or reject this build</h3>
                  </div>
                  <span className="text-xs text-muted-foreground">
                    {review.latestBuild ? "Build " + review.latestBuild.modelBuildId : "No eligible build"}
                  </span>
                </div>

                {!review.approvalMutationAvailable && (
                  <p className="mt-4 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-700 dark:text-amber-200">
                    Approval is disabled on this API instance. A release administrator must configure the guarded bridge.
                  </p>
                )}

                <div className="mt-5 grid gap-4">
                  <label className="text-sm font-semibold text-foreground">
                    Reviewer identity
                    <input
                      type="text"
                      autoComplete="username"
                      value={reviewer}
                      onChange={(event) => setReviewer(event.target.value)}
                      disabled={decisionPending || !canDecide}
                      placeholder="release-reviewer"
                      className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal text-foreground outline-none transition focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20 disabled:cursor-not-allowed disabled:opacity-50"
                    />
                  </label>
                  <label className="text-sm font-semibold text-foreground">
                    Review token
                    <input
                      type="password"
                      autoComplete="current-password"
                      value={reviewToken}
                      onChange={(event) => setReviewToken(event.target.value)}
                      disabled={decisionPending || !canDecide}
                      className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal text-foreground outline-none transition focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20 disabled:cursor-not-allowed disabled:opacity-50"
                    />
                  </label>
                  <label className="text-sm font-semibold text-foreground">
                    Decision note
                    <textarea
                      rows={4}
                      value={decisionNote}
                      onChange={(event) => setDecisionNote(event.target.value)}
                      disabled={decisionPending || !canDecide}
                      placeholder="Record the evidence and rationale reviewed for this decision."
                      className="mt-2 w-full resize-y rounded-xl border border-border bg-background/70 px-4 py-3 font-normal text-foreground outline-none transition focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20 disabled:cursor-not-allowed disabled:opacity-50"
                    />
                  </label>

                  {decisionError && (
                    <p role="alert" className="rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-700 dark:text-red-200">
                      {decisionError}
                    </p>
                  )}
                  {decisionNotice && (
                    <p role="status" className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-700 dark:text-emerald-200">
                      {decisionNotice}
                    </p>
                  )}

                  <div className="flex flex-wrap gap-3">
                    <button
                      type="button"
                      onClick={() => submitDecision("APPROVE")}
                      disabled={decisionPending || !canDecide}
                      className="rounded-xl bg-emerald-600 px-5 py-3 font-semibold text-white transition hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {decisionPending ? "Recording decision..." : "Approve & publish"}
                    </button>
                    <button
                      type="button"
                      onClick={() => submitDecision("REJECT")}
                      disabled={decisionPending || !canDecide}
                      className="rounded-xl border border-red-500/40 px-5 py-3 font-semibold text-red-600 transition hover:bg-red-500/10 disabled:cursor-not-allowed disabled:opacity-40 dark:text-red-300"
                    >
                      Reject build
                    </button>
                  </div>

                  {!canDecide && review.approvalMutationAvailable && (
                    <p className="text-sm text-muted-foreground">
                      Controls are available only for the latest build in AWAITING_APPROVAL with no recorded decision.
                    </p>
                  )}
                </div>
              </section>

              <section className="mt-6 rounded-xl border border-amber-500/30 bg-amber-500/10 px-5 py-4 text-sm text-amber-800 dark:text-amber-200">
                <p className="font-semibold">
                  {review.approval ? `Approval record: ${review.approval.status}` : "No approval record exists for the latest build."}
                </p>
                <p className="mt-1 opacity-80">
                  {review.approval
                    ? `${review.approval.approvedBy} · ${formatDate(review.approval.approvedAt)}`
                    : "Use the guarded decision controls above when this build is eligible."}
                </p>
              </section>
            </div>
          )}
        </section>
      </section>
    </main>
  );
}

function StatTile({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "emerald" | "amber" }) {
  const color = tone === "emerald" ? "text-emerald-600 dark:text-emerald-300" : tone === "amber" ? "text-amber-600 dark:text-amber-300" : "text-foreground";
  return (
    <div className="rounded-2xl border border-border bg-card/60 p-5 shadow-lg">
      <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className={`mt-2 font-mono text-3xl font-bold ${color}`}>{value}</p>
    </div>
  );
}

function DataPoint({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className={`mt-1 break-words text-sm text-foreground ${mono ? "font-mono text-xs" : ""}`}>{value}</p>
    </div>
  );
}
