import type { Metadata } from "next";
import { Suspense } from "react";
import { SearchTabs } from "./SearchTabs";

export const metadata: Metadata = {
  title: "Search | MolClass V2",
  description: "Search the molecule registry, or predict a molecule with an approved model.",
};

export default function SearchPage() {
  return (
    <main className="mx-auto max-w-7xl px-4 py-10 sm:px-6 lg:py-14">
      <header className="mb-6">
        <p className="font-mono text-xs uppercase tracking-[0.28em] text-blue-500">Compound registry</p>
        <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">Search</h1>
      </header>

      {/* useSearchParams needs a Suspense boundary so the shell can still prerender. */}
      <Suspense
        fallback={<div className="mt-8 h-32 animate-pulse rounded-2xl border border-border bg-card/40" />}
      >
        <SearchTabs />
      </Suspense>
    </main>
  );
}
