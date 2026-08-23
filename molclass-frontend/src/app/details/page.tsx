export default function DetailsPage() {
  return (
    <div className="max-w-4xl mx-auto mt-12 space-y-12">
      <div className="text-center space-y-4">
        <h1 className="text-4xl font-bold text-foreground">About MolClass</h1>
        <p className="text-xl text-muted-foreground">High-throughput bioactivity prediction via modern machine learning ensembles.</p>
      </div>

      <div className="bg-card/50 backdrop-blur-md rounded-2xl border border-border shadow-2xl p-8 space-y-8">
        
        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">How to Cite</h2>
          <div className="bg-muted/50 p-6 rounded-xl border border-border font-mono text-sm text-muted-foreground">
            <p className="mb-2">If you use this software for your work, please cite:</p>
            <p className="text-indigo-400">Bioinformatics. 2012 Aug 15;28(16):2200-1 Wildenhain J, Fitzgerald N, Tyers M.</p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">Version History (V2)</h2>
          <div className="text-muted-foreground space-y-2 leading-relaxed">
            <p>MolClass V2 replaces the original monolithic PHP architecture with a completely modernized, decoupled Next.js + React frontend running on TailwindCSS.</p>
            <p>The backend pipeline is powered by a high-performance Spring Boot API utilizing multi-threaded Weka prediction algorithms. The core database has been unified (Molclass V1.5 -&gt; V2) to support over 115 trained models.</p>
            <p>Legacy features such as Klekota-Roth fingerprints and preclustering by Murcko-Fragments remain intact, seamlessly integrated into the new architecture.</p>
          </div>

          <div className="pt-2">
            <h3 className="text-sm font-semibold uppercase tracking-wide text-foreground">Recent improvements</h3>
            <ul className="mt-3 list-disc space-y-2 pl-5 text-muted-foreground leading-relaxed">
              <li>Parallelized SMOTE oversampling for large, imbalanced training sets, cutting model build time without changing the resampled output (still fully deterministic).</li>
              <li>Redesigned the Ensemble classifier around RandomForest, a cross-validated auto-tuned KNN, and NaiveBayes, measurably improving holdout accuracy over the previous design.</li>
              <li>Hardened the prediction service: deduplicated overlapping CDK/Weka dependencies and closed a model-deserialization gap that had been silently failing every prediction from ensemble-family models.</li>
              <li>Benchmarked against the Tox21 Data Challenge: a default RandomForest-on-JUMBO setup matches DeepTox, the competition&rsquo;s winning method (see <a href="/news" className="text-indigo-400 hover:underline">News</a>).</li>
              <li>Consolidated structure and model search into a single interface with molecule thumbnails, multi-molecule batch prediction, and per-model AUC/F1 metrics.</li>
              <li>Added live progress reporting during SDF upload and import, replacing a static &ldquo;queued&rdquo; message with real record-by-record counts.</li>
            </ul>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">Contact Information</h2>
          <div className="bg-muted/30 p-6 rounded-xl border border-border/50 flex flex-col md:flex-row justify-between items-start md:items-center">
            <div className="text-muted-foreground">
              <p className="font-bold text-foreground">Jan Wildenhain</p>
              <p>EPAM</p>
              <p>Henley-on-Thames, UK</p>
            </div>
            <div className="mt-6 md:mt-0">
              <a href="mailto:jan.wildenhain@ed.ac.uk" className="px-6 py-3 bg-slate-700 hover:bg-slate-600 text-white font-medium rounded-lg transition-colors inline-block">
                Contact via Email
              </a>
            </div>
          </div>
          <p className="text-sm text-slate-500 italic mt-4">
            If you did not get a response to an email please do not hesitate to resend your email. We are very much interested in your feedback.
          </p>
        </section>

      </div>
    </div>
  );
}
