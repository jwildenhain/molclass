"use client";

import { FormEvent, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { Activity, ArrowLeft, Beaker, Clock, Search } from "lucide-react";
import Link from "next/link";
import { MoleculeStructure } from "@/components/MoleculeStructure";

interface MoleculeDetail {
  moleculeId: number;
  inchiKey: string | null;
  canonicalSmiles: string | null;
  name: string | null;
  normalizationStatus: string;
  datasetRegistrations: { datasetId: number; datasetName: string; sourceIdentifier: string | null }[];
  murckoScaffoldSmiles: string | null;
}

interface PredictionHistoryEntry {
  predictionJobId: number;
  modelBuildId: number;
  modelDefinitionId: number;
  modelName: string | null;
  algorithm: string;
  predictedClass: string;
  distribution: Record<string, number>;
  confidenceScore: number;
  applicabilityScore: number | null;
  inApplicabilityDomain: boolean | null;
  createdAt: string;
}

interface PublishedModel {
  modelDefinitionId: number;
  legacyModelId: number | null;
  name: string | null;
  algorithm: string;
  featureProfile: string;
  modelBuildId: number;
  holdoutAccuracy: number | null;
}

function displayValue(value: string | null | undefined) {
  return value?.trim() || "Not recorded";
}

function formatDate(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString();
}

export default function MoleculeDetailPage() {
  const params = useParams<{ id: string }>();
  const moleculeId = Number(params.id);

  const [molecule, setMolecule] = useState<MoleculeDetail | null>(null);
  const [history, setHistory] = useState<PredictionHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [modelQuery, setModelQuery] = useState("");
  const [models, setModels] = useState<PublishedModel[]>([]);
  const [selectedModelIds, setSelectedModelIds] = useState<Set<number>>(new Set());
  const [searchingModels, setSearchingModels] = useState(false);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  async function loadMolecule() {
    setLoading(true);
    setError(null);
    try {
      const [detailResponse, historyResponse] = await Promise.all([
        fetch(`/api/v3/molecules/${moleculeId}`, { cache: "no-store" }),
        fetch(`/api/v3/molecules/${moleculeId}/predictions?limit=50`, { cache: "no-store" }),
      ]);
      if (!detailResponse.ok) throw new Error(await detailResponse.text());
      if (!historyResponse.ok) throw new Error(await historyResponse.text());
      setMolecule((await detailResponse.json()) as MoleculeDetail);
      setHistory((await historyResponse.json()) as PredictionHistoryEntry[]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load this molecule");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (!Number.isFinite(moleculeId) || moleculeId <= 0) {
      setError("Invalid molecule ID");
      setLoading(false);
      return;
    }
    void loadMolecule();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [moleculeId]);

  const searchModels = async (event: FormEvent) => {
    event.preventDefault();
    setSearchingModels(true);
    try {
      const response = await fetch(`/api/v3/models?limit=100&query=${encodeURIComponent(modelQuery)}`);
      if (!response.ok) throw new Error(await response.text());
      setModels(await response.json());
    } catch {
      setModels([]);
    } finally {
      setSearchingModels(false);
    }
  };

  useEffect(() => {
    void (async () => {
      setSearchingModels(true);
      try {
        const response = await fetch("/api/v3/models?limit=100&query=");
        if (response.ok) setModels(await response.json());
      } finally {
        setSearchingModels(false);
      }
    })();
  }, []);

  function toggleModel(id: number) {
    setSelectedModelIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const runPredictions = async () => {
    if (selectedModelIds.size === 0) return;
    setRunning(true);
    setRunError(null);
    const ids = Array.from(selectedModelIds);
    const results = await Promise.allSettled(
      ids.map((id) =>
        fetch(`/api/v3/models/${id}/molecules/${moleculeId}/predict`, { method: "POST" }).then(async (response) => {
          if (!response.ok) throw new Error(await response.text());
          return response.json();
        }),
      ),
    );
    const failures = results.filter((result) => result.status === "rejected");
    if (failures.length > 0) {
      setRunError(`${failures.length} of ${ids.length} prediction(s) failed. Successful ones were still recorded below.`);
    }
    setSelectedModelIds(new Set());
    setRunning(false);
    await loadMolecule();
  };

  if (loading) {
    return <div className="mx-auto mt-20 max-w-3xl text-center text-muted-foreground">Loading molecule...</div>;
  }

  if (error || !molecule) {
    return (
      <div className="mx-auto mt-12 max-w-3xl">
        <Link href="/search" className="inline-flex items-center gap-1 text-sm font-semibold text-blue-500 hover:underline">
          <ArrowLeft className="h-4 w-4" /> Back to registry
        </Link>
        <div className="mt-6 rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-sm text-red-500">
          {error || "Molecule not found"}
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto mt-12 space-y-8">
      <Link href="/search" className="inline-flex items-center gap-1 text-sm font-semibold text-blue-500 hover:underline">
        <ArrowLeft className="h-4 w-4" /> Back to registry
      </Link>

      <header className="flex flex-col gap-6 sm:flex-row sm:items-start">
        <MoleculeStructure moleculeId={molecule.moleculeId} size={160} />
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-blue-500">Molecule {molecule.moleculeId}</p>
          <h1 className="mt-2 text-3xl font-bold text-foreground break-words">{displayValue(molecule.name)}</h1>
          <span className="mt-2 inline-block rounded-full bg-emerald-500/10 px-3 py-1 text-xs font-semibold uppercase text-emerald-700 dark:text-emerald-300">
            {displayValue(molecule.normalizationStatus)}
          </span>
        </div>
      </header>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="rounded-2xl border border-border bg-card/60 p-4 shadow-sm">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">InChIKey</p>
          <p className="mt-1 break-all font-mono text-xs text-foreground">{displayValue(molecule.inchiKey)}</p>
        </div>
        <div className="rounded-2xl border border-border bg-card/60 p-4 shadow-sm sm:col-span-2">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Canonical SMILES</p>
          <p className="mt-1 break-all font-mono text-xs text-foreground">{displayValue(molecule.canonicalSmiles)}</p>
        </div>
        <div className="rounded-2xl border border-border bg-card/60 p-4 shadow-sm">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">Murcko scaffold</p>
          <p className="mt-1 break-all font-mono text-xs text-foreground">
            {molecule.murckoScaffoldSmiles ?? "No ring system (acyclic)"}
          </p>
        </div>
      </section>

      {molecule.datasetRegistrations.length > 0 && (
        <section className="rounded-2xl border border-border bg-card/60 p-5 shadow-sm">
          <h2 className="text-sm font-bold uppercase tracking-wider text-foreground">Registered in</h2>
          <ul className="mt-3 space-y-1.5 text-sm">
            {molecule.datasetRegistrations.map((registration) => (
              <li key={registration.datasetId} className="flex flex-wrap items-baseline justify-between gap-2 text-muted-foreground">
                <span className="text-foreground">{registration.datasetName}</span>
                <span className="font-mono text-xs">{registration.sourceIdentifier ?? "no identifier"}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="rounded-2xl border border-border bg-gradient-to-br from-blue-500/10 via-card/70 to-emerald-500/10 p-6 shadow-lg">
        <div className="flex items-center gap-3">
          <Activity className="h-6 w-6 text-blue-500" />
          <h2 className="text-xl font-bold text-foreground">Run this molecule against a model</h2>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">Select one, several, or all published models.</p>

        <form onSubmit={searchModels} className="mt-4 flex gap-3">
          <input
            value={modelQuery}
            onChange={(event) => setModelQuery(event.target.value)}
            placeholder="Filter by name, algorithm, feature profile"
            className="min-w-0 flex-1 rounded-xl border border-border bg-background/70 px-4 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-blue-500/40"
          />
          <button className="inline-flex items-center gap-2 rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-blue-500">
            <Search className="h-4 w-4" /> Search
          </button>
        </form>

        {searchingModels ? (
          <p className="mt-4 text-sm text-muted-foreground">Loading models...</p>
        ) : (
          <div className="mt-4 max-h-64 space-y-1.5 overflow-y-auto">
            {models.map((model) => {
              const checked = selectedModelIds.has(model.modelDefinitionId);
              return (
                <label
                  key={model.modelDefinitionId}
                  className={`flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm transition-colors ${checked ? "border-emerald-500 bg-emerald-500/10" : "border-border hover:border-blue-500/50"}`}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggleModel(model.modelDefinitionId)}
                    className="h-4 w-4 rounded border-border accent-emerald-600"
                  />
                  <span className="min-w-0 flex-1 truncate text-foreground">{model.name || `Model ${model.modelDefinitionId}`}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{model.algorithm}</span>
                </label>
              );
            })}
          </div>
        )}

        <button
          onClick={runPredictions}
          disabled={running || selectedModelIds.size === 0}
          className="mt-4 w-full rounded-xl bg-emerald-600 px-5 py-3 font-semibold text-white hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40 sm:w-auto"
        >
          {running
            ? "Running..."
            : selectedModelIds.size > 1
              ? `Run against ${selectedModelIds.size} models`
              : "Run prediction"}
        </button>
        {runError && <p className="mt-3 text-sm text-amber-600 dark:text-amber-300">{runError}</p>}
      </section>

      <section>
        <div className="flex items-center gap-2">
          <Clock className="h-5 w-5 text-blue-500" />
          <h2 className="text-xl font-bold text-foreground">Prediction history</h2>
          <span className="text-xs text-muted-foreground">{history.length} record{history.length === 1 ? "" : "s"}</span>
        </div>

        {history.length === 0 ? (
          <div className="mt-4 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-14 text-center">
            <Beaker className="mx-auto h-8 w-8 text-muted-foreground" />
            <h3 className="mt-3 text-lg font-bold text-foreground">No predictions yet</h3>
            <p className="mt-1 text-muted-foreground">Run this molecule against a model above to start its history.</p>
          </div>
        ) : (
          <div className="mt-4 overflow-hidden rounded-2xl border border-border bg-card/60 shadow-lg">
            <div className="divide-y divide-border">
              {history.map((entry) => (
                <article key={entry.predictionJobId} className="grid gap-2 px-5 py-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1fr)_auto] sm:items-center sm:gap-4">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-bold text-foreground">{entry.modelName || `Model ${entry.modelDefinitionId}`}</p>
                    <p className="text-xs text-muted-foreground">{entry.algorithm} · build #{entry.modelBuildId}</p>
                  </div>
                  <div>
                    <p className="text-sm font-semibold text-foreground">{entry.predictedClass}</p>
                    <p className="text-xs text-muted-foreground">{(entry.confidenceScore * 100).toFixed(1)}% confidence</p>
                  </div>
                  <div className="text-xs">
                    {entry.applicabilityScore == null ? (
                      <span className="text-muted-foreground">AD undetermined</span>
                    ) : (
                      <span className={entry.inApplicabilityDomain ? "text-emerald-600 dark:text-emerald-300" : "text-amber-600 dark:text-amber-300"}>
                        {entry.inApplicabilityDomain ? "In domain" : "Outside domain"} · {(entry.applicabilityScore * 100).toFixed(0)}% scaffold similarity
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground sm:text-right">{formatDate(entry.createdAt)}</p>
                </article>
              ))}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
