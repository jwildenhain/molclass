import Link from "next/link";
import { Database, Network } from "lucide-react";

export default function PredictionListPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-14 sm:px-6 lg:py-20">
      <header>
        <p className="font-mono text-xs uppercase tracking-[0.28em] text-blue-500">Approved inference</p>
        <h1 className="mt-3 text-4xl font-bold tracking-tight text-foreground sm:text-6xl">Choose the registry, not a legacy batch.</h1>
        <p className="mt-5 max-w-2xl text-base leading-7 text-muted-foreground">
          Predictions run only with explicitly published v3 builds. Dataset records remain available for provenance and model preparation.
        </p>
      </header>

      <section className="mt-10 grid gap-5 md:grid-cols-2">
        <Link href="/prediction-list/models" className="group relative overflow-hidden rounded-3xl border border-blue-500/20 bg-blue-500/5 p-8 shadow-xl transition hover:-translate-y-1 hover:border-blue-500/50 hover:shadow-2xl">
          <div className="absolute -right-12 -top-12 h-40 w-40 rounded-full bg-blue-500/10 blur-2xl" />
          <div className="relative">
            <div className="grid h-14 w-14 place-items-center rounded-2xl bg-blue-500/15 text-blue-600 dark:text-blue-300"><Network className="h-7 w-7" /></div>
            <p className="mt-8 font-mono text-xs uppercase tracking-wider text-blue-600 dark:text-blue-300">Published only</p>
            <h2 className="mt-2 text-2xl font-bold text-foreground">Model and molecule search</h2>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">Select an approved build, find an indexed molecule by SDF identifier or chemistry, and run a prediction.</p>
          </div>
        </Link>

        <Link href="/dataset-review" className="group relative overflow-hidden rounded-3xl border border-emerald-500/20 bg-emerald-500/5 p-8 shadow-xl transition hover:-translate-y-1 hover:border-emerald-500/50 hover:shadow-2xl">
          <div className="absolute -right-12 -top-12 h-40 w-40 rounded-full bg-emerald-500/10 blur-2xl" />
          <div className="relative">
            <div className="grid h-14 w-14 place-items-center rounded-2xl bg-emerald-500/15 text-emerald-600 dark:text-emerald-300"><Database className="h-7 w-7" /></div>
            <p className="mt-8 font-mono text-xs uppercase tracking-wider text-emerald-600 dark:text-emerald-300">Durable provenance</p>
            <h2 className="mt-2 text-2xl font-bold text-foreground">Dataset registry</h2>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">Review identifiers, selected properties, import outcomes, model eligibility, and linked model definitions.</p>
          </div>
        </Link>
      </section>
    </main>
  );
}
