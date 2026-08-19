"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { ChangeEvent, DragEvent, FormEvent } from "react";

type Phase = "select" | "uploading" | "analyzing" | "configure" | "queued";

type PropertyAnalysis = {
  name: string;
  presentCount: number;
  missingCount: number;
  blankCount: number;
  distinctCount: number;
  inferredSqlType: string;
  storageRecommendation: string;
  identifierEligible: boolean;
};

type SdfAnalysis = {
  totalRecords: number;
  validRecords: number;
  malformedRecords: number;
  autoSelectedIdentifier: string | null;
  properties: PropertyAnalysis[];
  warnings: string[];
};

type UploadDetails = {
  uploadId: number;
  originalFilename: string;
  contentSha256: string;
  contentLength: number;
  status: string;
  analysis: SdfAnalysis | null;
  analysisError: { code?: string; message?: string } | null;
  job: {
    job_id: number;
    status: string;
    runstep: string;
    attempt_count: number;
    error_code: string | null;
    error_message: string | null;
  } | null;
};

type UploadAccepted = {
  uploadId: number;
  jobId: number;
  status: string;
};

type ImportAccepted = {
  importRunId: number;
  jobId: number;
  datasetId: number;
  status: string;
  idempotentReplay: boolean;
};

const phases: Array<{ key: Phase; number: string; label: string }> = [
  { key: "select", number: "01", label: "Upload" },
  { key: "analyzing", number: "02", label: "Analyze" },
  { key: "configure", number: "03", label: "Properties" },
  { key: "queued", number: "04", label: "Import" },
];

function phaseIndex(phase: Phase) {
  if (phase === "uploading") return 0;
  return phases.findIndex((item) => item.key === phase);
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function messageFromPayload(payload: unknown, fallback: string) {
  if (!payload || typeof payload !== "object") return fallback;
  const body = payload as Record<string, unknown>;
  const detail = body.detail;
  if (typeof detail === "string") return detail;
  if (detail && typeof detail === "object") {
    const value = detail as Record<string, unknown>;
    if (typeof value.message === "string") return value.message;
    if (typeof value.code === "string") return value.code.replaceAll("_", " ");
  }
  return fallback;
}

async function apiJson<T>(response: Response, fallback: string): Promise<T> {
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(messageFromPayload(payload, fallback));
  return payload as T;
}

export default function UploadPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [phase, setPhase] = useState<Phase>("select");
  const [file, setFile] = useState<File | null>(null);
  const [uploadId, setUploadId] = useState<number | null>(null);
  const [upload, setUpload] = useState<UploadDetails | null>(null);
  const [analysis, setAnalysis] = useState<SdfAnalysis | null>(null);
  const [selectedProperties, setSelectedProperties] = useState<Set<string>>(new Set());
  const [identifier, setIdentifier] = useState("");
  const [identifierConfirmed, setIdentifierConfirmed] = useState(false);
  const [datasetName, setDatasetName] = useState("");
  const [description, setDescription] = useState("");
  const [importResult, setImportResult] = useState<ImportAccepted | null>(null);
  const [submittingImport, setSubmittingImport] = useState(false);
  const [error, setError] = useState("");

  const currentStep = phaseIndex(phase);
  const eligibleIdentifiers = useMemo(
    () => analysis?.properties.filter((property) => property.identifierEligible) ?? [],
    [analysis],
  );

  const reset = () => {
    setPhase("select");
    setFile(null);
    setUploadId(null);
    setUpload(null);
    setAnalysis(null);
    setSelectedProperties(new Set());
    setIdentifier("");
    setIdentifierConfirmed(false);
    setDatasetName("");
    setDescription("");
    setImportResult(null);
    setError("");
    if (inputRef.current) inputRef.current.value = "";
  };

  const acceptFile = (candidate: File | undefined) => {
    if (!candidate) return;
    if (!candidate.name.toLowerCase().endsWith(".sdf")) {
      setError("Select an SDF file with the .sdf extension.");
      return;
    }
    reset();
    setFile(candidate);
    setDatasetName(candidate.name.replace(/\.sdf$/i, ""));
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    acceptFile(event.dataTransfer.files[0]);
  };

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    acceptFile(event.target.files?.[0]);
  };

  const initializeAnalysis = (next: SdfAnalysis) => {
    const autoIdentifier = next.autoSelectedIdentifier
      ?? next.properties.find((property) => property.identifierEligible)?.name
      ?? "";
    setAnalysis(next);
    setSelectedProperties(new Set(next.properties.map((property) => property.name)));
    setIdentifier(autoIdentifier);
    setIdentifierConfirmed(false);
    setError("");
    setPhase("configure");
  };

  useEffect(() => {
    if (phase !== "analyzing" || uploadId === null) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const response = await fetch(`/api/v1/uploads/${uploadId}`, { cache: "no-store" });
        const details = await apiJson<UploadDetails>(response, "Could not read upload status.");
        if (cancelled) return;
        setUpload(details);

        if (details.status === "ANALYZED" && details.analysis) {
          initializeAnalysis(details.analysis);
          return;
        }

        const failed = details.status === "ANALYSIS_FAILED"
          || details.job?.status === "FAILED"
          || details.job?.status === "CANCELLED";
        if (failed) {
          setError(
            details.analysisError?.message
              ?? details.job?.error_message
              ?? "SDF analysis failed. The upload remains recorded for diagnosis.",
          );
          setPhase("select");
          return;
        }

        setError("");
        timer = setTimeout(poll, 1500);
      } catch (pollError) {
        if (cancelled) return;
        setError(pollError instanceof Error ? pollError.message : "Could not read upload status.");
        timer = setTimeout(poll, 3000);
      }
    };

    void poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [phase, uploadId]);

  const submitUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!file) return;

    setError("");
    setPhase("uploading");
    const form = new FormData();
    form.append("file", file);

    try {
      const response = await fetch("/api/v1/uploads", { method: "POST", body: form });
      const accepted = await apiJson<UploadAccepted>(response, "The SDF upload was rejected.");
      if (!Number.isSafeInteger(accepted.uploadId)) throw new Error("The API returned an invalid upload id.");
      setUploadId(accepted.uploadId);
      setPhase("analyzing");
    } catch (uploadError) {
      setError(uploadError instanceof Error ? uploadError.message : "The SDF upload failed.");
      setPhase("select");
    }
  };

  const chooseIdentifier = (name: string) => {
    setIdentifier(name);
    setIdentifierConfirmed(false);
    setSelectedProperties((current) => new Set(current).add(name));
  };

  const toggleProperty = (name: string) => {
    if (name === identifier) return;
    setSelectedProperties((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const submitImport = async (event: FormEvent) => {
    event.preventDefault();
    if (uploadId === null || !identifier || !identifierConfirmed || !datasetName.trim()) return;

    setSubmittingImport(true);
    setError("");
    try {
      const response = await fetch(`/api/v1/uploads/${uploadId}/imports`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dataset_name: datasetName.trim(),
          description: description.trim() || null,
          identifier_property: identifier,
          identifier_confirmed: true,
          selected_properties: Array.from(selectedProperties),
          created_by: "web-operator",
        }),
      });
      const accepted = await apiJson<ImportAccepted>(response, "The import could not be queued.");
      setImportResult(accepted);
      setPhase("queued");
    } catch (importError) {
      setError(importError instanceof Error ? importError.message : "The import could not be queued.");
    } finally {
      setSubmittingImport(false);
    }
  };

  return (
    <main className="mx-auto max-w-6xl px-4 py-10 sm:px-6 lg:py-14">
      <section className="relative overflow-hidden rounded-3xl border border-border bg-card/70 shadow-2xl backdrop-blur-xl">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top_right,rgba(14,165,233,0.15),transparent_34%),radial-gradient(circle_at_bottom_left,rgba(245,158,11,0.10),transparent_30%)]" />
        <div className="relative border-b border-border px-6 py-8 sm:px-10">
          <p className="mb-3 font-mono text-xs uppercase tracking-[0.28em] text-sky-400">Durable dataset intake</p>
          <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <h1 className="max-w-3xl text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Inspect the chemistry before it enters the model pipeline.</h1>
              <p className="mt-4 max-w-2xl text-sm leading-6 text-muted-foreground sm:text-base">
                MolClass analyzes every SDF record first, proposes a complete unique identifier, and imports all properties unless you explicitly remove them.
              </p>
            </div>
            <div className="rounded-2xl border border-sky-500/20 bg-sky-500/10 px-5 py-4 text-sm text-sky-900 dark:text-sky-100">
              Per-record transactions<br />Failed molecules do not stop the dataset
            </div>
          </div>
        </div>

        <div className="relative grid gap-10 px-6 py-8 sm:px-10 lg:grid-cols-[220px_minmax(0,1fr)] lg:py-10">
          <nav aria-label="Import progress" className="space-y-2">
            {phases.map((item, index) => {
              const active = index === currentStep;
              const complete = index < currentStep;
              return (
                <div
                  key={item.key}
                  className={`flex items-center gap-3 rounded-xl border px-4 py-3 transition-colors ${
                    active
                      ? "border-sky-500/50 bg-sky-500/10 text-foreground"
                      : complete
                        ? "border-emerald-500/20 bg-emerald-500/5 text-emerald-300"
                        : "border-transparent text-muted-foreground"
                  }`}
                >
                  <span className="font-mono text-xs">{complete ? "OK" : item.number}</span>
                  <span className="text-sm font-semibold">{item.label}</span>
                </div>
              );
            })}
          </nav>

          <div className="min-w-0">
            {(phase === "select" || phase === "uploading" || phase === "analyzing") && (
              <form onSubmit={submitUpload} className="space-y-6">
                <div
                  className="group cursor-pointer rounded-2xl border-2 border-dashed border-border bg-muted/20 p-8 text-center transition hover:border-sky-500/60 hover:bg-sky-500/5 sm:p-12"
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={handleDrop}
                  onClick={() => phase === "select" && inputRef.current?.click()}
                >
                  <input
                    ref={inputRef}
                    id="file-upload"
                    type="file"
                    className="hidden"
                    accept=".sdf,chemical/x-mdl-sdfile"
                    onChange={handleFileChange}
                    disabled={phase !== "select"}
                  />
                  <div className="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-2xl border border-sky-500/30 bg-sky-500/10 font-mono text-sm font-bold text-sky-300">SDF</div>
                  <p className="text-lg font-semibold text-foreground">
                    {file ? file.name : "Drop an SDF file here"}
                  </p>
                  <p className="mt-2 text-sm text-muted-foreground">
                    {file ? formatBytes(file.size) : "or click to select one from this computer"}
                  </p>
                </div>

                {phase === "select" && (
                  <button
                    type="submit"
                    disabled={!file}
                    className="w-full rounded-xl bg-sky-500 px-5 py-3.5 font-bold text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Upload and analyze
                  </button>
                )}

                {(phase === "uploading" || phase === "analyzing") && (
                  <div className="rounded-2xl border border-border bg-muted/20 p-5" aria-live="polite">
                    <div className="flex items-center justify-between gap-4">
                      <div>
                        <p className="font-semibold text-foreground">
                          {phase === "uploading" ? "Securing upload" : "Analyzing every record"}
                        </p>
                        <p className="mt-1 text-sm text-muted-foreground">
                          {upload?.job?.runstep?.replaceAll("_", " ") ?? "This page will advance when analysis is complete."}
                        </p>
                      </div>
                      <span className="h-3 w-3 animate-pulse rounded-full bg-amber-400 shadow-[0_0_20px_rgba(251,191,36,0.8)]" />
                    </div>
                  </div>
                )}
              </form>
            )}

            {phase === "configure" && analysis && (
              <form onSubmit={submitImport} className="space-y-7">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Metric label="Records" value={analysis.totalRecords.toLocaleString()} />
                  <Metric label="Valid" value={analysis.validRecords.toLocaleString()} tone="emerald" />
                  <Metric label="Malformed" value={analysis.malformedRecords.toLocaleString()} tone={analysis.malformedRecords ? "amber" : "neutral"} />
                </div>

                <div className="grid gap-5 sm:grid-cols-2">
                  <label className="space-y-2 text-sm font-semibold text-foreground">
                    Dataset name
                    <input
                      value={datasetName}
                      onChange={(event) => setDatasetName(event.target.value)}
                      maxLength={255}
                      required
                      className="w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-sky-500"
                    />
                  </label>
                  <label className="space-y-2 text-sm font-semibold text-foreground">
                    Dataset description
                    <input
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      maxLength={10000}
                      placeholder="Optional provenance or assay context"
                      className="w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-sky-500"
                    />
                  </label>
                </div>

                <div>
                  <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                    <div>
                      <h2 className="text-xl font-bold text-foreground">Property manifest</h2>
                      <p className="mt-1 text-sm text-muted-foreground">All properties are selected by default. The identifier cannot be removed.</p>
                    </div>
                    <span className="font-mono text-xs text-sky-300">{selectedProperties.size} of {analysis.properties.length} selected</span>
                  </div>

                  <div className="overflow-hidden rounded-2xl border border-border">
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[700px] text-left text-sm">
                        <thead className="bg-muted/40 text-xs uppercase tracking-wider text-muted-foreground">
                          <tr>
                            <th className="px-4 py-3">Import</th>
                            <th className="px-4 py-3">Identifier</th>
                            <th className="px-4 py-3">Property</th>
                            <th className="px-4 py-3">Coverage</th>
                            <th className="px-4 py-3">Distinct</th>
                            <th className="px-4 py-3">MySQL type</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {analysis.properties.map((property) => (
                            <tr key={property.name} className="bg-background/30">
                              <td className="px-4 py-3">
                                <input
                                  type="checkbox"
                                  checked={selectedProperties.has(property.name)}
                                  disabled={property.name === identifier}
                                  onChange={() => toggleProperty(property.name)}
                                  aria-label={`Import ${property.name}`}
                                  className="h-4 w-4 accent-sky-500"
                                />
                              </td>
                              <td className="px-4 py-3">
                                <input
                                  type="radio"
                                  name="identifier"
                                  checked={identifier === property.name}
                                  disabled={!property.identifierEligible}
                                  onChange={() => chooseIdentifier(property.name)}
                                  aria-label={`Use ${property.name} as identifier`}
                                  className="h-4 w-4 accent-amber-400"
                                />
                              </td>
                              <td className="px-4 py-3 font-semibold text-foreground">
                                {property.name}
                                {property.name === analysis.autoSelectedIdentifier && (
                                  <span className="ml-2 rounded-full bg-amber-400/10 px-2 py-0.5 text-[10px] uppercase tracking-wide text-amber-300">auto</span>
                                )}
                              </td>
                              <td className="px-4 py-3 text-muted-foreground">
                                {property.presentCount.toLocaleString()} / {analysis.totalRecords.toLocaleString()}
                              </td>
                              <td className="px-4 py-3 text-muted-foreground">{property.distinctCount.toLocaleString()}</td>
                              <td className="px-4 py-3 font-mono text-xs text-sky-300">{property.inferredSqlType}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                </div>

                {eligibleIdentifiers.length === 0 ? (
                  <div className="rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-200">
                    No complete unique property is available. Correct the source SDF before importing.
                  </div>
                ) : (
                  <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-amber-400/25 bg-amber-400/5 p-4">
                    <input
                      type="checkbox"
                      checked={identifierConfirmed}
                      onChange={(event) => setIdentifierConfirmed(event.target.checked)}
                      className="mt-0.5 h-4 w-4 accent-amber-400"
                    />
                    <span className="text-sm leading-6 text-foreground">
                      Confirm <strong>{identifier}</strong> as the non-null, unique compound identifier for this dataset.
                    </span>
                  </label>
                )}

                <button
                  type="submit"
                  disabled={!identifier || !identifierConfirmed || !datasetName.trim() || submittingImport}
                  className="w-full rounded-xl bg-amber-400 px-5 py-3.5 font-bold text-slate-950 transition hover:bg-amber-300 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {submittingImport ? "Queueing import..." : "Confirm manifest and queue import"}
                </button>
              </form>
            )}

            {phase === "queued" && importResult && (
              <div className="space-y-6">
                <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/10 p-7">
                  <p className="font-mono text-xs uppercase tracking-[0.24em] text-emerald-300">Import accepted</p>
                  <h2 className="mt-3 text-2xl font-bold text-foreground">Dataset {importResult.datasetId} is in the durable queue.</h2>
                  <p className="mt-3 max-w-2xl text-sm leading-6 text-muted-foreground">
                    Job {importResult.jobId} will commit one molecule record at a time. A malformed molecule is audited and skipped without rolling back successful records.
                  </p>
                  <div className="mt-5 flex flex-wrap gap-3 font-mono text-xs text-emerald-200">
                    <span className="rounded-lg bg-black/20 px-3 py-2">run {importResult.importRunId}</span>
                    <span className="rounded-lg bg-black/20 px-3 py-2">status {importResult.status}</span>
                    {importResult.idempotentReplay && <span className="rounded-lg bg-black/20 px-3 py-2">idempotent replay</span>}
                  </div>
                </div>
                <button type="button" onClick={reset} className="rounded-xl border border-border px-5 py-3 font-semibold text-foreground transition hover:border-sky-500/50 hover:bg-sky-500/5">
                  Import another dataset
                </button>
              </div>
            )}

            {error && (
              <div className="mt-6 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-200" role="alert">
                {error}
              </div>
            )}
          </div>
        </div>
      </section>
    </main>
  );
}

function Metric({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "emerald" | "amber" }) {
  const color = tone === "emerald" ? "text-emerald-300" : tone === "amber" ? "text-amber-300" : "text-foreground";
  return (
    <div className="rounded-xl border border-border bg-muted/20 p-4">
      <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className={`mt-2 font-mono text-2xl font-bold ${color}`}>{value}</p>
    </div>
  );
}
