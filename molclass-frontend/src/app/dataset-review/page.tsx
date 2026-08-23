"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";
import { fetchWithTimeout } from "@/lib/fetchWithTimeout";

type Dataset = {
  datasetId: number;
  legacyBatchId: number | null;
  uploadId: number | null;
  name: string;
  originalFilename: string | null;
  description: string | null;
  status: string;
  totalRecords: number;
  importedRecords: number;
  failedRecords: number;
  notProcessedRecords: number;
  partialAcknowledgementRequired: boolean;
  modelEligible: boolean;
  identifierProperty: string | null;
  propertyCount: number;
  modelDefinitionCount: number;
  createdBy: string;
  createdAt: string;
  latestImport: { importRunId: number; status: string; runstep: string } | null;
};

async function apiJson<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error("The v3 dataset catalogue is unavailable.");
  return payload as T;
}

export default function DatasetReviewPage() {
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retryToken, setRetryToken] = useState(0);

  const retry = useCallback(() => {
    setLoading(true);
    setError("");
    setRetryToken((token) => token + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const response = await fetchWithTimeout("/api/v1/datasets?limit=250", { cache: "no-store" });
        const payload = await apiJson<{ total: number; datasets: Dataset[] }>(response);
        if (!cancelled) {
          setDatasets(payload.datasets);
          setTotal(payload.total);
        }
      } catch (loadError) {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "The dataset catalogue is unavailable.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [retryToken]);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return datasets.filter((dataset) => {
      const statusMatches = statusFilter === "ALL" || dataset.status === statusFilter;
      const textMatches = !needle
        || dataset.name.toLowerCase().includes(needle)
        || dataset.originalFilename?.toLowerCase().includes(needle)
        || dataset.identifierProperty?.toLowerCase().includes(needle)
        || String(dataset.datasetId) === needle;
      return statusMatches && textMatches;
    });
  }, [datasets, query, statusFilter]);

  const modelEligible = datasets.filter((dataset) => dataset.modelEligible).length;
  const attention = datasets.filter((dataset) => dataset.failedRecords > 0 || dataset.notProcessedRecords > 0).length;

  return (
    <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:py-14">
      <section className="mb-8">
        <p className="font-mono text-xs uppercase tracking-[0.28em] text-emerald-500">Database inventory</p>
        <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Review what was actually imported.</h1>
        <p className="mt-4 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
          Counts come from the v3 InnoDB model, including failed and not-processed records, selected properties, identifiers, and model eligibility.
        </p>
      </section>

      <section className="mb-6 grid gap-3 sm:grid-cols-3">
        <Metric label="Datasets" value={loading ? "--" : total.toLocaleString()} />
        <Metric label="Model eligible" value={loading ? "--" : modelEligible.toLocaleString()} tone="emerald" />
        <Metric label="Need attention" value={loading ? "--" : attention.toLocaleString()} tone={attention ? "amber" : "neutral"} />
      </section>

      <section className="overflow-hidden rounded-3xl border border-border bg-card/60 shadow-2xl backdrop-blur-xl">
        <div className="grid gap-4 border-b border-border p-5 sm:grid-cols-[minmax(0,1fr)_220px] sm:p-6">
          <label className="text-sm font-semibold text-foreground">
            Search datasets
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Name, file, identifier, or id"
              className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-emerald-400"
            />
          </label>
          <label className="text-sm font-semibold text-foreground">
            Import state
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-emerald-400">
              <option value="ALL">All states</option>
              <option value="READY">Ready</option>
              <option value="MIGRATED">Migrated</option>
              <option value="PARTIAL">Partial</option>
              <option value="IMPORTING">Importing</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>
        </div>

        {loading && <div className="p-12 text-center text-muted-foreground">Loading durable dataset records...</div>}
        {error && (
          <div className="m-6 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-700 dark:text-red-200">
            <span>{error}</span>
            <button
              type="button"
              onClick={retry}
              className="inline-flex items-center gap-1.5 rounded-lg border border-red-500/40 px-3 py-1.5 text-xs font-semibold text-red-700 transition hover:bg-red-500/10 dark:text-red-200"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Retry
            </button>
          </div>
        )}
        {!loading && !error && filtered.length === 0 && <div className="p-12 text-center text-muted-foreground">No dataset matches these filters.</div>}

        {!loading && !error && filtered.length > 0 && (
          <div className="divide-y divide-border">
            {filtered.map((dataset) => {
              const hasFailures = dataset.failedRecords > 0 || dataset.notProcessedRecords > 0;
              return (
                <article key={dataset.datasetId} className="grid gap-5 p-5 transition hover:bg-emerald-500/5 sm:p-6 lg:grid-cols-[minmax(0,1fr)_190px_220px_auto] lg:items-center">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate text-lg font-bold text-foreground">{dataset.name}</h2>
                      <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${hasFailures ? "bg-amber-400/10 text-amber-700 dark:text-amber-200" : "bg-emerald-500/10 text-emerald-700 dark:text-emerald-200"}`}>
                        {dataset.status}
                      </span>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs text-muted-foreground">dataset #{dataset.datasetId}{dataset.originalFilename ? ` / ${dataset.originalFilename}` : ""}</p>
                    <p className="mt-3 line-clamp-2 text-sm leading-6 text-muted-foreground">{dataset.description || "No description recorded."}</p>
                  </div>

                  <div>
                    <p className="text-xs uppercase tracking-wider text-muted-foreground">Record outcome</p>
                    <p className="mt-2 font-mono text-lg font-bold text-foreground">{dataset.importedRecords.toLocaleString()} / {dataset.totalRecords.toLocaleString()}</p>
                    <p className={`mt-1 text-xs ${hasFailures ? "text-amber-700 dark:text-amber-300" : "text-muted-foreground"}`}>
                      {dataset.failedRecords} failed / {dataset.notProcessedRecords} not processed
                    </p>
                  </div>

                  <div className="grid grid-cols-2 gap-3 text-sm">
                    <DataPoint label="Identifier" value={dataset.identifierProperty || "not recorded"} />
                    <DataPoint label="Properties" value={String(dataset.propertyCount)} />
                    <DataPoint label="Models" value={String(dataset.modelDefinitionCount)} />
                    <DataPoint label="Import run" value={dataset.latestImport ? `#${dataset.latestImport.importRunId}` : "legacy"} />
                  </div>

                  <div className="lg:text-right">
                    {dataset.modelEligible ? (
                      <Link href={`/model-creation/configure?dataset_id=${dataset.datasetId}`} className="inline-flex rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-4 py-2.5 text-sm font-bold text-emerald-800 transition hover:bg-emerald-500/20 dark:text-emerald-200">
                        Configure model
                      </Link>
                    ) : (
                      <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Not model eligible</span>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>
    </main>
  );
}

function Metric({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "emerald" | "amber" }) {
  const color = tone === "emerald" ? "text-emerald-600 dark:text-emerald-300" : tone === "amber" ? "text-amber-600 dark:text-amber-300" : "text-foreground";
  return (
    <div className="rounded-2xl border border-border bg-card/60 p-5 shadow-lg">
      <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className={`mt-2 font-mono text-3xl font-bold ${color}`}>{value}</p>
    </div>
  );
}

function DataPoint({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className="mt-1 truncate font-mono text-xs text-foreground" title={value}>{value}</p>
    </div>
  );
}
