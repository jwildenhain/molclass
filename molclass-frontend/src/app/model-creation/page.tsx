"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Beaker,
  Check,
  Dna,
  Droplets,
  FlaskConical,
  HelpCircle,
  Microscope,
  Pencil,
  Search,
  X,
} from "lucide-react";

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

const compactNumberFormat = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 0 });

async function responseJson<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error("The model dataset catalogue is unavailable.");
  return payload as T;
}

type Category = {
  label: string;
  icon: typeof Beaker;
  className: string;
};

function categorize(description: string | null): Category {
  const text = (description ?? "").toLowerCase();
  if (!text || text.includes("unclear") || text.includes("verify")) {
    return { label: "Needs review", icon: HelpCircle, className: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300" };
  }
  if (text.includes("receptor") || text.includes("nuclear")) {
    return { label: "Receptor target", icon: Dna, className: "border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300" };
  }
  if (text.includes("toxicity") || text.includes("liver")) {
    return { label: "Toxicity", icon: AlertTriangle, className: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-300" };
  }
  if (text.includes("clearance") || text.includes("solubility") || text.includes("protein binding") || text.includes("logd") || text.includes("adme")) {
    return { label: "ADME", icon: Droplets, className: "border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-300" };
  }
  if (text.includes("cyp") || text.includes("accase") || text.includes("thrombin") || text.includes("enzyme") || text.includes("carboxylase")) {
    return { label: "Enzyme target", icon: FlaskConical, className: "border-teal-500/30 bg-teal-500/10 text-teal-700 dark:text-teal-300" };
  }
  if (text.includes("pubchem") || text.includes("chembl") || text.includes("chembank") || text.includes("screen") || text.includes("hts") || text.includes("assay")) {
    return { label: "HTS screen", icon: Microscope, className: "border-blue-500/30 bg-blue-500/10 text-blue-700 dark:text-blue-300" };
  }
  return { label: "Compound set", icon: Beaker, className: "border-slate-500/30 bg-slate-500/10 text-slate-700 dark:text-slate-300" };
}

function DescriptionCell({
  dataset,
  onSaved,
}: {
  dataset: ModelDataset;
  onSaved: (datasetId: number, description: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(dataset.description ?? "");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  function startEdit() {
    setDraft(dataset.description ?? "");
    setSaveError("");
    setEditing(true);
  }

  async function save() {
    setSaving(true);
    setSaveError("");
    try {
      const response = await fetch(`/api/v1/datasets/${dataset.datasetId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ description: draft.trim() }),
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { detail?: { code?: string } } | null;
        throw new Error(payload?.detail?.code === "DATASET_NOT_FOUND" ? "Dataset no longer exists." : "Could not save the description.");
      }
      onSaved(dataset.datasetId, draft.trim());
      setEditing(false);
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : "Could not save the description.");
    } finally {
      setSaving(false);
    }
  }

  if (editing) {
    return (
      <div className="min-w-[260px] max-w-sm">
        <textarea
          autoFocus
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") setEditing(false);
            if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) void save();
          }}
          rows={3}
          placeholder="What is this dataset? e.g. GABA receptor binding assay"
          className="w-full resize-none rounded-lg border border-amber-400/60 bg-background/80 px-3 py-2 text-sm text-foreground outline-none focus:ring-2 focus:ring-amber-500/30"
        />
        {saveError && <p className="mt-1 text-xs text-red-500">{saveError}</p>}
        <div className="mt-2 flex items-center gap-2">
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-lg bg-amber-400 px-3 py-1.5 text-xs font-bold text-slate-950 transition hover:bg-amber-300 disabled:cursor-wait disabled:opacity-60"
          >
            <Check className="h-3.5 w-3.5" />
            {saving ? "Saving..." : "Save"}
          </button>
          <button
            type="button"
            onClick={() => setEditing(false)}
            disabled={saving}
            className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-xs font-semibold text-muted-foreground transition hover:text-foreground"
          >
            <X className="h-3.5 w-3.5" />
            Cancel
          </button>
        </div>
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={startEdit}
      className="group flex min-w-[220px] max-w-sm items-start gap-2 rounded-lg px-2 py-1.5 text-left transition hover:bg-amber-400/5"
    >
      <span className={`flex-1 text-sm leading-5 ${dataset.description ? "text-foreground" : "italic text-muted-foreground"}`}>
        {dataset.description || "Click to describe this dataset"}
      </span>
      <Pencil className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground opacity-0 transition group-hover:opacity-100" />
    </button>
  );
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
      || dataset.description?.toLowerCase().includes(needle)
      || String(dataset.datasetId) === needle,
    );
  }, [datasets, query]);

  const totalRecords = useMemo(() => datasets.reduce((sum, d) => sum + d.importedRecords, 0), [datasets]);
  const totalTargets = useMemo(() => datasets.reduce((sum, d) => sum + d.targets.length, 0), [datasets]);
  const needsReview = useMemo(
    () => datasets.filter((d) => !d.description || /unclear|verify/i.test(d.description)).length,
    [datasets],
  );

  function handleSaved(datasetId: number, description: string) {
    setDatasets((current) => current.map((d) => (d.datasetId === datasetId ? { ...d, description } : d)));
  }

  return (
    <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:py-14">
      <section className="relative mb-8 overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
        <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-amber-400/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-24 -left-12 h-64 w-64 rounded-full bg-sky-500/10 blur-3xl" />
        <div className="relative flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full border border-amber-500/30 bg-amber-500/10 px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.28em] text-amber-500">
              <Beaker className="h-3.5 w-3.5" />
              Model intake
            </div>
            <h1 className="mt-4 max-w-3xl text-3xl font-bold tracking-tight text-foreground sm:text-5xl">
              Choose a dataset with a verified target.
            </h1>
            <p className="mt-4 max-w-2xl text-sm leading-6 text-muted-foreground sm:text-base">
              Only model-eligible v3 datasets and properties with 2 to 100 observed classes appear here.
              Click any description to edit it — names are derived from the source SDF filename and refined by hand.
            </p>
          </div>
          <div className="relative grid grid-cols-3 gap-3">
            <div className="rounded-2xl border border-border bg-background/50 px-4 py-3 shadow-lg sm:px-5 sm:py-4">
              <p className="text-[10px] uppercase tracking-wider text-muted-foreground sm:text-xs">Eligible</p>
              <p className="mt-1 font-mono text-2xl font-bold text-foreground sm:text-3xl">{loading ? "--" : datasets.length}</p>
            </div>
            <div className="rounded-2xl border border-border bg-background/50 px-4 py-3 shadow-lg sm:px-5 sm:py-4">
              <p className="text-[10px] uppercase tracking-wider text-muted-foreground sm:text-xs">Records</p>
              <p
                className="mt-1 font-mono text-2xl font-bold text-foreground sm:text-3xl"
                title={loading ? undefined : totalRecords.toLocaleString()}
              >
                {loading ? "--" : compactNumberFormat.format(totalRecords)}
              </p>
            </div>
            <div className="rounded-2xl border border-border bg-background/50 px-4 py-3 shadow-lg sm:px-5 sm:py-4">
              <p className="text-[10px] uppercase tracking-wider text-muted-foreground sm:text-xs">Targets</p>
              <p className="mt-1 font-mono text-2xl font-bold text-foreground sm:text-3xl">{loading ? "--" : totalTargets.toLocaleString()}</p>
            </div>
          </div>
        </div>
      </section>

      <section className="overflow-hidden rounded-3xl border border-border bg-card/60 shadow-2xl backdrop-blur-xl">
        <div className="flex flex-col gap-4 border-b border-border p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <label className="block max-w-xl flex-1 text-sm font-semibold text-foreground">
            Find by dataset name, source file, description, or numeric id
            <div className="relative mt-2">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search model-ready datasets"
                className="w-full rounded-xl border border-border bg-background/70 py-3 pl-10 pr-4 font-normal outline-none transition focus:border-amber-400"
              />
            </div>
          </label>
          {!loading && !error && needsReview > 0 && (
            <div className="inline-flex shrink-0 items-center gap-2 self-start rounded-full border border-amber-500/30 bg-amber-500/10 px-4 py-2 text-sm font-semibold text-amber-600 dark:text-amber-300">
              <HelpCircle className="h-4 w-4" />
              {needsReview} need{needsReview === 1 ? "s" : ""} review
            </div>
          )}
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
                  <th className="w-20 px-5 py-4">ID</th>
                  <th className="px-5 py-4">Category</th>
                  <th className="px-5 py-4">What is this dataset?</th>
                  <th className="px-5 py-4">Records</th>
                  <th className="px-5 py-4">Targets</th>
                  <th className="px-5 py-4">Import state</th>
                  <th className="px-5 py-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filtered.map((dataset) => {
                  const category = categorize(dataset.description);
                  const CategoryIcon = category.icon;
                  return (
                    <tr key={dataset.datasetId} className="bg-background/20 align-top transition hover:bg-amber-400/5">
                      <td className="whitespace-nowrap px-5 py-5" title={dataset.originalFilename || dataset.name}>
                        <span className="font-mono text-sm font-semibold text-foreground">ID {dataset.datasetId}</span>
                      </td>
                      <td className="px-5 py-5">
                        <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-1 text-xs font-semibold ${category.className}`}>
                          <CategoryIcon className="h-3.5 w-3.5" />
                          {category.label}
                        </span>
                      </td>
                      <td className="px-5 py-5">
                        <DescriptionCell dataset={dataset} onSaved={handleSaved} />
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
                        <span className={`whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold ${
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
                          className="inline-flex whitespace-nowrap rounded-xl bg-amber-400 px-4 py-2.5 font-bold text-slate-950 transition hover:bg-amber-300"
                        >
                          Configure model
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
