"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

type ModelTarget = {
  propertyId: number;
  name: string;
  sqlType: string;
  presentCount: number;
  blankCount: number;
  distinctCount: number;
};

type ModelDataset = {
  datasetId: number;
  name: string;
  originalFilename: string | null;
  description: string | null;
  status: string;
  importedRecords: number;
  failedRecords: number;
  notProcessedRecords: number;
  partialAcknowledgementRequired: boolean;
  createdBy: string;
  createdAt: string;
  targets: ModelTarget[];
};

async function responseJson<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error("The model dataset catalogue is unavailable.");
  return payload as T;
}

export default function ModelCreationPage() {
  const [datasets, setDatasets] = useState<ModelDataset[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const response = await fetch("/api/v1/model-datasets", { cache: "no-store" });
        const payload = await responseJson<{ datasets: ModelDataset[] }>(response);
        if (!cancelled) setDatasets(payload.datasets);
      } catch (loadError) {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "The dataset catalogue is unavailable.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, []);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return datasets;
    return datasets.filter((dataset) =>
      dataset.name.toLowerCase().includes(needle)
      || dataset.originalFilename?.toLowerCase().includes(needle)
      || String(dataset.datasetId) === needle,
    );
  }, [datasets, query]);

  return (
    <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:py-14">
      <section className="mb-8 flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="font-mono text-xs uppercase tracking-[0.28em] text-amber-500">Model intake</p>
          <h1 className="mt-3 max-w-3xl text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Choose a dataset with a verified target.</h1>
          <p className="mt-4 max-w-2xl text-sm leading-6 text-muted-foreground sm:text-base">
            Only model-eligible v3 datasets and properties with 2 to 100 observed classes appear here.
          </p>
        </div>
        <div className="rounded-2xl border border-border bg-card/60 px-5 py-4 shadow-lg">
          <p className="text-xs uppercase tracking-wider text-muted-foreground">Eligible datasets</p>
          <p className="mt-1 font-mono text-3xl font-bold text-foreground">{loading ? "--" : datasets.length}</p>
        </div>
      </section>

      <section className="overflow-hidden rounded-3xl border border-border bg-card/60 shadow-2xl backdrop-blur-xl">
        <div className="border-b border-border p-5 sm:p-6">
          <label className="block max-w-xl text-sm font-semibold text-foreground">
            Find by dataset name, source file, or numeric id
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search model-ready datasets"
              className="mt-2 w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-amber-400"
            />
          </label>
        </div>

        {loading && <div className="p-12 text-center text-muted-foreground">Loading verified datasets...</div>}
        {error && <div className="m-6 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-700 dark:text-red-200">{error}</div>}
        {!loading && !error && filtered.length === 0 && (
          <div className="p-12 text-center text-muted-foreground">No model-ready dataset matches this search.</div>
        )}

        {!loading && !error && filtered.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-muted/40 text-xs uppercase tracking-wider text-muted-foreground">
                <tr>
                  <th className="px-5 py-4">Dataset</th>
                  <th className="px-5 py-4">Records</th>
                  <th className="px-5 py-4">Targets</th>
                  <th className="px-5 py-4">Import state</th>
                  <th className="px-5 py-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filtered.map((dataset) => (
                  <tr key={dataset.datasetId} className="bg-background/20 transition hover:bg-amber-400/5">
                    <td className="px-5 py-5">
                      <p className="font-semibold text-foreground">{dataset.name}</p>
                      <p className="mt-1 max-w-md truncate font-mono text-xs text-muted-foreground">
                        #{dataset.datasetId} {dataset.originalFilename ? ` / ${dataset.originalFilename}` : ""}
                      </p>
                    </td>
                    <td className="px-5 py-5 font-mono text-foreground">{dataset.importedRecords.toLocaleString()}</td>
                    <td className="px-5 py-5">
                      <div className="flex flex-wrap gap-2">
                        {dataset.targets.map((target) => (
                          <span key={target.propertyId} className="rounded-full border border-sky-500/20 bg-sky-500/10 px-2.5 py-1 text-xs font-semibold text-sky-700 dark:text-sky-200">
                            {target.name} / {target.distinctCount} classes
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-5 py-5">
                      <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${
                        dataset.partialAcknowledgementRequired
                          ? "bg-amber-400/10 text-amber-700 dark:text-amber-200"
                          : "bg-emerald-500/10 text-emerald-700 dark:text-emerald-200"
                      }`}>
                        {dataset.status}
                      </span>
                    </td>
                    <td className="px-5 py-5 text-right">
                      <Link
                        href={`/model-creation/configure?dataset_id=${dataset.datasetId}`}
                        className="inline-flex rounded-xl bg-amber-400 px-4 py-2.5 font-bold text-slate-950 transition hover:bg-amber-300"
                      >
                        Configure model
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
