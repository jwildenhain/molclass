"use client";

import { FormEvent, MouseEvent, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Activity, Check, Database, Pencil, RefreshCw, Search, X } from "lucide-react";
import { MoleculeStructure } from "@/components/MoleculeStructure";
import { fetchWithTimeout } from "@/lib/fetchWithTimeout";
import { nextSort, SortableHeader, type SortDirection } from "@/components/SortableHeader";

function compareNullableNumber(a: number | null, b: number | null, dir: 1 | -1) {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return (a - b) * dir;
}

function ModelNameCell({
  model,
  onSaved,
}: {
  model: PublishedModel;
  onSaved: (modelDefinitionId: number, name: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(model.name ?? "");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  function startEdit(event: MouseEvent) {
    event.stopPropagation();
    setDraft(model.name ?? `Model ${model.modelDefinitionId}`);
    setSaveError("");
    setEditing(true);
  }

  async function save() {
    const trimmed = draft.trim();
    if (!trimmed) {
      setSaveError("Name can't be empty.");
      return;
    }
    setSaving(true);
    setSaveError("");
    try {
      const response = await fetchWithTimeout(`/api/v1/model-definitions/${model.modelDefinitionId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model_name: trimmed }),
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { detail?: { code?: string } } | null;
        throw new Error(payload?.detail?.code === "MODEL_DEFINITION_NOT_FOUND" ? "Model no longer exists." : "Could not rename the model.");
      }
      onSaved(model.modelDefinitionId, trimmed);
      setEditing(false);
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : "Could not rename the model.");
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="min-w-[220px] max-w-xs" onClick={(event) => event.stopPropagation()}>
        <input
          autoFocus
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setEditing(false);
            if (event.key === "Enter") void save();
          }}
          maxLength={255}
          className="w-full rounded-lg border border-blue-400/60 bg-background/80 px-2.5 py-1.5 text-sm font-semibold text-foreground outline-none focus:ring-2 focus:ring-blue-500/30"
        />
        {saveError && <p className="mt-1 text-xs text-red-500">{saveError}</p>}
        <div className="mt-1.5 flex items-center gap-2">
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-lg bg-blue-600 px-2.5 py-1 text-xs font-bold text-white transition hover:bg-blue-500 disabled:cursor-wait disabled:opacity-60"
          >
            <Check className="h-3.5 w-3.5" />
            {saving ? "Saving..." : "Save"}
          </button>
          <button
            type="button"
            onClick={() => setEditing(false)}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-lg border border-border px-2.5 py-1 text-xs font-semibold text-muted-foreground transition hover:text-foreground"
          >
            <X className="h-3.5 w-3.5" />
            Cancel
          </button>
        </div>
      </div>
    );
  }

  return (
    <button type="button" onClick={startEdit} className="group flex items-center gap-1.5 text-left">
      <span className="font-semibold text-foreground">{model.name || `Model ${model.modelDefinitionId}`}</span>
      <Pencil className="h-3 w-3 shrink-0 text-muted-foreground opacity-0 transition group-hover:opacity-100" />
    </button>
  );
}

interface PublishedModel {
  modelDefinitionId: number;
  legacyModelId: number | null;
  name: string | null;
  algorithm: string;
  featureProfile: string;
  modelBuildId: number;
  trainingCount: number;
  validationCount: number;
  holdoutCount: number;
  excludedCount: number;
  publishedAt: string;
  holdoutAccuracy: number | null;
  holdoutAuc: number | null;
  holdoutF1: number | null;
}

interface Molecule {
  moleculeId: number;
  inchiKey: string | null;
  canonicalSmiles: string | null;
  name: string | null;
  normalizationStatus: string;
  sourceIdentifier?: string;
}

interface Prediction {
  modelDefinitionId: number;
  modelBuildId: number;
  moleculeId: number;
  predictionJobId: number;
  predictedClass: string;
  distribution: Record<string, number>;
  responseStrength: number;
  applicabilityScore: number | null;
  inApplicabilityDomain: boolean | null;
  trainingScaffoldCount: number;
}

type PredictionOutcome =
  | { status: "success"; model: PublishedModel; molecule: Molecule; prediction: Prediction }
  | { status: "error"; model: PublishedModel; molecule: Molecule; message: string };

export function ModelMoleculeSearchPanel() {
  const searchParams = useSearchParams();
  const [models, setModels] = useState<PublishedModel[]>([]);
  const [modelQuery, setModelQuery] = useState("");
  const [selectedModelIds, setSelectedModelIds] = useState<Set<number>>(new Set());
  const [selectedMoleculeIds, setSelectedMoleculeIds] = useState<Set<number>>(new Set());
  const [moleculeCache, setMoleculeCache] = useState<Map<number, Molecule>>(new Map());
  const [outcomes, setOutcomes] = useState<PredictionOutcome[]>([]);
  const [loadingModels, setLoadingModels] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function cacheMolecules(found: Molecule[]) {
    setMoleculeCache((current) => {
      const next = new Map(current);
      for (const molecule of found) next.set(molecule.moleculeId, molecule);
      return next;
    });
  }

  useEffect(() => {
    const raw = searchParams.get("molecules");
    if (!raw) return;
    const ids = Array.from(new Set(raw.split(",").map((id) => Number(id.trim())).filter((id) => Number.isFinite(id) && id > 0)));
    if (ids.length === 0) return;
    let cancelled = false;
    void (async () => {
      if (cancelled) return;
      setSelectedMoleculeIds((current) => new Set([...current, ...ids]));
      const found = await Promise.all(
        ids.map(async (id) => {
          try {
            const response = await fetchWithTimeout(`/api/v3/molecules/${id}`, { cache: "no-store" });
            if (!response.ok) return null;
            return (await response.json()) as Molecule;
          } catch {
            return null;
          }
        }),
      );
      if (cancelled) return;
      cacheMolecules(found.filter((m): m is Molecule => m !== null));
    })();
    return () => { cancelled = true; };
  }, [searchParams]);

  const loadModels = async (query = "") => {
    setLoadingModels(true);
    setError(null);
    try {
      const response = await fetchWithTimeout(`/api/v3/models?limit=100&query=${encodeURIComponent(query)}`);
      if (!response.ok) throw new Error(await response.text());
      setModels(await response.json());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load published models");
    } finally {
      setLoadingModels(false);
    }
  };

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const response = await fetchWithTimeout("/api/v3/models?limit=100&query=");
        if (!response.ok) throw new Error(await response.text());
        const payload = (await response.json()) as PublishedModel[];
        if (cancelled) return;
        setModels(payload);
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause.message : "Could not load published models");
      } finally {
        if (!cancelled) setLoadingModels(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, []);

  const searchModels = (event: FormEvent) => {
    event.preventDefault();
    loadModels(modelQuery);
  };

  function handleModelRenamed(modelDefinitionId: number, name: string) {
    setModels((current) => current.map((m) => (m.modelDefinitionId === modelDefinitionId ? { ...m, name } : m)));
  }

  function toggleModel(modelDefinitionId: number) {
    setSelectedModelIds((current) => {
      const next = new Set(current);
      if (next.has(modelDefinitionId)) next.delete(modelDefinitionId);
      else next.add(modelDefinitionId);
      return next;
    });
    setOutcomes([]);
  }

  function toggleSelectAll() {
    setSelectedModelIds((current) =>
      current.size === models.length ? new Set() : new Set(models.map((m) => m.modelDefinitionId)),
    );
    setOutcomes([]);
  }

  function removeMolecule(moleculeId: number) {
    setSelectedMoleculeIds((current) => {
      const next = new Set(current);
      next.delete(moleculeId);
      return next;
    });
    setOutcomes([]);
  }

  type ModelSortKey = "model" | "algorithm" | "features" | "split" | "holdout" | "auc" | "f1";
  const [modelSort, setModelSort] = useState<{ key: ModelSortKey; direction: SortDirection } | null>(null);
  const toggleModelSort = (key: ModelSortKey) => setModelSort((current) => nextSort(key, current));

  const sortedModels = useMemo(() => {
    if (!modelSort) return models;
    const dir = modelSort.direction === "asc" ? 1 : -1;
    return [...models].sort((a, b) => {
      switch (modelSort.key) {
        case "model":
          return (a.name || `Model ${a.modelDefinitionId}`).localeCompare(b.name || `Model ${b.modelDefinitionId}`) * dir;
        case "algorithm":
          return a.algorithm.localeCompare(b.algorithm) * dir;
        case "features":
          return a.featureProfile.localeCompare(b.featureProfile) * dir;
        case "split":
          return ((a.trainingCount + a.validationCount + a.holdoutCount) - (b.trainingCount + b.validationCount + b.holdoutCount)) * dir;
        case "holdout":
          return compareNullableNumber(a.holdoutAccuracy, b.holdoutAccuracy, dir as 1 | -1);
        case "auc":
          return compareNullableNumber(a.holdoutAuc, b.holdoutAuc, dir as 1 | -1);
        case "f1":
          return compareNullableNumber(a.holdoutF1, b.holdoutF1, dir as 1 | -1);
      }
    });
  }, [models, modelSort]);

  const selectedModels = models.filter((model) => selectedModelIds.has(model.modelDefinitionId));
  const selectedMolecules = Array.from(selectedMoleculeIds)
    .map((id) => moleculeCache.get(id))
    .filter((m): m is Molecule => m !== undefined);

  const groupedOutcomes = useMemo(() => {
    const groups = new Map<number, { molecule: Molecule; items: PredictionOutcome[] }>();
    for (const outcome of outcomes) {
      const existing = groups.get(outcome.molecule.moleculeId);
      if (existing) existing.items.push(outcome);
      else groups.set(outcome.molecule.moleculeId, { molecule: outcome.molecule, items: [outcome] });
    }
    return Array.from(groups.values());
  }, [outcomes]);

  const runPredictions = async () => {
    if (selectedModels.length === 0 || selectedMolecules.length === 0) return;
    setBusy(true);
    setError(null);
    setOutcomes([]);
    const pairs = selectedMolecules.flatMap((molecule) => selectedModels.map((model) => ({ model, molecule })));
    const results = await Promise.all(
      pairs.map(async ({ model, molecule }): Promise<PredictionOutcome> => {
        try {
          const response = await fetch(
            `/api/v3/models/${model.modelDefinitionId}/molecules/${molecule.moleculeId}/predict`,
            { method: "POST" },
          );
          if (!response.ok) throw new Error(await response.text());
          return { status: "success", model, molecule, prediction: (await response.json()) as Prediction };
        } catch (cause) {
          return {
            status: "error",
            model,
            molecule,
            message: cause instanceof Error ? cause.message : "Prediction failed",
          };
        }
      }),
    );
    setOutcomes(results);
    setBusy(false);
  };

  return (
    <section className="mt-8 space-y-8">
      <header className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-blue-500">Published registry</p>
          <h2 className="text-2xl font-bold text-foreground mt-2 sm:text-3xl">Classification models</h2>
          <p className="text-muted-foreground mt-2 max-w-2xl">
            Search human-approved CDK 2.12 and Weka 3.8.7 builds, then predict an indexed molecule against one,
            several, or all of them at once.
          </p>
        </div>
        <div className="rounded-full border border-emerald-500/30 bg-emerald-500/10 px-4 py-2 text-sm text-emerald-500">
          {models.length} published
        </div>
      </header>

      {error && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-sm text-red-500">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => void loadModels(modelQuery)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-red-500/40 px-3 py-1.5 text-xs font-semibold text-red-700 transition hover:bg-red-500/10 dark:text-red-200"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Retry
          </button>
        </div>
      )}

      <form onSubmit={searchModels} className="bg-card/50 backdrop-blur-md rounded-2xl border border-border p-5 shadow-lg">
        <label className="block text-sm font-medium text-muted-foreground mb-2" htmlFor="model-search">
          Model name, ID, algorithm, or feature profile
        </label>
        <div className="flex flex-col gap-3 sm:flex-row">
          <input
            id="model-search"
            value={modelQuery}
            onChange={(event) => setModelQuery(event.target.value)}
            placeholder="e.g. RandomForest, MCAT, 102"
            className="flex-1 bg-background border border-border rounded-xl px-4 py-3 text-foreground focus:outline-none focus:ring-2 focus:ring-blue-500/40"
          />
          <button className="inline-flex items-center justify-center gap-2 rounded-xl bg-blue-600 px-5 py-3 font-semibold text-white hover:bg-blue-500">
            <Search className="h-4 w-4" /> Search
          </button>
        </div>
      </form>

      <section className="bg-card/50 backdrop-blur-md rounded-2xl border border-border shadow-2xl overflow-hidden">
        {loadingModels ? (
          <div className="p-12 text-center text-muted-foreground animate-pulse">Loading published models...</div>
        ) : models.length === 0 ? (
          <div className="p-12 text-center">
            <Database className="h-10 w-10 mx-auto text-muted-foreground mb-4" />
            <h2 className="text-xl font-semibold text-foreground">No published v3 models</h2>
            <p className="text-muted-foreground mt-2">Rebuilt models remain unavailable here until a human approves them.</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <div className="flex items-center justify-between gap-3 border-b border-border/50 bg-muted/30 px-4 py-3">
              <label className="inline-flex items-center gap-2 text-sm font-medium text-foreground">
                <input
                  type="checkbox"
                  checked={selectedModelIds.size === models.length && models.length > 0}
                  onChange={toggleSelectAll}
                  className="h-4 w-4 rounded border-border accent-blue-600"
                />
                Select all
              </label>
              <span className="text-sm text-muted-foreground">
                {selectedModelIds.size} of {models.length} selected
              </span>
            </div>
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-muted/50 border-b border-border/50">
                  <th className="p-4 text-muted-foreground font-semibold w-10"></th>
                  <SortableHeader label="Model" className="p-4 text-muted-foreground" active={modelSort?.key === "model"} direction={modelSort?.key === "model" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("model")} />
                  <SortableHeader label="Algorithm" className="p-4 text-muted-foreground" active={modelSort?.key === "algorithm"} direction={modelSort?.key === "algorithm" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("algorithm")} />
                  <SortableHeader label="Features" className="p-4 text-muted-foreground" active={modelSort?.key === "features"} direction={modelSort?.key === "features" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("features")} />
                  <SortableHeader label="Split" className="p-4 text-muted-foreground" active={modelSort?.key === "split"} direction={modelSort?.key === "split" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("split")} />
                  <SortableHeader label="Holdout" className="p-4 text-muted-foreground" active={modelSort?.key === "holdout"} direction={modelSort?.key === "holdout" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("holdout")} />
                  <SortableHeader label="AUC" className="p-4 text-muted-foreground" active={modelSort?.key === "auc"} direction={modelSort?.key === "auc" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("auc")} />
                  <SortableHeader label="F1" className="p-4 text-muted-foreground" active={modelSort?.key === "f1"} direction={modelSort?.key === "f1" ? modelSort.direction : "asc"} onClick={() => toggleModelSort("f1")} />
                </tr>
              </thead>
              <tbody>
                {sortedModels.map((model) => {
                  const checked = selectedModelIds.has(model.modelDefinitionId);
                  return (
                    <tr
                      key={model.modelDefinitionId}
                      onClick={() => toggleModel(model.modelDefinitionId)}
                      className={`cursor-pointer border-b border-border/50 transition-colors ${checked ? "bg-emerald-500/5" : "hover:bg-muted/30"}`}
                    >
                      <td className="p-4">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleModel(model.modelDefinitionId)}
                          onClick={(event) => event.stopPropagation()}
                          className="h-4 w-4 rounded border-border accent-emerald-600"
                        />
                      </td>
                      <td className="p-4">
                        <ModelNameCell model={model} onSaved={handleModelRenamed} />
                        <div className="text-xs text-muted-foreground mt-1">
                          v3 #{model.modelDefinitionId} · legacy #{model.legacyModelId ?? "n/a"} · build #{model.modelBuildId}
                        </div>
                      </td>
                      <td className="p-4 text-foreground">{model.algorithm}</td>
                      <td className="p-4"><span className="rounded-full bg-blue-500/10 px-3 py-1 text-sm text-blue-500">{model.featureProfile}</span></td>
                      <td className="p-4 text-sm text-muted-foreground">
                        {model.trainingCount}/{model.validationCount}/{model.holdoutCount}
                        {model.excludedCount > 0 && <span className="block text-amber-500">{model.excludedCount} excluded</span>}
                      </td>
                      <td className="p-4 text-foreground">
                        {model.holdoutAccuracy == null ? "n/a" : `${(model.holdoutAccuracy * 100).toFixed(1)}%`}
                      </td>
                      <td className="p-4 text-foreground">
                        {model.holdoutAuc == null ? "n/a" : model.holdoutAuc.toFixed(3)}
                      </td>
                      <td className="p-4 text-foreground">
                        {model.holdoutF1 == null ? "n/a" : model.holdoutF1.toFixed(3)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section>
        <div className="rounded-2xl border border-border bg-gradient-to-br from-blue-500/10 via-card/70 to-emerald-500/10 p-6 shadow-lg">
          <div className="flex items-center gap-3">
            <Activity className="h-6 w-6 text-blue-500" />
            <h2 className="text-xl font-bold text-foreground">Prediction</h2>
          </div>

          {selectedMolecules.length === 0 && (
            <p className="mt-4 text-sm leading-6 text-muted-foreground">
              Pick molecules from{" "}
              <Link href="/search" className="font-semibold text-blue-500 hover:underline">
                Structure search
              </Link>
              {" "}and use &ldquo;Predict selected&rdquo; to bring them here.
            </p>
          )}

          {selectedMolecules.length > 0 && (
            <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {selectedMolecules.map((molecule) => (
                <div key={molecule.moleculeId} className="flex items-center gap-2 rounded-xl border border-border bg-background/50 p-3">
                  <MoleculeStructure moleculeId={molecule.moleculeId} size={40} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-foreground">
                      {molecule.sourceIdentifier || molecule.name || `Molecule ${molecule.moleculeId}`}
                    </p>
                    <p className="text-xs text-muted-foreground">#{molecule.moleculeId}</p>
                  </div>
                  <button
                    onClick={() => removeMolecule(molecule.moleculeId)}
                    className="shrink-0 rounded-lg p-1.5 text-muted-foreground transition hover:bg-red-500/10 hover:text-red-500"
                    aria-label={`Remove molecule ${molecule.moleculeId}`}
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
          )}

          <dl className="mt-5 space-y-3 text-sm">
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">Models</dt>
              <dd className="text-right text-foreground">
                {selectedModels.length === 0 ? "Select one or more models" : `${selectedModels.length} selected`}
              </dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">Molecules</dt>
              <dd className="text-right text-foreground">
                {selectedMolecules.length === 0 ? "Select one or more molecules" : `${selectedMolecules.length} selected`}
              </dd>
            </div>
          </dl>
          <button
            onClick={runPredictions}
            disabled={busy || selectedModels.length === 0 || selectedMolecules.length === 0}
            className="mt-6 w-full rounded-xl bg-emerald-600 px-5 py-3 font-semibold text-white hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy
              ? "Working..."
              : selectedModels.length > 1 || selectedMolecules.length > 1
                ? `Run ${selectedMolecules.length} molecule${selectedMolecules.length === 1 ? "" : "s"} against ${selectedModels.length} model${selectedModels.length === 1 ? "" : "s"}`
                : "Run approved model"}
          </button>

          {groupedOutcomes.length > 0 && (
            <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {groupedOutcomes.map(({ molecule, items }) => (
                <div key={molecule.moleculeId} className="rounded-2xl border border-border bg-background/40 p-4">
                  <div className="flex items-start gap-3">
                    <MoleculeStructure moleculeId={molecule.moleculeId} size={48} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-bold text-foreground">
                        {molecule.sourceIdentifier || molecule.name || `Molecule ${molecule.moleculeId}`}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        #{molecule.moleculeId} · {items.length} model{items.length === 1 ? "" : "s"}
                      </p>
                      <Link
                        href={`/molecules/${molecule.moleculeId}`}
                        className="text-xs font-semibold text-blue-500 hover:underline"
                      >
                        View history →
                      </Link>
                    </div>
                  </div>

                  <div className="mt-3 space-y-3">
                    {items.map((outcome) => (
                      <div
                        key={outcome.model.modelDefinitionId}
                        className={`rounded-xl border p-4 ${outcome.status === "success" ? "border-emerald-500/30 bg-background/70" : "border-red-500/30 bg-red-500/10"}`}
                      >
                        <p className="text-xs uppercase tracking-widest text-muted-foreground">
                          {outcome.model.name || `Model ${outcome.model.modelDefinitionId}`} · {outcome.model.algorithm}
                        </p>
                        {outcome.status === "error" ? (
                          <p className="mt-1 text-sm text-red-500">{outcome.message}</p>
                        ) : (
                          <>
                            <div className="mt-1 flex items-baseline justify-between gap-3">
                              <p className="text-xl font-bold text-foreground">{outcome.prediction.predictedClass}</p>
                              <p className="text-xs text-muted-foreground">{(outcome.prediction.responseStrength * 100).toFixed(1)}% confidence</p>
                            </div>
                            <div className="mt-2 flex items-center gap-2 text-xs">
                              {outcome.prediction.applicabilityScore == null ? (
                                <span className="text-muted-foreground">Applicability domain: undetermined (no scaffold data)</span>
                              ) : (
                                <>
                                  <span
                                    className={`rounded-full px-2 py-0.5 font-semibold uppercase ${
                                      outcome.prediction.inApplicabilityDomain
                                        ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-300"
                                        : "bg-amber-500/10 text-amber-600 dark:text-amber-300"
                                    }`}
                                  >
                                    {outcome.prediction.inApplicabilityDomain ? "In domain" : "Outside domain"}
                                  </span>
                                  <span className="text-muted-foreground">
                                    scaffold similarity {(outcome.prediction.applicabilityScore * 100).toFixed(0)}%
                                    {" · "}
                                    {outcome.prediction.trainingScaffoldCount} known scaffolds
                                  </span>
                                </>
                              )}
                            </div>
                            <div className="mt-3 space-y-1.5">
                              {Object.entries(outcome.prediction.distribution).map(([label, probability]) => (
                                <div key={label}>
                                  <div className="flex justify-between text-xs text-muted-foreground"><span>{label}</span><span>{(probability * 100).toFixed(2)}%</span></div>
                                  <div className="mt-0.5 h-1.5 rounded-full bg-muted overflow-hidden"><div className="h-full bg-blue-500" style={{ width: `${Math.max(0, Math.min(100, probability * 100))}%` }} /></div>
                                </div>
                              ))}
                            </div>
                          </>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>
    </section>
  );
}
