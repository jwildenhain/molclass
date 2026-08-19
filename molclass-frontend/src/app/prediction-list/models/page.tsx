"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { Activity, Database, Search } from "lucide-react";
import { MoleculeStructure } from "@/components/MoleculeStructure";

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
  | { status: "success"; model: PublishedModel; prediction: Prediction }
  | { status: "error"; model: PublishedModel; message: string };

export default function ModelsPage() {
  const [models, setModels] = useState<PublishedModel[]>([]);
  const [modelQuery, setModelQuery] = useState("");
  const [moleculeQuery, setMoleculeQuery] = useState("");
  const [molecules, setMolecules] = useState<Molecule[]>([]);
  const [selectedModelIds, setSelectedModelIds] = useState<Set<number>>(new Set());
  const [selectedMolecule, setSelectedMolecule] = useState<Molecule | null>(null);
  const [outcomes, setOutcomes] = useState<PredictionOutcome[]>([]);
  const [loadingModels, setLoadingModels] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadModels = async (query = "") => {
    setLoadingModels(true);
    setError(null);
    try {
      const response = await fetch(`/api/v3/models?limit=100&query=${encodeURIComponent(query)}`);
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
        const response = await fetch("/api/v3/models?limit=100&query=");
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

  const searchMolecules = async (event: FormEvent) => {
    event.preventDefault();
    if (!moleculeQuery.trim()) return;
    setBusy(true);
    setError(null);
    setOutcomes([]);
    try {
      const response = await fetch(`/api/v3/molecules?limit=25&query=${encodeURIComponent(moleculeQuery)}`);
      if (!response.ok) throw new Error(await response.text());
      setMolecules(await response.json());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not search molecules");
    } finally {
      setBusy(false);
    }
  };

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

  const selectedModels = models.filter((model) => selectedModelIds.has(model.modelDefinitionId));

  const runPredictions = async () => {
    if (selectedModels.length === 0 || !selectedMolecule) return;
    setBusy(true);
    setError(null);
    setOutcomes([]);
    const results = await Promise.all(
      selectedModels.map(async (model): Promise<PredictionOutcome> => {
        try {
          const response = await fetch(
            `/api/v3/models/${model.modelDefinitionId}/molecules/${selectedMolecule.moleculeId}/predict`,
            { method: "POST" },
          );
          if (!response.ok) throw new Error(await response.text());
          return { status: "success", model, prediction: (await response.json()) as Prediction };
        } catch (cause) {
          return {
            status: "error",
            model,
            message: cause instanceof Error ? cause.message : "Prediction failed",
          };
        }
      }),
    );
    setOutcomes(results);
    setBusy(false);
  };

  return (
    <div className="max-w-7xl mx-auto mt-12 space-y-8">
      <header className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-blue-500">Published registry</p>
          <h1 className="text-4xl font-bold text-foreground mt-2">Classification models</h1>
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
        <div className="rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-sm text-red-500">
          {error}
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
                  <th className="p-4 text-muted-foreground font-semibold">Model</th>
                  <th className="p-4 text-muted-foreground font-semibold">Algorithm</th>
                  <th className="p-4 text-muted-foreground font-semibold">Features</th>
                  <th className="p-4 text-muted-foreground font-semibold">Split</th>
                  <th className="p-4 text-muted-foreground font-semibold">Holdout</th>
                </tr>
              </thead>
              <tbody>
                {models.map((model) => {
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
                        <div className="font-semibold text-foreground">{model.name || `Model ${model.modelDefinitionId}`}</div>
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
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]">
        <div className="bg-card/50 backdrop-blur-md rounded-2xl border border-border p-6 shadow-lg">
          <h2 className="text-xl font-bold text-foreground">Find a molecule</h2>
          <p className="text-sm text-muted-foreground mt-1">Exact SDF identifier, molecule ID, InChIKey, canonical SMILES, or name prefix.</p>
          <form onSubmit={searchMolecules} className="flex gap-3 mt-5">
            <input
              value={moleculeQuery}
              onChange={(event) => setMoleculeQuery(event.target.value)}
              placeholder="Compound identifier"
              className="min-w-0 flex-1 bg-background border border-border rounded-xl px-4 py-3 text-foreground focus:outline-none focus:ring-2 focus:ring-blue-500/40"
            />
            <button disabled={busy} className="rounded-xl bg-foreground px-4 py-3 font-semibold text-background disabled:opacity-50">
              Search
            </button>
          </form>
          <div className="mt-5 space-y-2 max-h-96 overflow-y-auto">
            {molecules.map((molecule) => (
              <div
                key={molecule.moleculeId}
                className={`rounded-xl border p-3 transition-colors ${selectedMolecule?.moleculeId === molecule.moleculeId ? "border-emerald-500 bg-emerald-500/10" : "border-border hover:border-blue-500/60"}`}
              >
                <button
                  onClick={() => { setSelectedMolecule(molecule); setOutcomes([]); }}
                  className="flex w-full items-center gap-3 text-left"
                >
                  <MoleculeStructure moleculeId={molecule.moleculeId} size={56} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-3">
                      <span className="truncate font-semibold text-foreground">{molecule.sourceIdentifier || molecule.name || `Molecule ${molecule.moleculeId}`}</span>
                      <span className="shrink-0 text-xs text-muted-foreground">#{molecule.moleculeId}</span>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs text-muted-foreground">{molecule.canonicalSmiles || molecule.inchiKey || "No canonical text"}</p>
                  </div>
                </button>
                <Link
                  href={`/molecules/${molecule.moleculeId}`}
                  className="mt-1.5 inline-block text-xs font-semibold text-blue-500 hover:underline"
                >
                  View molecule &amp; prediction history →
                </Link>
              </div>
            ))}
          </div>
        </div>

        <div className="rounded-2xl border border-border bg-gradient-to-br from-blue-500/10 via-card/70 to-emerald-500/10 p-6 shadow-lg">
          <div className="flex items-center gap-3">
            <Activity className="h-6 w-6 text-blue-500" />
            <h2 className="text-xl font-bold text-foreground">Prediction</h2>
          </div>

          {selectedMolecule && (
            <div className="mt-4 flex items-center gap-3 rounded-xl border border-border bg-background/50 p-3">
              <MoleculeStructure moleculeId={selectedMolecule.moleculeId} size={64} />
              <div className="min-w-0">
                <p className="truncate font-semibold text-foreground">
                  {selectedMolecule.sourceIdentifier || selectedMolecule.name || `Molecule ${selectedMolecule.moleculeId}`}
                </p>
                <p className="text-xs text-muted-foreground">#{selectedMolecule.moleculeId}</p>
              </div>
            </div>
          )}

          <dl className="mt-5 space-y-3 text-sm">
            <div className="flex justify-between gap-4">
              <dt className="text-muted-foreground">Models</dt>
              <dd className="text-right text-foreground">
                {selectedModels.length === 0 ? "Select one or more models" : `${selectedModels.length} selected`}
              </dd>
            </div>
            <div className="flex justify-between gap-4"><dt className="text-muted-foreground">Molecule</dt><dd className="text-right text-foreground">{selectedMolecule ? `#${selectedMolecule.moleculeId}` : "Select a molecule"}</dd></div>
          </dl>
          <button
            onClick={runPredictions}
            disabled={busy || selectedModels.length === 0 || !selectedMolecule}
            className="mt-6 w-full rounded-xl bg-emerald-600 px-5 py-3 font-semibold text-white hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy ? "Working..." : selectedModels.length > 1 ? `Run against ${selectedModels.length} models` : "Run approved model"}
          </button>

          {outcomes.length > 0 && (
            <div className="mt-6 space-y-3">
              {outcomes.map((outcome) => (
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
          )}
        </div>
      </section>
    </div>
  );
}
