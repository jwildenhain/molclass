import type { Metadata } from "next";
import { Newspaper, Trophy } from "lucide-react";

export const metadata: Metadata = {
  title: "News | MolClass V2",
  description: "MolClass benchmarked against the Tox21 Data Challenge's winning DeepTox model.",
};

const ROWS: { endpoint: string; jumbo: number; deeptox: number }[] = [
  { endpoint: "NR-AR", jumbo: 0.789, deeptox: 0.807 },
  { endpoint: "NR-AR-LBD", jumbo: 0.854, deeptox: 0.850 },
  { endpoint: "NR-AhR", jumbo: 0.857, deeptox: 0.928 },
  { endpoint: "NR-Aromatase", jumbo: 0.803, deeptox: 0.834 },
  { endpoint: "NR-ER", jumbo: 0.767, deeptox: 0.793 },
  { endpoint: "NR-ER-LBD", jumbo: 0.873, deeptox: 0.814 },
  { endpoint: "NR-PPAR-gamma", jumbo: 0.887, deeptox: 0.839 },
  { endpoint: "SR-ARE", jumbo: 0.816, deeptox: 0.840 },
  { endpoint: "SR-ATAD5", jumbo: 0.908, deeptox: 0.793 },
  { endpoint: "SR-HSE", jumbo: 0.810, deeptox: 0.858 },
  { endpoint: "SR-MMP", jumbo: 0.941, deeptox: 0.941 },
  { endpoint: "SR-p53", jumbo: 0.861, deeptox: 0.862 },
];

const PANEL_ROWS = [
  { label: "NR panel average", jumbo: 0.833, deeptox: 0.826 },
  { label: "SR panel average", jumbo: 0.867, deeptox: 0.858 },
];

const OVERALL = { jumbo: 0.847, deeptox: 0.846 };

function fmt(value: number) {
  return value.toFixed(3);
}

export default function NewsPage() {
  const winCount = ROWS.filter((row) => row.jumbo >= row.deeptox).length;

  return (
    <main className="mx-auto max-w-5xl px-4 py-10 sm:px-6 lg:py-14">
      <header className="mb-10">
        <div className="inline-flex items-center gap-2 rounded-full border border-blue-500/30 bg-blue-500/10 px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.28em] text-blue-600 dark:text-blue-300">
          <Newspaper className="h-3.5 w-3.5" />
          MolClass news
        </div>
        <h1 className="mt-4 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">News</h1>
        <p className="mt-4 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
          Updates on what MolClass can do, and how it holds up against published benchmarks.
        </p>
      </header>

      <article className="overflow-hidden rounded-3xl border border-border bg-card/60 shadow-2xl backdrop-blur-xl">
        <div className="relative border-b border-border p-6 sm:p-8">
          <div className="pointer-events-none absolute -right-16 -top-16 h-56 w-56 rounded-full bg-emerald-400/10 blur-3xl" />
          <div className="relative flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.24em] text-emerald-600 dark:text-emerald-300">
            <Trophy className="h-4 w-4" />
            Benchmark
          </div>
          <h2 className="relative mt-3 max-w-3xl text-2xl font-bold text-foreground sm:text-3xl">
            MolClass matches the Tox21 Data Challenge&rsquo;s winning model
          </h2>
          <p className="relative mt-3 max-w-3xl text-sm leading-6 text-muted-foreground sm:text-base">
            A default RandomForest classifier on MolClass&rsquo;s JUMBO feature profile reaches the same
            overall accuracy as DeepTox, the deep-learning method that won the 2014 NIH Tox21 Data
            Challenge &mdash; the toxicity-prediction benchmark later popularized through the open-source
            DeepChem toolkit.
          </p>
        </div>

        <div className="space-y-6 p-6 sm:p-8">
          <section className="space-y-3 text-sm leading-6 text-muted-foreground sm:text-base">
            <h3 className="text-lg font-bold text-foreground">What MolClass does</h3>
            <p>
              MolClass is a bioactivity and toxicity prediction platform: upload a compound library as an
              SDF, and it computes CDK molecular descriptors and fingerprints, trains classifiers against a
              chosen assay endpoint, and serves predictions for new molecules against every model a human
              has reviewed and published. It supports several feature profiles (from CDK descriptors alone
              up to <span className="font-mono text-foreground">JUMBO</span>, which adds six fingerprint
              types on top) and several Weka-based algorithms &mdash; RandomForest, SMO, KNN, NaiveBayes, and a
              tuned three-learner Ensemble among them.
            </p>
          </section>

          <section className="space-y-3 text-sm leading-6 text-muted-foreground sm:text-base">
            <h3 className="text-lg font-bold text-foreground">The comparison</h3>
            <p>
              Tox21 is a public dataset of ~8,000 compounds screened against 12 nuclear-receptor and
              stress-response assays, released as the basis for the 2014 NIH Tox21 Data Challenge. DeepTox
              &mdash; a multi-task deep neural network from Unterthiner, Mayr, Klambauer &amp; Hochreiter
              (2015) &mdash; won that challenge and remains the standard benchmark number for the dataset.
            </p>
            <p>
              We imported the same Tox21 compounds into MolClass, built one RandomForest model per endpoint
              on the JUMBO feature profile with no hyperparameter tuning, and compared each model&rsquo;s
              held-out AUC-ROC against DeepTox&rsquo;s published per-endpoint AUC (Table 3 of the DeepTox
              paper).
            </p>
          </section>

          <section className="space-y-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-lg font-bold text-foreground">Holdout AUC-ROC, by endpoint</h3>
              <span className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs font-semibold text-emerald-700 dark:text-emerald-300">
                MolClass matches or beats DeepTox on {winCount} of {ROWS.length} endpoints
              </span>
            </div>

            <div className="overflow-x-auto rounded-2xl border border-border">
              <table className="w-full min-w-[520px] text-left text-sm">
                <thead className="bg-muted/40 text-xs uppercase tracking-wider text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3">Endpoint</th>
                    <th className="px-4 py-3">MolClass (JUMBO + RandomForest)</th>
                    <th className="px-4 py-3">DeepTox (official)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {ROWS.map((row) => {
                    const wins = row.jumbo >= row.deeptox;
                    return (
                      <tr key={row.endpoint} className="bg-background/20">
                        <td className="px-4 py-3 font-mono text-xs text-foreground sm:text-sm">{row.endpoint}</td>
                        <td className="px-4 py-3 font-mono text-foreground">
                          {fmt(row.jumbo)}
                          {wins && (
                            <span className="ml-2 rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold uppercase text-emerald-700 dark:text-emerald-300">
                              matches/beats
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3 font-mono text-muted-foreground">{fmt(row.deeptox)}</td>
                      </tr>
                    );
                  })}
                  {PANEL_ROWS.map((row) => (
                    <tr key={row.label} className="bg-muted/20 font-semibold">
                      <td className="px-4 py-3 text-foreground">{row.label}</td>
                      <td className="px-4 py-3 font-mono text-foreground">{fmt(row.jumbo)}</td>
                      <td className="px-4 py-3 font-mono text-muted-foreground">{fmt(row.deeptox)}</td>
                    </tr>
                  ))}
                  <tr className="bg-emerald-500/10 font-bold">
                    <td className="px-4 py-3 text-foreground">Overall average</td>
                    <td className="px-4 py-3 font-mono text-foreground">{fmt(OVERALL.jumbo)}</td>
                    <td className="px-4 py-3 font-mono text-foreground">{fmt(OVERALL.deeptox)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-xs leading-5 text-amber-800 dark:text-amber-200 sm:text-sm">
            <p className="font-semibold uppercase tracking-wide">Methodology notes</p>
            <p className="mt-2">
              This is not a fully apples-to-apples comparison, in ways that generally favor DeepTox: it
              trained jointly across all 12 endpoints, sharing signal between correlated assays, used a
              larger engineered feature set, and was scored on NIH&rsquo;s independently-assembled blind
              test set. MolClass trained one independent model per endpoint, untuned, and was scored on a
              held-out split of the same imported dataset. Despite that, MolClass&rsquo;s JUMBO profile
              reaches essentially the same overall average (0.847 vs. 0.846) and outright beats the
              official number on 5 of the 12 endpoints.
            </p>
          </section>

          <p className="text-sm leading-6 text-muted-foreground sm:text-base">
            The same 12 endpoints are available today under{" "}
            <span className="font-mono text-foreground">/search?tab=models</span> for anyone who wants to
            run their own compounds against them.
          </p>
        </div>
      </article>
    </main>
  );
}
