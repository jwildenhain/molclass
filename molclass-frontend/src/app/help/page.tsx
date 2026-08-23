import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import {
  ArrowRight,
  Beaker,
  CheckCircle2,
  HelpCircle,
  Search as SearchIcon,
  UploadCloud,
  UserCheck,
} from "lucide-react";

export const metadata: Metadata = {
  title: "Help | MolClass V2",
  description: "A step-by-step walkthrough of the MolClass workflow, from uploading a dataset to running a prediction.",
};

/** A faithful, hand-built illustration of a real page's layout — not a literal screenshot. */
function PagePreview({ route, children }: { route: string; children: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-background/60 shadow-lg">
      <div className="flex items-center gap-2 border-b border-border bg-muted/40 px-4 py-2.5">
        <span className="h-2.5 w-2.5 rounded-full bg-red-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-amber-400/70" />
        <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/70" />
        <span className="ml-3 truncate rounded-md bg-background px-3 py-1 font-mono text-[11px] text-muted-foreground">
          molclass.local{route}
        </span>
      </div>
      <div className="p-4 sm:p-5">{children}</div>
    </div>
  );
}

function StepNumber({ n }: { n: number }) {
  return (
    <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-blue-600 font-mono text-sm font-bold text-white">
      {n}
    </span>
  );
}

export default function HelpPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-10 sm:px-6 lg:py-14">
      <header className="mb-10">
        <div className="inline-flex items-center gap-2 rounded-full border border-blue-500/30 bg-blue-500/10 px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.28em] text-blue-600 dark:text-blue-300">
          <HelpCircle className="h-3.5 w-3.5" />
          Getting started
        </div>
        <h1 className="mt-4 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Help</h1>
        <p className="mt-4 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
          The full MolClass workflow, from a raw SDF file to a published prediction model you can query.
          Every screen below is redrawn from the real page&rsquo;s layout to walk through what you&rsquo;ll
          see &mdash; it&rsquo;s an illustration, not a live screenshot, so the exact numbers and colors
          will differ from your session.
        </p>
      </header>

      <div className="space-y-8">
        {/* Step 1 — Upload */}
        <section className="overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr] lg:items-center">
            <div>
              <div className="flex items-center gap-3">
                <StepNumber n={1} />
                <h2 className="text-xl font-bold text-foreground sm:text-2xl">Upload a compound library</h2>
              </div>
              <p className="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
                Start at <Link href="/upload" className="font-semibold text-blue-500 hover:underline">Upload</Link>.
                Drop in an SDF file; MolClass analyzes every record, lets you pick which fields become
                model targets, and then imports it durably &mdash; one molecule at a time, so a single bad
                record never rolls back the rest. A live progress bar shows records loaded, succeeded, and
                skipped as the import runs.
              </p>
            </div>
            <PagePreview route="/upload">
              <div className="rounded-xl border border-dashed border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
                <UploadCloud className="mx-auto h-8 w-8 text-emerald-500" />
                <p className="mt-2 text-sm font-semibold text-foreground">Drop your SDF file here</p>
                <p className="text-xs text-muted-foreground">or click to browse</p>
              </div>
              <div className="mt-4 rounded-xl border border-sky-500/30 bg-sky-500/10 p-4">
                <p className="text-xs font-semibold uppercase tracking-wide text-sky-600 dark:text-sky-300">Loading molecules</p>
                <div className="mt-2 flex items-baseline justify-between text-xs">
                  <span className="font-semibold text-foreground">6,412 of 8,014 records loaded</span>
                  <span className="text-muted-foreground">80%</span>
                </div>
                <div className="mt-1.5 h-2 w-full overflow-hidden rounded-full bg-black/10">
                  <div className="h-full w-4/5 rounded-full bg-sky-500" />
                </div>
              </div>
            </PagePreview>
          </div>
        </section>

        {/* Step 2 — Model creation */}
        <section className="overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr] lg:items-center">
            <div>
              <div className="flex items-center gap-3">
                <StepNumber n={2} />
                <h2 className="text-xl font-bold text-foreground sm:text-2xl">Configure a model</h2>
              </div>
              <p className="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
                In <Link href="/model-creation" className="font-semibold text-blue-500 hover:underline">Model
                Creation</Link>, pick from datasets that already have a usable target &mdash; a property
                with 2 to 100 distinct classes. Click a dataset&rsquo;s ID to choose the target property,
                feature profile (CDK descriptors alone, or JUMBO with added fingerprints), and algorithm
                (RandomForest, SMO, KNN, Ensemble, and more), then start the build.
              </p>
            </div>
            <PagePreview route="/model-creation">
              <div className="overflow-hidden rounded-xl border border-border">
                <div className="grid grid-cols-[auto_1fr_auto] gap-3 border-b border-border bg-muted/40 px-3 py-2 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                  <span>ID</span>
                  <span>What is this dataset?</span>
                  <span>Action</span>
                </div>
                {[
                  { id: "91", desc: "Tox21 nuclear-receptor screen" },
                  { id: "87", desc: "Kinase inhibition panel" },
                ].map((row) => (
                  <div key={row.id} className="grid grid-cols-[auto_1fr_auto] items-center gap-3 border-b border-border/60 px-3 py-3 last:border-b-0">
                    <span className="rounded bg-muted px-2 py-1 font-mono text-xs text-foreground">ID {row.id}</span>
                    <span className="truncate text-xs text-muted-foreground sm:text-sm">{row.desc}</span>
                    <span className="rounded-lg bg-amber-400 px-2.5 py-1 text-[10px] font-bold text-slate-950">Configure</span>
                  </div>
                ))}
              </div>
            </PagePreview>
          </div>
        </section>

        {/* Step 3 — Model review */}
        <section className="overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr] lg:items-center">
            <div>
              <div className="flex items-center gap-3">
                <StepNumber n={3} />
                <h2 className="text-xl font-bold text-foreground sm:text-2xl">Review and approve the build</h2>
              </div>
              <p className="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
                Once the worker finishes training, the build lands in{" "}
                <Link href="/model-review" className="font-semibold text-blue-500 hover:underline">Model
                Review</Link> as <span className="font-mono text-foreground">AWAITING_APPROVAL</span> &mdash;
                nothing is ever auto-published. Open a build to see its full holdout evidence (accuracy,
                AUC, F1, confusion matrix) and either approve it into the published registry or reject it.
              </p>
            </div>
            <PagePreview route="/model-review">
              <div className="space-y-3">
                {[
                  { name: "Mitochondrial uncoupler", algo: "RandomForest" },
                  { name: "Model 118", algo: "SMO" },
                ].map((row) => (
                  <div key={row.name} className="flex items-center justify-between gap-3 rounded-xl border border-border p-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-foreground">{row.name}</p>
                      <p className="text-xs text-muted-foreground">{row.algo} &middot; awaiting approval</p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <span className="rounded-lg bg-emerald-600 px-3 py-1.5 text-[10px] font-bold text-white">Approve</span>
                      <span className="rounded-lg border border-border px-3 py-1.5 text-[10px] font-semibold text-muted-foreground">Reject</span>
                    </div>
                  </div>
                ))}
              </div>
            </PagePreview>
          </div>
        </section>

        {/* Step 4 — Search & predict */}
        <section className="overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr] lg:items-center">
            <div>
              <div className="flex items-center gap-3">
                <StepNumber n={4} />
                <h2 className="text-xl font-bold text-foreground sm:text-2xl">Find molecules and run predictions</h2>
              </div>
              <p className="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
                <Link href="/search" className="font-semibold text-blue-500 hover:underline">Search</Link> has
                two tabs. <strong className="text-foreground">Structure search</strong> looks up the
                registry by ID, name, InChIKey, SMILES, or a drawn structure, and shows a thumbnail per
                match &mdash; select any number of them and hit &ldquo;Predict selected&rdquo;.{" "}
                <strong className="text-foreground">Model &amp; molecule search</strong> is where that
                selection lands: pick one or more published models and run every molecule against every
                model at once.
              </p>
            </div>
            <PagePreview route="/search?tab=models">
              <div className="mb-3 flex gap-2">
                <span className="rounded-t-lg border-b-2 border-transparent px-3 py-1.5 text-xs font-semibold text-muted-foreground">Structure search</span>
                <span className="rounded-t-lg border-b-2 border-blue-500 px-3 py-1.5 text-xs font-semibold text-foreground">Model &amp; molecule search</span>
              </div>
              <div className="grid grid-cols-2 gap-3">
                {["Caffeine", "Aspirin"].map((name) => (
                  <div key={name} className="flex items-center gap-2 rounded-xl border border-border p-2.5">
                    <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg border border-border bg-white">
                      <Beaker className="h-4 w-4 text-blue-500" />
                    </span>
                    <span className="truncate text-xs font-semibold text-foreground">{name}</span>
                  </div>
                ))}
              </div>
              <div className="mt-3 flex items-center justify-between rounded-xl bg-emerald-600 px-4 py-2.5 text-xs font-bold text-white">
                Run 2 molecules against 1 model
                <ArrowRight className="h-3.5 w-3.5" />
              </div>
            </PagePreview>
          </div>
        </section>

        {/* Step 5 — Review a molecule */}
        <section className="overflow-hidden rounded-3xl border border-border bg-card/60 p-6 shadow-2xl backdrop-blur-xl sm:p-8">
          <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr] lg:items-center">
            <div>
              <div className="flex items-center gap-3">
                <StepNumber n={5} />
                <h2 className="text-xl font-bold text-foreground sm:text-2xl">Read the result &mdash; and its history</h2>
              </div>
              <p className="mt-3 text-sm leading-6 text-muted-foreground sm:text-base">
                Every prediction reports a predicted class, a confidence score, and an applicability-domain
                flag (whether the molecule&rsquo;s scaffold actually resembles the training set). Click any
                molecule to open its own page, which keeps a running history of every prediction ever made
                against it, and lets you queue up new ones against any other published model.
              </p>
            </div>
            <PagePreview route="/molecules/4711">
              <div className="flex items-center gap-3 border-b border-border pb-3">
                <span className="grid h-12 w-12 shrink-0 place-items-center rounded-lg border border-border bg-white">
                  <Beaker className="h-5 w-5 text-blue-500" />
                </span>
                <div>
                  <p className="text-sm font-bold text-foreground">Caffeine</p>
                  <p className="text-xs text-muted-foreground">#4711</p>
                </div>
              </div>
              <div className="mt-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3">
                <div className="flex items-baseline justify-between">
                  <span className="text-sm font-bold text-foreground">active</span>
                  <span className="text-xs text-muted-foreground">81.2% confidence</span>
                </div>
                <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-emerald-500/20 px-2 py-0.5 text-[10px] font-semibold uppercase text-emerald-700 dark:text-emerald-300">
                  <CheckCircle2 className="h-3 w-3" /> In domain
                </span>
              </div>
            </PagePreview>
          </div>
        </section>
      </div>

      <section className="mt-10 rounded-2xl border border-border bg-muted/30 p-6 text-sm leading-6 text-muted-foreground sm:p-8">
        <div className="flex items-center gap-2 text-foreground">
          <UserCheck className="h-4 w-4" />
          <h2 className="font-bold">Where things live, if you get lost</h2>
        </div>
        <ul className="mt-3 space-y-1.5">
          <li><span className="font-mono text-foreground">/upload</span> &mdash; bring compounds in.</li>
          <li><span className="font-mono text-foreground">/model-creation</span> &mdash; turn a dataset into a training run.</li>
          <li><span className="font-mono text-foreground">/model-review</span> &mdash; approve or reject a finished build.</li>
          <li><span className="font-mono text-foreground">/search</span> &mdash; look up molecules, or predict against published models.</li>
          <li><span className="font-mono text-foreground">/dataset-review</span> &mdash; audit import health and model eligibility across every dataset.</li>
        </ul>
        <p className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
          <SearchIcon className="h-3.5 w-3.5 shrink-0" />
          Still stuck? See <Link href="/details" className="font-semibold text-blue-500 hover:underline">About</Link> for how to reach us.
        </p>
      </section>
    </main>
  );
}
