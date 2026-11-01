"use client";

import { useEffect, useRef, useState } from "react";
import { fetchWithTimeout } from "@/lib/fetchWithTimeout";

type Progress = { stage: string; percent: number; completed: number; total: number; detail: string };
type Job = { status: string; runstep?: string; error_message?: string | null; attempt_count?: number; progress?: Progress | null; result?: { moleculeId?: number } | null };
const stages = [
  ["REGISTER_MOLECULE", "Register structure"],
  ["COMPUTE_DESCRIPTORS", "Calculate molecular properties"],
  ["COMPUTE_FINGERPRINTS", "Create fingerprints"],
  ["SAVE_FEATURES", "Save calculated features"],
  ["PREDICT", "Run prediction"],
] as const;

export function RegistrationProgress({ jobId, onComplete }: { jobId: number; onComplete: (id: number) => void }) {
  const [job, setJob] = useState<Job | null>(null);
  const [connectionError, setConnectionError] = useState(false);
  const onCompleteRef = useRef(onComplete);
  useEffect(() => { onCompleteRef.current = onComplete; }, [onComplete]);
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const response = await fetchWithTimeout(`/api/v1/jobs/${jobId}`, { cache: "no-store" });
        if (response.status === 404) {
          if (!cancelled) setJob({ status: "FAILED", error_message: "This registration job was not found." });
          return;
        }
        if (!response.ok) throw new Error("Status unavailable");
        const next = await response.json() as Job;
        if (cancelled) return;
        setConnectionError(false);
        setJob(next);
        if (next.status === "SUCCEEDED") {
          const moleculeId = next.result?.moleculeId;
          if (moleculeId) timer = setTimeout(() => { if (!cancelled) onCompleteRef.current(moleculeId); }, 600);
          else setJob({ ...next, status: "FAILED", error_message: "The job completed but no molecule ID was returned." });
          return;
        }
        if (["FAILED", "CANCELLED"].includes(next.status)) return;
      } catch {
        if (cancelled) return;
        setConnectionError(true);
      }
      if (!cancelled) timer = setTimeout(poll, 1500);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [jobId]);

  const done = job?.status === "SUCCEEDED";
  const failed = job?.status === "FAILED" || job?.status === "CANCELLED";
  const stage = done ? "COMPLETE" : job?.progress?.stage || job?.runstep || "QUEUED";
  const index = done ? stages.length : stages.findIndex(([id]) => id === stage);
  const percent = done ? 100 : Math.max(0, Math.min(99, job?.progress?.percent ?? 0));
  return (
    <section aria-label="Registration progress" className="mt-6 rounded-2xl border border-blue-500/30 bg-card/70 p-5 text-left">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-bold">Registration progress</h2>
        <span className="text-sm text-muted-foreground">Job #{jobId}{job?.attempt_count ? ` · attempt ${job.attempt_count}` : ""}</span>
      </div>
      <p role="status" className="mt-3 text-sm">
        {failed ? "Stopped" : done ? "Completed — opening model selection…" : stage === "QUEUED" ? "Queued — waiting for a worker. No need to submit again." : job?.progress?.detail || "Processing structure…"}
        {!failed && ` · ${percent}%`}
      </p>
      <progress aria-label="Registration completion" max={100} value={percent} className="mt-3 h-3 w-full accent-blue-600" />
      <ol className="mt-4 space-y-2 text-sm">
        {stages.map(([id, label], i) => (
          <li key={id} className="flex flex-wrap justify-between gap-2">
            <span>{label}</span>
            <span className="text-muted-foreground">{i < index ? "Completed" : i === index ? failed ? "Stopped" : job?.progress ? `${job.progress.completed} / ${job.progress.total}` : "In progress" : "Waiting"}</span>
          </li>
        ))}
      </ol>
      {failed && <p role="alert" className="mt-3 text-sm text-red-600">{job?.error_message || "This job was cancelled."}</p>}
      {connectionError && <p role="alert" className="mt-3 text-sm text-amber-700">Connection interrupted. Retrying status checks; your submitted job may still be running.</p>}
      <p className="mt-3 text-xs text-muted-foreground">Progress measures completed calculation steps, not time remaining. Property counts include calculators that finish with unavailable values. You can refresh this page to resume monitoring. Clearing the search leaves the submitted job running.</p>
    </section>
  );
}
