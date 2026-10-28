"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { useSearchParams } from "next/navigation";
import { Activity, Beaker, Info, PencilRuler, Search } from "lucide-react";
import type { SketcherHandle } from "./MoleculeSketcher";

import { MoleculeStructure } from "@/components/MoleculeStructure";
import { fetchWithTimeout } from "@/lib/fetchWithTimeout";

const LinkedStructurePreview = dynamic(() => import("./LinkedStructurePreview").then(module => module.LinkedStructurePreview), {
  ssr: false,
  loading: () => <p className="my-6">Loading linked structures…</p>,
});

const MoleculeSketcher = dynamic(() => import("./MoleculeSketcher"), {
  ssr: false,
  loading: () => (
    <div className="grid h-[520px] w-full place-items-center rounded-xl border border-border bg-background/50 text-sm text-muted-foreground">
      Loading structure editor...
    </div>
  ),
});

type Molecule = {
  moleculeId: number;
  inchiKey: string | null;
  canonicalSmiles: string | null;
  name: string | null;
  normalizationStatus: string | null;
  sourceIdentifier?: string | null;
};

type MatchMode = "exact" | "substructure";

type SearchMeta = {
  truncated: boolean;
  indexedMolecules: number;
  totalMolecules: number;
};

function extractMeta(payload: unknown): SearchMeta | null {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const envelope = payload as Record<string, unknown>;
  if (typeof envelope.truncated !== "boolean") return null;
  return {
    truncated: envelope.truncated,
    indexedMolecules: Number(envelope.indexedMolecules) || 0,
    totalMolecules: Number(envelope.totalMolecules) || 0,
  };
}

function extractMolecules(payload: unknown): Molecule[] {
  if (Array.isArray(payload)) {
    return payload as Molecule[];
  }

  if (!payload || typeof payload !== "object") {
    return [];
  }

  const envelope = payload as Record<string, unknown>;
  for (const key of ["items", "content", "molecules", "results"]) {
    if (Array.isArray(envelope[key])) {
      return envelope[key] as Molecule[];
    }
  }

  return [];
}

function displayValue(value: string | null | undefined) {
  return value?.trim() || "Not recorded";
}

async function errorDetail(response: Response, fallback: string) {
  const payload = await response.json().catch(() => null) as { message?: string } | null;
  return payload?.message || fallback;
}

export function StructureSearchPanel() {
  const params = useSearchParams();
  const linkedSmiles = (params.get("smiles") || "").slice(0, 10000);
  const linkedScaffold = (params.get("scaffold") || "").slice(0, 10000);
  const [query, setQuery] = useState(linkedSmiles);
  const [matchMode, setMatchMode] = useState<MatchMode>("exact");
  const [results, setResults] = useState<Molecule[]>([]);
  const [meta, setMeta] = useState<SearchMeta | null>(null);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showSketcher, setShowSketcher] = useState(false);
  const [sketcherError, setSketcherError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [adhocStatus, setAdhocStatus] = useState<"idle" | "submitting" | "error">("idle");
  const [adhocError, setAdhocError] = useState<string | null>(null);
  const sketcherRef = useRef<SketcherHandle>(null);

  const queryRef = useRef<HTMLInputElement>(null);
  const operationVersion = useRef(0);
  useEffect(() => () => { operationVersion.current += 1; }, []);

  function resetSearch(value: string) {
    operationVersion.current += 1;
    setQuery(value);
    setLoading(false);
    setSelectedIds(new Set());
    setResults([]);
    setMeta(null);
    setSearched(false);
    setError(null);
    setAdhocStatus("idle");
    setAdhocError(null);
  }

  function clearLinkedStructure() {
    resetSearch("");
    setMatchMode("exact");
    setSketcherError(null);
    sketcherRef.current?.clear();
    setShowSketcher(false);
    window.history.replaceState(null, "", "/search");
    queryRef.current?.focus();
  }

  async function predictNewStructure() {
    const smiles = query.trim();
    if (!smiles) return;
    const version = ++operationVersion.current;
    setAdhocStatus("submitting");
    setAdhocError(null);

    try {
      const modelsResponse = await fetchWithTimeout("/api/v3/models?limit=1&query=");
      if (!modelsResponse.ok) {
        throw new Error(await errorDetail(modelsResponse, "Could not load published models."));
      }
      if (version !== operationVersion.current) return;
      const models = (await modelsResponse.json()) as { modelDefinitionId: number }[];
      const seedModelId = models[0]?.modelDefinitionId;
      if (!seedModelId) {
        throw new Error("No published models are available to predict against.");
      }

      if (version !== operationVersion.current) return;
      const submitResponse = await fetchWithTimeout("/api/v1/molecules/predict", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ smiles, model_definition_id: seedModelId }),
      });
      if (!submitResponse.ok) {
        throw new Error(await errorDetail(submitResponse, `Could not submit this structure (${submitResponse.status}).`));
      }
      const { jobId } = (await submitResponse.json()) as { jobId: number };

      const deadline = Date.now() + 30000;
      let moleculeId: number | null = null;
      while (Date.now() < deadline) {
        if (version !== operationVersion.current) return;
        const jobResponse = await fetchWithTimeout(`/api/v1/jobs/${jobId}`, { cache: "no-store" });
        if (!jobResponse.ok) {
          throw new Error(await errorDetail(jobResponse, "Could not check prediction status."));
        }
        const job = (await jobResponse.json()) as {
          status: string;
          error_message: string | null;
          result: { moleculeId: number } | null;
        };
        if (job.status === "SUCCEEDED") {
          moleculeId = job.result?.moleculeId ?? null;
          break;
        }
        if (job.status === "FAILED") {
          throw new Error(job.error_message || "MolClass could not process this structure.");
        }
        await new Promise((resolve) => setTimeout(resolve, 1500));
      }

      if (version !== operationVersion.current) return;
      if (moleculeId == null) {
        throw new Error("Registering this structure is taking longer than expected. Try again shortly.");
      }
      openModelSelection([moleculeId]);
    } catch (submitError) {
      if (version !== operationVersion.current) return;
      setAdhocStatus("error");
      setAdhocError(submitError instanceof Error ? submitError.message : "Could not predict this structure.");
    }
  }

  function toggleSelected(moleculeId: number) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(moleculeId)) next.delete(moleculeId);
      else next.add(moleculeId);
      return next;
    });
  }

  function toggleSelectAllResults() {
    setSelectedIds((current) =>
      current.size === results.length ? new Set() : new Set(results.map((m) => m.moleculeId)),
    );
  }

  function openModelSelection(moleculeIds: number[]) {
    // Both panels are client-side on this page. Update the URL immediately
    // instead of waiting for a server navigation before showing model selection.
    window.history.pushState(null, "", `/search?tab=models&molecules=${moleculeIds.join(",")}`);
    window.scrollTo({ top: 0, behavior: "instant" });
  }

  function predictSelected() {
    if (selectedIds.size === 0) return;
    openModelSelection(Array.from(selectedIds));
  }

  async function runSearch(rawQuery: string, mode: MatchMode) {
    resetSearch(rawQuery);
    const version = operationVersion.current;
    setLoading(true);
    setError(null);
    setSelectedIds(new Set());

    try {
      const trimmed = rawQuery.trim();
      const url =
        mode === "substructure"
          ? `/api/v3/molecules/substructure?${new URLSearchParams({ smiles: trimmed, limit: "25" })}`
          : `/api/v3/molecules?${new URLSearchParams({ query: trimmed, limit: "25" })}`;

      const response = await fetch(url, {
        headers: { Accept: "application/json" },
        cache: "no-store",
      });

      if (!response.ok) {
        throw new Error(await errorDetail(response, `Molecule search failed (${response.status}).`));
      }

      const payload = await response.json();
      if (version !== operationVersion.current) return;
      setResults(extractMolecules(payload));
      setMeta(extractMeta(payload));
      setSearched(true);
    } catch (searchError) {
      if (version !== operationVersion.current) return;
      setResults([]);
      setMeta(null);
      setSearched(true);
      setError(
        searchError instanceof Error
          ? searchError.message
          : "Molecule search failed.",
      );
    } finally {
      if (version === operationVersion.current) setLoading(false);
    }
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void runSearch(query, matchMode);
  }

  async function searchDrawnStructure() {
    setSketcherError(null);
    const version = operationVersion.current;
    try {
      const smiles = await sketcherRef.current?.getSmiles();
      if (!smiles || version !== operationVersion.current) return;
      setQuery(smiles);
      await runSearch(smiles, matchMode);
    } catch (drawError) {
      if (version !== operationVersion.current) return;
      setSketcherError(
        drawError instanceof Error ? drawError.message : "Could not read the drawn structure.",
      );
    }
  }

  return (
    <section className="mt-8">
      <header className="mb-8">
        <div className="flex flex-col justify-between gap-5 lg:flex-row lg:items-end">
          <div>
            <p className="font-mono text-xs uppercase tracking-[0.28em] text-blue-500">Compound registry</p>
            <h2 className="mt-3 text-2xl font-bold tracking-tight text-foreground sm:text-3xl">Find a molecule without loading a model.</h2>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
              Search the canonical molecule registry by database ID, name, source identifier, InChIKey, or
              canonical SMILES — draw a structure directly, or search for it as a substructure.
            </p>
          </div>
          <div className="inline-flex items-center gap-2 self-start rounded-full border border-blue-500/30 bg-blue-500/10 px-4 py-2 text-sm text-blue-600 dark:text-blue-300">
            <Beaker className="h-4 w-4" />
            Registry lookup
          </div>
        </div>
      </header>

      {linkedSmiles && <LinkedStructurePreview
        smiles={linkedSmiles}
        scaffold={linkedScaffold}
        onSearch={(value, isScaffold) => {
          const mode = isScaffold ? "substructure" : "exact";
          setQuery(value); setMatchMode(mode); void runSearch(value, mode);
        }}
      />}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-xl border border-border bg-card/60 p-1 shadow-sm">
          <button
            type="button"
            onClick={() => setMatchMode("exact")}
            className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${
              matchMode === "exact" ? "bg-blue-600 text-white" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Exact match
          </button>
          <button
            type="button"
            onClick={() => setMatchMode("substructure")}
            className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${
              matchMode === "substructure" ? "bg-blue-600 text-white" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Substructure
          </button>
        </div>
        <button
            type="button"
            onClick={clearLinkedStructure}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-blue-500"
          >
            {linkedSmiles ? "Clear, back to search" : "Clear search"}
          </button>
      </div>

      <form onSubmit={search} className="grid gap-3 rounded-2xl border border-border bg-card/60 p-5 shadow-lg backdrop-blur-md sm:grid-cols-[1fr_auto_auto] sm:p-6">
        <label className="sr-only" htmlFor="molecule-query">Molecule search</label>
        <input
          ref={queryRef}
          id="molecule-query"
          value={query}
          onChange={(event) => resetSearch(event.target.value)}
          placeholder={
            matchMode === "substructure"
              ? "Fragment SMILES, e.g. c1ccccc1 for any benzene ring"
              : "ID, InChIKey, caffeine, name*, Cn1c(=O)... — * and ? wildcards allowed"
          }
          className="min-w-0 rounded-xl border border-border bg-background/70 px-4 py-3 font-mono text-sm text-foreground outline-none transition focus:border-blue-400 focus:ring-2 focus:ring-blue-500/20"
        />
        <button
          type="submit"
          disabled={loading}
          className="inline-flex items-center justify-center gap-2 rounded-xl bg-blue-600 px-6 py-3 font-semibold text-white transition hover:bg-blue-500 disabled:cursor-wait disabled:opacity-60"
        >
          <Search className="h-4 w-4" />
          {loading ? "Searching..." : query.trim() ? "Search registry" : "Browse registry"}
        </button>
        <button
          type="button"
          onClick={() => setShowSketcher((value) => !value)}
          className={`inline-flex items-center justify-center gap-2 rounded-xl border px-6 py-3 font-semibold transition ${
            showSketcher
              ? "border-emerald-500 bg-emerald-500/10 text-emerald-600 dark:text-emerald-300"
              : "border-border text-foreground hover:border-blue-500/60 hover:bg-muted/30"
          }`}
        >
          <PencilRuler className="h-4 w-4" />
          {showSketcher ? "Hide sketcher" : "Draw structure"}
        </button>
      </form>

      {matchMode === "exact" ? (
        <p className="mt-2 flex items-start gap-2 px-1 text-xs leading-5 text-muted-foreground">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          Exact match compares the whole structure or identifier. Use <code className="rounded bg-muted px-1">*</code> for
          any run of characters and <code className="rounded bg-muted px-1">?</code> for a single character in name or
          identifier searches.
        </p>
      ) : (
        <p className="mt-2 flex items-start gap-2 px-1 text-xs leading-5 text-muted-foreground">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          Substructure search returns a <strong className="font-semibold text-foreground">page of</strong> registry
          molecules containing the drawn or typed fragment, lowest molecule ID first — not a complete or ranked list.
          It only sees molecules that already have a substructure fingerprint
          {meta && meta.totalMolecules > 0
            ? ` (${meta.indexedMolecules.toLocaleString()} of ${meta.totalMolecules.toLocaleString()} molecules, ${Math.round((meta.indexedMolecules / meta.totalMolecules) * 100)}%)`
            : ""}
          , so an absent result does not prove the fragment is absent from the registry.
        </p>
      )}

      {showSketcher && (
        <section className="mt-4 rounded-2xl border border-border bg-card/60 p-5 shadow-lg backdrop-blur-md sm:p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg font-bold text-foreground">Draw a structure</h2>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => sketcherRef.current?.clear()}
                className="rounded-xl border border-border px-4 py-2 text-sm font-semibold text-foreground transition hover:bg-muted/30"
              >
                Clear
              </button>
              <button
                type="button"
                onClick={() => void searchDrawnStructure()}
                disabled={loading}
                className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-emerald-500 disabled:cursor-wait disabled:opacity-60"
              >
                <Search className="h-4 w-4" />
                {matchMode === "substructure" ? "Search for this fragment" : "Search this structure"}
              </button>
            </div>
          </div>

          <div className="mt-4">
            <MoleculeSketcher ref={sketcherRef} onError={setSketcherError} />
          </div>

          {sketcherError && (
            <p role="alert" className="mt-3 rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-700 dark:text-red-200">
              {sketcherError}
            </p>
          )}
        </section>
      )}

      {error && (
        <div role="alert" className="mt-6 rounded-xl border border-red-500/30 bg-red-500/10 px-5 py-4 text-sm text-red-700 dark:text-red-200">
          {error}
        </div>
      )}

      {!searched && !error && (
        <section className="mt-8 grid gap-4 sm:grid-cols-3">
          {[
            ["Identifiers", "Exact database and source compound identifiers, with wildcards"],
            ["Chemistry", "Canonical SMILES, InChIKey, and substructure fragments"],
            ["Provenance", "Normalization status alongside every match"],
          ].map(([title, body]) => (
            <div key={title} className="rounded-2xl border border-border bg-card/60 p-5 shadow-lg">
              <h2 className="text-lg font-bold text-foreground">{title}</h2>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">{body}</p>
            </div>
          ))}
        </section>
      )}

      {searched && !loading && !error && results.length === 0 && (
        <section className="mt-8 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-14 text-center">
          <h2 className="text-xl font-bold text-foreground">No matching molecules</h2>
          <p className="mt-2 text-muted-foreground">
            {matchMode === "substructure"
              ? meta && meta.totalMolecules > 0
                ? `No match among the ${meta.indexedMolecules.toLocaleString()} molecules currently indexed for substructure search. The remaining ${(meta.totalMolecules - meta.indexedMolecules).toLocaleString()} are not searchable this way yet, so this does not rule the fragment out.`
                : "No indexed molecule contains this fragment. Indexing is incomplete, so this does not rule the fragment out."
              : "Try a shorter identifier fragment, a wildcard (* or ?), or browse without a query. If this is a new structure, you can register and predict it directly below."}
          </p>
          {matchMode === "exact" && query.trim() && (
            <div className="mt-6">
              <button
                type="button"
                onClick={() => void predictNewStructure()}
                disabled={adhocStatus === "submitting"}
                className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-6 py-3 font-semibold text-white transition hover:bg-emerald-500 disabled:cursor-wait disabled:opacity-60"
              >
                <Activity className="h-4 w-4" />
                {adhocStatus === "submitting" ? "Registering and predicting..." : "Register this structure and predict"}
              </button>
              {adhocStatus === "error" && adhocError && (
                <p role="alert" className="mx-auto mt-3 max-w-md text-sm text-red-600 dark:text-red-300">{adhocError}</p>
              )}
            </div>
          )}
        </section>
      )}

      {results.length > 0 && (
        <section className="mt-8">
          <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-xl font-bold text-foreground">Registry matches</h2>
            <p className="text-xs uppercase tracking-wider text-muted-foreground">
              {meta?.truncated
                ? `showing first ${results.length}`
                : `${results.length} result${results.length === 1 ? "" : "s"}`}
            </p>
          </div>

          {meta?.truncated && (
            <p className="mb-4 flex items-start gap-2 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-xs leading-5 text-amber-800 dark:text-amber-200">
              <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              More matches exist beyond this page. These are the {results.length} lowest-numbered indexed molecules
              containing the fragment, not the best or only matches. Narrow the fragment to see a more specific set.
            </p>
          )}

          <div className="overflow-hidden rounded-2xl border border-border bg-card/60 shadow-lg">
            <div className="flex items-center justify-between gap-3 border-b border-border/50 bg-muted/30 px-5 py-3">
              <label className="inline-flex items-center gap-2 text-sm font-medium text-foreground">
                <input
                  type="checkbox"
                  checked={selectedIds.size === results.length && results.length > 0}
                  onChange={toggleSelectAllResults}
                  className="h-4 w-4 rounded border-border accent-blue-600"
                />
                Select all
              </label>
              <div className="flex items-center gap-3">
                <span className="text-sm text-muted-foreground">{selectedIds.size} selected</span>
                <button
                  type="button"
                  onClick={predictSelected}
                  disabled={selectedIds.size === 0}
                  className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <Activity className="h-3.5 w-3.5" />
                  Predict selected
                </button>
              </div>
            </div>
            <div className="divide-y divide-border">
              {results.map((molecule) => {
                const checked = selectedIds.has(molecule.moleculeId);
                return (
                  <article
                    key={molecule.moleculeId}
                    onClick={() => toggleSelected(molecule.moleculeId)}
                    className={`grid cursor-pointer gap-3 px-5 py-4 transition-colors sm:grid-cols-[auto_auto_minmax(0,1.3fr)_100px_minmax(0,1.3fr)_minmax(0,1.6fr)] sm:items-center sm:gap-4 ${checked ? "bg-emerald-500/5" : "hover:bg-muted/30"}`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleSelected(molecule.moleculeId)}
                      onClick={(event) => event.stopPropagation()}
                      className="h-4 w-4 rounded border-border accent-emerald-600"
                    />
                    <MoleculeStructure moleculeId={molecule.moleculeId} size={56} />
                    <div className="min-w-0">
                      <p className="text-[10px] font-semibold uppercase tracking-wider text-blue-500">
                        Molecule {molecule.moleculeId}
                      </p>
                      <p className="truncate text-sm font-bold text-foreground" title={displayValue(molecule.name || molecule.sourceIdentifier)}>
                        {displayValue(molecule.name || molecule.sourceIdentifier)}
                      </p>
                    </div>
                    <span className="w-fit rounded-full bg-emerald-500/10 px-2.5 py-1 text-[10px] font-semibold uppercase text-emerald-700 dark:text-emerald-300">
                      {displayValue(molecule.normalizationStatus)}
                    </span>
                    <p className="truncate font-mono text-xs text-muted-foreground" title={displayValue(molecule.inchiKey)}>
                      {displayValue(molecule.inchiKey)}
                    </p>
                    <p className="truncate font-mono text-xs text-muted-foreground" title={displayValue(molecule.canonicalSmiles)}>
                      {displayValue(molecule.canonicalSmiles)}
                    </p>
                  </article>
                );
              })}
            </div>
          </div>
        </section>
      )}
    </section>
  );
}
