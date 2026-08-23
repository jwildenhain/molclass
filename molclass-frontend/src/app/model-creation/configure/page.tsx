"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import { RefreshCw } from "lucide-react";
import { fetchWithTimeout } from "@/lib/fetchWithTimeout";

type ClassLabel = { label: string; supportCount: number };
type Target = {
  propertyId: number;
  name: string;
  sqlType: string;
  presentCount: number;
  blankCount: number;
  distinctCount: number;
  classLabels: ClassLabel[];
};
type Dataset = {
  datasetId: number;
  name: string;
  originalFilename: string | null;
  description: string | null;
  status: string;
  importedRecords: number;
  failedRecords: number;
  notProcessedRecords: number;
  partialAcknowledgementRequired: boolean;
  targets: Target[];
};
type Option = { code: string; name: string; description: string };
type Profile = {
  featureProfileId: number;
  code: string;
  version: string;
  description: string | null;
  status: string;
};
type ModelOptions = {
  algorithms: Option[];
  featureSelections: Option[];
  featureProfiles: Profile[];
};
type CreationResult = {
  modelDefinitionId: number;
  status: string;
  datasetId: number;
  targetProperty: string;
  declaredClassLabels: string[];
  positiveClassLabel: string;
};

function payloadMessage(payload: unknown, fallback: string) {
  if (!payload || typeof payload !== "object") return fallback;
  const detail = (payload as Record<string, unknown>).detail;
  if (typeof detail === "string") return detail;
  if (detail && typeof detail === "object") {
    const value = detail as Record<string, unknown>;
    if (typeof value.code === "string") return value.code.replaceAll("_", " ");
  }
  return fallback;
}

async function apiJson<T>(response: Response, fallback: string): Promise<T> {
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(payloadMessage(payload, fallback));
  return payload as T;
}

function minorityLabel(labels: ClassLabel[]) {
  return labels.reduce<ClassLabel | null>(
    (smallest, item) => !smallest || item.supportCount < smallest.supportCount ? item : smallest,
    null,
  )?.label ?? "";
}

function ConfigureModelForm() {
  const searchParams = useSearchParams();
  const datasetId = Number(searchParams.get("dataset_id"));
  const datasetIdValid = Number.isSafeInteger(datasetId) && datasetId > 0;
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [options, setOptions] = useState<ModelOptions | null>(null);
  const [targetPropertyId, setTargetPropertyId] = useState("");
  const [featureProfileId, setFeatureProfileId] = useState("");
  const [algorithmCode, setAlgorithmCode] = useState("RandomForest");
  const [featureSelectionCode, setFeatureSelectionCode] = useState("CfsSubsetEval");
  const [positiveClass, setPositiveClass] = useState("");
  const [modelName, setModelName] = useState("");
  const [createdBy, setCreatedBy] = useState("web-operator");
  const [partialAcknowledged, setPartialAcknowledged] = useState(false);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<CreationResult | null>(null);
  const [retryToken, setRetryToken] = useState(0);

  const retry = useCallback(() => {
    setLoading(true);
    setError("");
    setRetryToken((token) => token + 1);
  }, []);

  useEffect(() => {
    if (!datasetIdValid) return;
    let cancelled = false;
    const load = async () => {
      try {
        const [datasetResponse, optionsResponse] = await Promise.all([
          fetchWithTimeout(`/api/v1/model-datasets/${datasetId}`, { cache: "no-store" }),
          fetchWithTimeout("/api/v1/model-options", { cache: "no-store" }),
        ]);
        const nextDataset = await apiJson<Dataset>(datasetResponse, "The selected dataset is not model-ready.");
        const nextOptions = await apiJson<ModelOptions>(optionsResponse, "Model options are unavailable.");
        if (cancelled) return;
        const target = nextDataset.targets[0];
        const profile = nextOptions.featureProfiles.find((item) => item.code === "ALL") ?? nextOptions.featureProfiles[0];
        setDataset(nextDataset);
        setOptions(nextOptions);
        setTargetPropertyId(String(target.propertyId));
        setPositiveClass(minorityLabel(target.classLabels));
        setFeatureProfileId(profile ? String(profile.featureProfileId) : "");
        setModelName(`${nextDataset.name} - ${target.name}`.slice(0, 255));
      } catch (loadError) {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Model configuration could not be loaded.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [datasetId, datasetIdValid, retryToken]);

  const selectedTarget = useMemo(
    () => dataset?.targets.find((target) => target.propertyId === Number(targetPropertyId)) ?? null,
    [dataset, targetPropertyId],
  );
  const selectedProfile = useMemo(
    () => options?.featureProfiles.find((profile) => profile.featureProfileId === Number(featureProfileId)) ?? null,
    [options, featureProfileId],
  );
  const selectedAlgorithm = useMemo(
    () => options?.algorithms.find((algorithm) => algorithm.code === algorithmCode) ?? null,
    [options, algorithmCode],
  );
  const selectedFeatureSelection = useMemo(
    () => options?.featureSelections.find((selection) => selection.code === featureSelectionCode) ?? null,
    [options, featureSelectionCode],
  );

  const changeTarget = (value: string) => {
    setTargetPropertyId(value);
    const target = dataset?.targets.find((item) => item.propertyId === Number(value));
    if (target) setPositiveClass(minorityLabel(target.classLabels));
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!dataset || !selectedTarget || !positiveClass || !featureProfileId) return;
    setSubmitting(true);
    setError("");
    try {
      const response = await fetchWithTimeout("/api/v1/model-definitions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          dataset_id: dataset.datasetId,
          target_property_id: selectedTarget.propertyId,
          feature_profile_id: Number(featureProfileId),
          model_name: modelName.trim(),
          algorithm_code: algorithmCode,
          feature_selection_code: featureSelectionCode,
          positive_class_label: positiveClass,
          partial_dataset_acknowledged: partialAcknowledged,
          created_by: createdBy.trim(),
        }),
      });
      setResult(await apiJson<CreationResult>(response, "The model definition could not be created."));
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "The model definition could not be created.");
    } finally {
      setSubmitting(false);
    }
  };

  if (!datasetIdValid) return <ErrorPanel message="A valid dataset_id query parameter is required." />;
  if (loading) return <div className="p-12 text-center text-muted-foreground">Loading verified model configuration...</div>;
  if (error && !dataset) return <ErrorPanel message={error} onRetry={retry} />;
  if (!dataset || !options || !selectedTarget) return <ErrorPanel message="The selected dataset has no supported target property." />;

  if (result) {
    return (
      <div className="space-y-6">
        <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/10 p-7">
          <p className="font-mono text-xs uppercase tracking-[0.24em] text-emerald-700 dark:text-emerald-300">Definition accepted</p>
          <h2 className="mt-3 text-2xl font-bold text-foreground">Model definition {result.modelDefinitionId} is pending rebuild.</h2>
          <p className="mt-3 text-sm leading-6 text-muted-foreground">
            Feature generation and Weka training run through the controlled worker pipeline. The resulting build will require explicit evaluation and approval before publication.
          </p>
          <div className="mt-5 flex flex-wrap gap-2 font-mono text-xs text-emerald-800 dark:text-emerald-200">
            <span className="rounded-lg bg-black/5 px-3 py-2 dark:bg-black/20">status {result.status}</span>
            <span className="rounded-lg bg-black/5 px-3 py-2 dark:bg-black/20">target {result.targetProperty}</span>
            <span className="rounded-lg bg-black/5 px-3 py-2 dark:bg-black/20">positive {result.positiveClassLabel}</span>
          </div>
        </div>
        <Link href="/model-creation" className="inline-flex rounded-xl border border-border px-5 py-3 font-semibold text-foreground transition hover:border-amber-400/50 hover:bg-amber-400/5">
          Return to datasets
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="space-y-7">
      <div className="rounded-2xl border border-border bg-muted/20 p-5">
        <p className="font-mono text-xs uppercase tracking-wider text-muted-foreground">Dataset {dataset.datasetId}</p>
        <h2 className="mt-2 text-xl font-bold text-foreground">{dataset.name}</h2>
        <p className="mt-2 text-sm text-muted-foreground">{dataset.importedRecords.toLocaleString()} imported records / {dataset.status}</p>
      </div>

      <div className="grid gap-5 sm:grid-cols-2">
        <SelectField label="Target property" value={targetPropertyId} onChange={changeTarget}>
          {dataset.targets.map((target) => <option key={target.propertyId} value={target.propertyId}>{target.name} / {target.distinctCount} classes</option>)}
        </SelectField>
        <SelectField label="Feature profile" value={featureProfileId} onChange={setFeatureProfileId}>
          {options.featureProfiles.map((profile) => <option key={profile.featureProfileId} value={profile.featureProfileId}>{profile.code} / {profile.version}</option>)}
        </SelectField>
        <SelectField label="Weka algorithm" value={algorithmCode} onChange={setAlgorithmCode}>
          {options.algorithms.map((algorithm) => <option key={algorithm.code} value={algorithm.code}>{algorithm.name}</option>)}
        </SelectField>
        <SelectField label="Feature selection" value={featureSelectionCode} onChange={setFeatureSelectionCode}>
          {options.featureSelections.map((selection) => <option key={selection.code} value={selection.code}>{selection.name}</option>)}
        </SelectField>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <InfoCard label="Profile" value={`${selectedProfile?.code ?? ""} / ${selectedProfile?.status ?? ""}`} detail={selectedProfile?.description ?? "Versioned CDK feature contract."} />
        <InfoCard label="Training path" value={selectedAlgorithm?.name ?? algorithmCode} detail={`${selectedAlgorithm?.description ?? ""} ${selectedFeatureSelection?.description ?? ""}`} />
      </div>

      <fieldset>
        <legend className="text-sm font-semibold text-foreground">Positive class</legend>
        <p className="mt-1 text-sm text-muted-foreground">Choose the class whose probability and recall should be treated as positive during evaluation.</p>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {selectedTarget.classLabels.map((item) => (
            <label key={item.label} className={`cursor-pointer rounded-xl border p-4 transition ${positiveClass === item.label ? "border-amber-400 bg-amber-400/10" : "border-border bg-muted/10"}`}>
              <div className="flex items-start gap-3">
                <input type="radio" name="positiveClass" value={item.label} checked={positiveClass === item.label} onChange={() => setPositiveClass(item.label)} className="mt-1 h-4 w-4 accent-amber-400" />
                <span>
                  <span className="block font-semibold text-foreground">{item.label}</span>
                  <span className="mt-1 block font-mono text-xs text-muted-foreground">{item.supportCount.toLocaleString()} records</span>
                </span>
              </div>
            </label>
          ))}
        </div>
      </fieldset>

      <div className="grid gap-5 sm:grid-cols-2">
        <TextField label="Model name" value={modelName} onChange={setModelName} maxLength={255} />
        <TextField label="Created by" value={createdBy} onChange={setCreatedBy} maxLength={255} />
      </div>

      {dataset.partialAcknowledgementRequired && (
        <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-amber-400/30 bg-amber-400/10 p-4">
          <input type="checkbox" checked={partialAcknowledged} onChange={(event) => setPartialAcknowledged(event.target.checked)} className="mt-0.5 h-4 w-4 accent-amber-400" />
          <span className="text-sm text-foreground">Acknowledge that this dataset contains failed or not-processed import records.</span>
        </label>
      )}

      {error && <ErrorPanel message={error} />}
      <button
        type="submit"
        disabled={submitting || !modelName.trim() || !createdBy.trim() || !positiveClass || (dataset.partialAcknowledgementRequired && !partialAcknowledged)}
        className="w-full rounded-xl bg-amber-400 px-5 py-3.5 font-bold text-slate-950 transition hover:bg-amber-300 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {submitting ? "Creating definition..." : "Create pending model definition"}
      </button>
    </form>
  );
}

function SelectField({ label, value, onChange, children }: { label: string; value: string; onChange: (value: string) => void; children: React.ReactNode }) {
  return (
    <label className="space-y-2 text-sm font-semibold text-foreground">
      {label}
      <select value={value} onChange={(event) => onChange(event.target.value)} className="w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-amber-400">
        {children}
      </select>
    </label>
  );
}

function TextField({ label, value, onChange, maxLength }: { label: string; value: string; onChange: (value: string) => void; maxLength: number }) {
  return (
    <label className="space-y-2 text-sm font-semibold text-foreground">
      {label}
      <input value={value} onChange={(event) => onChange(event.target.value)} maxLength={maxLength} required className="w-full rounded-xl border border-border bg-background/70 px-4 py-3 font-normal outline-none transition focus:border-amber-400" />
    </label>
  );
}

function InfoCard({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <div className="rounded-xl border border-border bg-muted/20 p-4">
      <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className="mt-2 font-semibold text-foreground">{value}</p>
      <p className="mt-2 text-xs leading-5 text-muted-foreground">{detail}</p>
    </div>
  );
}

function ErrorPanel({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-700 dark:text-red-200"
    >
      <span>{message}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="inline-flex items-center gap-1.5 rounded-lg border border-red-500/40 px-3 py-1.5 text-xs font-semibold text-red-700 transition hover:bg-red-500/10 dark:text-red-200"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Retry
        </button>
      )}
    </div>
  );
}

export default function ConfigureModelPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-10 sm:px-6 lg:py-14">
      <div className="mb-8">
        <p className="font-mono text-xs uppercase tracking-[0.28em] text-amber-500">Controlled training</p>
        <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Define the model before the worker builds it.</h1>
        <p className="mt-4 max-w-2xl text-sm leading-6 text-muted-foreground">Every definition records its dataset, target, class order, CDK profile, Weka algorithm, and operator.</p>
      </div>
      <section className="rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
        <Suspense fallback={<div className="p-12 text-center text-muted-foreground">Loading model configuration...</div>}>
          <ConfigureModelForm />
        </Suspense>
      </section>
    </main>
  );
}
